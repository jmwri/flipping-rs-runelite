package com.flippingrs;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
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
 * them directly keeps the wiring visible in one place. startUp() is
 * deliberately not called -- it builds a nav button and a real HTTP client,
 * neither of which the behaviour under test needs.
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
	private final ScheduledExecutorService sendExecutor = Executors.newSingleThreadScheduledExecutor();

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

		// Nothing on the server until a test says otherwise: a reply with
		// every part absent, which the plugin treats as "leave it alone".
		when(api.account(anyString())).thenReturn(new FlippingRsApi.Panel());
		when(api.trades(anyString(), any())).thenReturn(new FlippingRsApi.Panel());
		when(api.journal(anyString(), any(), anyInt())).thenReturn(new FlippingRsApi.Panel());
		when(api.watchlists(anyString(), any())).thenReturn(new FlippingRsApi.Panel());
		when(api.submitOffers(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.Reconciliation());
		when(api.submitHistory(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.Reconciliation());

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

		final FlippingRsPanel[] built = new FlippingRsPanel[1];
		SwingUtilities.invokeAndWait(() -> built[0] = new FlippingRsPanel());
		panel = built[0];
		set("panel", panel);

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
	}

	private void set(String name, Object value) throws Exception
	{
		final Field f = FlippingRsPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	/** The queue the plugin is using for the logged-in account. */
	TransactionQueue queue() throws Exception
	{
		final java.lang.reflect.Method m = FlippingRsPlugin.class
			.getDeclaredMethod("queueFor", long.class);
		m.setAccessible(true);
		return (TransactionQueue) m.invoke(plugin, client.getAccountHash());
	}

	void drain() throws Exception
	{
		invoke("drain");
	}

	void connect() throws Exception
	{
		invoke("connect");
		settleSwing();
	}

	/** What the offer-screen overlay would draw for an item right now. */
	FlippingRsApi.Quote watchedQuote(int itemId) throws Exception
	{
		final java.lang.reflect.Method m = FlippingRsPlugin.class.getDeclaredMethod("watchedQuote", int.class);
		m.setAccessible(true);
		return (FlippingRsApi.Quote) m.invoke(plugin, itemId);
	}

	void addToWatchlist(int itemId) throws Exception
	{
		invoke("addToWatchlist", itemId);
		settleSwing();
	}

	void removeFromWatchlist(int itemId) throws Exception
	{
		invoke("removeFromWatchlist", itemId);
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

	/** Waits for the net thread to finish whatever was handed to it. */
	void settleNet() throws Exception
	{
		sendExecutor.submit(() ->
		{
		}).get(10, TimeUnit.SECONDS);
	}

	void close()
	{
		diskExecutor.shutdownNow();
		sendExecutor.shutdownNow();
	}
}
