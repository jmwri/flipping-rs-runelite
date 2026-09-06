package com.flippingrs;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds a FlippingRsPlugin with its collaborators mocked.
 *
 * <p>The fields are set by reflection rather than through Guice. The plugin's
 * dependencies are @Inject fields, and standing up an injector for them would
 * add a test-only dependency and a layer of indirection to hide behind; setting
 * them directly keeps the wiring visible in one place.
 *
 * <p>startUp() is not called: it builds a nav button and a real HTTP client
 * that the behaviour under test does not need, and it replaces the executors
 * everything here runs on. The two pieces of it the tests do need are split
 * out of it and called from here -- wiring the sidebar's buttons and
 * dropdowns to the plugin, so a test can drive them the way a user does, and
 * forgetting the last session, which reEnable() drives. What is left
 * unreached is that startUp calls either of them.
 */
final class FlippingRsPluginTestSupport
{
	final FlippingRsPlugin plugin = new FlippingRsPlugin();
	final Client client = mock(Client.class);
	final ConfigManager configManager = mock(ConfigManager.class);
	final ItemManager itemManager = mock(ItemManager.class, RETURNS_DEEP_STUBS);
	final ClientThread clientThread = mock(ClientThread.class);
	final FlippingRsApi api = mock(FlippingRsApi.class);
	final FlippingRsConfig config = mock(FlippingRsConfig.class);
	final Gson gson = new Gson();

	/**
	 * A real panel, because which journal it shows is part of what is under
	 * test: a picker that names one account while trades go to another is
	 * the kind of wrong that nobody notices.
	 */
	final FlippingRsPanel panel;

	/** Stands in for RuneLite's per-RuneScape-profile config store. */
	final Map<String, String> profileConfig = new HashMap<>();
	/** And for the plain plugin settings that are written, not declared. */
	final Map<String, String> pluginConfig = new HashMap<>();

	private final ScheduledExecutorService diskExecutor = Executors.newSingleThreadScheduledExecutor();

	/**
	 * The net thread, counting the delayed work put on it.
	 *
	 * <p>The plugin coalesces the account-tab re-reads it defers, so a burst of
	 * sends cannot become a burst of requests fifteen seconds later. Nothing
	 * about that is visible once the tasks are queued -- they all fire long
	 * after any test would wait -- so the only way to see it is to count what
	 * was queued.
	 */
	private static final class Net extends java.util.concurrent.ScheduledThreadPoolExecutor
	{
		private final java.util.concurrent.atomic.AtomicInteger delayed =
			new java.util.concurrent.atomic.AtomicInteger();

		Net()
		{
			super(1);
		}

		/** How often the sender was last set to run, in seconds, or -1. */
		private final java.util.concurrent.atomic.AtomicLong period =
			new java.util.concurrent.atomic.AtomicLong(-1);

		@Override
		public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
		{
			// submit and execute come through here too, with no delay at all.
			if (delay > 0)
			{
				delayed.incrementAndGet();
			}
			return super.schedule(command, delay, unit);
		}

		@Override
		public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
			Runnable command, long initialDelay, long delay, TimeUnit unit)
		{
			period.set(unit.toSeconds(delay));
			return super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
		}
	}

	private final Net sendExecutor = new Net();

	/**
	 * The client's notifier. Real enough to record what the plugin asked for,
	 * so a test can say that a trade nobody would otherwise hear about was
	 * said out loud.
	 */
	final net.runelite.client.Notifier notifier = mock(net.runelite.client.Notifier.class);

	/** What the plugin has raised a notification about, in order. */
	final java.util.List<String> notifications = new java.util.ArrayList<>();

	FlippingRsPluginTestSupport(java.io.File queueDir) throws Exception
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(1234L);
		when(client.getWorld()).thenReturn(302);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(itemManager.getItemComposition(anyInt()).getName()).thenReturn("Abyssal whip");

		when(config.apiKey()).thenReturn("frs_key");
		when(config.enabled()).thenReturn(true);
		when(config.setupOverlay()).thenReturn(true);
		when(config.syncSeconds()).thenReturn(30);
		when(config.notifyProblems()).thenReturn(net.runelite.client.config.Notification.ON);
		when(config.notifyOfferComplete()).thenReturn(net.runelite.client.config.Notification.OFF);
		doAnswer(inv ->
		{
			notifications.add(inv.getArgument(1, String.class));
			return null;
		}).when(notifier).notify(any(net.runelite.client.config.Notification.class), anyString());

		// Nothing on the server until a test says otherwise: a reply with
		// every part absent, which the plugin treats as "leave it alone".
		when(api.account(anyString())).thenReturn(new PanelData());
		when(api.trades(anyString(), any())).thenReturn(new PanelData());
		when(api.journal(anyString(), any(), anyInt())).thenReturn(new PanelData());
		when(api.watchlists(anyString(), any(), any())).thenReturn(new PanelData());
		when(api.submitOffers(anyString(), anyString(), anyList())).thenReturn(new Reconciliation());
		when(api.submitHistory(anyString(), anyString(), anyList())).thenReturn(new Reconciliation());

		// Item names are looked up on the client thread. Run that inline.
		doAnswer(inv ->
		{
			inv.getArgument(0, Runnable.class).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));

		when(configManager.getConfiguration(eq(FlippingRsConfig.GROUP), anyString()))
			.thenAnswer((Answer<String>) inv -> pluginConfig.get(inv.getArgument(1, String.class)));
		doAnswer(inv ->
		{
			final Object value = inv.getArgument(2);
			pluginConfig.put(inv.getArgument(1, String.class), value == null ? null : value.toString());
			return null;
		}).when(configManager).setConfiguration(eq(FlippingRsConfig.GROUP), anyString(), any());
		doAnswer(inv ->
		{
			pluginConfig.remove(inv.getArgument(1, String.class));
			return null;
		}).when(configManager).unsetConfiguration(eq(FlippingRsConfig.GROUP), anyString());

		// A real read/write store, so baselines actually persist between events
		// the way they do in the client. Without this every event looks like the
		// first sighting of its slot and nothing is ever reported.
		when(configManager.getRSProfileConfiguration(eq(FlippingRsConfig.GROUP), anyString()))
			.thenAnswer((Answer<String>) inv -> profileConfig.get(inv.getArgument(1, String.class)));
		doAnswer(inv ->
		{
			// Not String.valueOf(inv.getArgument(2)): the argument's type is
			// inferred, and the compiler picks the char[] overload, which then
			// fails at runtime with a ClassCastException that names neither.
			final Object value = inv.getArgument(2);
			profileConfig.put(inv.getArgument(1, String.class), value == null ? null : value.toString());
			return null;
		}).when(configManager).setRSProfileConfiguration(eq(FlippingRsConfig.GROUP), anyString(), any());
		doAnswer(inv ->
		{
			profileConfig.remove(inv.getArgument(1, String.class));
			return null;
		}).when(configManager).unsetRSProfileConfiguration(eq(FlippingRsConfig.GROUP), anyString());

		set("notifier", notifier);
		set("client", client);
		set("clientThread", clientThread);
		set("configManager", configManager);
		set("itemManager", itemManager);
		set("config", config);
		set("gson", gson);
		set("api", api);
		set("diskExecutor", diskExecutor);
		set("sendExecutor", sendExecutor);

		// Point the queue at a temporary directory instead of ~/.runelite.
		set("queueDir", queueDir);

		// What startUp does after the fields are in place: build the
		// collaborators that hold them. startUp itself is still not called.
		plugin.wire();

		// The panel, wired to the plugin the way startUp does it, so a test
		// that drives the sidebar goes the way a user does rather than
		// reaching past it. After wire(), because the actions it is given
		// reach the collaborators that call built there.
		final FlippingRsPanel[] built = new FlippingRsPanel[1];
		SwingUtilities.invokeAndWait(() -> built[0] = new FlippingRsPanel(plugin.new SidebarActions()));
		panel = built[0];
		set("panel", panel);
	}

	private void set(String name, Object value) throws Exception
	{
		final Field f = FlippingRsPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	/**
	 * The journal the logged-in character files under, the way the plugin
	 * stores it: keyed by account hash, so it can be read for an account
	 * nobody is logged into.
	 */
	void chooseJournal(String id)
	{
		pluginConfig.put(ProfileStore.ACCOUNT_KEY + "." + client.getAccountHash(), id);
	}

	/** And the same for another character. */
	void chooseJournalFor(long accountHash, String id)
	{
		pluginConfig.put(ProfileStore.ACCOUNT_KEY + "." + accountHash, id);
	}

	@Nullable
	String chosenJournal()
	{
		return pluginConfig.get(ProfileStore.ACCOUNT_KEY + "." + client.getAccountHash());
	}

	/** The queue the plugin is using for the logged-in account. */
	TransactionQueue queue() throws Exception
	{
		return queueFor(client.getAccountHash());
	}

	/** And the queue for any account, logged in or not. */
	TransactionQueue queueFor(long accountHash) throws Exception
	{
		return sender().queueFor(accountHash);
	}

	void drain() throws Exception
	{
		sender().drain();
	}

	/** The user pressing "Try set-aside trades again". */
	void retrySetAside() throws Exception
	{
		sender().retrySetAside();
		settleSwing();
	}

	/** The panel reads the plugin built in wire(). */
	private PanelReads reads() throws Exception
	{
		final Field f = FlippingRsPlugin.class.getDeclaredField("reads");
		f.setAccessible(true);
		return (PanelReads) f.get(plugin);
	}

	/** The sender the plugin built in wire(). */
	private TransactionSender sender() throws Exception
	{
		final Field f = FlippingRsPlugin.class.getDeclaredField("sender");
		f.setAccessible(true);
		return (TransactionSender) f.get(plugin);
	}

	/**
	 * Connects and waits for everything it set going.
	 *
	 * <p>Both threads, and the Swing thread twice, because connect hands work
	 * to each and each hands work back: the panel adopts a journal on the
	 * Swing thread, which sends the net thread off to re-read two tabs, which
	 * post their results back to the Swing thread.
	 *
	 * <p>Waiting for all of it is not tidiness. A test that carries on while
	 * the net thread is still calling the api mock, and then stubs that same
	 * mock, is stubbing it from one thread while another invokes it -- which
	 * Mockito does not support, and which showed up as one test in the class
	 * failing perhaps one run in three.
	 */
	void connect() throws Exception
	{
		reads().connect();
		settleSwing();
		settleNet();
		settleSwing();
	}

	/** One firing of the timer that keeps the watchlist quotes current. */
	void quotesTick() throws Exception
	{
		reads().quotesTick();
		settleSwing();
	}

	/** What the offer-screen overlay would draw for an item right now. */
	Quote watchedQuote(int itemId) throws Exception
	{
		final java.lang.reflect.Method m = FlippingRsPlugin.class.getDeclaredMethod("watchedQuote", int.class);
		m.setAccessible(true);
		return (Quote) m.invoke(plugin, itemId);
	}

	void addToWatchlist(int itemId) throws Exception
	{
		invoke("addToWatchlist", itemId);
		settleSwing();
	}

	void closePosition(String id, long price, Long qty) throws Exception
	{
		final java.lang.reflect.Method m = FlippingRsPlugin.class.getDeclaredMethod(
			"closePosition", String.class, long.class, Long.class);
		m.setAccessible(true);
		m.invoke(plugin, id, price, qty);
		settleSwing();
	}

	void deletePosition(String id) throws Exception
	{
		final java.lang.reflect.Method m = FlippingRsPlugin.class.getDeclaredMethod("deletePosition", String.class);
		m.setAccessible(true);
		m.invoke(plugin, id);
		settleSwing();
	}

	void removeFromWatchlist(int itemId) throws Exception
	{
		invoke("removeFromWatchlist", itemId);
		settleSwing();
	}

	/**
	 * The sidebar being opened on the FlippingRS panel, which is what startUp
	 * wires the panel's onShown to. Nothing the sidebar shows is read or drawn
	 * while it is shut, so a test about what it shows has to say it is open.
	 */
	void showSidebar() throws Exception
	{
		sidebar(true);
	}

	/** The sidebar being closed again, which RuneLite reports the same way. */
	void hideSidebar() throws Exception
	{
		sidebar(false);
	}

	private void sidebar(boolean shown) throws Exception
	{
		// Through the panel's own hooks, which is what RuneLite calls when the
		// sidebar opens and closes, so the wiring between them is exercised too.
		SwingUtilities.invokeAndWait(() ->
		{
			if (shown)
			{
				panel.onActivate();
			}
			else
			{
				panel.onDeactivate();
			}
		});
		settleNet();
		settleSwing();
	}

	/** Where the plugin would talk to, given the settings as they stand. */
	okhttp3.HttpUrl serverUrl() throws Exception
	{
		final java.lang.reflect.Method m = FlippingRsPlugin.class.getDeclaredMethod("baseUrl");
		m.setAccessible(true);
		return (okhttp3.HttpUrl) m.invoke(plugin);
	}

	/** RuneLite's --developer-mode flag, which the plugin is given at injection. */
	void setDeveloperMode(boolean on) throws Exception
	{
		set("developerMode", on);
	}

	/**
	 * As if the client had begun closing. Set directly, because doing it the
	 * real way stops the threads this harness runs everything on.
	 */
	void markShuttingDown() throws Exception
	{
		set("shuttingDown", true);
	}

	/** Pushes the session counts and the waiting buffer to the sidebar. */
	void refreshPending() throws Exception
	{
		reads().refreshPending();
		settle();
		settleSwing();
	}

	/** The offset the plugin would tell the site this machine is at. */
	int timezoneOffsetMinutes() throws Exception
	{
		final java.lang.reflect.Method m = PanelReads.class.getDeclaredMethod("tzOffsetMinutes");
		m.setAccessible(true);
		return (int) m.invoke(null);
	}

	/** One read of the two account tabs, throttling and coalescing and all. */
	void refreshAccountTabs() throws Exception
	{
		reads().refreshAccountTabs();
		settleNet();
		settleSwing();
	}

	/**
	 * The plugin being disabled and enabled again, which RuneLite does on the
	 * same instance. Only the part of startUp that forgets the last session;
	 * the rest of it builds a nav button and a real HTTP client.
	 */
	void reEnable() throws Exception
	{
		plugin.newSession();
	}

	/** The user pressing "Send now" on the Activity tab. */
	void pressSendNow() throws Exception
	{
		SwingUtilities.invokeAndWait(panel::pressSendNowForTest);
		settleNet();
		settleSwing();
	}

	/** How many delayed re-reads the plugin has queued on the net thread. */
	int deferredReadsForTest()
	{
		return sendExecutor.delayed.get();
	}

	/**
	 * As if the account tabs had last been read long ago, so the next read
	 * goes out rather than being coalesced into the fifteen-second window.
	 * Opening the sidebar reads them, and a test that then asserts on the read
	 * a send triggers would otherwise be asserting against a read the opening
	 * had already used up.
	 */
	void tabsLastReadLongAgo() throws Exception
	{
		final Field f = PanelReads.class.getDeclaredField("accountTabsRefreshedAt");
		f.setAccessible(true);
		f.setLong(reads(), System.nanoTime() - TimeUnit.MINUTES.toNanos(1));
	}

	/**
	 * As if the last slot snapshot had been taken this many seconds ago, so a
	 * test can say what happens once the gap between snapshots has run out
	 * without waiting out the real one.
	 */
	void offersLastSnapshotSecondsAgo(long seconds) throws Exception
	{
		final Field catchUpField = FlippingRsPlugin.class.getDeclaredField("catchUp");
		catchUpField.setAccessible(true);
		final Object catchUp = catchUpField.get(plugin);
		final Field at = CatchUp.class.getDeclaredField("lastOfferSnapshotAt");
		at.setAccessible(true);
		at.setLong(catchUp, System.nanoTime() - TimeUnit.SECONDS.toNanos(seconds));
	}

	/**
	 * The exchange's right-click menu, which startUp builds and wire does not.
	 *
	 * <p>Tests here drive the plugin through wire rather than startUp, so this
	 * is null unless a test asks for it -- and a test about the menu that
	 * forgets would pass on the menu not existing.
	 */
	void wireExchangeMenu() throws Exception
	{
		set("geMenu", new GeMenu(client, itemManager, id -> { }, id -> { }));
	}

	/** How often the sender is set to run, in seconds, or -1 if it never was. */
	long syncPeriodSecondsForTest()
	{
		return sendExecutor.period.get();
	}

	/** The user picking a journal in the dropdown, listener, wiring and all. */
	void chooseAccount(String id) throws Exception
	{
		SwingUtilities.invokeAndWait(() -> panel.setSelectedForTest(id));
		settleNet();
		settleSwing();
	}

	/** The user picking a watchlist in the dropdown, the same way. */
	void chooseWatchlist(String id) throws Exception
	{
		SwingUtilities.invokeAndWait(() -> panel.setSelectedWatchlistForTest(id));
		settleNet();
		settleSwing();
	}

	private void invoke(String name, int arg) throws Exception
	{
		final java.lang.reflect.Method m = FlippingRsPlugin.class.getDeclaredMethod(name, int.class);
		m.setAccessible(true);
		m.invoke(plugin, arg);
	}

	/** Waits for panel updates the plugin marshalled onto the Swing thread. */
	void settleSwing() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	private void invoke(String name) throws Exception
	{
		final java.lang.reflect.Method m = FlippingRsPlugin.class.getDeclaredMethod(name);
		m.setAccessible(true);
		m.invoke(plugin);
	}

	/** Waits for the disk thread to finish the work an offer event handed it. */
	void settle() throws Exception
	{
		diskExecutor.submit(() ->
		{
		}).get(10, TimeUnit.SECONDS);
	}

	/**
	 * Parks the net thread until the returned latch is released. Work handed
	 * to it meanwhile queues up behind, in order.
	 *
	 * <p>For the tests about something changing between a read on one thread
	 * and the send on the other. Without it, such a test is racing the net
	 * thread to the change: the send may already have gone, and if what the
	 * test changes is a mock's stubbing, the stub and the call are on two
	 * threads at once -- which Mockito does not support.
	 */
	CountDownLatch holdNet()
	{
		final CountDownLatch release = new CountDownLatch(1);
		sendExecutor.execute(() ->
		{
			try
			{
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		});
		return release;
	}

	/** Waits for the net thread to finish whatever was handed to it. */
	void settleNet() throws Exception
	{
		sendExecutor.submit(() ->
		{
		}).get(10, TimeUnit.SECONDS);
	}

	/** Stops the io thread on its own, the way disabling the plugin does. */
	void stopDiskThread()
	{
		diskExecutor.shutdown();
	}

	/** Stops the net thread on its own, so a hand-off to it is refused. */
	void stopSendThread()
	{
		sendExecutor.shutdown();
	}

	void close()
	{
		diskExecutor.shutdownNow();
		sendExecutor.shutdownNow();
	}
}
