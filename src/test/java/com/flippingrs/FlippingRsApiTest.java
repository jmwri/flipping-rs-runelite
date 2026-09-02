package com.flippingrs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.OkHttpClient;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The HTTP layer, against a real server.
 *
 * <p>Two things are pinned here. The surface: every call goes to
 * {@code /api/plugin}, because that is the boundary between what a plugin
 * key may do and what the Elite API sells. And the retry split: whether a
 * failure is worth trying again governs whether a trade is retried until it
 * lands or set aside, so it is pinned rather than reasoned about.
 */
public class FlippingRsApiTest
{
	private MockWebServer server;
	private FlippingRsApi api;

	@Before
	public void setUp() throws IOException
	{
		server = new MockWebServer();
		server.start();
		api = new FlippingRsApi(new OkHttpClient(), new Gson(), server.url("/"));
	}

	@After
	public void tearDown() throws IOException
	{
		server.shutdown();
	}

	private static GeTransaction fill(String id)
	{
		final GeTransaction tx = new GeTransaction();
		tx.id = id;
		tx.offerRef = "offer-1";
		tx.itemId = 4151;
		tx.itemName = "Abyssal whip";
		tx.side = "buy";
		tx.quantity = 10;
		tx.grossValue = 10_000_000;
		tx.offerPrice = 1_000_000;
		tx.offerTotal = 10;
		tx.occurredAt = "2026-08-31T12:00:00Z";
		return tx;
	}

	private List<GeTransaction> oneFill()
	{
		return Collections.singletonList(fill("t1"));
	}

	// ------------------------------------------------------------ the panel

	private static final String FULL_PANEL =
		"{\"me\":{\"displayName\":\"Jim\",\"effectiveTier\":\"pro\",\"onTrial\":true,\"trialDaysLeft\":5},"
			+ "\"accounts\":[{\"id\":\"a1\",\"label\":\"Main\",\"isDefault\":true},{\"id\":\"a2\",\"label\":\"\"}],"
			+ "\"recentTransactions\":[{\"id\":\"t2\",\"itemId\":4151,\"itemName\":\"Abyssal whip\",\"side\":\"sell\","
			+ "\"quantity\":1,\"grossValue\":1500000,\"occurredAt\":\"2026-08-31T12:05:00.123456789Z\",\"flipId\":\"f1\"}],"
			+ "\"week\":{\"completedFlips\":12,\"openFlips\":2,\"realisedProfit\":1200000,\"winRate\":0.75,\"gpPerHour\":45000},"
			+ "\"positions\":{\"positions\":[{\"itemId\":4151,\"itemName\":\"Abyssal whip\",\"remainingQty\":10,"
			+ "\"buyPrice\":1480000,\"currentSell\":1520000,\"unrealisedPnl\":96000,\"unrealisedRoi\":0.0065,"
			+ "\"breakEvenSell\":1510204,\"hoursHeld\":5.5,\"stale\":false}],"
			+ "\"summary\":{\"openPositions\":1,\"costBasis\":14800000,\"marketValue\":15200000,\"unrealisedPnl\":96000,"
			+ "\"marketDataAvailable\":true}},"
			+ "\"watchlists\":[{\"id\":\"wl_1\",\"name\":\"Plan\",\"itemIds\":[4151,11802]},{\"id\":\"wl_2\",\"itemIds\":null}],"
			+ "\"quotes\":[{\"id\":4151,\"name\":\"Abyssal whip\",\"buyLimit\":70,\"instantBuy\":1520000,\"instantSell\":1480000,"
			+ "\"netMargin\":9600,\"roi\":0.0065,\"profitPerLimit\":672000,\"volume24h\":1234},null]}";

	/** Each tab has its own read; all of them answer in the one panel shape. */
	@Test
	public void eachTabReadsItsOwnEndpointWithTheKey() throws Exception
	{
		server.enqueue(new MockResponse().setBody(FULL_PANEL));
		final FlippingRsApi.Panel account = api.account("frs_secret");
		RecordedRequest request = server.takeRequest();
		assertEquals("GET", request.getMethod());
		assertEquals("/api/plugin/account", request.getPath());
		assertEquals("frs_secret", request.getHeader("X-Api-Key"));
		assertEquals("Pro trial, 5 days left", account.getMe().describePlan());
		assertEquals(2, account.getAccounts().size());
		assertEquals("a nameless account shows its id", "a2", account.getAccounts().get(1).toString());

		server.enqueue(new MockResponse().setBody(FULL_PANEL));
		final FlippingRsApi.Panel trades = api.trades("frs_secret", "a1");
		assertEquals("/api/plugin/trades?accountId=a1", server.takeRequest().getPath());
		assertEquals("t2", trades.getRecentTransactions().get(0).id);

		server.enqueue(new MockResponse().setBody(FULL_PANEL));
		final FlippingRsApi.Panel journal = api.journal("frs_secret", "a1", 60);
		assertEquals("/api/plugin/journal?tzOffset=60&accountId=a1", server.takeRequest().getPath());
		assertEquals(12, journal.getWeek().getCompletedFlips());
		assertEquals(10L, journal.getPositions().getPositions().get(0).getRemainingQty());
		assertEquals(96_000L, journal.getPositions().getSummary().unrealisedPnl);

		server.enqueue(new MockResponse().setBody(FULL_PANEL));
		final FlippingRsApi.Panel lists = api.watchlists("frs_secret", "wl_1");
		assertEquals("/api/plugin/watchlists?watchlistId=wl_1", server.takeRequest().getPath());
		assertEquals(Arrays.asList(4151, 11802), lists.getWatchlists().get(0).getItemIds());
		assertTrue("null items are an empty list, not an NPE", lists.getWatchlists().get(1).getItemIds().isEmpty());
		assertEquals(1_480_000L, lists.getQuotes().get(4151).getBuyAt());
		assertEquals(1_520_000L, lists.getQuotes().get(4151).getSellAt());
		assertEquals("a null entry in the quotes array is skipped", 1, lists.getQuotes().size());
	}

	/** Without a journal chosen the account parameter is simply left off. */
	@Test
	public void anUnchosenJournalIsLeftOffTheQuery() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{}"));
		api.trades("k", null);
		assertEquals("/api/plugin/trades", server.takeRequest().getPath());

		server.enqueue(new MockResponse().setBody("{}"));
		api.watchlists("k", null);
		assertEquals("/api/plugin/watchlists", server.takeRequest().getPath());
	}

	/**
	 * A part a tab's endpoint does not return comes back null, and null means
	 * "leave what is showing alone", which is not the same as empty.
	 */
	@Test
	public void partsATabDoesNotReturnComeBackNullNotEmpty() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"quotes\":[]}"));

		final FlippingRsApi.Panel panel = api.watchlists("k", null);

		assertNotNull(panel.getQuotes());
		assertTrue(panel.getQuotes().isEmpty());
		assertNull(panel.getAccounts());
		assertNull(panel.getWatchlists());
		assertNull(panel.getRecentTransactions());
		assertNull(panel.getWeek());
		assertNull(panel.getPositions());
		assertNull(panel.getMe());
	}

	@Test
	public void anEmptyReplyIsAPanelWithNothingInIt() throws Exception
	{
		server.enqueue(new MockResponse().setBody("null"));
		final FlippingRsApi.Panel panel = api.account("k");
		assertNotNull(panel);
		assertNull(panel.getAccounts());
	}

	@Test
	public void theServersOwnMessageIsSurfaced() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(401).setBody(
			"{\"error\":{\"code\":\"unauthorized\",\"message\":\"Invalid or revoked API key.\"}}"));

		try
		{
			api.account("frs_stale");
			fail("expected a failure");
		}
		catch (IOException e)
		{
			assertEquals("Invalid or revoked API key.", e.getMessage());
		}
	}

	// ------------------------------------------------------------- ingest

	/**
	 * The batch has to arrive as {accountId, transactions[]}, because the server
	 * files everything in it under that one account.
	 */
	@Test
	public void submitPostsTheAgreedShapeToThePluginEndpoint() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"accepted\":1}"));

		api.submit("frs_secret", "acct-9", oneFill());

		final RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertEquals("/api/plugin/transactions", request.getPath());
		assertEquals("frs_secret", request.getHeader("X-Api-Key"));

		// The instance API, not the static parseString: runelite-client pins
		// Gson 2.8.5, which predates the static one.
		final JsonObject body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject();
		assertEquals("acct-9", body.get("accountId").getAsString());
		final JsonObject sent = body.getAsJsonArray("transactions").get(0).getAsJsonObject();
		assertEquals("t1", sent.get("id").getAsString());
		assertEquals("offer-1", sent.get("offerRef").getAsString());
		assertEquals("buy", sent.get("side").getAsString());
		assertEquals(10, sent.get("quantity").getAsLong());
		// The exact gp, which is the only field the server does money maths on.
		assertEquals(10_000_000L, sent.get("grossValue").getAsLong());
		assertEquals("2026-08-31T12:00:00Z", sent.get("occurredAt").getAsString());
	}

	@Test
	public void submitReadsBackWhatTheServerDid() throws Exception
	{
		server.enqueue(new MockResponse().setBody(
			"{\"accepted\":2,\"duplicate\":1,\"rejected\":3,\"flipsOpened\":1,"
				+ "\"flipsClosed\":1,\"unmatchedSellQty\":7,\"problems\":[\"row 4: bad side\"]}"));

		final FlippingRsApi.IngestResult result = api.submit("k", "a", oneFill());

		assertEquals(1, result.getFlipsOpened());
		assertEquals(1, result.getFlipsClosed());
		assertEquals(7, result.getUnmatchedSellQty());
		// Rejected rows are dropped from the queue, so this is the only chance
		// anyone has to learn a trade did not make it.
		assertEquals(3, result.getRejected());
		assertEquals(Collections.singletonList("row 4: bad side"), result.getProblems());
	}

	// ------------------------------------- retry or drop: the decision that matters

	/**
	 * Anything that is not about this batch is worth retrying. That includes
	 * a bad key: a mistyped one gets fixed, and the user who fixes it expects
	 * the trades from the meantime to go out, not to have been deleted thirty
	 * seconds at a time while the panel said "last attempt failed".
	 */
	@Test
	public void failuresThatAreNotAboutTheBatchAreWorthRetrying() throws Exception
	{
		for (int code : new int[]{500, 502, 503, 504, 429, 408, 401, 402, 403, 404})
		{
			server.enqueue(new MockResponse().setResponseCode(code));
			try
			{
				api.submit("k", "a", oneFill());
				fail("HTTP " + code + " should have thrown");
			}
			catch (FlippingRsApi.PermanentException e)
			{
				fail("HTTP " + code + " is temporary; treating it as permanent discards the trade");
			}
			catch (IOException expected)
			{
				// Right: the queue holds the batch and tries again.
			}
		}
	}

	/**
	 * A malformed, oversized or invalid batch will be just as bad in five
	 * minutes. Retrying it forever would wedge the queue behind a batch that
	 * can never drain, losing every trade after it.
	 */
	@Test
	public void aBatchTheServerCannotAcceptIsNotWorthRetrying() throws Exception
	{
		for (int code : new int[]{400, 413, 422})
		{
			server.enqueue(new MockResponse().setResponseCode(code));
			try
			{
				api.submit("k", "a", oneFill());
				fail("HTTP " + code + " should have thrown");
			}
			catch (FlippingRsApi.PermanentException expected)
			{
				// Right: set the batch aside and let the rest through.
			}
		}
	}

	@Test
	public void aFailureWithNoUsableBodyStillSaysSomething() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(503).setBody("<html>gateway</html>"));

		try
		{
			api.submit("k", "a", oneFill());
			fail("expected a failure");
		}
		catch (IOException e)
		{
			assertNotNull(e.getMessage());
			assertTrue("should name the status: " + e.getMessage(), e.getMessage().contains("503"));
		}
	}

	// ------------------------------------------------------------ robustness

	@Test
	public void aSuccessfulResponseThatIsNotJsonFailsSafely() throws Exception
	{
		server.enqueue(new MockResponse().setBody("<html>a login page</html>"));

		try
		{
			api.submit("k", "a", oneFill());
			fail("expected a failure rather than a bogus result");
		}
		catch (FlippingRsApi.PermanentException e)
		{
			fail("garbage from a proxy is worth retrying, not a reason to drop the trade");
		}
		catch (IOException expected)
		{
			// Retryable: this is usually a captive portal or a proxy, not us.
		}
	}

	/**
	 * body.string() has no ceiling and the client runs with -Xmx768m. A reply
	 * large enough to exhaust the heap must fail the sync, not the game.
	 */
	@Test
	public void anEnormousResponseDoesNotExhaustMemory() throws Exception
	{
		final StringBuilder huge = new StringBuilder(4 << 20);
		while (huge.length() < (4 << 20))
		{
			huge.append("aaaaaaaaaaaaaaaa");
		}
		server.enqueue(new MockResponse().setBody(huge.toString()));

		try
		{
			api.submit("k", "a", oneFill());
			fail("expected a parse failure");
		}
		catch (IOException expected)
		{
			// Read up to the cap, failed to parse, reported. No OOM.
		}
	}

	/**
	 * A 200 that accounts for none of the rows did not come from this API. A
	 * proxy or captive portal answering with an empty object used to be taken
	 * as confirmation, and the batch was deleted on the word of something that
	 * never saw it.
	 */
	@Test
	public void aReplyThatAcknowledgesNothingIsRetriedNotConfirmed() throws Exception
	{
		for (String body : new String[]{"null", "{}", "{\"accepted\":0,\"duplicate\":0,\"rejected\":0}"})
		{
			server.enqueue(new MockResponse().setBody(body));
			try
			{
				api.submit("k", "a", oneFill());
				fail("a reply of " + body + " should not confirm a batch");
			}
			catch (FlippingRsApi.PermanentException e)
			{
				fail("not a reason to drop the trade either");
			}
			catch (IOException expected)
			{
				// The batch stays queued.
			}
		}
	}

	@Test
	public void duplicatesCountAsAcknowledged() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"accepted\":0,\"duplicate\":1,\"rejected\":0}"));

		final FlippingRsApi.IngestResult result = api.submit("k", "a", oneFill());

		assertEquals(0, result.getRejected());
	}

	/**
	 * The batch was accepted; only the shape of the explanation changed. A
	 * parse failure here would report the send as retryable, and the same
	 * batch would be re-sent every tick with the queue wedged behind it.
	 */
	@Test
	public void structuredProblemsDoNotFailAnAcceptedBatch() throws Exception
	{
		server.enqueue(new MockResponse().setBody(
			"{\"accepted\":1,\"rejected\":1,\"problems\":[{\"row\":4,\"reason\":\"bad side\"},\"row 5: late\",null]}"));

		final FlippingRsApi.IngestResult result = api.submit("k", "a", oneFill());

		assertEquals(1, result.getRejected());
		assertEquals(2, result.getProblems().size());
		assertTrue(result.getProblems().get(0).contains("bad side"));
		assertEquals("row 5: late", result.getProblems().get(1));
	}

	// ---------------------------------------------------------- watchlists

	@Test
	public void aWatchlistIsCreatedWithItsFirstItem() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(201).setBody(
			"{\"id\":\"wl_9\",\"name\":\"Plan\",\"itemIds\":[4151]}"));

		final FlippingRsApi.Watchlist created = api.createWatchlist("k", "Plan", Collections.singletonList(4151));

		final RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertEquals("/api/plugin/watchlists", request.getPath());
		final JsonObject body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject();
		assertEquals("Plan", body.get("name").getAsString());
		assertEquals(4151, body.getAsJsonArray("itemIds").get(0).getAsInt());
		assertEquals("wl_9", created.getId());
	}

	/** Only the items are sent: the name is the user's, and the server keeps it when absent. */
	@Test
	public void aWatchlistUpdateReplacesTheItemsAndLeavesTheNameAlone() throws Exception
	{
		server.enqueue(new MockResponse().setBody(
			"{\"id\":\"wl_1\",\"name\":\"Plan\",\"itemIds\":[4151,11802]}"));

		final FlippingRsApi.Watchlist updated = api.updateWatchlist("k", "wl_1", Arrays.asList(4151, 11802));

		final RecordedRequest request = server.takeRequest();
		assertEquals("PATCH", request.getMethod());
		assertEquals("/api/plugin/watchlists/wl_1", request.getPath());
		final JsonObject body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject();
		assertFalse("the name must not be sent", body.has("name"));
		assertEquals(2, body.getAsJsonArray("itemIds").size());
		assertEquals(Arrays.asList(4151, 11802), updated.getItemIds());
	}

	/**
	 * A plan limit is a message for the user, not a reason to drop anything.
	 * It arrives as a retryable failure carrying the server's own words.
	 */
	@Test
	public void aPlanLimitOnWatchlistsSurfacesTheServersMessage() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(402).setBody(
			"{\"error\":{\"code\":\"upgrade_required\",\"message\":\"This feature requires the Pro plan.\","
				+ "\"details\":{\"feature\":\"watchlists\",\"requiredTier\":\"Pro\"}}}"));

		try
		{
			api.createWatchlist("k", "Plan", Collections.singletonList(4151));
			fail("expected a failure");
		}
		catch (FlippingRsApi.PermanentException e)
		{
			fail("a plan limit is not a malformed request");
		}
		catch (IOException e)
		{
			assertEquals("This feature requires the Pro plan.", e.getMessage());
		}
	}

	@Test
	public void aWatchlistReplyWithoutAnIdIsAFailureNotAPhantom() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{}"));

		try
		{
			api.updateWatchlist("k", "wl_1", Collections.singletonList(4151));
			fail("expected a failure");
		}
		catch (IOException expected)
		{
			// The panel would otherwise show a list with no id that nothing
			// can be added to.
		}
	}

	// ------------------------------------------------- catching the server up

	@Test
	public void openOffersAreSentForReconciliation() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"reconciled\":1,\"recovered\":1}"));
		final FlippingRsApi.OfferState state = new FlippingRsApi.OfferState();
		state.slot = 3;
		state.offerRef = "ref-1";
		state.itemId = 4151;
		state.itemName = "Abyssal whip";
		state.side = "buy";
		state.price = 1_000_000;
		state.totalQuantity = 10;
		state.quantitySold = 4;
		state.spent = 4_000_000;
		state.state = "BUYING";

		final FlippingRsApi.Reconciliation result = api.submitOffers("frs_secret", "acct-1", Collections.singletonList(state));

		final RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertEquals("/api/plugin/offers", request.getPath());
		assertEquals("frs_secret", request.getHeader("X-Api-Key"));
		final JsonObject body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject();
		assertEquals("acct-1", body.get("accountId").getAsString());
		final JsonObject sent = body.getAsJsonArray("offers").get(0).getAsJsonObject();
		assertEquals(3, sent.get("slot").getAsInt());
		assertEquals("ref-1", sent.get("offerRef").getAsString());
		assertEquals(4, sent.get("quantitySold").getAsLong());
		assertEquals("BUYING", sent.get("state").getAsString());
		assertEquals(1, result.getRecovered());
	}

	@Test
	public void theHistoryScreenIsSentAsRead() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"matched\":1,\"added\":1,\"ignored\":0}"));
		final FlippingRsApi.HistoryRow row = new FlippingRsApi.HistoryRow();
		row.position = 0;
		row.itemId = 4151;
		row.itemName = "Abyssal whip";
		row.side = "sell";
		row.quantity = 3;
		row.grossValue = 4_560_000;

		final FlippingRsApi.Reconciliation result = api.submitHistory("k", "acct-1", Collections.singletonList(row));

		final RecordedRequest request = server.takeRequest();
		assertEquals("/api/plugin/history", request.getPath());
		final JsonObject body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject();
		final JsonObject sent = body.getAsJsonArray("rows").get(0).getAsJsonObject();
		assertEquals("sell", sent.get("side").getAsString());
		assertEquals(4_560_000L, sent.get("grossValue").getAsLong());
		assertEquals(1, result.getAdded());
	}

	/** The whole surface, pinned: nothing outside /api/plugin is ever called. */
	@Test
	public void everyCallStaysUnderThePluginPrefix() throws Exception
	{
		for (int i = 0; i < 4; i++)
		{
			server.enqueue(new MockResponse().setBody("{}"));
		}
		server.enqueue(new MockResponse().setBody("{\"accepted\":1}"));
		server.enqueue(new MockResponse().setBody("{\"id\":\"wl_1\"}"));
		server.enqueue(new MockResponse().setBody("{\"id\":\"wl_1\"}"));
		server.enqueue(new MockResponse().setBody("{}"));
		server.enqueue(new MockResponse().setBody("{}"));

		api.account("k");
		api.trades("k", "a");
		api.journal("k", "a", 0);
		api.watchlists("k", "w");
		api.submit("k", "a", oneFill());
		api.createWatchlist("k", "Plan", Collections.singletonList(1));
		api.updateWatchlist("k", "wl_1", Collections.singletonList(1));
		api.submitOffers("k", "a", Collections.emptyList());
		api.submitHistory("k", "a", Collections.emptyList());

		for (int i = 0; i < 9; i++)
		{
			final String path = server.takeRequest().getPath();
			assertTrue(path, path.startsWith("/api/plugin/"));
		}
	}
}
