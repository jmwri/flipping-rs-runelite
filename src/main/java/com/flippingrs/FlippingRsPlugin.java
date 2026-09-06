package com.flippingrs;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.ScriptID;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Notification;
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
 * client's events, and the reads that fill the side panel. The rest is
 * delegated: {@link OfferTracker} turns slot updates into fills,
 * {@link TransactionQueue} keeps them on disk, {@link TransactionSender} gets
 * them to the server and says what became of them, {@link ProfileStore}
 * remembers what is per character, {@link Watchlists} owns the watchlist and
 * quote caches, {@link CatchUp} reports the open slots and the history screen,
 * and {@link PositionActions} closes and deletes positions.
 */
@Slf4j
@PluginDescriptor(
	name = "FlippingRS",
	description = "Keeps your flippingrs.com journal up to date on its own: every Grand Exchange trade is recorded as it happens",
	tags = {"grand", "exchange", "ge", "flip", "flipping", "merching", "profit", "journal", "tracker", "tax"}
)
public class FlippingRsPlugin extends Plugin
{
	/** What {@link Client#getAccountHash()} returns when nobody is logged in. */
	static final long NO_ACCOUNT = -1L;

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
	private Notifier notifier;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private net.runelite.client.ui.overlay.tooltip.TooltipManager tooltipManager;

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

	private volatile FlippingRsApi api;
	private FlippingRsPanel panel;
	private GeMenu geMenu;
	private GeItemInfoOverlay infoOverlay;
	private GeSetupText setupText;
	private GeHistoryText historyText;
	private GeTooltip tooltip;
	private ExaminePrices examinePrices;

	// The collaborators. Built by wire(), from the fields above, once those
	// are in place: in startUp, or by a test that sets them directly.
	private ProfileStore store;
	private TransactionSender sender;
	private PanelReads reads;
	private Watchlists watchlists;
	private CatchUp catchUp;
	private PositionActions positions;

	/**
	 * Where the pending-queue files live. RuneLite's own directory in normal
	 * use; a test redirects it so it never writes into real client data. Read
	 * by {@link #wire()}, so a test sets it before calling that.
	 */
	private File queueDir = new File(RuneLite.RUNELITE_DIR, "flippingrs");

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

	private NavigationButton navButton;
	private ScheduledFuture<?> syncTask;

	/** Incremented on the game thread, read on the Swing and io threads. */
	private final AtomicInteger recordedThisSession = new AtomicInteger();

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

		newSession();

		api = newApi();
		wire();

		geMenu = new GeMenu(client, itemManager, this::openItem,
			itemId -> submit(sendExecutor, () -> addToWatchlist(itemId)));
		infoOverlay = new GeItemInfoOverlay(client, config, this::watchedQuote, watchlists::showingOffers);
		overlayManager.add(infoOverlay);
		setupText = new GeSetupText(client, config, this::watchedQuote);
		historyText = new GeHistoryText(client, config, this::watchedQuote);
		tooltip = new GeTooltip(client, config, this::watchedQuote, tooltipManager);
		examinePrices = new ExaminePrices(client, config, chatMessageManager, this::watchedQuote,
			itemId -> watchlists.showingExamined(itemId), this::itemName);

		panel = new FlippingRsPanel(new SidebarActions());

		navButton = NavigationButton.builder()
			.tooltip("FlippingRS")
			.icon(icon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		scheduleSync();
		quoteTask = sendExecutor.scheduleWithFixedDelay(reads::quotesTick,
			QUOTE_REFRESH_SECONDS, QUOTE_REFRESH_SECONDS, TimeUnit.SECONDS);
		submit(sendExecutor, reads::connect);
	}

	/**
	 * Forgets everything that belonged to the last time the plugin ran.
	 *
	 * <p>RuneLite reuses the plugin instance across disable and enable, so
	 * "this session" has to be reset by hand or it carries over: a count of
	 * trades recorded, a time something was last sent, a sidebar the plugin
	 * still thinks is open, a login it still thinks it is arriving from.
	 *
	 * <p>Separate from startUp so that it is reachable without building a nav
	 * button and a real HTTP client. Nothing here fails loudly when it is
	 * missed -- the plugin runs on with the last session's answers.
	 */
	void newSession()
	{
		recordedThisSession.set(0);
		shuttingDown = false;
		loggedInTick = -1;
		arrivingInWorld = true;
		if (reads != null)
		{
			reads.newSession();
		}
		if (sender != null)
		{
			// Null on the first startUp, which calls this before wire() builds
			// the collaborators. On a re-enable the sender is the one from last
			// time, still holding when it last sent.
			sender.newSession();
		}
	}

	/**
	 * The things the sidebar can ask the plugin to do.
	 *
	 * <p>An inner class rather than a lambda apiece, so that adding a button to
	 * the panel does not compile until this says what pressing it does. Every
	 * method is on the Swing thread and hands its work straight to another one:
	 * the panel must not wait on a disk write or a request.
	 */
	final class SidebarActions implements PanelActions
	{
		@Override
		public void sendNow()
		{
			submit(sendExecutor, sender::drain);
		}

		@Override
		public void retrySetAside()
		{
			submit(sendExecutor, sender::retrySetAside);
		}

		@Override
		public void reconnect()
		{
			submit(sendExecutor, reads::connect);
		}

		@Override
		public void accountChosen()
		{
			rememberChosenAccount();
		}

		@Override
		public void watchlistChosen()
		{
			rememberChosenWatchlist();
		}

		@Override
		public void openItem(int itemId)
		{
			FlippingRsPlugin.this.openItem(itemId);
		}

		@Override
		public void removeItem(int itemId)
		{
			submit(sendExecutor, () -> removeFromWatchlist(itemId));
		}

		@Override
		public void findFlips()
		{
			LinkBrowser.browse(api.finderUrl());
		}

		@Override
		public void closePosition(String positionId, long sellPrice, @Nullable Long sellQty)
		{
			submit(sendExecutor, () -> FlippingRsPlugin.this.closePosition(positionId, sellPrice, sellQty));
		}

		@Override
		public void deletePosition(String positionId)
		{
			submit(sendExecutor, () -> FlippingRsPlugin.this.deletePosition(positionId));
		}

		@Override
		public void shown()
		{
			reads.sidebarShown(true);
		}

		@Override
		public void hidden()
		{
			reads.sidebarShown(false);
		}
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
		sender = new TransactionSender(client, config, store, () -> api, this::onPanel, gson, queueDir,
			() -> reads.afterSend(), () -> shuttingDown, recordedThisSession::get, this::notifyProblem);
		watchlists = new Watchlists(client, itemManager, clientThread, config, store, () -> api, this::onPanel,
			this::itemName, () -> reads.refresh(PanelReads.Tab.WATCHLISTS), work -> submit(sendExecutor, work),
			this::examinedPriced);
		catchUp = new CatchUp(client, config, store, () -> api, this::onPanel, this::itemName, sender::drain,
			() -> reads.afterSend(), work -> submit(sendExecutor, work));
		positions = new PositionActions(config, () -> api, this::onPanel, () -> reads.refresh(PanelReads.Tab.JOURNAL));
		// Last, because it is the one that needs all of the others. The four
		// above reach back to it through lambdas rather than references, so
		// the cycle is closed at call time instead of at construction.
		reads = new PanelReads(client, clientThread, itemManager, config, store, () -> api, this::onPanel,
			watchlists, sender::drain, sender::retrySetAside, catchUp::snapshotAfterSend,
			sender::queueFor,
			work -> submit(sendExecutor, work), this::scheduleOnce,
			work -> submit(diskExecutor, work), recordedThisSession::get);
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
		submit(sendExecutor, sender::drain);
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
		if (setupText != null)
		{
			// The line belongs to a screen this plugin is no longer keeping up
			// to date, so it goes rather than sitting there frozen.
			clientThread.invoke(setupText::reset);
			setupText = null;
		}
		if (historyText != null)
		{
			final GeHistoryText history = historyText;
			clientThread.invoke(history::reset);
			historyText = null;
		}
		tooltip = null;
		examinePrices = null;
		if (infoOverlay != null)
		{
			overlayManager.remove(infoOverlay);
			infoOverlay = null;
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
	private Quote watchedQuote(int itemId)
	{
		return watchlists.quoteFor(itemId);
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

		// A finished offer, watched happening rather than replayed on login.
		// Said out loud only if asked for: the client shows it too, and a fast
		// flipper would get one of these every few seconds.
		if (GeTransaction.SOURCE_LIVE.equals(tx.source) && (tx.completed || tx.cancelled))
		{
			notifyOfferDone(tx);
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
			final TransactionQueue queue = sender.queueFor(accountHash);
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
			sender.queueFor(accountHash).add(tx);
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
			// The exchange cannot still be open on a world the client has left.
			// Closing it here as well as on WidgetClosed is not belt and
			// braces: logging out tears the widget tree down without a
			// WidgetClosed for each of its interfaces, so with only that one
			// the flag stuck on, and the quote timer went on making a request
			// every thirty seconds, forever, against a sixty-a-minute limit,
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
		final GeSetupText text = setupText;
		if (text != null)
		{
			// Every tick, not only when the client rebuilds the screen: the
			// prices move under it, and a rebuild this does not know about
			// would otherwise leave the line gone until the screen is closed.
			text.update();
		}
		final GeHistoryText history = historyText;
		if (history != null)
		{
			history.update();
		}
	}

	/**
	 * The tooltip follows the pointer rather than the tick, so it is offered on
	 * every frame rather than six times a second. RuneLite clears the tooltips
	 * it was given each frame, so this has to be one of them.
	 */
	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		final GeTooltip hover = tooltip;
		if (hover != null)
		{
			hover.update();
		}
	}

	/**
	 * The client has just rebuilt the offer setup screen, throwing away
	 * anything added to it. Putting the line back here rather than waiting for
	 * the next tick is what stops it flickering as the screen opens.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		final GeSetupText text = setupText;
		if (text != null && event.getScriptId() == ScriptID.GE_OFFERS_SETUP_BUILD)
		{
			text.rebuilt();
		}
	}

	/** Which item an examine was asked about; the message itself says nothing. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final ExaminePrices examine = examinePrices;
		if (examine != null)
		{
			examine.clicked(event);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		final ExaminePrices examine = examinePrices;
		if (examine != null)
		{
			examine.examined(event);
		}
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
		// False when nothing has been loaded yet; connect does this when it
		// succeeds.
		reads.accountChanged();
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
			final boolean draining = submit(sendExecutor, () ->
			{
				try
				{
					sender.drain();
				}
				finally
				{
					done.complete(null);
				}
			});
			if (!draining)
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
				// A key that has just been changed is the likeliest reason the
				// server refused a batch over something that was never the
				// rows' fault, so what was filed gets another go.
				submit(sendExecutor, sender::retrySetAside);
				submit(sendExecutor, reads::connect);
				break;
			// Turning recording back on has to re-check the key and reload the
			// journals, because nothing was contacted while it was off.
			case "enabled":
				submit(sendExecutor, reads::connect);
				break;
			case "syncSeconds":
				scheduleSync();
				break;
			case "baseUrl":
				if (developerMode)
				{
					api = newApi();
					watchlists.forget();
					submit(sendExecutor, reads::connect);
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
		syncTask = sendExecutor.scheduleWithFixedDelay(sender::drain, seconds, seconds, TimeUnit.SECONDS);
	}

	// ------------------------------------------------------------ the journal

	/**
	 * The journal picker changed. Swing thread.
	 *
	 * <p>Here rather than in {@link PanelReads} because the panel reference is
	 * the plugin's, and it is nulled on shutdown: everything else asks for the
	 * panel through {@link #onPanel}, which hops to the Swing thread, and this
	 * is already on it.
	 */
	private void rememberChosenAccount()
	{
		final FlippingRsPanel target = panel;
		if (target != null)
		{
			reads.rememberChosenAccountFrom(target, true);
		}
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Runs work on the net thread once a delay has passed, tolerating a
	 * shutdown that has already stopped it.
	 *
	 * @return false if the executor is gone, so the caller can undo whatever
	 *         it set up in expectation of the work running
	 */
	private boolean scheduleOnce(long nanos, Runnable work)
	{
		if (sendExecutor == null || sendExecutor.isShutdown())
		{
			return false;
		}
		try
		{
			sendExecutor.schedule(work, nanos, TimeUnit.NANOSECONDS);
			return true;
		}
		catch (RejectedExecutionException e)
		{
			log.debug("dropped a deferred re-read submitted during shutdown", e);
			return false;
		}
	}

	/**
	 * A price arrived for an item somebody examined before anyone had one.
	 *
	 * <p>Net thread, from the fetch that answered it. The chat is the client's,
	 * so the line itself is said on the client thread.
	 */
	private void examinedPriced(int itemId)
	{
		clientThread.invoke(() ->
		{
			final ExaminePrices examine = examinePrices;
			if (examine != null)
			{
				examine.priced(itemId);
			}
		});
	}

	/**
	 * Gets the user's attention about a trade that could not be recorded.
	 *
	 * <p>Never throws. It is called from the sender, which promises the same,
	 * and a notifier that fails is not worth cancelling the schedule over.
	 */
	private void notifyOfferDone(GeTransaction tx)
	{
		final String what = tx.cancelled ? "cancelled" : ("buy".equals(tx.side) ? "bought" : "sold");
		notify(config.notifyOfferComplete(), "Your " + nameFor(tx) + " offer " + what + ".");
	}

	/** The item's name if the client gave one, else something that still reads. */
	private static String nameFor(GeTransaction tx)
	{
		return tx.itemName == null || tx.itemName.isEmpty() ? "Grand Exchange" : tx.itemName;
	}

	private void notifyProblem(String message)
	{
		notify(config.notifyProblems(), message);
	}

	/**
	 * Raises a notification, if the setting asks for one.
	 *
	 * <p>Never throws. One caller is the sender, which promises the same and
	 * runs on a schedule an escaping exception would cancel for good; another
	 * is the game thread's capture, where an exception lands in RuneLite's
	 * event bus as an uncaught subscriber error. Neither is worth a
	 * notification failing.
	 */
	private void notify(Notification when, String message)
	{
		try
		{
			notifier.notify(when, message);
		}
		catch (RuntimeException e)
		{
			log.debug("could not raise a notification", e);
		}
	}

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
