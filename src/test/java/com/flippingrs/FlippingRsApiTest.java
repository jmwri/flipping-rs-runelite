package com.flippingrs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
			+ "\"buyPrice\":1480000,\"currentSell\":1500000,\"currentBuy\":1520000,\"unrealisedPnl\":96000,\"unrealisedRoi\":0.0065,"
			+ "\"breakEvenSell\":1510204,\"hoursHeld\":5.5,\"stale\":true}],"
			+ "\"summary\":{\"openPositions\":1,\"costBasis\":14800000,\"marketValue\":15200000,\"unrealisedPnl\":96000,"
			+ "\"marketDataAvailable\":false}},"
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
		assertTrue("which journal is the default one, that a new character adopts",
			account.getAccounts().get(0).isDefault);
		assertFalse(account.getAccounts().get(1).isDefault);

		server.enqueue(new MockResponse().setBody(FULL_PANEL));
		final FlippingRsApi.Panel trades = api.trades("frs_secret", "a1");
		assertEquals("/api/plugin/trades?accountId=a1", server.takeRequest().getPath());

		server.enqueue(new MockResponse().setBody(FULL_PANEL));
		final FlippingRsApi.Panel journal = api.journal("frs_secret", "a1", 60);
		assertEquals("/api/plugin/journal?tzOffset=60&accountId=a1", server.takeRequest().getPath());
		assertEverySentFieldOfTheJournalArrived(journal);

		server.enqueue(new MockResponse().setBody(FULL_PANEL));
		final FlippingRsApi.Panel lists = api.watchlists("frs_secret", "wl_1");
		assertEquals("/api/plugin/watchlists?watchlistId=wl_1", server.takeRequest().getPath());
		assertEquals(Arrays.asList(4151, 11802), lists.getWatchlists().get(0).getItemIds());
		assertTrue("null items are an empty list, not an NPE", lists.getWatchlists().get(1).getItemIds().isEmpty());
		assertEquals("Plan", lists.getWatchlists().get(0).toString());
		assertEquals("a null entry in the quotes array is skipped", 1, lists.getQuotes().size());
		assertEveryFieldOfTheQuoteArrived(lists.getQuotes().get(4151));

		assertEquals("t2", trades.getRecentTransactions().get(0).id);
		final GeTransaction recent = trades.getRecentTransactions().get(0);
		assertEquals("Abyssal whip", recent.itemName);
		assertEquals("sell", recent.side);
		assertEquals(1L, recent.quantity);
		assertEquals(1_500_000L, recent.grossValue);
		assertEquals("2026-08-31T12:05:00.123456789Z", recent.occurredAt);
		assertEquals(4151, recent.itemId);
	}

	/**
	 * Every figure the Journal tab shows, read back out of one reply.
	 *
	 * <p>These are matched to the server's JSON by field name, so a name that
	 * drifts from the site's does not fail: the field stays at zero and the
	 * tab shows a break-even of nothing, an age of nothing, a return of
	 * nought per cent. A sample of the fields cannot catch that; only all of
	 * them can.
	 */
	private static void assertEverySentFieldOfTheJournalArrived(FlippingRsApi.Panel journal)
	{
		final FlippingRsApi.Analytics week = journal.getWeek();
		assertEquals("completed flips", 12, week.getCompletedFlips());
		assertEquals("open flips", 2, week.getOpenFlips());
		assertEquals("realised profit", 1_200_000L, week.getRealisedProfit());
		assertEquals("win rate", 0.75, week.getWinRate(), 0.0);
		assertEquals("gp per hour", 45_000L, week.getGpPerHour());

		final FlippingRsApi.Position p = journal.getPositions().getPositions().get(0);
		assertEquals("item", 4151, p.getItemId());
		assertEquals("item name", "Abyssal whip", p.getItemName());
		assertEquals("how many are left", 10L, p.getRemainingQty());
		assertEquals("what they cost", 1_480_000L, p.getBuyPrice());
		assertEquals("what a patient sale lists at", 1_520_000L, p.getCurrentBuy());
		assertEquals("what an instant sale gets", 1_500_000L, p.getCurrentSell());
		assertEquals("profit so far", 96_000L, p.getUnrealisedPnl());
		assertEquals("return so far", 0.0065, p.getUnrealisedRoi(), 0.0);
		assertEquals("the price that breaks even", 1_510_204L, p.getBreakEvenSell());
		assertEquals("how long it has been held", 5.5, p.getHoursHeld(), 0.0);
		assertTrue("whether it is stale", p.isStale());

		final FlippingRsApi.Positions.Summary totals = journal.getPositions().getSummary();
		assertEquals("open positions", 1, totals.openPositions);
		assertEquals("cost basis", 14_800_000L, totals.costBasis);
		assertEquals("market value", 15_200_000L, totals.marketValue);
		assertEquals("unrealised", 96_000L, totals.unrealisedPnl);
		assertFalse("whether there are prices at all", totals.marketDataAvailable);
	}

	/** The same for a watchlist card's quote. */
	private static void assertEveryFieldOfTheQuoteArrived(FlippingRsApi.Quote q)
	{
		assertEquals("id", 4151, q.getId());
		// buyAt is the site's instantSell and sellAt its instantBuy: what you
		// pay to buy now is what someone else is selling at.
		assertEquals("buy at", 1_480_000L, q.getBuyAt());
		assertEquals("sell at", 1_520_000L, q.getSellAt());
		assertEquals("margin after tax", 9_600L, q.getNetMargin());
		assertEquals("return", 0.0065, q.getRoi(), 0.0);
		assertEquals("buy limit", 70, q.getBuyLimit());
		assertEquals("profit per limit", 672_000L, q.getProfitPerLimit());
		assertEquals("how much trades in a day", 1234L, q.getVolume24h());
	}

	/**
	 * The combo boxes render toString through a JLabel, which interprets a
	 * string beginning with "<html>" as markup. A name like that must reach
	 * the label in a form Swing will not interpret, and any other name must
	 * reach it untouched.
	 */
	@Test
	public void namesThatLookLikeMarkupAreShownAsTyped()
	{
		final FlippingRsApi.GameAccount account = new FlippingRsApi.GameAccount();
		account.id = "a1";
		account.label = "<html><b>Main</b>";
		assertFalse(javax.swing.plaf.basic.BasicHTML.isHTMLString(account.toString()));
		assertTrue(account.toString().trim().startsWith("<html>"));

		final FlippingRsApi.Watchlist watchlist = new FlippingRsApi.Watchlist();
		watchlist.id = "w1";
		watchlist.name = "<HTML>Plan";
		assertFalse("the check is case-insensitive, so the guard must be too",
			javax.swing.plaf.basic.BasicHTML.isHTMLString(watchlist.toString()));

		watchlist.name = "Plan <html> not at the start";
		assertEquals("only a leading tag is touched", "Plan <html> not at the start", watchlist.toString());
		assertEquals("Plan", FlippingRsApi.plain("Plan"));
		assertNull(FlippingRsApi.plain(null));
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

	/**
	 * The panel lays a failure's message out as wrapped HTML on the event
	 * thread. The body is capped at a megabyte; without this, the rest of that
	 * megabyte would arrive in a Swing label and freeze the interface -- the
	 * same failure the body cap exists to prevent, one step further in.
	 */
	@Test
	public void anEnormousServerMessageIsCutDownBeforeItReachesThePanel() throws Exception
	{
		final StringBuilder huge = new StringBuilder();
		for (int i = 0; i < 20_000; i++)
		{
			huge.append('x');
		}
		server.enqueue(new MockResponse().setResponseCode(422).setBody(
			"{\"error\":{\"code\":\"bad\",\"message\":\"" + huge + "\"}}"));

		try
		{
			api.submit("frs_key", "acct-1", java.util.Collections.singletonList(new GeTransaction()));
			fail("expected a failure");
		}
		catch (IOException e)
		{
			assertTrue("the message must be cut down: " + e.getMessage().length(),
				e.getMessage().length() < 400);
			assertTrue(e.getMessage().endsWith("..."));
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

	/**
	 * The plugin sends these fields and no others.
	 *
	 * <p>The README makes a promise about this: the character name is never
	 * sent, and neither is anything about other players, the inventory, the
	 * bank, where the player is, or their chat. Every test around this one
	 * checks that a field it does send carries the right value; none of them
	 * would notice a field appearing that should not be there at all.
	 *
	 * <p>That is the failure worth guarding. A field added for debugging, or
	 * picked up from an object that grew one, goes out to a third-party
	 * service on every send and nothing in the plugin looks any different. So
	 * the whole set is written down here, and anything added to it has to be
	 * added here too -- which is the point at which somebody has to decide
	 * whether it belongs.
	 */
	@Test
	public void nothingIsSentBeyondTheFieldsTheReadmeNames() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"accepted\":1}"));
		final GeTransaction tx = oneFill().get(0);
		// Every field set, so none is left out of the JSON for being null.
		tx.offerRef = "offer-1";
		tx.itemName = "Abyssal whip";
		tx.offerPrice = 1000;
		tx.offerTotal = 10;
		tx.completed = true;
		tx.cancelled = true;
		tx.estimated = true;
		tx.slot = 3;
		tx.world = 302;
		tx.source = GeTransaction.SOURCE_ADOPTED;
		api.submit("k", "a", Collections.singletonList(tx));

		final JsonObject batch = new JsonParser().parse(server.takeRequest().getBody().readUtf8())
			.getAsJsonObject();
		assertEquals(new java.util.TreeSet<>(Arrays.asList("accountId", "transactions")),
			new java.util.TreeSet<>(batch.keySet()));
		assertEquals("the fields of a trade",
			new java.util.TreeSet<>(Arrays.asList(
				"id", "offerRef", "itemId", "itemName", "side", "quantity", "grossValue",
				"offerPrice", "offerTotal", "completed", "cancelled", "estimated",
				"slot", "world", "occurredAt", "source")),
			new java.util.TreeSet<>(batch.getAsJsonArray("transactions").get(0)
				.getAsJsonObject().keySet()));

		server.enqueue(new MockResponse().setBody("{}"));
		final FlippingRsApi.OfferState state = new FlippingRsApi.OfferState();
		state.offerRef = "ref-1";
		state.itemName = "Abyssal whip";
		state.side = "buy";
		state.state = "BUYING";
		api.submitOffers("k", "a", Collections.singletonList(state));

		final JsonObject offers = new JsonParser().parse(server.takeRequest().getBody().readUtf8())
			.getAsJsonObject();
		assertEquals(new java.util.TreeSet<>(Arrays.asList("accountId", "offers")),
			new java.util.TreeSet<>(offers.keySet()));
		assertEquals("the fields of an open offer",
			new java.util.TreeSet<>(Arrays.asList(
				"slot", "offerRef", "itemId", "itemName", "side", "price", "totalQuantity",
				"quantitySold", "spent", "spentEstimated", "state")),
			new java.util.TreeSet<>(offers.getAsJsonArray("offers").get(0)
				.getAsJsonObject().keySet()));

		server.enqueue(new MockResponse().setBody("{}"));
		final FlippingRsApi.HistoryRow row = new FlippingRsApi.HistoryRow();
		row.itemName = "Abyssal whip";
		row.side = "sell";
		api.submitHistory("k", "a", Collections.singletonList(row));

		final JsonObject history = new JsonParser().parse(server.takeRequest().getBody().readUtf8())
			.getAsJsonObject();
		assertEquals(new java.util.TreeSet<>(Arrays.asList("accountId", "rows")),
			new java.util.TreeSet<>(history.keySet()));
		assertEquals("the fields of a history row",
			new java.util.TreeSet<>(Arrays.asList(
				"position", "itemId", "itemName", "side", "quantity", "grossValue")),
			new java.util.TreeSet<>(history.getAsJsonArray("rows").get(0)
				.getAsJsonObject().keySet()));
	}

	// ------------------------------------- retry or drop: the decision that matters

	/**
	 * Answers every request, however many the client makes, with one status.
	 *
	 * @see #failuresThatAreNotAboutTheBatchAreWorthRetrying
	 */
	private void answerEverythingWith(int code)
	{
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				return new MockResponse().setResponseCode(code);
			}
		});
	}

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
			// Answered by a dispatcher rather than a queued response, because
			// OkHttp retries a 408 by itself. A queued one is taken by that
			// retry, and every code after it in this list then met a server
			// with nothing left to say: the call timed out, the timeout is an
			// IOException, and the loop went green having never asked about a
			// revoked key, a lapsed plan or a moved endpoint at all.
			answerEverythingWith(code);
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
	 * A batch the server rejected every row of has still been answered.
	 *
	 * <p>A reply that accounts for none of the rows is not the server's and is
	 * retried, which is what stops a proxy's empty answer from deleting a
	 * batch. But rejected rows are accounted for: the server read them, said
	 * no, and will say no again in five minutes. Counting only the ones it
	 * liked would leave the batch queued for ever with every later trade stuck
	 * behind it -- the same wedge the permanent-failure split exists to
	 * prevent, reached by another road.
	 */
	@Test
	public void aBatchTheServerRejectedEveryRowOfIsNotRetried() throws Exception
	{
		server.enqueue(new MockResponse().setBody(
			"{\"accepted\":0,\"duplicate\":0,\"rejected\":3,\"problems\":[\"row 1: bad side\"]}"));

		final FlippingRsApi.IngestResult result = api.submit("k", "a", oneFill());

		assertEquals("the rows were read and refused, not lost in transit", 3, result.getRejected());
	}

	/**
	 * An exception with nothing to say is named rather than shown as blank.
	 *
	 * <p>What comes back from here goes straight into the sidebar after
	 * "Could not connect: ", and not every failure carries a message -- so a
	 * sentence that stops at the colon tells the user nothing at all.
	 */
	@Test
	public void aFailureWithNoMessageIsNamedByItsKind()
	{
		assertEquals("SocketTimeoutException",
			FlippingRsApi.describe(new java.net.SocketTimeoutException()));
		assertEquals("SocketTimeoutException",
			FlippingRsApi.describe(new java.net.SocketTimeoutException("")));
		assertEquals("timed out", FlippingRsApi.describe(new java.io.IOException("timed out")));
	}

	/**
	 * A reply too big to hold is not held.
	 *
	 * <p>Reading a body puts all of it in memory, and the client runs in 768
	 * megabytes. Every real reply here is a few kilobytes, but a server having
	 * a bad day -- or anything sitting between here and it -- can answer with
	 * far more, and a big enough one takes the game down rather than failing a
	 * sync. So only the first megabyte is read, and a reply cut off there does
	 * not parse, which is the right answer: the batch is held and tried again.
	 *
	 * <p>The reply here is valid JSON that would confirm the batch if it were
	 * read whole, so this fails if the cap is ever taken off.
	 */
	@Test
	public void aReplyTooBigToHoldIsNotRead() throws Exception
	{
		final StringBuilder body = new StringBuilder(2_000_000);
		body.append("{\"accepted\":1,\"padding\":\"");
		for (int i = 0; i < 1_500_000; i++)
		{
			body.append('a');
		}
		body.append("\"}");
		server.enqueue(new MockResponse().setBody(body.toString()));

		try
		{
			api.submit("k", "a", oneFill());
			fail("a reply of " + body.length() + " bytes should not have been read whole");
		}
		catch (FlippingRsApi.PermanentException e)
		{
			fail("the batch is fine; it is the reply that could not be read");
		}
		catch (IOException expected)
		{
			// Right: cut off, so it does not parse, so the batch is tried again.
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
			answerEverythingWith(code);
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

	// -------------------------------------------------------------- positions

	@Test
	public void closingAPositionPostsThePriceAndQuantityToItsRoute() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"id\":\"f1\",\"state\":\"closed\"}"));

		api.closePosition("frs_secret", "f1", 1_520_000, 4L);

		final RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertEquals("/api/plugin/positions/f1/close", request.getPath());
		assertEquals("frs_secret", request.getHeader("X-Api-Key"));
		final JsonObject body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject();
		assertEquals(1_520_000L, body.get("sellPrice").getAsLong());
		assertEquals(4L, body.get("sellQty").getAsLong());
	}

	/** No quantity means "everything still held", which the server applies when the field is absent. */
	@Test
	public void closingWithoutAQuantityLeavesTheFieldOut() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{}"));

		api.closePosition("k", "f1", 1_520_000, null);

		final JsonObject body = new JsonParser().parse(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
		assertFalse(body.has("sellQty"));
	}

	@Test
	public void deletingAPositionUsesTheDeleteMethodOnItsRoute() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"status\":\"deleted\"}"));

		api.deletePosition("frs_secret", "f1");

		final RecordedRequest request = server.takeRequest();
		assertEquals("DELETE", request.getMethod());
		assertEquals("/api/plugin/positions/f1", request.getPath());
		assertEquals("frs_secret", request.getHeader("X-Api-Key"));
	}

	/** A refusal carries the server's own words, which the Journal tab shows. */
	@Test
	public void aRefusedCloseSurfacesTheServersMessage() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(409).setBody(
			"{\"error\":{\"code\":\"conflict\",\"message\":\"Every item in this flip has already been sold.\"}}"));

		try
		{
			api.closePosition("k", "f1", 1_000, null);
			fail("expected a failure");
		}
		catch (IOException e)
		{
			assertEquals("Every item in this flip has already been sold.", e.getMessage());
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
		server.enqueue(new MockResponse().setBody(
			"{\"matched\":1,\"added\":1,\"ignored\":1,\"problems\":[{\"row\":2,\"reason\":\"no side\"}]}"));
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
		assertEquals("structured problems are read, not fatal", 1, result.getProblems().size());
		assertTrue(result.getProblems().get(0).contains("no side"));
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
		server.enqueue(new MockResponse().setBody("{}"));
		server.enqueue(new MockResponse().setBody("{}"));
		api.closePosition("k", "f1", 1, null);
		api.deletePosition("k", "f1");

		for (int i = 0; i < 11; i++)
		{
			final String path = server.takeRequest().getPath();
			assertTrue(path, path.startsWith("/api/plugin/"));
		}
	}
}
