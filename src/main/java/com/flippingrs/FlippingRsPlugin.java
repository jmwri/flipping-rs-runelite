package com.flippingrs;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

/**
 * Records Grand Exchange trades to a FlippingRS journal automatically.
 *
 * <p>The division of labour is the design. This plugin observes and reports; it
 * does not decide anything. It never pairs a sale with a purchase, never works
 * out what a flip earned, and never applies the sale tax. All of that is the
 * server's, computed from the fills sent from here, so that the maths can be
 * corrected and replayed over the history rather than being frozen inside
 * whichever plugin version a user installed months ago.
 *
 * <p>Three properties are non-negotiable, because breaking any of them shows up
 * as wrong money in somebody's journal:
 *
 * <ul>
 *   <li>A trade is never recorded twice. Ids are minted before a fill is first
 *       sent and the server drops repeats, so retrying is free.
 *   <li>A trade is never invented. Progress made while the plugin was not
 *       watching is adopted silently rather than backdated to now.
 *   <li>A trade is not lost to a flaky network. Fills queue to disk and survive
 *       a restart.
 * </ul>
 */
@Slf4j
@PluginDescriptor(
	name = "FlippingRS",
	description = "Automatically records your Grand Exchange trades to your flippingrs.com journal",
	tags = {"grand", "exchange", "ge", "flip", "flipping", "merch", "profit", "journal", "tracker", "tax"}
)
public class FlippingRsPlugin extends Plugin
{
	/** Config key prefix for the per-slot baseline. */
	private static final String OFFER_KEY = "offer";
	/** Config key for the chosen FlippingRS game account, per RuneScape profile. */
	private static final String ACCOUNT_KEY = "gameAccountId";

	/** Matches the server's cap on one ingest call. */
	private static final int MAX_BATCH = 500;

	/** What {@link Client#getAccountHash()} returns when nobody is logged in. */
	private static final long NO_ACCOUNT = -1L;

	@Inject
	private Client client;

	@Inject
	private FlippingRsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	private final OfferTracker tracker = new OfferTracker();

	/**
	 * Queue and disk work. Everything that touches {@link TransactionQueue}
	 * runs here and nowhere else, which keeps file writes off the game thread
	 * and means the queue's monitor is essentially uncontended.
	 */
	private ScheduledExecutorService diskExecutor;

	/**
	 * Network. Separate from {@link #diskExecutor} because a request can block for the
	 * whole call timeout, and a slow server must not also stall the recording
	 * of trades that are still happening while it is slow.
	 *
	 * <p>Both are the plugin's own rather than RuneLite's injected
	 * ScheduledExecutorService, which is a SINGLE thread shared by every plugin
	 * in the client. Blocking that on an HTTP call stalls everyone else's
	 * scheduled work, which is exactly the kind of client-wide stutter users
	 * blame on whatever they installed most recently.
	 */
	private ScheduledExecutorService sendExecutor;

	/**
	 * Pending fills, one queue per RuneScape account.
	 *
	 * <p>Separate queues because the FlippingRS journal a trade belongs to is
	 * remembered per RuneScape account, so a main's fills and an alt's cannot be
	 * sent in the same batch or under the same id. It also stops two clients
	 * logged into two accounts overwriting each other's file.
	 */
	private final Map<Long, TransactionQueue> queues = new ConcurrentHashMap<>();

	/**
	 * Guards the sender. A scheduled tick and the panel's button can fire at
	 * once, and two threads draining the same queue would send the same batch
	 * twice. The server de-duplicates it, but doing it at all is wasteful and
	 * makes the panel's counts nonsense.
	 */
	private final AtomicBoolean sending = new AtomicBoolean();

	private FlippingRsApi api;
	private FlippingRsPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> syncTask;

	/** Incremented on the game thread, read on the Swing and io threads. */
	private final AtomicInteger recordedThisSession = new AtomicInteger();
	@Nullable
	// Written on the net thread, read on the Swing thread. Without volatile the
	// panel can keep showing a stale "last sent" indefinitely.
	private volatile Instant lastSyncAt;

	@Provides
	FlippingRsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlippingRsConfig.class);
	}

	@Override
	protected void startUp()
	{
		diskExecutor = Executors.newSingleThreadScheduledExecutor(thread("flippingrs-io"));
		sendExecutor = Executors.newSingleThreadScheduledExecutor(thread("flippingrs-net"));

		// RuneLite's injected client sets no call timeout, so a server that
		// accepts a connection and then goes quiet holds the thread until the
		// OS gives up. A bound turns that into a retry instead of a wedge.
		api = new FlippingRsApi(
			okHttpClient.newBuilder().callTimeout(Duration.ofSeconds(30)).build(), gson);

		panel = new FlippingRsPanel();
		panel.onSyncNow(() -> submit(sendExecutor, this::drain));
		panel.onReconnect(() -> submit(sendExecutor, this::connect));
		panel.onAccountChosen(this::rememberChosenAccount);

		navButton = NavigationButton.builder()
			.tooltip("FlippingRS")
			.icon(icon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		scheduleSync();
		submit(sendExecutor, this::connect);
	}

	/**
	 * Submits work, tolerating a shutdown that has already happened.
	 *
	 * <p>shutDown stops the executors, but an offer event already in flight on
	 * the game thread can still arrive afterwards. Without this the submission
	 * throws RejectedExecutionException straight into RuneLite's event bus,
	 * which logs it as an uncaught subscriber error -- an alarming entry for
	 * the entirely ordinary act of disabling a plugin.
	 */
	private static void submit(ScheduledExecutorService on, Runnable work)
	{
		if (on == null || on.isShutdown())
		{
			return;
		}
		try
		{
			on.execute(work);
		}
		catch (RejectedExecutionException e)
		{
			// Lost the race with shutdown. Nothing to do and nothing wrong.
			log.debug("dropped work submitted during shutdown", e);
		}
	}

	private static ThreadFactory thread(String name)
	{
		return r ->
		{
			// Daemon, so a stuck request can never keep the client's JVM alive
			// after the user has closed it.
			final Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		};
	}

	@Override
	protected void shutDown()
	{
		if (syncTask != null)
		{
			syncTask.cancel(false);
			syncTask = null;
		}
		// One last attempt, so someone who disables the plugin mid-session does
		// not leave the evening's last few trades unsent. If it fails they are
		// still on disk for next time, because every fill was written through
		// as it arrived.
		//
		// Deliberately not awaited. shutDown runs on the caller's thread, and
		// blocking it on a network round trip would freeze the client on plugin
		// disable and on exit -- the exact failure this whole arrangement is
		// meant to avoid. shutdown() lets already-queued disk work finish.
		submit(sendExecutor, this::drain);
		sendExecutor.shutdown();
		diskExecutor.shutdown();

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
	}

	// --------------------------------------------------------------- capture

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		final GrandExchangeOffer offer = event.getOffer();
		final int slot = event.getSlot();

		// While logging in, hopping or logging out, the client clears every
		// slot. That is not the user emptying their offers, and acting on it
		// would throw away the baselines that stop the next login re-reporting
		// everything still on the exchange.
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// A trade needs an account to attribute it to, and the account hash is
		// what the per-profile baseline is keyed by. Without one there is
		// nowhere to read a baseline from or write one to.
		final long accountHash = client.getAccountHash();
		if (accountHash == NO_ACCOUNT)
		{
			return;
		}

		final SavedOffer previous = loadOffer(slot);

		final OfferTracker.Observation seen = tracker.observe(
			slot, previous, offer, () -> itemName(offer.getItemId()), client.getWorld(), Instant.now());

		// Persist the new baseline before queueing the fill. If the client dies
		// between the two, one fill is lost; in the other order, every fill in
		// this slot is reported again on the next login. A missing trade is
		// visible and fixable by hand. A duplicated one is neither, because
		// nobody notices a profit figure that is quietly too high.
		if (seen.saved == null)
		{
			clearOffer(slot);
		}
		else
		{
			saveOffer(slot, seen.saved);
		}

		if (seen.adopted)
		{
			log.debug("adopted an in-progress offer in slot {} without reporting it", slot);
			onPanel(p -> p.setStatus(
				"Picked up an offer that was already in progress. Its earlier fills were not "
					+ "recorded, so a sale out of it may not match a purchase.",
				ColorScheme.BRAND_ORANGE));
		}

		final GeTransaction tx = seen.transaction;
		if (tx == null)
		{
			return;
		}

		if (!config.enabled())
		{
			// Off means off, not "hold it and send it when they turn it back
			// on". Someone who stops recording mid-session means those trades to
			// stay out of their journal.
			log.debug("recording is off; discarding {}", tx);
			return;
		}

		final int recorded = recordedThisSession.incrementAndGet();

		// Hand off, rather than queueing inline. This method runs on the game
		// thread, and TransactionQueue.add writes the whole queue file through
		// to disk before returning -- create a temp file, serialise every
		// pending fill, write, atomic move. Doing that here stalled the game
		// for the length of a disk write on every single Grand Exchange fill,
		// and got worse the more was pending. It could also block behind the
		// sender's own flush, since both take the queue's monitor.
		//
		// queueFor is on this side of the handoff too: constructing a queue
		// reads its file back, so the first fill after login was a disk read on
		// the game thread as well.
		submit(diskExecutor, () ->
		{
			final TransactionQueue queue = queueFor(accountHash);
			queue.add(tx);
			onPanel(p ->
			{
				p.addRecent(tx);
				p.setCounts(recorded, queue.size());
			});
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!FlippingRsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		switch (event.getKey())
		{
			case "apiKey":
			case "baseUrl":
				submit(sendExecutor, this::connect);
				break;
			case "syncSeconds":
				scheduleSync();
				break;
			default:
				break;
		}
	}

	// ---------------------------------------------------------------- sending

	private void scheduleSync()
	{
		if (syncTask != null)
		{
			syncTask.cancel(false);
		}
		final long seconds = Math.max(5, config.syncSeconds());
		syncTask = sendExecutor.scheduleWithFixedDelay(this::drain, seconds, seconds, TimeUnit.SECONDS);
	}

	/**
	 * Sends whatever is waiting for the account that is logged in.
	 *
	 * <p>Only that account's queue: which FlippingRS journal a trade belongs to
	 * is remembered per RuneScape account, and that setting is only readable for
	 * the profile that is currently active. Another account's pending fills wait
	 * on disk until it next logs in, which is the only way to file them
	 * correctly rather than quickly.
	 *
	 * <p>Never throws. This runs on RuneLite's shared scheduler, where an
	 * exception escaping a {@code scheduleWithFixedDelay} task cancels it for
	 * good -- the plugin would go quiet with nothing in the log to say why.
	 */
	private void drain()
	{
		if (!sending.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			final long accountHash = client.getAccountHash();
			if (accountHash == NO_ACCOUNT)
			{
				return;
			}
			final TransactionQueue queue = queueFor(accountHash);
			if (queue.isEmpty())
			{
				return;
			}

			final String key = config.apiKey().trim();
			if (key.isEmpty())
			{
				onPanel(p -> p.setStatus(
					"No API key yet. Add one in the plugin settings; trades are being kept until you do.",
					ColorScheme.BRAND_ORANGE));
				return;
			}
			final String accountId = chosenAccount();
			if (accountId == null)
			{
				onPanel(p -> p.setStatus(
					"No journal picked for this account. Choose one above; trades are being kept until you do.",
					ColorScheme.BRAND_ORANGE));
				return;
			}

			// The queue was chosen from the account hash; the journal id came
			// from whichever RuneScape profile is active *now*. Those are two
			// separate reads of state that changes when somebody hops or
			// relogs, and pairing a mismatched two would post one account's
			// trades into the other's journal. Ingestion is idempotent by id,
			// so re-sending would not undo it -- the entries would simply stay
			// under the wrong account, which is precisely what storing the
			// choice per profile exists to prevent. Cheaper to notice and wait
			// for the next tick.
			if (client.getAccountHash() != accountHash)
			{
				log.debug("account changed while preparing a batch; leaving it queued");
				return;
			}

			final List<GeTransaction> batch = queue.peek(MAX_BATCH);

			final FlippingRsApi.IngestResult result;
			try
			{
				result = api.submit(config.baseUrl(), key, accountId, batch);
			}
			catch (FlippingRsApi.PermanentException e)
			{
				// Retrying cannot help, and leaving this at the head of the
				// queue would wedge every later trade behind it forever. Drop
				// exactly what was refused -- see dropRefused -- and let the
				// rest through.
				dropRefused(queue, batch, e);
				return;
			}

			queue.confirm(batch);
			lastSyncAt = Instant.now();

			log.debug("sent {} fills: {} flips opened, {} closed, {} unmatched",
				batch.size(), result.getFlipsOpened(), result.getFlipsClosed(), result.getUnmatchedSellQty());

			// A 200 can still refuse individual rows, and the batch is dropped
			// from the queue regardless -- so if this is not surfaced here, the
			// trade is gone and nobody is ever told. Silently losing one is far
			// worse than a blunt warning, because the journal then disagrees
			// with what the player remembers doing and nothing explains why.
			if (result.getRejected() > 0)
			{
				log.warn("flippingrs.com refused {} of {} fills: {}",
					result.getRejected(), batch.size(), result.getProblems());
			}

			onPanel(p -> {
				p.setCounts(recordedThisSession.get(), queue.size());
				p.setLastSync(lastSyncAt, null);
				if (result.getRejected() > 0)
				{
					p.setStatus(result.getRejected() + " trade(s) could not be recorded and have been "
						+ "dropped. See the client log for why.", ColorScheme.PROGRESS_ERROR_COLOR);
				}
				else if (result.getUnmatchedSellQty() > 0)
				{
					p.setStatus("Connected. " + result.getUnmatchedSellQty()
							+ " item(s) sold with no recorded purchase behind them, so they are not in a flip.",
						ColorScheme.BRAND_ORANGE);
				}
				else
				{
					p.setStatus("Connected and recording.", ColorScheme.PROGRESS_COMPLETE_COLOR);
				}
			});
		}
		catch (IOException e)
		{
			// Worth retrying: the batch stays queued for the next tick.
			log.debug("could not reach flippingrs.com; will retry", e);
			onPanel(p -> p.setLastSync(null, e.getMessage()));
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure while sending", e);
			onPanel(p -> p.setLastSync(null, "Unexpected error; see the client log."));
		}
		finally
		{
			sending.set(false);
		}
	}

	/**
	 * Discards exactly the fills the server refused.
	 *
	 * <p>The batch and its queue are passed in rather than looked up again, and
	 * that is the whole point of this method existing. Re-reading the queue here
	 * discarded whatever was at the head of it *now*, which is not the same
	 * list: fills arrive on the disk thread while a request is in flight, and
	 * peek returns from the head, so a refused batch of three that had since
	 * grown to five silently threw away two trades that had never been sent.
	 * Re-deriving the account could also point this at a different account's
	 * queue entirely, if the player had hopped or logged out mid-request.
	 */
	private void dropRefused(TransactionQueue queue, List<GeTransaction> batch,
		FlippingRsApi.PermanentException cause)
	{
		queue.confirm(batch);
		log.warn("dropped {} fills that flippingrs.com will not accept", batch.size(), cause);
		onPanel(p -> {
			p.setCounts(recordedThisSession.get(), queue.size());
			p.setLastSync(null, cause.getMessage());
		});
	}

	/** Checks the key and loads the journals it can file trades under. */
	private void connect()
	{
		final String key = config.apiKey().trim();
		if (key.isEmpty())
		{
			onPanel(p -> {
				p.setAccounts(Collections.emptyList(), null);
				p.setStatus("Add your API key in the plugin settings. Create one at flippingrs.com "
					+ "under Account, API keys, with the RuneLite plugin scope.", ColorScheme.LIGHT_GRAY_COLOR);
			});
			return;
		}

		try
		{
			final List<FlippingRsApi.GameAccount> accounts = api.accounts(config.baseUrl(), key);
			final String chosen = chosenAccount();
			onPanel(p -> {
				p.setAccounts(accounts, chosen);
				p.setStatus(accounts.isEmpty()
						? "Connected, but this FlippingRS account has no game accounts to file trades under."
						: "Connected and recording.",
					accounts.isEmpty() ? ColorScheme.BRAND_ORANGE : ColorScheme.PROGRESS_COMPLETE_COLOR);
				// A RuneScape account seen for the first time has nothing chosen
				// yet. Adopting whatever the panel selected saves a setup step,
				// and it can still be changed.
				if (chosen == null)
				{
					rememberChosenAccountFrom(p);
				}
			});
			// The key may have been missing or wrong while trades piled up.
			submit(sendExecutor, this::drain);
		}
		catch (IOException e)
		{
			log.debug("could not reach flippingrs.com", e);
			onPanel(p -> p.setStatus("Could not connect: " + e.getMessage(), ColorScheme.PROGRESS_ERROR_COLOR));
		}
	}

	// ------------------------------------------------------------ persistence

	/**
	 * The FlippingRS journal this RuneScape account files under.
	 *
	 * <p>Stored per RuneScape profile rather than as a plugin setting, so an alt
	 * gets its own journal without anyone remembering to change a dropdown
	 * before logging in. Getting it wrong mixes two accounts' numbers together,
	 * and since buy limits are tracked per game account, the damage is not
	 * cosmetic.
	 */
	@Nullable
	private String chosenAccount()
	{
		final String id = configManager.getRSProfileConfiguration(FlippingRsConfig.GROUP, ACCOUNT_KEY);
		return id == null || id.isEmpty() ? null : id;
	}

	private void rememberChosenAccount()
	{
		final FlippingRsPanel target = panel;
		if (target != null)
		{
			rememberChosenAccountFrom(target);
		}
	}

	private void rememberChosenAccountFrom(FlippingRsPanel from)
	{
		final String id = from.selectedAccountId();
		if (id == null)
		{
			return;
		}
		if (client.getAccountHash() == NO_ACCOUNT)
		{
			// There is no RuneScape profile to attach the choice to yet. Saying
			// so beats writing it somewhere it will never be read back from.
			from.setStatus("Log in to RuneScape first, so this choice can be remembered for that account.",
				ColorScheme.BRAND_ORANGE);
			return;
		}
		configManager.setRSProfileConfiguration(FlippingRsConfig.GROUP, ACCOUNT_KEY, id);
	}

	@Nullable
	private SavedOffer loadOffer(int slot)
	{
		final String json = configManager.getRSProfileConfiguration(
			FlippingRsConfig.GROUP, OFFER_KEY + "." + slot);
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, SavedOffer.class);
		}
		catch (RuntimeException e)
		{
			// A baseline we cannot read is the same as not having one: the offer
			// is adopted rather than re-reported, which is the safe direction.
			log.warn("could not read the saved baseline for slot {}", slot, e);
			return null;
		}
	}

	private void saveOffer(int slot, SavedOffer offer)
	{
		configManager.setRSProfileConfiguration(
			FlippingRsConfig.GROUP, OFFER_KEY + "." + slot, gson.toJson(offer));
	}

	private void clearOffer(int slot)
	{
		configManager.unsetRSProfileConfiguration(FlippingRsConfig.GROUP, OFFER_KEY + "." + slot);
	}

	/**
	 * Where the pending-queue files live. RuneLite's own directory in normal
	 * use; a test redirects it so it never writes into real client data.
	 */
	private File queueDir = new File(RuneLite.RUNELITE_DIR, "flippingrs");

	private TransactionQueue queueFor(long accountHash)
	{
		return queues.computeIfAbsent(accountHash,
			hash -> new TransactionQueue(gson, new File(queueDir, "queue-" + hash + ".json")));
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Item names come from the item manager, which wants the client thread. The
	 * only caller is the offer event, which is already on it.
	 */
	private String itemName(int itemId)
	{
		try
		{
			final String name = itemManager.getItemComposition(itemId).getName();
			return name == null ? "" : name;
		}
		catch (RuntimeException e)
		{
			// The server resolves the real name from the item id anyway, so a
			// missing one costs nothing but a less readable panel line.
			log.debug("could not resolve a name for item {}", itemId, e);
			return "";
		}
	}

	/** Runs a panel update on the Swing thread, if the panel still exists. */
	private void onPanel(Consumer<FlippingRsPanel> action)
	{
		final FlippingRsPanel target = panel;
		if (target == null)
		{
			return;
		}
		SwingUtilities.invokeLater(() -> action.accept(target));
	}

	private BufferedImage icon()
	{
		try
		{
			return ImageUtil.loadImageResource(FlippingRsPlugin.class, "/icon.png");
		}
		catch (RuntimeException e)
		{
			// A missing icon must not stop the plugin loading.
			log.debug("no icon resource found; using a blank one", e);
			return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		}
	}
}
