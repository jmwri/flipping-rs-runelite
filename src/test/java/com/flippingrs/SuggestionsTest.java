package com.flippingrs;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Suggestions tab, against a local server speaking the contract: the
 * request it makes, what it does with each kind of answer, and when it asks.
 */
public class SuggestionsTest
{
	private static final String GOLDEN =
		"{\"updatedAt\":1760640000,\"budget\":10000000,\"members\":true,"
			+ "\"items\":[{\"id\":4151,\"name\":\"Abyssal whip\",\"buy\":768867,\"sell\":765331,\"margin\":3536,"
			+ "\"roi\":0.0046,\"buyLimit\":70,\"volume24h\":4826,\"confidence\":72,\"profitPerLimit\":247520}]}";

	private MockWebServer server;
	private FlippingRsApi api;
	private final Gson gson = new Gson();

	@Before
	public void setUp() throws IOException
	{
		server = new MockWebServer();
		server.start();
		api = new FlippingRsApi(new OkHttpClient(), gson, server.url("/"));
	}

	@After
	public void tearDown() throws IOException
	{
		server.shutdown();
	}

	private static void onEdt(Runnable body) throws InterruptedException, InvocationTargetException
	{
		SwingUtilities.invokeAndWait(body);
	}

	// ----------------------------------------------------------- the request

	@Test
	public void theGoldenReplyParses() throws Exception
	{
		server.enqueue(new MockResponse().setBody(GOLDEN));
		final Suggestions reply = api.suggestions("key", 0, 10);
		assertEquals(1760640000L, reply.getUpdatedAt());
		assertEquals(1, reply.getItems().size());
		final Suggestion s = reply.getItems().get(0);
		assertEquals(4151, s.getId());
		assertEquals("Abyssal whip", s.getName());
		assertEquals(768867, s.getBuy());
		assertEquals(765331, s.getSell());
		assertEquals(3536, s.getMargin());
		assertEquals(0.0046, s.getRoi(), 0.00001);
		assertEquals(70, s.getBuyLimit());
		assertEquals(4826, s.getVolume24h());
		assertEquals(72, s.getConfidence());
		assertEquals(247520, s.getProfitPerLimit());
	}

	@Test
	public void missingAndZeroFieldsDoNotBreakTheReply() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"items\":[{},null,{\"id\":7,\"buy\":0,\"roi\":0}]}"));
		final List<Suggestion> items = api.suggestions("key", 0, 10).getItems();
		assertEquals("the null entry is dropped", 2, items.size());
		assertNull(items.get(0).getName());
		assertEquals(0, items.get(0).getBuy());
		assertEquals(7, items.get(1).getId());

		server.enqueue(new MockResponse().setBody("{}"));
		assertTrue("no items field", api.suggestions("key", 0, 10).getItems().isEmpty());

		server.enqueue(new MockResponse().setBody("null"));
		assertTrue("a null body", api.suggestions("key", 0, 10).getItems().isEmpty());
	}

	@Test
	public void anEmptyListIsAnEmptyList() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"updatedAt\":1,\"items\":[]}"));
		assertTrue(api.suggestions("key", 0, 10).getItems().isEmpty());
	}

	@Test
	public void theRequestIsAReadOnlyGetWithTheKeyAndNothingElse() throws Exception
	{
		server.enqueue(new MockResponse().setBody(GOLDEN));
		api.suggestions("the-key", 5_000_000, 10);
		final RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertEquals("GET", request.getMethod());
		assertEquals("/api/plugin/suggestions", request.getRequestUrl().encodedPath());
		assertEquals("the-key", request.getHeader("X-Api-Key"));
		assertEquals("5000000", request.getRequestUrl().queryParameter("budget"));
		assertEquals("10", request.getRequestUrl().queryParameter("limit"));
		assertEquals("only the budget and the row count", 2, request.getRequestUrl().querySize());
		assertEquals("no body", 0, request.getBodySize());
	}

	@Test
	public void noBudgetMeansNoBudgetParameter() throws Exception
	{
		server.enqueue(new MockResponse().setBody(GOLDEN));
		server.enqueue(new MockResponse().setBody(GOLDEN));
		api.suggestions("k", 0, 10);
		api.suggestions("k", -5, 10);
		for (int i = 0; i < 2; i++)
		{
			final RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
			assertFalse(request.getRequestUrl().queryParameterNames().contains("budget"));
		}
	}

	@Test
	public void aRefusedKeyIsToldApartFromAnOutage() throws Exception
	{
		for (int code : new int[]{401, 403})
		{
			server.enqueue(new MockResponse().setResponseCode(code)
				.setBody("{\"error\":{\"message\":\"nope\"}}"));
			try
			{
				api.suggestions("k", 0, 10);
				fail("expected a refusal for " + code);
			}
			catch (FlippingRsApi.KeyRefusedException e)
			{
				assertEquals("nope", e.getMessage());
			}
		}
		server.enqueue(new MockResponse().setResponseCode(500));
		try
		{
			api.suggestions("k", 0, 10);
			fail();
		}
		catch (FlippingRsApi.KeyRefusedException | FlippingRsApi.RateLimitedException e)
		{
			fail("a 500 is neither");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage().contains("500"));
		}
	}

	@Test
	public void aRateLimitIsToldApart() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(429));
		try
		{
			api.suggestions("k", 0, 10);
			fail();
		}
		catch (FlippingRsApi.RateLimitedException expected)
		{
			// as intended
		}
	}

	// --------------------------------------------------------------- the words

	private static Suggestion suggestion(int id, String name, long buy, long sell, long margin, double roi,
		int limit, long volume, long perLimit)
	{
		final Suggestion s = new Suggestion();
		s.id = id;
		s.name = name;
		s.buy = buy;
		s.sell = sell;
		s.margin = margin;
		s.roi = roi;
		s.buyLimit = limit;
		s.volume24h = volume;
		s.profitPerLimit = perLimit;
		return s;
	}

	@Test
	public void largeNumbersUseTheWatchlistsFormatting()
	{
		final Suggestion s = suggestion(4151, "Abyssal whip", 768_867, 765_331, 3_536, 0.0046, 70, 4_826, 247_520);
		assertEquals("Buy 768,867 · Sell 765,331", SuggestionsTab.pricesText(s));
		assertEquals("Margin +3,536 · ROI 0.5%", SuggestionsTab.marginText(s));
		assertEquals("Limit 70 · +247.5K per limit · Volume 4.8K/24h", SuggestionsTab.limitText(s));

		final Suggestion big = suggestion(1, "Twisted bow", 1_480_000_000L, 1_520_000_000L, 31_000_000L, 0.02, 8, 12,
			248_000_000L);
		assertEquals("Buy 1,480,000,000 · Sell 1,520,000,000", SuggestionsTab.pricesText(big));
		assertEquals("Limit 8 · +248.00M per limit · Volume 12/24h", SuggestionsTab.limitText(big));
	}

	@Test
	public void smallNumbersAndMissingOnesAreLeftOutRatherThanShownAsZero()
	{
		final Suggestion small = suggestion(995, "Cheap thing", 12, 15, 2, 0.1666, 25_000, 900_000, 50_000);
		assertEquals("Buy 12 · Sell 15", SuggestionsTab.pricesText(small));
		assertEquals("Margin +2 · ROI 16.7%", SuggestionsTab.marginText(small));
		assertEquals("Limit 25000 · +50.0K per limit · Volume 900.0K/24h", SuggestionsTab.limitText(small));

		final Suggestion empty = new Suggestion();
		assertEquals("", SuggestionsTab.pricesText(empty));
		assertEquals("", SuggestionsTab.marginText(empty));
		assertEquals("", SuggestionsTab.limitText(empty));
		assertEquals("Unknown item", SuggestionsTab.nameOf(empty));
		empty.id = 9;
		assertEquals("Item 9", SuggestionsTab.nameOf(empty));
	}

	// ------------------------------------------------------------- the reads

	/** A tab, a feed and a clock the test moves. */
	private final class Rig
	{
		final FlippingRsConfig config = mock(FlippingRsConfig.class);
		final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
		final TestPanelActions actions = new TestPanelActions();
		FlippingRsPanel panel;
		SuggestionReads reads;
		final List<String> notices = new ArrayList<>();

		Rig() throws Exception
		{
			when(config.enabled()).thenReturn(true);
			when(config.apiKey()).thenReturn("a-key");
			onEdt(() ->
			{
				panel = new FlippingRsPanel(actions);
				actions.suggestionsShowing = showing -> reads.tabShowing(showing);
				actions.refreshSuggestions = () -> reads.refreshNow();
			});
			reads = new SuggestionReads(config, () -> api,
				action ->
				{
					try
					{
						if (SwingUtilities.isEventDispatchThread())
						{
							action.accept(panel);
							return;
						}
						onEdt(() -> action.accept(panel));
					}
					catch (Exception e)
					{
						throw new AssertionError(e);
					}
				},
				Runnable::run, Runnable::run, id -> null, clock::get);
		}

		void secondsPass(long seconds)
		{
			clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
		}

		void openTab() throws Exception
		{
			reads.sidebarShown(true);
			onEdt(() -> panel.selectTabForTest("Suggestions"));
		}

		String text() throws Exception
		{
			final StringBuilder out = new StringBuilder();
			onEdt(() ->
			{
				final List<JLabel> labels = new ArrayList<>();
				for (Component card : panel.cardsForTest("Suggestions"))
				{
					collect(card, labels);
				}
				for (JLabel label : labels)
				{
					out.append(plain(label)).append('\n');
				}
			});
			return out.toString();
		}
	}

	private static void collect(Component c, List<JLabel> out)
	{
		if (c instanceof JLabel)
		{
			out.add((JLabel) c);
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				collect(child, out);
			}
		}
	}

	private static void collectButtons(Component c, List<JButton> out)
	{
		if (c instanceof JButton)
		{
			out.add((JButton) c);
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				collectButtons(child, out);
			}
		}
	}

	private static String plain(JLabel label)
	{
		final String text = label.getText() == null ? "" : label.getText();
		return text.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
	}

	private static JButton buttonNamed(Component root, String text)
	{
		final List<JButton> buttons = new ArrayList<>();
		collectButtons(root, buttons);
		for (JButton button : buttons)
		{
			if (text.equals(button.getText()))
			{
				return button;
			}
		}
		throw new AssertionError("no button " + text);
	}

	private void answer(String body)
	{
		server.enqueue(new MockResponse().setBody(body));
	}

	@Test
	public void noKeyMeansNoRequestAndTheSameNoticeAsElsewhere() throws Exception
	{
		final Rig rig = new Rig();
		when(rig.config.apiKey()).thenReturn("   ");
		rig.openTab();
		rig.reads.tick();
		rig.secondsPass(120);
		rig.reads.tick();
		rig.reads.refreshNow();
		assertEquals(0, server.getRequestCount());
		assertTrue(rig.text(), rig.text().contains("Add your plugin key in the plugin settings."));
	}

	@Test
	public void recordingOffMeansNoRequest() throws Exception
	{
		final Rig rig = new Rig();
		when(rig.config.enabled()).thenReturn(false);
		rig.openTab();
		rig.reads.tick();
		assertEquals(0, server.getRequestCount());
		assertTrue(rig.text().contains("Recording is off"));
	}

	@Test
	public void aKeyEnteredWhileTheTabIsUpIsUsedOnTheNextCheck() throws Exception
	{
		final Rig rig = new Rig();
		when(rig.config.apiKey()).thenReturn("");
		rig.openTab();
		assertEquals(0, server.getRequestCount());
		when(rig.config.apiKey()).thenReturn("fresh");
		answer(GOLDEN);
		rig.secondsPass(5);
		rig.reads.tick();
		assertEquals(1, server.getRequestCount());
		assertEquals("fresh", server.takeRequest().getHeader("X-Api-Key"));
		assertTrue(rig.text().contains("Abyssal whip"));
	}

	@Test
	public void nothingIsReadWhileTheTabOrTheSidebarIsNotShowing() throws Exception
	{
		final Rig rig = new Rig();
		// Neither.
		rig.reads.tick();
		// The sidebar is open, on another tab.
		rig.reads.sidebarShown(true);
		rig.secondsPass(300);
		rig.reads.tick();
		rig.reads.refreshNow();
		assertEquals(0, server.getRequestCount());

		// The tab is selected, but the sidebar is shut.
		rig.reads.sidebarShown(false);
		onEdt(() -> rig.panel.selectTabForTest("Suggestions"));
		rig.secondsPass(300);
		rig.reads.tick();
		rig.reads.refreshNow();
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void itReadsWhenTheTabIsShownThenOnceAMinute() throws Exception
	{
		final Rig rig = new Rig();
		for (int i = 0; i < 4; i++)
		{
			answer(GOLDEN);
		}
		rig.openTab();
		assertEquals("on first show", 1, server.getRequestCount());

		rig.secondsPass(30);
		rig.reads.tick();
		assertEquals("not yet", 1, server.getRequestCount());

		rig.secondsPass(30);
		rig.reads.tick();
		assertEquals("a minute on", 2, server.getRequestCount());

		// Away and back inside the minute: no new read.
		onEdt(() -> rig.panel.selectTabForTest("Journal"));
		rig.secondsPass(10);
		rig.reads.tick();
		assertEquals("another tab is up", 2, server.getRequestCount());
		onEdt(() -> rig.panel.selectTabForTest("Suggestions"));
		assertEquals("back inside the minute", 2, server.getRequestCount());

		// The sidebar shuts for ten minutes and opens again.
		rig.reads.sidebarShown(false);
		rig.secondsPass(600);
		rig.reads.tick();
		assertEquals("shut", 2, server.getRequestCount());
		rig.reads.sidebarShown(true);
		assertEquals("opened again", 3, server.getRequestCount());
	}

	@Test
	public void theBudgetIsSentOnlyWhenItIsSetAndChangingItReadsAgain() throws Exception
	{
		final Rig rig = new Rig();
		answer(GOLDEN);
		answer(GOLDEN);
		rig.openTab();
		assertFalse(server.takeRequest().getRequestUrl().queryParameterNames().contains("budget"));

		when(rig.config.suggestionsBudget()).thenReturn(20_000_000);
		rig.secondsPass(5);
		rig.reads.tick();
		assertEquals("20000000", server.takeRequest().getRequestUrl().queryParameter("budget"));
	}

	@Test
	public void aRefreshButtonReadsButNotInAFlurry() throws Exception
	{
		final Rig rig = new Rig();
		answer(GOLDEN);
		answer(GOLDEN);
		answer(GOLDEN);
		rig.openTab();
		final JButton refresh = buttonNamed(rig.panel.getWrappedPanel(), "Refresh");

		onEdt(refresh::doClick);
		assertEquals("just read", 1, server.getRequestCount());

		rig.secondsPass(6);
		onEdt(refresh::doClick);
		assertEquals(2, server.getRequestCount());
		onEdt(refresh::doClick);
		assertEquals("a second press straight after is ignored", 2, server.getRequestCount());
	}

	@Test
	public void aRateLimitSaysSoKeepsTheRowsAndWaitsAMinute() throws Exception
	{
		final Rig rig = new Rig();
		answer(GOLDEN);
		server.enqueue(new MockResponse().setResponseCode(429));
		answer(GOLDEN);
		rig.openTab();
		rig.secondsPass(60);
		rig.reads.tick();
		assertEquals(2, server.getRequestCount());
		assertTrue(rig.text(), rig.text().contains("Rate limited, trying again in a minute."));
		assertTrue("the rows stay", rig.text().contains("Abyssal whip"));

		rig.secondsPass(10);
		rig.reads.refreshNow();
		rig.reads.tick();
		assertEquals("neither the button nor the timer pushes it", 2, server.getRequestCount());

		rig.secondsPass(50);
		rig.reads.tick();
		assertEquals(3, server.getRequestCount());
		assertFalse(rig.text().contains("Rate limited"));
	}

	@Test
	public void aRefusedKeyPointsAtTheAccountTabAndNeverMentionsAPlan() throws Exception
	{
		final Rig rig = new Rig();
		server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":{\"message\":\"x\"}}"));
		rig.openTab();
		final String text = rig.text();
		assertTrue(text, text.contains("flippingrs.com did not accept your plugin key."));
		assertFalse(text.toLowerCase().contains("plan"));
		assertFalse(text.toLowerCase().contains("upgrade"));

		final JButton link = buttonNamed(rig.panel.getWrappedPanel(), SuggestionsTab.KEY_HINT);
		onEdt(link::doClick);
		assertEquals("Account", rig.panel.selectedTabForTest());
	}

	@Test
	public void anOutageSaysSoInPlainText() throws Exception
	{
		final Rig rig = new Rig();
		server.enqueue(new MockResponse().setResponseCode(500));
		rig.openTab();
		assertTrue(rig.text(), rig.text().contains("Couldn't load suggestions: flippingrs.com returned HTTP 500"));
		assertFalse(rig.text().contains("Account tab"));
	}

	@Test
	public void anEmptyAnswerSaysNothingFitsAndLoadingShowsFirst() throws Exception
	{
		final Rig rig = new Rig();
		onEdt(() -> rig.panel.selectTabForTest("Suggestions"));
		assertTrue("before the sidebar is open", rig.text().contains("Loading suggestions"));
		answer("{\"items\":[]}");
		rig.reads.sidebarShown(true);
		assertTrue(rig.text(), rig.text().contains("Nothing fits this budget right now"));
		assertFalse(rig.text().contains("Loading"));
	}

	@Test
	public void noMoreThanFifteenRowsAreDrawn() throws Exception
	{
		final Rig rig = new Rig();
		final StringBuilder items = new StringBuilder();
		for (int i = 1; i <= 40; i++)
		{
			items.append(i > 1 ? "," : "").append("{\"id\":").append(i).append(",\"name\":\"Item number ")
				.append(i).append("\",\"buy\":100,\"sell\":110}");
		}
		answer("{\"items\":[" + items + "]}");
		rig.openTab();
		final String text = rig.text();
		assertTrue(text.contains("Item number 15"));
		assertFalse(text.contains("Item number 16"));
		assertTrue("in the server's order", text.indexOf("Item number 2") < text.indexOf("Item number 3"));
	}

	@Test
	public void aReplyThatChangesNothingRedrawsNothing() throws Exception
	{
		final Rig rig = new Rig();
		answer(GOLDEN);
		answer(GOLDEN);
		rig.openTab();
		final Component[] before = new Component[1];
		onEdt(() -> before[0] = rig.panel.cardsForTest("Suggestions")[0]);
		rig.secondsPass(60);
		rig.reads.tick();
		assertEquals(2, server.getRequestCount());
		final Component[] after = new Component[1];
		onEdt(() -> after[0] = rig.panel.cardsForTest("Suggestions")[0]);
		assertSame("the same row, not a rebuilt one", before[0], after[0]);
	}

	@Test
	public void theFootingSaysHowOldItIsAndWhatRefreshesIt() throws Exception
	{
		final Rig rig = new Rig();
		answer(GOLDEN);
		rig.openTab();
		final String line = rig.panel.freshnessTextForTest("Suggestions", System.currentTimeMillis() + 22_000);
		assertTrue(line, line.startsWith("Updated just now"));
		assertTrue(line, line.contains("next in 38s"));
	}

	@Test
	public void aRowCanCopyItsNameAndDoesNothingElse() throws Exception
	{
		final Rig rig = new Rig();
		answer(GOLDEN);
		final List<String> copied = new ArrayList<>();
		onEdt(() -> rig.panel.clipboardForTest(copied::add));
		rig.openTab();
		final JButton copy = buttonNamed(rig.panel.getWrappedPanel(), "Copy name");
		onEdt(copy::doClick);
		assertEquals(Collections.singletonList("Abyssal whip"), copied);
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void theTabTellsThePluginWhenItComesAndGoes() throws Exception
	{
		final TestPanelActions actions = new TestPanelActions();
		final List<Boolean> seen = new ArrayList<>();
		actions.suggestionsShowing = seen::add;
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel(actions);
			panel.selectTabForTest("Suggestions");
			panel.selectTabForTest("Suggestions");
			panel.selectTabForTest("Journal");
		});
		assertEquals("once each way, however often it is picked", java.util.Arrays.asList(true, false), seen);
	}

	@Test
	public void noRequestCarriesTradeData() throws Exception
	{
		final Rig rig = new Rig();
		when(rig.config.suggestionsBudget()).thenReturn(1_000_000);
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				return new MockResponse().setBody(GOLDEN);
			}
		});
		rig.openTab();
		rig.secondsPass(60);
		rig.reads.tick();
		rig.reads.refreshNow();
		final Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList("budget", "limit"));
		final int count = server.getRequestCount();
		assertTrue(count >= 2);
		for (int i = 0; i < count; i++)
		{
			final RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
			assertEquals("GET", request.getMethod());
			assertEquals(0, request.getBodySize());
			assertTrue(allowed.containsAll(request.getRequestUrl().queryParameterNames()));
			assertEquals("/api/plugin/suggestions", request.getRequestUrl().encodedPath());
		}
	}

	@Test
	public void theBudgetSettingDefaultsToUsingTheSitesDefault() throws Exception
	{
		final FlippingRsConfig defaults = new FlippingRsConfig()
		{
		};
		assertEquals(0, defaults.suggestionsBudget());
	}

	// ----------------------------------------------------------------- looks

	/**
	 * Paints the tab with three rows into a PNG, so there is something to look
	 * at. Written to build/ and not asserted on beyond not being blank.
	 */
	@Test
	public void theTabPaintsSomething() throws Exception
	{
		final Rig rig = new Rig();
		answer("{\"updatedAt\":1760640000,\"budget\":10000000,\"items\":["
			+ "{\"id\":4151,\"name\":\"Abyssal whip\",\"buy\":768867,\"sell\":765331,\"margin\":3536,\"roi\":0.0046,"
			+ "\"buyLimit\":70,\"volume24h\":4826,\"confidence\":72,\"profitPerLimit\":247520},"
			+ "{\"id\":11802,\"name\":\"Armadyl godsword\",\"buy\":7650000,\"sell\":7900000,\"margin\":120000,"
			+ "\"roi\":0.0157,\"buyLimit\":8,\"volume24h\":512,\"profitPerLimit\":960000},"
			+ "{\"id\":556,\"name\":\"Air rune\",\"buy\":12,\"sell\":15,\"margin\":2,\"roi\":0.1666,"
			+ "\"buyLimit\":25000,\"volume24h\":9000000,\"profitPerLimit\":50000}]}");
		rig.openTab();
		final String dir = System.getProperty("suggestions.shots", "build/suggestions");
		final BufferedImage[] image = new BufferedImage[1];
		onEdt(() ->
		{
			final JPanel body = rig.panel;
			final int width = net.runelite.client.ui.PluginPanel.PANEL_WIDTH;
			body.setSize(new Dimension(width, 2000));
			layoutAll(body);
			final Dimension wanted = body.getPreferredSize();
			body.setSize(new Dimension(width, wanted.height));
			layoutAll(body);
			image[0] = new BufferedImage(width, wanted.height, BufferedImage.TYPE_INT_RGB);
			body.paint(image[0].getGraphics());
		});
		new File(dir).mkdirs();
		javax.imageio.ImageIO.write(image[0], "png", new File(dir, "suggestions-tab.png"));
		final java.util.Set<Integer> colours = new java.util.HashSet<>();
		for (int x = 0; x < image[0].getWidth(); x += 3)
		{
			for (int y = 0; y < image[0].getHeight(); y += 3)
			{
				colours.add(image[0].getRGB(x, y));
			}
		}
		assertTrue("not a blank frame: " + colours.size() + " colours", colours.size() > 5);
		assertNotNull(rig.panel.suggestionsForTest());
	}

	private static void layoutAll(Container c)
	{
		c.doLayout();
		for (Component child : c.getComponents())
		{
			if (child instanceof Container)
			{
				layoutAll((Container) child);
			}
		}
	}
}
