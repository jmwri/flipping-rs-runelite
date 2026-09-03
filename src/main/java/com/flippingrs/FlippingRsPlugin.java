package com.flippingrs;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
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
import javax.inject.Named;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import okhttp3.HttpUrl;
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
 *       watching goes out once as a recovered fill with no time on it, for
 *       the server to judge, and is never backdated to now.
 *   <li>A trade is not lost to a flaky network. Fills queue to disk and survive
 *       a restart.
 * </ul>
 *
 * <p>What lives here is the plugin's lifecycle, the capture of fills from the
 * client's events, the sending of what is queued, and the reads that fill the
 * side panel. The rest is delegated: {@link OfferTracker} turns slot updates
 * into fills, {@link TransactionQueue} keeps them on disk, {@link ProfileStore}
 * remembers what is per character, {@link Watchlists} owns the watchlist and
 * quote caches, {@link CatchUp} reports the open slots and the history screen,
 * and {@link PositionActions} closes and deletes positions.
 */
@Slf4j
@PluginDescriptor(
	name = "FlippingRS",
	description = "Keeps your flippingrs.com journal up to date on its own: every Grand Exchange trade is recorded as it happens",
	tags = {"grand", "exchange", "ge", "flip", "flipping", "merch", "profit", "journal", "tracker", "tax"}
)
public class FlippingRsPlugin extends Plugin
{
	/** Matches the server's cap on one ingest call. */
	private static final int MAX_BATCH = 500;

	/** What {@link Client#getAccountHash()} returns when nobody is logged in. */
	private static final long NO_ACCOUNT = -1L;

	/**
	 * Worlds whose exchange is not the real economy. A Deadman, Leagues or beta
	 * world has its own prices, its own buy limits and, at the end of the
	 * season, no items at all. Recording those into the same journal as the
	 * main game poisons the averages and the limit timers in exactly the way
	 * an invented trade would.
	 */
	private static final EnumSet<WorldType> SEPARATE_ECONOMY = EnumSet.of(
		WorldType.DEADMAN, WorldType.SEASONAL, WorldType.BETA_WORLD, WorldType.NOSAVE_MODE,
		WorldType.TOURNAMENT_WORLD, WorldType.QUEST_SPEEDRUNNING, WorldType.PVP_ARENA,
		WorldType.FRESH_START_WORLD);

	/** How often the watchlist's quotes are refreshed: the same cadence the site's own data moves at. */
	private static final long QUOTE_REFRESH_SECONDS = 30;

	/**
	 * Ticks after login during which offer deltas are the client replaying
	 * what the exchange did while nobody was watching. Two, the window
	 * RuneLite's own Grand Exchange plugin uses for the same burst.
	 */
	private static final int LOGIN_BURST_TICKS = 2;

	/**
	 * How often the account tabs are re-read after sends, at most.
	 *
	 * <p>The plugin scope is rate limited at thirty requests a minute, and a
	 * "Send every" of five seconds with slots filling continuously would be
	 * twelve ingests plus twenty-four re-reads. Coalescing the re-reads keeps
	 * that near twenty. A re-read that comes too soon is deferred, not
	 * dropped, so the last send of a burst still gets its refresh.
	 */
	private static final long ACCOUNT_TABS_REFRESH_SECONDS = 15;

	/** The tabs that are re-read on their own; Account is only read by connect. */
	private enum PanelTab
	{
		TRADES, JOURNAL, WATCHLISTS
	}

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
	private ClientThread clientThread;

	/**
	 * Whether the client was started with --developer-mode. The only thing it
	 * unlocks is the server URL setting, for running against a local server.
	 */
	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	private final OfferTracker tracker = new OfferTracker();

	/**
	 * Queue and disk work. Everything that touches {@link TransactionQueue}
	 * runs here, which keeps file writes off the game thread and means the
	 * queue's monitor is essentially uncontended. The one exception is a fill
	 * captured after this executor has stopped, which the game thread writes
	 * through itself rather than drop.
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
	 * Guards the sender against re-entry. Every drain -- the scheduled tick,
	 * the panel's button, the offer and history catch-ups, shutdown -- runs on
	 * {@link #sendExecutor}, which has one thread, so today this is never
	 * contended. It stays because the invariant it protects matters: two
	 * threads draining the same queue would send the same batch twice, and
	 * while the server would drop the repeat, the panel's counts would be
	 * nonsense. A future caller on another thread hits this rather than that.
	 */
	private final AtomicBoolean sending = new AtomicBoolean();

	private volatile FlippingRsApi api;
	private FlippingRsPanel panel;
	private GeMenu geMenu;
	private GeQuoteOverlay quoteOverlay;

	// The collaborators. Built by wire(), from the fields above, once those
	// are in place: in startUp, or by a test that sets them directly.
	private ProfileStore store;
	private Watchlists watchlists;
	private CatchUp catchUp;
	private PositionActions positions;

	private ScheduledFuture<?> quoteTask;
	private int loggedInTick = -1;

	/**
	 * Whether the next LOGGED_IN is the client arriving in the world rather
	 * than finishing a map load. See {@link #onGameStateChanged}. Starts true
	 * so that a plugin enabled before login treats the first one as an
	 * arrival, which is the conservative direction.
	 */
	private boolean arrivingInWorld = true;

	/**
	 * Set while the client or the plugin is stopping, so the final drain
	 * sends and nothing more: the exit budget is ten seconds for every
	 * plugin together, and re-reading tabs nobody will see is not worth any
	 * of it.
	 */
	private volatile boolean shuttingDown;

	/**
	 * When the account tabs were last re-read, on the nanoTime clock, or
	 * {@link #NEVER}. That clock's origin is arbitrary and it may well be
	 * negative, so "not yet" needs a marker of its own rather than a zero,
	 * and every comparison against it is a subtraction rather than a sum, so
	 * that a wrap comes out right instead of deferring a refresh for the
	 * next three hundred years.
	 */
	private static final long NEVER = Long.MIN_VALUE;

	/**
	 * Whether the sidebar is currently showing this panel. Set from the
	 * panel's own activate and deactivate, and read on the net thread to
	 * decide whether a tab is worth re-reading at all.
	 */
	private volatile boolean sidebarShown;

	private volatile long accountTabsRefreshedAt = NEVER;
	private final AtomicBoolean accountTabsRefreshPending = new AtomicBoolean();

	/**
	 * The journals the key can file under, as last loaded. Kept so that a
	 * login on a different RuneScape account can re-point the picker at that
	 * account's remembered journal without another round trip. Null until the
	 * first successful load.
	 */
	@Nullable
	private volatile List<FlippingRsApi.GameAccount> knownAccounts;
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

		// RuneLite reuses the plugin instance across disable and enable, so
		// "this session" has to be reset by hand or it carries over.
		recordedThisSession.set(0);
		lastSyncAt = null;
		knownAccounts = null;
		shuttingDown = false;
		loggedInTick = -1;
		arrivingInWorld = true;
		// The deferred re-read these two coalesce was scheduled on the executor
		// the last shutDown stopped, so it will never run and never clear the
		// flag. Left set, it swallows the first coalesced re-read of the new
		// session.
		accountTabsRefreshedAt = NEVER;
		accountTabsRefreshPending.set(false);
		sidebarShown = false;

		api = newApi();
		wire();

		geMenu = new GeMenu(client, itemManager, this::openItem,
			itemId -> submit(sendExecutor, () -> addToWatchlist(itemId)));
		quoteOverlay = new GeQuoteOverlay(client, this::watchedQuote);
		overlayManager.add(quoteOverlay);

		panel = new FlippingRsPanel();
		panel.onSyncNow(() -> submit(sendExecutor, this::drain));
		panel.onReconnect(() -> submit(sendExecutor, this::connect));
		panel.onAccountChosen(this::rememberChosenAccount);
		panel.onWatchlistChosen(this::rememberChosenWatchlist);
		panel.onOpenItem(this::openItem);
		panel.onRemoveItem(itemId -> submit(sendExecutor, () -> removeFromWatchlist(itemId)));
		panel.onFindFlips(() -> LinkBrowser.browse(api.finderUrl()));
		panel.onClosePosition((id, price, qty) -> submit(sendExecutor, () -> closePosition(id, price, qty)));
		panel.onDeletePosition(id -> submit(sendExecutor, () -> deletePosition(id)));
		panel.onShown(() -> sidebarShown(true));
		panel.onHidden(() -> sidebarShown(false));

		navButton = NavigationButton.builder()
			.tooltip("FlippingRS")
			.icon(icon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		scheduleSync();
		quoteTask = sendExecutor.scheduleWithFixedDelay(this::quotesTick,
			QUOTE_REFRESH_SECONDS, QUOTE_REFRESH_SECONDS, TimeUnit.SECONDS);
		submit(sendExecutor, this::connect);
	}

	/**
	 * Builds the collaborators from the injected fields and the executors.
	 *
	 * <p>Separate from startUp so a test can set the fields and call this
	 * without the rest of startUp, which builds a nav button and a real HTTP
	 * client. Each collaborator reads {@link #api} through a supplier rather
	 * than holding it, because a developer-mode server change replaces it.
	 */
	void wire()
	{
		store = new ProfileStore(configManager, gson);
		watchlists = new Watchlists(client, itemManager, clientThread, config, store, () -> api, this::onPanel,
			this::itemName, () -> refresh(PanelTab.WATCHLISTS), work -> submit(sendExecutor, work));
		catchUp = new CatchUp(client, config, store, () -> api, this::onPanel, this::itemName, this::drain,
			this::refreshAccountTabsAfterSend, work -> submit(sendExecutor, work));
		positions = new PositionActions(config, () -> api, this::onPanel, () -> refresh(PanelTab.JOURNAL));
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
	private static boolean submit(ScheduledExecutorService on, Runnable work)
	{
		if (on == null || on.isShutdown())
		{
			return false;
		}
		try
		{
			on.execute(work);
			return true;
		}
		catch (RejectedExecutionException e)
		{
			// Lost the race with shutdown. Nothing to do and nothing wrong.
			log.debug("dropped work submitted during shutdown", e);
			return false;
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
		if (quoteTask != null)
		{
			quoteTask.cancel(false);
			quoteTask = null;
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
		shuttingDown = true;
		submit(sendExecutor, this::drain);
		// Null-guarded because startUp can throw part way through -- a toolbar
		// that will not take the nav button, say -- and RuneLite still calls
		// shutDown on a plugin whose startUp failed. An NPE here would bury the
		// real cause under a second, less useful stack trace.
		if (sendExecutor != null)
		{
			sendExecutor.shutdown();
		}
		if (diskExecutor != null)
		{
			diskExecutor.shutdown();
		}

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (quoteOverlay != null)
		{
			overlayManager.remove(quoteOverlay);
			quoteOverlay = null;
		}
		panel = null;
		geMenu = null;
		if (watchlists != null)
		{
			watchlists.reset();
		}
	}

	/**
	 * The HTTP client for the server the plugin talks to.
	 *
	 * <p>RuneLite's injected client sets no call timeout, so a server that
	 * accepts a connection and then goes quiet holds the thread until the OS
	 * gives up. A bound turns that into a retry instead of a wedge.
	 */
	private FlippingRsApi newApi()
	{
		return new FlippingRsApi(
			okHttpClient.newBuilder().callTimeout(Duration.ofSeconds(30)).build(), gson, baseUrl());
	}

	/**
	 * flippingrs.com, unless this is a developer-mode client with the server
	 * URL setting filled in. The setting is not consulted at all otherwise,
	 * so a value left in an ordinary install's config can do nothing.
	 */
	private HttpUrl baseUrl()
	{
		if (!developerMode)
		{
			return FlippingRsApi.BASE_URL;
		}
		final String configured = config.baseUrl();
		if (configured == null || configured.trim().isEmpty())
		{
			return FlippingRsApi.BASE_URL;
		}
		final HttpUrl parsed = HttpUrl.parse(configured.trim());
		if (parsed == null)
		{
			log.warn("ignoring the server URL setting: {} is not a URL", configured);
			return FlippingRsApi.BASE_URL;
		}
		if (!parsed.equals(FlippingRsApi.BASE_URL))
		{
			log.info("developer mode: talking to {} instead of flippingrs.com", parsed);
		}
		return parsed;
	}

	// ------------------------------------------------------ exchange menu

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		final GeMenu menu = geMenu;
		if (menu == null || !config.geMenuEntries())
		{
			return;
		}
		menu.onMenuOpened(event);
	}

	private void openItem(int itemId)
	{
		LinkBrowser.browse(api.itemUrl(itemId));
	}

	// ------------------------------------------------------ watchlist, positions
	//
	// Thin: the work is in Watchlists and PositionActions. These exist so the
	// menu, the panel and the overlay have one place to hand off to.

	/** Client thread, from the overlay, once per frame. */
	@Nullable
	private FlippingRsApi.Quote watchedQuote(int itemId)
	{
		return watchlists.watchedQuote(itemId);
	}

	/** Swing thread, from the picker. */
	private void rememberChosenWatchlist()
	{
		final FlippingRsPanel target = panel;
		if (target == null)
		{
			return;
		}
		final String id = target.selectedWatchlistId();
		if (id != null)
		{
			watchlists.chosen(id);
		}
	}

	/** Net thread. */
	private void addToWatchlist(int itemId)
	{
		watchlists.add(itemId);
	}

	/** Net thread. */
	private void removeFromWatchlist(int itemId)
	{
		watchlists.remove(itemId);
	}

	/** Net thread. */
	private void closePosition(String positionId, long sellPrice, @Nullable Long sellQty)
	{
		positions.close(positionId, sellPrice, sellQty);
	}

	/** Net thread. */
	private void deletePosition(String positionId)
	{
		positions.delete(positionId);
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
		//
		// Only the clears are filtered, which is the line RuneLite's own
		// Grand Exchange plugin draws. Filtering every event outside LOGGED_IN
		// also dropped fills that arrived while the client was LOADING a
		// region, without advancing the baseline. Usually the next event's
		// delta covered it -- but an offer that completed in that window and
		// was then collected had its final fill cleared away with the slot.
		if (offer.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
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

		final SavedOffer previous = store.loadOffer(slot);

		final OfferTracker.Observation seen = tracker.observe(
			slot, previous, offer, () -> itemName(offer.getItemId()), client.getWorld(), Instant.now());

		// Persist the new baseline before queueing the fill. If the client dies
		// between the two, one fill is lost; in the other order, every fill in
		// this slot is reported again on the next login. A missing trade is
		// visible and fixable by hand. A duplicated one is neither, because
		// nobody notices a profit figure that is quietly too high.
		if (seen.saved == null)
		{
			store.clearOffer(slot);
		}
		else
		{
			store.saveOffer(slot, seen.saved);
		}

		watchlists.offerChanged(offer.getItemId());

		if (seen.adopted)
		{
			log.debug("adopted an in-progress offer in slot {}; its progress goes out as a recovered fill", slot);
			onPanel(p -> p.setActivityNotice(
				"Found an offer that was already part-way done. What had already traded has been sent to your "
					+ "journal as a recovered trade without a time, and flippingrs.com will check it isn't already there.",
				ColorScheme.BRAND_ORANGE));
		}

		final GeTransaction tx = seen.transaction;
		if (tx == null)
		{
			return;
		}

		// A delta seen within a couple of ticks of logging in is the client
		// replaying what the exchange did while nobody was watching. It is
		// real, but its time is not now: an offer that filled overnight and
		// was stamped with the login time could put a sale ahead of the
		// purchase it belongs to. It goes out untimed, like an adoption, and
		// the server treats it the same way.
		if (GeTransaction.SOURCE_LIVE.equals(tx.source) && loggedInTick >= 0
			&& client.getTickCount() - loggedInTick <= LOGIN_BURST_TICKS)
		{
			tx.source = GeTransaction.SOURCE_ADOPTED;
			tx.occurredAt = null;
		}

		if (!config.enabled())
		{
			// Off means the plugin is not recording as it goes: the fill is
			// discarded rather than held for later, and the baseline above
			// still advanced, so it is not re-reported as a live fill either.
			// It is not a promise that the trade can never reach the journal.
			// Once recording is back on, the catch-up from the open slots and
			// the history screen reports what the client shows, and the server
			// may recover a completed offer from that, untimed. The settings
			// text and the README say so.
			log.debug("recording is off; discarding {}", tx);
			return;
		}

		if (onSeparateEconomy())
		{
			// The baseline still advanced above, so nothing is re-reported if
			// the same offer is looked at again. The fill itself belongs to a
			// world whose gp is not the journal's gp.
			log.debug("on a world with its own economy; not recording {}", tx);
			return;
		}

		final int recorded = recordedThisSession.incrementAndGet();

		// Hand off, rather than queueing inline. This method runs on the game
		// thread, and TransactionQueue.add writes the fill through to disk
		// before returning: an append normally, a full rewrite of the file on
		// the add that has to evict. Either is a disk write, and on the game
		// thread that is a stall on every Grand Exchange fill. It could also
		// block behind the sender's rewrite after a confirmed send, since both
		// take the queue's monitor.
		//
		// queueFor is on this side of the handoff too: constructing a queue
		// reads its file back, so the first fill after login would otherwise
		// be a disk read on the game thread as well.
		final boolean handedOver = submit(diskExecutor, () ->
		{
			final TransactionQueue queue = queueFor(accountHash);
			queue.add(tx);
			// Read here rather than inside the Swing lambda: size() takes the
			// queue's monitor, and the Swing thread should not wait on a disk
			// rewrite the net thread happens to be in the middle of.
			final int waiting = queue.size();
			final List<GeTransaction> buffered = queue.newest(FlippingRsPanel.RECENT_SHOWN);
			onPanel(p ->
			{
				p.setCounts(recorded, waiting);
				p.setPending(buffered);
			});
		});

		if (!handedOver)
		{
			// The io thread is gone: the plugin is being disabled and an offer
			// event was already in flight on the game thread. Everywhere else
			// that loses this race has nothing to lose -- a panel update, a
			// re-read -- but this is a trade that happened, and dropping it
			// breaks the one promise the queue exists to keep. Write it through
			// here instead. It is a disk write on the game thread, which is
			// exactly what the handoff above avoids, but it happens only while
			// the plugin is stopping: a stall nobody is playing through beats a
			// trade nobody recorded. It goes out on the next login.
			log.debug("the io thread is gone; writing {} through from the game thread", tx);
			queueFor(accountHash).add(tx);
		}
	}

	/** Whether the world the client is on trades in gp that is not the journal's gp. */
	private boolean onSeparateEconomy()
	{
		final EnumSet<WorldType> types = client.getWorldType();
		if (types == null)
		{
			return false;
		}
		for (WorldType type : types)
		{
			if (SEPARATE_ECONOMY.contains(type))
			{
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------- client events

	/**
	 * Notices the client arriving in the world, which is what the login burst
	 * follows -- and only that.
	 *
	 * <p>LOGGED_IN is not the same thing as having just logged in. The client
	 * drops to LOADING and back to LOGGED_IN every time it loads a map region,
	 * which is every few minutes of running about. Treating each of those as a
	 * login meant any fill that happened to land in the second after one was
	 * stripped of its time and sent as though nobody had watched it, when the
	 * plugin had watched it happen and knew exactly when. An untimed fill is
	 * the right answer for progress made while the plugin was away; it is a
	 * loss for one it saw.
	 *
	 * <p>LOADING is the only state that comes between two LOGGED_INs without
	 * the client having left the world. Anything else -- LOGGING_IN, HOPPING,
	 * CONNECTION_LOST, the login screen -- means the next LOGGED_IN is an
	 * arrival, and after an arrival the exchange does replay every slot.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			if (arrivingInWorld)
			{
				arrivingInWorld = false;
				loggedInTick = client.getTickCount();
				catchUp.loggedIn(loggedInTick);
			}
			return;
		}
		if (state != GameState.LOADING)
		{
			arrivingInWorld = true;
			// The exchange cannot still be open on a world the client has left,
			// and it is only ever closed here: logging out tears the widget
			// tree down without a WidgetClosed for each of its interfaces, so
			// the flag stuck on and the quote timer went on making a request
			// every thirty seconds, forever, against a thirty-a-minute limit,
			// for an offer screen that had been gone since the last session.
			watchlists.exchangeOpen(false);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.GE_OFFERS)
		{
			watchlists.exchangeOpen(true);
			catchUp.exchangeOpened(client.getTickCount());
		}
		else if (event.getGroupId() == InterfaceID.GE_HISTORY)
		{
			catchUp.historyOpened(client.getTickCount());
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.GE_OFFERS)
		{
			watchlists.exchangeOpen(false);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		catchUp.tick(client.getTickCount());
	}

	/**
	 * A different RuneScape account is now active, or none is.
	 *
	 * <p>The journal is remembered per account, but the picker was only ever
	 * redrawn by {@link #connect}. Logging out of a main and into an alt left
	 * it showing the main's journal while trades went to the alt's -- or were
	 * held with "no journal picked" while the panel plainly showed one. The
	 * picker is re-pointed here from the list already loaded, and an account
	 * seen for the first time adopts the default the way a fresh install does.
	 */
	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		final List<FlippingRsApi.GameAccount> accounts = knownAccounts;
		if (accounts == null)
		{
			// Nothing loaded yet; connect will do this when it succeeds.
			return;
		}
		final String chosen = store.chosenAccount();
		onPanel(p ->
		{
			p.setAccounts(accounts, chosen);
			if (chosen == null)
			{
				rememberChosenAccountFrom(p, false);
			}
		});
		// The recent trades, the journal and the buffer are the account's, so
		// they change with it.
		submit(sendExecutor, () ->
		{
			refresh(PanelTab.TRADES);
			refresh(PanelTab.JOURNAL);
		});
		refreshPending();
	}

	/**
	 * The client is closing. RuneLite does not call shutDown for that; this is
	 * the only notice, and it waits a bounded time for whatever is handed to
	 * it. Two things are worth that wait: the fill the game thread just handed
	 * to the io thread, which is otherwise on a daemon thread that dies with
	 * the JVM, and a last send, so the evening's final trades are not held
	 * until the next login.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		shuttingDown = true;
		final CompletableFuture<Void> done = new CompletableFuture<>();
		final boolean queued = submit(diskExecutor, () ->
		{
			// Everything the game thread handed over before this ran is now on
			// disk. From here the send is the only thing left.
			final boolean sending = submit(sendExecutor, () ->
			{
				try
				{
					drain();
				}
				finally
				{
					done.complete(null);
				}
			});
			if (!sending)
			{
				done.complete(null);
			}
		});
		if (!queued)
		{
			return;
		}
		event.waitFor(done);
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
			// Turning recording back on has to re-check the key and reload the
			// journals, because nothing was contacted while it was off.
			case "enabled":
				submit(sendExecutor, this::connect);
				break;
			case "syncSeconds":
				scheduleSync();
				break;
			case "baseUrl":
				if (developerMode)
				{
					api = newApi();
					watchlists.forget();
					submit(sendExecutor, this::connect);
				}
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
			syncTask = null;
		}
		// Same race the submit helper exists for: a ConfigChanged already in
		// flight can land after shutDown has stopped the executor.
		if (sendExecutor == null || sendExecutor.isShutdown())
		{
			return;
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
	 * <p>Never throws. It runs as a {@code scheduleWithFixedDelay} task on
	 * {@link #sendExecutor}, and an exception escaping such a task cancels it
	 * for good -- the plugin would go quiet with nothing in the log to say why.
	 */
	private void drain()
	{
		// The claim above that this never throws has to hold for every line of
		// it, so the only thing outside the try is the one statement that
		// cannot throw. Reading a setting goes through a config proxy, and an
		// exception from that used to escape and cancel the schedule.
		if (!sending.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			// Checked before anything else, and before connect's equivalent
			// check, because "Record trades" being off is a promise that the
			// plugin is not talking to flippingrs.com at all -- not merely that
			// it has stopped capturing. Anything already queued stays on disk
			// and goes out when recording is turned back on; it was captured
			// while the user wanted it recorded, so discarding it would be its
			// own kind of surprise.
			if (!config.enabled())
			{
				return;
			}
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
					"No API key yet. Add one in the plugin settings. Your trades are being kept safe until you do.",
					ColorScheme.BRAND_ORANGE));
				return;
			}
			final String accountId = store.chosenAccount();
			if (accountId == null)
			{
				onPanel(p -> p.setStatus(
					"No journal chosen for this character yet. Pick one on the Account tab. Your trades are being "
						+ "kept safe until you do.",
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

			final Sent sent = new Sent();
			if (!send(queue, key, accountId, batch, sent))
			{
				// Retrying cannot help, and leaving this at the head of the
				// queue would wedge every later trade behind it forever. Find
				// the rows at fault, set exactly those aside, and let the rest
				// through.
				narrow(queue, key, accountId, batch, sent);
			}

			if (sent.accepted > 0)
			{
				lastSyncAt = Instant.now();
			}
			final int waiting = queue.size();
			final List<GeTransaction> buffered = queue.newest(FlippingRsPanel.RECENT_SHOWN);

			log.debug("sent {} fills: {} flips opened, {} closed, {} unmatched",
				sent.accepted, sent.flipsOpened, sent.flipsClosed, sent.unmatchedSellQty);

			// A 200 can still refuse individual rows, and the batch is dropped
			// from the queue regardless -- so if this is not surfaced here, the
			// trade is gone and nobody is ever told. Silently losing one is far
			// worse than a blunt warning, because the journal then disagrees
			// with what the player remembers doing and nothing explains why.
			if (sent.rejected > 0)
			{
				log.warn("flippingrs.com refused {} of {} fills: {}", sent.rejected, batch.size(), sent.problems);
			}
			if (sent.setAside > 0)
			{
				log.warn("set aside {} fills that flippingrs.com will not accept; they are in {}",
					sent.setAside, queue.droppedFile(), sent.cause);
			}

			if (sent.accepted > 0 && !shuttingDown)
			{
				refreshAccountTabsAfterSend();
			}

			final Instant syncedAt = lastSyncAt;
			final String droppedFile = queue.droppedFile().getName();
			onPanel(p -> {
				p.setCounts(recordedThisSession.get(), waiting);
				p.setPending(buffered);
				if (sent.accepted > 0)
				{
					p.setLastSync(syncedAt, null);
					p.setStatus("Connected and recording.", ColorScheme.PROGRESS_COMPLETE_COLOR);
				}
				else
				{
					p.setLastSync(null, FlippingRsApi.describe(sent.cause));
				}
				if (sent.setAside > 0)
				{
					p.setActivityNotice("flippingrs.com couldn't accept " + sent.setAside + " trade(s). They have been "
						+ "set aside in " + droppedFile + " in your RuneLite folder so nothing is lost. The client log "
						+ "says why.", ColorScheme.PROGRESS_ERROR_COLOR);
				}
				else if (sent.rejected > 0)
				{
					p.setActivityNotice("flippingrs.com couldn't record " + sent.rejected
						+ " trade(s). The client log says why.", ColorScheme.PROGRESS_ERROR_COLOR);
				}
				else if (sent.unmatchedSellQty > 0)
				{
					p.setActivityNotice(sent.unmatchedSellQty
							+ " item(s) were sold without a recorded purchase, so they can't be counted as a flip yet.",
						ColorScheme.BRAND_ORANGE);
				}
				else
				{
					p.setActivityNotice(null, ColorScheme.LIGHT_GRAY_COLOR);
				}
			});
		}
		catch (IOException e)
		{
			// Worth retrying: the batch stays queued for the next tick.
			log.debug("could not send to flippingrs.com; will retry", e);
			final String why = FlippingRsApi.describe(e);
			onPanel(p -> p.setLastSync(null, why));
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure while sending", e);
			onPanel(p -> p.setLastSync(null, "Something went wrong while sending. Details are in the client log."));
		}
		finally
		{
			sending.set(false);
		}
	}

	/** What one drain achieved, added up over however many sends it took. */
	private static final class Sent
	{
		/** Rows the server took, whatever it then made of them. */
		int accepted;
		/** Rows the server took and then refused individually, in a 200. They are gone. */
		int rejected;
		/** Rows set aside on disk after a refusal that retrying cannot fix. */
		int setAside;
		int flipsOpened;
		int flipsClosed;
		long unmatchedSellQty;
		final List<String> problems = new java.util.ArrayList<>();
		/** The last permanent refusal, for the panel and the log. */
		@Nullable
		FlippingRsApi.PermanentException cause;

		void took(int rows, FlippingRsApi.IngestResult result)
		{
			accepted += rows;
			rejected += result.getRejected();
			flipsOpened += result.getFlipsOpened();
			flipsClosed += result.getFlipsClosed();
			unmatchedSellQty += result.getUnmatchedSellQty();
			problems.addAll(result.getProblems());
		}
	}

	/**
	 * One send of one batch. On a 2xx the rows are confirmed out of the queue
	 * and the result is added up.
	 *
	 * @return false if the server refused the batch for good, with the cause
	 *         recorded on {@code sent}; anything retryable propagates
	 */
	private boolean send(TransactionQueue queue, String key, String accountId, List<GeTransaction> batch, Sent sent)
		throws IOException
	{
		try
		{
			final FlippingRsApi.IngestResult result = api.submit(key, accountId, batch);
			queue.confirm(batch);
			sent.took(batch.size(), result);
			return true;
		}
		catch (FlippingRsApi.PermanentException e)
		{
			sent.cause = e;
			return false;
		}
	}

	/**
	 * Finds the rows behind a refused batch and sets exactly those aside.
	 *
	 * <p>A 400 or 422 says the server will not take this batch. It does not
	 * say which row is at fault, and setting aside five hundred fills for one
	 * bad row is a lot of journal to lose. So a refused batch of more than one
	 * is split in half and each half sent on its own; one bad row is found in
	 * about nine rounds of that, and every good row goes through. If both
	 * halves are refused as well, the fault is taken to be the batch as a
	 * whole -- the envelope, the key, the account -- and both are set aside
	 * without going further, which keeps a refusal that no split can fix to
	 * three requests rather than a thousand.
	 *
	 * <p>The batch is the one that was sent, not a fresh read of the queue.
	 * Fills arrive on the disk thread while a request is in flight, and peek
	 * returns from the head, so re-reading would set aside trades that had
	 * never been sent.
	 *
	 * @param batch a batch the server has just refused as a whole
	 */
	private void narrow(TransactionQueue queue, String key, String accountId, List<GeTransaction> batch, Sent sent)
		throws IOException
	{
		if (batch.size() <= 1)
		{
			setAside(queue, batch, sent);
			return;
		}
		final int mid = batch.size() / 2;
		final List<GeTransaction> first = batch.subList(0, mid);
		final List<GeTransaction> second = batch.subList(mid, batch.size());
		final boolean firstTaken = send(queue, key, accountId, first, sent);
		final boolean secondTaken = send(queue, key, accountId, second, sent);
		if (!firstTaken && !secondTaken)
		{
			setAside(queue, batch, sent);
			return;
		}
		if (!firstTaken)
		{
			narrow(queue, key, accountId, first, sent);
		}
		if (!secondTaken)
		{
			narrow(queue, key, accountId, second, sent);
		}
	}

	/**
	 * Takes refused fills out of the queue and onto the sibling file, where a
	 * user who is told a trade could not be recorded can still find it.
	 */
	private static void setAside(TransactionQueue queue, List<GeTransaction> batch, Sent sent)
	{
		queue.reject(batch);
		sent.setAside += batch.size();
	}

	/** The machine's UTC offset, so the server's daily buckets fall on the player's calendar. */
	private static int tzOffsetMinutes()
	{
		return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000;
	}

	/**
	 * Shows what is still buffered for the logged-in account. Disk thread,
	 * because opening the queue reads its file.
	 */
	private void refreshPending()
	{
		final long accountHash = client.getAccountHash();
		if (accountHash == NO_ACCOUNT)
		{
			onPanel(p ->
			{
				p.setPending(Collections.emptyList());
				p.setCounts(recordedThisSession.get(), 0);
			});
			return;
		}
		submit(diskExecutor, () ->
		{
			final TransactionQueue queue = queueFor(accountHash);
			final int waiting = queue.size();
			final List<GeTransaction> buffered = queue.newest(FlippingRsPanel.RECENT_SHOWN);
			onPanel(p ->
			{
				p.setCounts(recordedThisSession.get(), waiting);
				p.setPending(buffered);
			});
		});
	}

	// ------------------------------------------------------------ panel reads

	/**
	 * Puts a panel reply on the screen. Only the parts present are touched,
	 * so a partial read leaves the rest of the sidebar as it was.
	 *
	 * @param connecting true for the read that doubles as the connection
	 *                   test, which also sets the connection status
	 */
	private void applyPanel(FlippingRsApi.Panel reply, boolean connecting)
	{
		final FlippingRsApi.Me me = reply.getMe();
		if (me != null)
		{
			final String plan = "Plan: " + me.describePlan();
			onPanel(p -> p.setSubscription(plan));
		}

		final List<FlippingRsApi.GameAccount> accounts = reply.getAccounts();
		if (accounts != null)
		{
			knownAccounts = accounts;
			final String chosen = store.chosenAccount();

			// The remembered journal is gone -- deleted on the site, or the key
			// now belongs to a different FlippingRS account. Sending to it
			// would be refused every tick, and the picker would meanwhile show
			// whichever entry sorted first. Forget the choice and say so, and
			// hold the trades until a real one is made.
			final boolean orphaned = chosen != null && !accounts.isEmpty() && !contains(accounts, chosen);
			if (orphaned)
			{
				log.warn("the journal remembered for this account ({}) no longer exists; forgetting it", chosen);
				store.forgetChosenAccount();
			}

			onPanel(p -> {
				// The orphaned id is passed through on purpose: the panel shows
				// no selection for a remembered journal it cannot find, where a
				// null would have it select the default as if nothing had ever
				// been chosen.
				p.setAccounts(accounts, chosen);
				if (orphaned)
				{
					p.setStatus("The journal this character was using no longer exists on flippingrs.com. Pick "
						+ "another below. Your trades are being kept safe until you do.", ColorScheme.BRAND_ORANGE);
					return;
				}
				if (connecting)
				{
					p.setStatus(accounts.isEmpty()
							? "Connected, but your flippingrs.com account has no journals yet. Create one on the site first."
							: "Connected and recording.",
						accounts.isEmpty() ? ColorScheme.BRAND_ORANGE : ColorScheme.PROGRESS_COMPLETE_COLOR);
				}
				// A RuneScape account seen for the first time has nothing chosen
				// yet. Adopting whatever the panel selected saves a setup step,
				// and it can still be changed. If nobody is logged in yet, the
				// profile-changed event does this on login instead. (An orphaned
				// choice returned above, so this never adopts over one.)
				if (chosen == null)
				{
					rememberChosenAccountFrom(p, false);
				}
			});
		}
		else if (connecting)
		{
			onPanel(p -> p.setStatus("Connected and recording.", ColorScheme.PROGRESS_COMPLETE_COLOR));
		}

		watchlists.accept(reply.getWatchlists(), reply.getQuotes());

		final List<GeTransaction> all = reply.getRecentTransactions();
		if (all != null)
		{
			// Only the rows that will actually be drawn. The tab shows the
			// newest few and drops the rest, and resolving a sprite for a row
			// nobody will see is item-manager work on the game thread for
			// nothing -- however many the server decides to send back.
			final List<GeTransaction> rows = all.size() > FlippingRsPanel.RECENT_SHOWN
				? all.subList(0, FlippingRsPanel.RECENT_SHOWN)
				: all;
			// Sprites come from the item manager, which wants the client thread.
			clientThread.invoke(() ->
			{
				final Map<Integer, net.runelite.client.util.AsyncBufferedImage> images = new java.util.HashMap<>();
				for (GeTransaction tx : rows)
				{
					if (!images.containsKey(tx.itemId))
					{
						images.put(tx.itemId, spriteOf(tx.itemId));
					}
				}
				onPanel(p -> p.setActivity(rows, images));
			});
		}

		final FlippingRsApi.Analytics week = reply.getWeek();
		final FlippingRsApi.Positions open = reply.getPositions();
		if (week != null && open != null)
		{
			onPanel(p -> p.setJournal(week, open));
		}
	}

	/**
	 * Checks the key and loads every tab.
	 *
	 * <p>The Account read is the connection test: if it fails, nothing else
	 * is tried and the Account tab says why. The other tabs are then read one
	 * by one, and each reports its own failure on its own tab, since a key
	 * that just worked is not a broken connection.
	 */
	private void connect()
	{
		if (!config.enabled())
		{
			onPanel(p -> {
				p.setAccounts(Collections.emptyList(), null);
				p.setStatus("Recording is off. Nothing is being recorded or sent to flippingrs.com. Switch "
					+ "\"Record trades\" back on in the plugin settings to carry on.",
					ColorScheme.LIGHT_GRAY_COLOR);
				// Old rows next to a status that says nothing is being read
				// would be a picture of a journal the plugin is not looking at.
				p.setPaused("Recording is off, so nothing is being read from flippingrs.com.");
			});
			// And the offer screen, which draws the same quotes the sidebar
			// does. Left alone it would go on showing the site's prices, frozen
			// at whatever they were when recording was switched off, in front
			// of the box where a price gets typed.
			watchlists.forget();
			return;
		}

		final String key = config.apiKey().trim();
		if (key.isEmpty())
		{
			onPanel(p -> {
				p.setAccounts(Collections.emptyList(), null);
				p.setStatus("Add your API key in the plugin settings. You can create one on flippingrs.com "
					+ "under Account, then API keys.", ColorScheme.LIGHT_GRAY_COLOR);
				p.setPaused("Add an API key to see your journal here.");
			});
			watchlists.forget();
			return;
		}

		try
		{
			applyPanel(api.account(key), true);
		}
		catch (IOException e)
		{
			log.debug("could not reach flippingrs.com", e);
			final String why = FlippingRsApi.describe(e);
			onPanel(p -> p.setStatus("Could not connect: " + why, ColorScheme.PROGRESS_ERROR_COLOR));
			return;
		}
		refresh(PanelTab.TRADES);
		refresh(PanelTab.JOURNAL);
		accountTabsRefreshedAt = System.nanoTime();
		refresh(PanelTab.WATCHLISTS);
		refreshPending();
		// The key may have been missing or wrong while trades piled up.
		submit(sendExecutor, this::drain);
	}

	/**
	 * The sidebar opened on this panel, or closed. Swing thread.
	 *
	 * <p>Opening reads everything the sidebar shows, because while it was shut
	 * none of it was. The two account tabs go through the same throttle the
	 * sends use, so opening and closing it repeatedly cannot become a burst of
	 * requests against a limit of thirty a minute.
	 */
	private void sidebarShown(boolean shown)
	{
		sidebarShown = shown;
		watchlists.sidebarShown(shown);
		if (!shown)
		{
			return;
		}
		submit(sendExecutor, () ->
		{
			refresh(PanelTab.WATCHLISTS);
			refreshAccountTabs();
		});
	}

	/** Re-reads Trades and Journal after a send, no more often than the limit allows. Net thread. */
	private void refreshAccountTabsAfterSend()
	{
		// Not a panel read, and so not conditional on anyone looking: this is
		// how the server learns what the open slots hold and recovers a fill
		// the plugin never saw. CatchUp keeps its own, longer, minimum gap.
		clientThread.invoke(catchUp::snapshotAfterSend);

		if (!sidebarShown)
		{
			// Nobody can see the two tabs. Reading them anyway is two requests
			// per send against a limit of thirty a minute that the sends
			// themselves draw on, to redraw a panel that is shut -- and a
			// flipper keeps the exchange open and the sidebar shut. Opening it
			// reads them.
			return;
		}
		refreshAccountTabs();
	}

	/**
	 * Re-reads Trades and Journal, no more often than the limit allows. Net
	 * thread. A read that comes too soon is deferred rather than dropped, so
	 * the last send of a burst still gets its refresh.
	 */
	private void refreshAccountTabs()
	{
		final long now = System.nanoTime();
		final long since = now - accountTabsRefreshedAt;
		final long window = TimeUnit.SECONDS.toNanos(ACCOUNT_TABS_REFRESH_SECONDS);
		if (accountTabsRefreshedAt == NEVER || since >= window)
		{
			accountTabsRefreshedAt = now;
			refresh(PanelTab.TRADES);
			refresh(PanelTab.JOURNAL);
			return;
		}
		final long wait = window - since;
		if (!accountTabsRefreshPending.compareAndSet(false, true))
		{
			// One is already on its way, and it will see this send's rows too.
			return;
		}
		try
		{
			sendExecutor.schedule(() ->
			{
				accountTabsRefreshPending.set(false);
				accountTabsRefreshedAt = System.nanoTime();
				refresh(PanelTab.TRADES);
				refresh(PanelTab.JOURNAL);
			}, wait, TimeUnit.NANOSECONDS);
		}
		catch (RejectedExecutionException e)
		{
			// Shutting down; the next connect re-reads everything anyway.
			accountTabsRefreshPending.set(false);
		}
	}

	/**
	 * Re-reads one tab from its endpoint and redraws it. Net thread. Never
	 * throws, because the watchlist refresh also runs on a fixed-delay
	 * schedule, where an escaping exception would cancel it for good.
	 *
	 * <p>A failure is reported on that tab, not as a failed connection: the
	 * key was good a moment ago and the fills are still going out.
	 */
	private void refresh(PanelTab tab)
	{
		try
		{
			if (!config.enabled())
			{
				return;
			}
			final String key = config.apiKey().trim();
			if (key.isEmpty())
			{
				return;
			}
			final String accountId = store.chosenAccount();
			final FlippingRsApi.Panel part;
			switch (tab)
			{
				case TRADES:
					if (accountId == null)
					{
						return;
					}
					part = api.trades(key, accountId);
					break;
				case JOURNAL:
					if (accountId == null)
					{
						return;
					}
					part = api.journal(key, accountId, tzOffsetMinutes());
					break;
				case WATCHLISTS:
					part = api.watchlists(key, store.rememberedWatchlistId());
					break;
				default:
					return;
			}
			applyPanel(part, false);
		}
		catch (IOException e)
		{
			log.warn("could not refresh the {} tab: {}", tab, e.getMessage());
			final String why = FlippingRsApi.describe(e);
			onPanel(p ->
			{
				switch (tab)
				{
					case TRADES:
						p.setActivityProblem(why);
						break;
					case JOURNAL:
						p.setJournalProblem(why);
						break;
					case WATCHLISTS:
						p.setWatchlistProblem(why);
						break;
					default:
						break;
				}
			});
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure refreshing the {} tab", tab, e);
		}
	}

	/**
	 * The tick that keeps quotes current, while there is something to quote
	 * and somewhere it is shown.
	 *
	 * <p>Guarded for the same reason {@link #drain} is: this runs as a
	 * fixed-delay task, and an exception escaping one cancels it for good.
	 * Deciding whether there is anything to quote reads a setting through a
	 * config proxy, which is outside refresh's own guard.
	 */
	private void quotesTick()
	{
		try
		{
			if (watchlists.wantsQuotes())
			{
				refresh(PanelTab.WATCHLISTS);
			}
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure deciding whether to refresh the quotes", e);
		}
	}

	private static boolean contains(List<FlippingRsApi.GameAccount> accounts, String id)
	{
		for (FlippingRsApi.GameAccount account : accounts)
		{
			if (account != null && id.equals(account.id))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ the journal

	private void rememberChosenAccount()
	{
		final FlippingRsPanel target = panel;
		if (target != null)
		{
			rememberChosenAccountFrom(target, true);
		}
	}

	/**
	 * Stores the panel's selection as this RuneScape account's journal.
	 *
	 * <p>The journal is what Trades and Journal are read for, so a change of
	 * one has to re-read both. Without that, picking a different journal left
	 * the two tabs showing the previous one's rows until something else
	 * happened to refresh them -- a sidebar naming one journal over another
	 * journal's numbers, which is the one thing this panel exists to get
	 * right. The same call covers the first journal a character adopts: the
	 * adoption is handed to the Swing thread and lands after the connect that
	 * asked for it has already read both tabs and found nothing chosen.
	 *
	 * <p>And whatever is queued goes out, because the panel has been telling
	 * the user their trades are being kept safe until they pick one.
	 *
	 * @param interactive true when the user just picked it, in which case a
	 *                    choice that cannot be stored is worth telling them
	 *                    about. The automatic adoptions pass false: on a client
	 *                    started before login there is nothing to attach the
	 *                    choice to yet, and that is not something to nag over.
	 */
	private void rememberChosenAccountFrom(FlippingRsPanel from, boolean interactive)
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
			if (interactive)
			{
				from.setStatus("Log in first, so this choice can be saved for that character.",
					ColorScheme.BRAND_ORANGE);
			}
			return;
		}
		if (id.equals(store.chosenAccount()))
		{
			// Repopulating the picker on every reconnect re-selects the same
			// entry, and re-reading two tabs for that would be two requests
			// against a thirty-a-minute limit for no news.
			return;
		}
		store.rememberChosenAccount(id);
		submit(sendExecutor, () ->
		{
			refresh(PanelTab.TRADES);
			refresh(PanelTab.JOURNAL);
			drain();
		});
	}

	// ------------------------------------------------------------- the queue

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

	/** Item names come from the item manager, which wants the client thread. */
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

	/** The item's sprite, or null if the client will not give one. Client thread. */
	@Nullable
	private net.runelite.client.util.AsyncBufferedImage spriteOf(int itemId)
	{
		try
		{
			return itemManager.getImage(itemId);
		}
		catch (RuntimeException e)
		{
			log.debug("no sprite for item {}", itemId, e);
			return null;
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
