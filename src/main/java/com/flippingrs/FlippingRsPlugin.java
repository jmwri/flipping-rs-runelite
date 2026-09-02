package com.flippingrs;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 */
@Slf4j
@PluginDescriptor(
	name = "FlippingRS",
	description = "Keeps your flippingrs.com journal up to date on its own: every Grand Exchange trade is recorded as it happens",
	tags = {"grand", "exchange", "ge", "flip", "flipping", "merch", "profit", "journal", "tracker", "tax"}
)
public class FlippingRsPlugin extends Plugin
{
	/** Config key prefix for the per-slot baseline. */
	private static final String OFFER_KEY = "offer";
	/** Config key for the chosen FlippingRS game account, per RuneScape profile. */
	private static final String ACCOUNT_KEY = "gameAccountId";
	/**
	 * Config key for which watchlist the right-click entry adds to. A plain
	 * plugin setting rather than per profile: a watchlist is a person's, not a
	 * character's. Only the choice is kept; the list itself lives on the site.
	 */
	private static final String WATCHLIST_KEY = "watchlistId";
	/** What a watchlist is called when the plugin has to create the first one. */
	private static final String NEW_WATCHLIST_NAME = "Plan";
	/** The server's cap on one watchlist. */
	private static final int MAX_WATCHLIST_ITEMS = 50;

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

	private volatile FlippingRsApi api;
	private FlippingRsPanel panel;
	private GeMenu geMenu;
	private GeQuoteOverlay quoteOverlay;

	/**
	 * The owner's watchlists, as last read from the server. A cache for the
	 * panel and for computing the next edit, never a record: every change goes
	 * to the server first and this is replaced with what it sent back.
	 */
	@Nullable
	private volatile List<FlippingRsApi.Watchlist> watchlists;

	/**
	 * The site's quotes for the watched items, as last read from the panel
	 * endpoint. Refreshed once a minute while there is something to quote,
	 * and only ever a cache for the cards.
	 */
	private volatile Map<Integer, FlippingRsApi.Quote> quotes = Collections.emptyMap();
	private ScheduledFuture<?> quoteTask;

	/** How often the watchlist's quotes are refreshed: the same cadence the site's own data moves at. */
	private static final long QUOTE_REFRESH_SECONDS = 30;
	/** The tabs that are re-read on their own; Account is only read by connect. */
	private enum Tab
	{
		TRADES, JOURNAL, WATCHLISTS
	}

	/**
	 * Ticks after login during which offer deltas are the client replaying
	 * what the exchange did while nobody was watching. Two, the window
	 * RuneLite's own Grand Exchange plugin uses for the same burst.
	 */
	private static final int LOGIN_BURST_TICKS = 2;
	private int loggedInTick = -1;

	/**
	 * Set while the client or the plugin is stopping, so the final drain
	 * sends and nothing more: the exit budget is ten seconds for every
	 * plugin together, and re-reading tabs nobody will see is not worth any
	 * of it.
	 */
	private volatile boolean shuttingDown;

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
	private volatile long accountTabsRefreshedAt;
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

		api = newApi();
		watchlists = null;

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
		watchlists = null;
		quotes = Collections.emptyMap();
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

	// ------------------------------------------------------------ watchlist

	/** The watchlist the right-click entry adds to: the remembered one, else the first. */
	@Nullable
	private FlippingRsApi.Watchlist currentWatchlist(List<FlippingRsApi.Watchlist> lists)
	{
		if (lists.isEmpty())
		{
			return null;
		}
		final String remembered = configManager.getConfiguration(FlippingRsConfig.GROUP, WATCHLIST_KEY);
		for (FlippingRsApi.Watchlist watchlist : lists)
		{
			if (watchlist.id != null && watchlist.id.equals(remembered))
			{
				return watchlist;
			}
		}
		return lists.get(0);
	}

	/** Puts the cached watchlists on the panel, with the chosen one's items named. */
	private void showWatchlists()
	{
		final List<FlippingRsApi.Watchlist> lists = watchlists;
		if (lists == null)
		{
			return;
		}
		final FlippingRsApi.Watchlist current = currentWatchlist(lists);
		final String selected = current == null ? null : current.id;
		final List<Integer> ids = current == null ? Collections.emptyList() : current.getItemIds();
		// Names, prices and sprites come from the item manager, which wants
		// the client thread.
		clientThread.invoke(() ->
		{
			final List<FlippingRsPanel.WatchedItem> items = new ArrayList<>(ids.size());
			for (Integer id : ids)
			{
				if (id != null)
				{
					items.add(describeItem(id));
				}
			}
			onPanel(p ->
			{
				p.setWatchlists(lists, selected);
				p.setWatchlistItems(items);
			});
		});
	}

	/**
	 * What the watchlist shows for an item, from what the client already has:
	 * its name and sprite, RuneLite's exchange price, the buy limit, the alch
	 * value, and whether the player has an offer on it right now. None of it
	 * comes from the FlippingRS market API, which a plugin key cannot reach;
	 * the numbers that need that live on the item's page, one click away.
	 *
	 * <p>Client thread.
	 */
	private FlippingRsPanel.WatchedItem describeItem(int itemId)
	{
		int price = 0;
		int limit = 0;
		int alch = 0;
		net.runelite.client.util.AsyncBufferedImage image = null;
		try
		{
			price = itemManager.getItemPrice(itemId);
			final net.runelite.client.game.ItemStats stats = itemManager.getItemStats(itemId);
			limit = stats == null ? 0 : stats.getGeLimit();
			alch = itemManager.getItemComposition(itemId).getHaPrice();
			image = itemManager.getImage(itemId);
		}
		catch (RuntimeException e)
		{
			// A row with a name and no numbers beats no row.
			log.debug("could not describe item {}", itemId, e);
		}
		return new FlippingRsPanel.WatchedItem(itemId, itemName(itemId), image, price, limit, alch,
			liveOffer(itemId), quotes.get(itemId));
	}

	/** The player's current offer on an item, in a few words, or null. */
	@Nullable
	private String liveOffer(int itemId)
	{
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}
		for (GrandExchangeOffer offer : offers)
		{
			if (offer == null || offer.getItemId() != itemId || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			final String verb;
			switch (offer.getState())
			{
				case BUYING:
					verb = "Buying";
					break;
				case SELLING:
					verb = "Selling";
					break;
				case BOUGHT:
					verb = "Bought";
					break;
				case SOLD:
					verb = "Sold";
					break;
				case CANCELLED_BUY:
					verb = "Buy cancelled";
					break;
				case CANCELLED_SELL:
					verb = "Sell cancelled";
					break;
				default:
					continue;
			}
			return verb + " " + offer.getQuantitySold() + "/" + offer.getTotalQuantity()
				+ " at " + FlippingRsPanel.gp(offer.getPrice());
		}
		return null;
	}

	/**
	 * The site's quote for an item, if it is on the shown watchlist and the
	 * setting allows the overlay; else null. Client thread, from the overlay,
	 * once per frame -- it reads the live watchlist and quote caches, so an
	 * item added or removed in the sidebar shows or vanishes on the offer
	 * screen the moment the server has confirmed the edit.
	 */
	@Nullable
	private FlippingRsApi.Quote watchedQuote(int itemId)
	{
		if (!config.setupOverlay() || !isWatched(itemId))
		{
			return null;
		}
		return quotes.get(itemId);
	}

	/** Whether an item is on the watchlist the panel is showing. */
	private boolean isWatched(int itemId)
	{
		final List<FlippingRsApi.Watchlist> lists = watchlists;
		if (lists == null)
		{
			return false;
		}
		final FlippingRsApi.Watchlist current = currentWatchlist(lists);
		return current != null && current.getItemIds().contains(itemId);
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
		if (id == null)
		{
			return;
		}
		configManager.setConfiguration(FlippingRsConfig.GROUP, WATCHLIST_KEY, id);
		showWatchlists();
		submit(sendExecutor, () -> refresh(Tab.WATCHLISTS));
	}

	private void addToWatchlist(int itemId)
	{
		changeWatchlist(itemId, true);
	}

	private void removeFromWatchlist(int itemId)
	{
		changeWatchlist(itemId, false);
	}

	/**
	 * Adds an item to, or removes it from, the chosen watchlist on the server.
	 *
	 * <p>Net thread. The server holds the list, so the edit is sent first and
	 * the panel redrawn from what comes back; nothing is changed locally on
	 * the assumption that it will go through. When the owner has no watchlist
	 * at all, the first add creates one.
	 */
	private void changeWatchlist(int itemId, boolean add)
	{
		if (!config.enabled())
		{
			// "Record trades" off is a promise not to contact the server at
			// all, and a watchlist edit is contact.
			onPanel(p -> p.setWatchlistNotice("Switch \"Record trades\" back on in the plugin settings to change "
				+ "your watchlist.", ColorScheme.BRAND_ORANGE));
			return;
		}
		final String key = config.apiKey().trim();
		if (key.isEmpty())
		{
			onPanel(p -> p.setWatchlistNotice("Add your API key in the plugin settings to use watchlists.",
				ColorScheme.BRAND_ORANGE));
			return;
		}
		try
		{
			List<FlippingRsApi.Watchlist> lists = watchlists;
			if (lists == null)
			{
				final List<FlippingRsApi.Watchlist> fromServer = api.watchlists(key, null).getWatchlists();
				lists = fromServer == null ? Collections.emptyList() : fromServer;
			}
			final FlippingRsApi.Watchlist current = currentWatchlist(lists);
			final FlippingRsApi.Watchlist updated;
			if (current == null)
			{
				if (!add)
				{
					return;
				}
				updated = api.createWatchlist(key, NEW_WATCHLIST_NAME, Collections.singletonList(itemId));
				configManager.setConfiguration(FlippingRsConfig.GROUP, WATCHLIST_KEY, updated.id);
				lists = new ArrayList<>(lists);
				lists.add(updated);
			}
			else
			{
				final List<Integer> ids = new ArrayList<>(current.getItemIds());
				final String name = current.toString();
				if (add)
				{
					if (ids.contains(itemId))
					{
						onPanel(p -> p.setWatchlistNotice("Already on " + name + ".", ColorScheme.LIGHT_GRAY_COLOR));
						return;
					}
					if (ids.size() >= MAX_WATCHLIST_ITEMS)
					{
						onPanel(p -> p.setWatchlistNotice(name + " is full: a watchlist holds " + MAX_WATCHLIST_ITEMS
							+ " items. Remove something or pick another watchlist.", ColorScheme.BRAND_ORANGE));
						return;
					}
					ids.add(itemId);
				}
				else if (!ids.remove(Integer.valueOf(itemId)))
				{
					return;
				}
				updated = api.updateWatchlist(key, current.id, ids);
				lists = replacing(lists, updated);
			}
			watchlists = lists;
			showWatchlists();
			refresh(Tab.WATCHLISTS);
			final String name = updated.toString();
			onPanel(p -> p.setWatchlistNotice((add ? "Added to " : "Removed from ") + name + ".",
				ColorScheme.PROGRESS_COMPLETE_COLOR));
		}
		catch (IOException e)
		{
			// A plan limit arrives here too, with the server's own words.
			log.debug("could not change the watchlist", e);
			final String why = describe(e);
			onPanel(p -> p.setWatchlistNotice("Couldn't update your watchlist: " + why, ColorScheme.PROGRESS_ERROR_COLOR));
		}
	}

	private static List<FlippingRsApi.Watchlist> replacing(
		List<FlippingRsApi.Watchlist> lists, FlippingRsApi.Watchlist updated)
	{
		final List<FlippingRsApi.Watchlist> out = new ArrayList<>(lists.size());
		for (FlippingRsApi.Watchlist watchlist : lists)
		{
			out.add(updated.id.equals(watchlist.id) ? updated : watchlist);
		}
		return out;
	}

	// ------------------------------------------------------------- activity

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

		// A watched item's card shows the player's offer on it; keep that
		// line current. Only that line: rebuilding every card, sprites and
		// all, on each fill of a watched item was real work for a flipper
		// with a long list and fast items.
		if (isWatched(offer.getItemId()))
		{
			final int itemId = offer.getItemId();
			final String live = liveOffer(itemId);
			onPanel(p -> p.updateWatchedOffer(itemId, live));
		}

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
			// Off means off, not "hold it and send it when they turn it back
			// on". Someone who stops recording mid-session means those trades to
			// stay out of their journal.
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
		final String chosen = chosenAccount();
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
			refresh(Tab.TRADES);
			refresh(Tab.JOURNAL);
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
					watchlists = null;
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
	 * <p>Never throws. This runs on RuneLite's shared scheduler, where an
	 * exception escaping a {@code scheduleWithFixedDelay} task cancels it for
	 * good -- the plugin would go quiet with nothing in the log to say why.
	 */
	private void drain()
	{
		// Checked before anything else, and before connect's equivalent check,
		// because "Record trades" being off is a promise that the plugin is not
		// talking to flippingrs.com at all -- not merely that it has stopped
		// capturing. Anything already queued stays on disk and goes out when
		// recording is turned back on; it was captured while the user wanted it
		// recorded, so discarding it would be its own kind of surprise.
		if (!config.enabled())
		{
			return;
		}
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
					"No API key yet. Add one in the plugin settings. Your trades are being kept safe until you do.",
					ColorScheme.BRAND_ORANGE));
				return;
			}
			final String accountId = chosenAccount();
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

			final FlippingRsApi.IngestResult result;
			try
			{
				result = api.submit(key, accountId, batch);
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
			final int waiting = queue.size();
			final List<GeTransaction> buffered = queue.newest(FlippingRsPanel.RECENT_SHOWN);

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

			if (!shuttingDown)
			{
				refreshAccountTabsAfterSend();
			}

			onPanel(p -> {
				p.setCounts(recordedThisSession.get(), waiting);
				p.setPending(buffered);
				p.setLastSync(lastSyncAt, null);
				p.setStatus("Connected and recording.", ColorScheme.PROGRESS_COMPLETE_COLOR);
				if (result.getRejected() > 0)
				{
					p.setActivityNotice("flippingrs.com couldn't record " + result.getRejected()
						+ " trade(s). The client log says why.", ColorScheme.PROGRESS_ERROR_COLOR);
				}
				else if (result.getUnmatchedSellQty() > 0)
				{
					p.setActivityNotice(result.getUnmatchedSellQty()
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
			final String why = describe(e);
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

	/**
	 * Sets aside exactly the fills the server refused.
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
		queue.reject(batch);
		log.warn("set aside {} fills that flippingrs.com will not accept; they are in {}",
			batch.size(), queue.droppedFile(), cause);
		final int waiting = queue.size();
		final List<GeTransaction> buffered = queue.newest(FlippingRsPanel.RECENT_SHOWN);
		final String why = describe(cause);
		onPanel(p -> {
			p.setCounts(recordedThisSession.get(), waiting);
			p.setPending(buffered);
			p.setLastSync(null, why);
			p.setActivityNotice("flippingrs.com couldn't accept " + batch.size() + " trade(s). They have been set aside "
				+ "in " + queue.droppedFile().getName() + " in your RuneLite folder so nothing is lost. The client log "
				+ "says why.", ColorScheme.PROGRESS_ERROR_COLOR);
		});
	}

	/**
	 * Something to show for an exception. Not every IOException carries a
	 * message, and passing null on to the panel made a failed send read as
	 * "Last sent: never", which is the opposite of what happened.
	 */
	private static String describe(Throwable e)
	{
		final String message = e.getMessage();
		return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
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

	/**
	 * Puts a panel reply on the screen. Only the parts present are touched,
	 * so a partial read leaves the rest of the sidebar as it was.
	 *
	 * @param connecting true for the read that doubles as the connection
	 *                   test, which also sets the connection status
	 */
	private void applyPanel(FlippingRsApi.Panel panel, boolean connecting)
	{
		final FlippingRsApi.Me me = panel.getMe();
		if (me != null)
		{
			final String plan = "Plan: " + me.describePlan();
			onPanel(p -> p.setSubscription(plan));
		}

		final List<FlippingRsApi.GameAccount> accounts = panel.getAccounts();
		if (accounts != null)
		{
			knownAccounts = accounts;
			final String chosen = chosenAccount();

			// The remembered journal is gone -- deleted on the site, or the key
			// now belongs to a different FlippingRS account. Sending to it
			// would be refused every tick, and the picker would meanwhile show
			// whichever entry sorted first. Forget the choice and say so, and
			// hold the trades until a real one is made.
			final boolean orphaned = chosen != null && !accounts.isEmpty() && !contains(accounts, chosen);
			if (orphaned)
			{
				log.warn("the journal remembered for this account ({}) no longer exists; forgetting it", chosen);
				forgetChosenAccount();
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

		final List<FlippingRsApi.Watchlist> lists = panel.getWatchlists();
		final Map<Integer, FlippingRsApi.Quote> prices = panel.getQuotes();
		if (lists != null)
		{
			watchlists = lists;
		}
		if (prices != null)
		{
			quotes = prices;
		}
		if (lists != null || prices != null)
		{
			showWatchlists();
		}

		final List<GeTransaction> rows = panel.getRecentTransactions();
		if (rows != null)
		{
			onPanel(p -> p.setActivity(rows));
		}

		final FlippingRsApi.Analytics week = panel.getWeek();
		final FlippingRsApi.Positions open = panel.getPositions();
		if (week != null && open != null)
		{
			onPanel(p -> p.setJournal(week, open));
		}
	}

	/** The watchlist the picker is set to, or null if none has been picked. */
	@Nullable
	private String rememberedWatchlistId()
	{
		final String id = configManager.getConfiguration(FlippingRsConfig.GROUP, WATCHLIST_KEY);
		return id == null || id.isEmpty() ? null : id;
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
			return;
		}

		try
		{
			applyPanel(api.account(key), true);
		}
		catch (IOException e)
		{
			log.debug("could not reach flippingrs.com", e);
			final String why = describe(e);
			onPanel(p -> p.setStatus("Could not connect: " + why, ColorScheme.PROGRESS_ERROR_COLOR));
			return;
		}
		refresh(Tab.TRADES);
		refresh(Tab.JOURNAL);
		accountTabsRefreshedAt = System.nanoTime();
		refresh(Tab.WATCHLISTS);
		refreshPending();
		// The key may have been missing or wrong while trades piled up.
		submit(sendExecutor, this::drain);
	}

	/** Re-reads Trades and Journal after a send, no more often than the limit allows. Net thread. */
	private void refreshAccountTabsAfterSend()
	{
		final long now = System.nanoTime();
		final long wait = accountTabsRefreshedAt + TimeUnit.SECONDS.toNanos(ACCOUNT_TABS_REFRESH_SECONDS) - now;
		if (accountTabsRefreshedAt == 0 || wait <= 0)
		{
			accountTabsRefreshedAt = now;
			clientThread.invoke(() -> snapshotOffers(OFFER_SNAPSHOT_AFTER_SEND_SECONDS));
			refresh(Tab.TRADES);
			refresh(Tab.JOURNAL);
			return;
		}
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
				clientThread.invoke(() -> snapshotOffers(OFFER_SNAPSHOT_AFTER_SEND_SECONDS));
				refresh(Tab.TRADES);
				refresh(Tab.JOURNAL);
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
	private void refresh(Tab tab)
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
			final String accountId = chosenAccount();
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
					part = api.watchlists(key, rememberedWatchlistId());
					break;
				default:
					return;
			}
			applyPanel(part, false);
		}
		catch (IOException e)
		{
			log.warn("could not refresh the {} tab: {}", tab, e.getMessage());
			final String why = describe(e);
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

	/** The minute tick that keeps quotes current, while there is something to quote. */
	private void quotesTick()
	{
		final List<FlippingRsApi.Watchlist> lists = watchlists;
		if (lists == null)
		{
			return;
		}
		final FlippingRsApi.Watchlist current = currentWatchlist(lists);
		if (current == null || current.getItemIds().isEmpty())
		{
			return;
		}
		refresh(Tab.WATCHLISTS);
	}

	// ------------------------------------------------- catching the server up
	//
	// Three things the server cannot see for itself: an offer that was placed
	// and filled while the plugin was not running (reported once as a
	// recovered fill when the tracker adopts it), the state of the open slots
	// (sent as a snapshot for the server to reconcile against its fills), and
	// the history screen (sent as read, for the server to match against the
	// completed offers it has). In every case the plugin reports what the
	// client shows and the server decides what is new.

	/** Ticks to wait after login for the client's offer burst to settle before a snapshot. */
	private static final int LOGIN_SETTLE_TICKS = 3;
	/** Snapshots closer together than this are skipped; the state has not changed. */
	private static final long OFFER_SNAPSHOT_SECONDS = 10;
	/**
	 * After a send, rarer still: the send just told the server the state, and
	 * the reconciliation is a safety net rather than the record. This keeps
	 * the worst-case minute well inside the plugin scope's rate limit.
	 */
	private static final long OFFER_SNAPSHOT_AFTER_SEND_SECONDS = 60;
	/** The history screen fills a tick or two after it opens; give up after this many looks. */
	private static final int HISTORY_READ_ATTEMPTS = 5;

	private int offerSnapshotDueTick = -1;
	private int historyReadDueTick = -1;
	private int historyReadAttempts;
	private volatile long lastOfferSnapshotAt;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loggedInTick = client.getTickCount();
			offerSnapshotDueTick = loggedInTick + LOGIN_SETTLE_TICKS;
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.GE_OFFERS)
		{
			offerSnapshotDueTick = client.getTickCount() + 1;
		}
		else if (event.getGroupId() == InterfaceID.GE_HISTORY)
		{
			historyReadDueTick = client.getTickCount() + 2;
			historyReadAttempts = 0;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		final int tick = client.getTickCount();
		if (offerSnapshotDueTick >= 0 && tick >= offerSnapshotDueTick)
		{
			offerSnapshotDueTick = -1;
			snapshotOffers(OFFER_SNAPSHOT_SECONDS);
		}
		if (historyReadDueTick >= 0 && tick >= historyReadDueTick)
		{
			readHistory(tick);
		}
	}

	/**
	 * Reads the open slots and hands them to the net thread. Client thread,
	 * because the offers and the baselines are read here.
	 */
	private void snapshotOffers(long minGapSeconds)
	{
		if (!config.enabled())
		{
			return;
		}
		final long now = System.nanoTime();
		if (lastOfferSnapshotAt != 0 && now - lastOfferSnapshotAt < TimeUnit.SECONDS.toNanos(minGapSeconds))
		{
			return;
		}
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		final List<FlippingRsApi.OfferState> open = new ArrayList<>();
		for (int slot = 0; slot < offers.length; slot++)
		{
			final GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			final SavedOffer saved = loadOffer(slot);
			final FlippingRsApi.OfferState state = new FlippingRsApi.OfferState();
			state.slot = slot;
			state.offerRef = saved == null ? null : saved.offerRef;
			state.itemId = offer.getItemId();
			state.itemName = itemName(offer.getItemId());
			state.side = SavedOffer.isBuy(offer.getState()) ? "buy" : "sell";
			state.price = offer.getPrice();
			state.totalQuantity = offer.getTotalQuantity();
			state.quantitySold = offer.getQuantitySold();
			state.spent = offer.getSpent();
			state.spentEstimated = offer.getSpent() < 0;
			state.state = offer.getState().name();
			open.add(state);
		}
		lastOfferSnapshotAt = now;
		submit(sendExecutor, () -> sendOffers(open));
	}

	/**
	 * Net thread. A failure here costs nothing but the reconciliation; the
	 * next snapshot retries.
	 *
	 * <p>The buffer is sent first. An adopted fill still waiting in the queue
	 * is exactly the shortfall the server would otherwise recover from this
	 * snapshot, and the server's dedupe deliberately trusts a fill under an
	 * offer's own reference, so sending both would count it twice.
	 */
	private void sendOffers(List<FlippingRsApi.OfferState> open)
	{
		if (!config.enabled())
		{
			return;
		}
		drain();
		final String key = config.apiKey().trim();
		final String accountId = chosenAccount();
		if (key.isEmpty() || accountId == null)
		{
			return;
		}
		try
		{
			final FlippingRsApi.Reconciliation result = api.submitOffers(key, accountId, open);
			if (!result.getProblems().isEmpty())
			{
				log.warn("flippingrs.com could not read {} of the open offers: {}",
					result.getProblems().size(), result.getProblems());
			}
			if (result.getRecovered() > 0)
			{
				final int recovered = result.getRecovered();
				onPanel(p -> p.setActivityNotice("Recovered " + recovered + " trade(s) from your open offers that had "
					+ "been missed. They are saved without a time.", ColorScheme.BRAND_ORANGE));
				refreshAccountTabsAfterSend();
			}
		}
		catch (IOException e)
		{
			// A plan cap arrives here too, in the server's words, and a
			// snapshot that cannot be reconciled is worth a line on Activity
			// rather than a log entry nobody reads.
			log.warn("could not send the open offers: {}", e.getMessage());
			final String why = describe(e);
			onPanel(p -> p.setActivityNotice("Couldn't check your open offers against your journal: " + why,
				ColorScheme.BRAND_ORANGE));
		}
	}

	/** Client thread. The history list fills a tick or two after the screen opens. */
	private void readHistory(int tick)
	{
		if (!config.enabled())
		{
			historyReadDueTick = -1;
			return;
		}
		final List<FlippingRsApi.HistoryRow> rows =
			GeHistoryReader.read(client.getWidget(InterfaceID.GeHistory.LIST), this::itemName);
		if (rows.isEmpty() && ++historyReadAttempts < HISTORY_READ_ATTEMPTS)
		{
			historyReadDueTick = tick + 1;
			return;
		}
		historyReadDueTick = -1;
		if (rows.isEmpty())
		{
			return;
		}
		submit(sendExecutor, () -> sendHistory(rows));
	}

	/**
	 * Net thread. The buffer is sent first, for the same reason as
	 * {@link #sendOffers}: a completed offer whose fills are still queued
	 * would be unmatched on the screen and added a second time.
	 */
	private void sendHistory(List<FlippingRsApi.HistoryRow> rows)
	{
		if (!config.enabled())
		{
			return;
		}
		drain();
		final String key = config.apiKey().trim();
		final String accountId = chosenAccount();
		if (key.isEmpty() || accountId == null)
		{
			return;
		}
		try
		{
			final FlippingRsApi.Reconciliation result = api.submitHistory(key, accountId, rows);
			if (!result.getProblems().isEmpty())
			{
				log.warn("flippingrs.com could not read {} history row(s): {}",
					result.getProblems().size(), result.getProblems());
			}
			if (result.getAdded() > 0)
			{
				final int added = result.getAdded();
				onPanel(p -> p.setActivityNotice("Recovered " + added + " trade(s) from your Grand Exchange history. "
					+ "They are saved without a time.", ColorScheme.BRAND_ORANGE));
				refreshAccountTabsAfterSend();
			}
		}
		catch (IOException e)
		{
			log.warn("could not send the exchange history: {}", e.getMessage());
			final String why = describe(e);
			onPanel(p -> p.setActivityNotice("Couldn't send your Grand Exchange history: " + why,
				ColorScheme.PROGRESS_ERROR_COLOR));
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
			rememberChosenAccountFrom(target, true);
		}
	}

	/**
	 * Stores the panel's selection as this RuneScape account's journal.
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
		configManager.setRSProfileConfiguration(FlippingRsConfig.GROUP, ACCOUNT_KEY, id);
	}

	private void forgetChosenAccount()
	{
		configManager.unsetRSProfileConfiguration(FlippingRsConfig.GROUP, ACCOUNT_KEY);
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
