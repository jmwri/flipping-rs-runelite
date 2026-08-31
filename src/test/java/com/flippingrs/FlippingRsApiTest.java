package com.flippingrs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The HTTP layer, against a real server.
 *
 * <p>Most of what this class decides is not "did the call work" but "is this
 * failure worth trying again", and that single decision governs whether a
 * trade is retried until it lands or dropped on the floor. Getting it backwards
 * either loses trades or wedges the queue behind a batch that can never
 * succeed, so it is pinned here rather than reasoned about.
 */
public class FlippingRsApiTest
{
	private MockWebServer server;
	private FlippingRsApi api;
	private String baseUrl;

	@Before
	public void setUp() throws IOException
	{
		server = new MockWebServer();
		server.start();
		baseUrl = server.url("/").toString();
		api = new FlippingRsApi(new OkHttpClient(), new Gson());
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

	// ------------------------------------------------------------- requests

	@Test
	public void accountsAuthenticatesAndHitsTheRightPath() throws Exception
	{
		server.enqueue(new MockResponse().setBody(
			"{\"accounts\":[{\"id\":\"a1\",\"label\":\"Main\",\"isDefault\":true}]}"));

		final List<FlippingRsApi.GameAccount> accounts = api.accounts(baseUrl, "frs_secret");

		final RecordedRequest request = server.takeRequest();
		assertEquals("GET", request.getMethod());
		assertEquals("/api/journal/accounts", request.getPath());
		assertEquals("frs_secret", request.getHeader("X-Api-Key"));

		assertEquals(1, accounts.size());
		assertEquals("a1", accounts.get(0).id);
		assertEquals("the combo box renders toString", "Main", accounts.get(0).toString());
	}

	/**
	 * The batch has to arrive as {accountId, transactions[]}, because the server
	 * files everything in it under that one account.
	 */
	@Test
	public void submitPostsTheAgreedShape() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{\"accepted\":1}"));

		api.submit(baseUrl, "frs_secret", "acct-9", oneFill());

		final RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertEquals("/api/journal/transactions", request.getPath());
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

		final FlippingRsApi.IngestResult result = api.submit(baseUrl, "k", "a", oneFill());

		assertEquals(1, result.getFlipsOpened());
		assertEquals(1, result.getFlipsClosed());
		assertEquals(7, result.getUnmatchedSellQty());
		// Rejected rows are dropped from the queue, so this is the only chance
		// anyone has to learn a trade did not make it.
		assertEquals(3, result.getRejected());
		assertEquals(Collections.singletonList("row 4: bad side"), result.getProblems());
	}

	// ------------------------------------- retry or drop: the decision that matters

	@Test
	public void serverErrorsAreWorthRetrying() throws Exception
	{
		for (int code : new int[]{500, 502, 503, 504, 429, 408})
		{
			server.enqueue(new MockResponse().setResponseCode(code));
			try
			{
				api.submit(baseUrl, "k", "a", oneFill());
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
	 * A revoked key does not un-revoke itself and a malformed batch will be just
	 * as malformed in five minutes. Retrying these forever would wedge the queue
	 * behind a batch that can never drain, losing every trade after it.
	 */
	@Test
	public void clientErrorsAreNotWorthRetrying() throws Exception
	{
		for (int code : new int[]{400, 401, 402, 403, 404})
		{
			server.enqueue(new MockResponse().setResponseCode(code));
			try
			{
				api.submit(baseUrl, "k", "a", oneFill());
				fail("HTTP " + code + " should have thrown");
			}
			catch (FlippingRsApi.PermanentException expected)
			{
				// Right: drop the batch and let the rest through.
			}
		}
	}

	@Test
	public void theServersOwnMessageIsSurfaced() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(401).setBody(
			"{\"error\":{\"code\":\"unauthorized\",\"message\":\"Invalid or revoked API key.\"}}"));

		try
		{
			api.accounts(baseUrl, "frs_stale");
			fail("expected a failure");
		}
		catch (IOException e)
		{
			assertEquals("Invalid or revoked API key.", e.getMessage());
		}
	}

	@Test
	public void aFailureWithNoUsableBodyStillSaysSomething() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(503).setBody("<html>gateway</html>"));

		try
		{
			api.submit(baseUrl, "k", "a", oneFill());
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
			api.submit(baseUrl, "k", "a", oneFill());
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
	 * body.string() has no ceiling, the base URL is a user setting, and the
	 * client runs with -Xmx768m. A reply large enough to exhaust the heap must
	 * fail the sync, not the game.
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
			api.submit(baseUrl, "k", "a", oneFill());
			fail("expected a parse failure");
		}
		catch (IOException expected)
		{
			// Read up to the cap, failed to parse, reported. No OOM.
		}
	}

	@Test
	public void anAccountsPayloadWithNoAccountsIsEmptyNotNull() throws Exception
	{
		server.enqueue(new MockResponse().setBody("{}"));
		assertTrue(api.accounts(baseUrl, "k").isEmpty());

		server.enqueue(new MockResponse().setBody("{\"accounts\":null}"));
		assertTrue("a null array must not become an NPE in the panel",
			api.accounts(baseUrl, "k").isEmpty());
	}

	@Test
	public void aResultWithNoBodyIsStillAResult() throws Exception
	{
		server.enqueue(new MockResponse().setBody("null"));

		final FlippingRsApi.IngestResult result = api.submit(baseUrl, "k", "a", oneFill());

		assertNotNull("callers read this without a null check", result);
		assertEquals(0, result.getRejected());
		assertTrue(result.getProblems().isEmpty());
	}

	/**
	 * A base URL the user has mistyped can never work, so it must not be
	 * retried forever.
	 */
	@Test
	public void anUnusableBaseUrlIsPermanent()
	{
		try
		{
			api.submit("not a url", "k", "a", oneFill());
			fail("expected a failure");
		}
		catch (FlippingRsApi.PermanentException expected)
		{
			assertTrue(expected.getMessage().contains("not a url"));
		}
		catch (IOException e)
		{
			fail("a malformed base URL will never start working: " + e);
		}
	}

	@Test
	public void anEmptyLabelFallsBackToTheIdSoTheListIsNeverBlank() throws Exception
	{
		server.enqueue(new MockResponse().setBody(
			"{\"accounts\":[{\"id\":\"a1\",\"label\":\"\"},{\"id\":\"a2\"}]}"));

		final List<FlippingRsApi.GameAccount> accounts = api.accounts(baseUrl, "k");

		assertEquals("a1", accounts.get(0).toString());
		assertEquals("a2", accounts.get(1).toString());
		assertFalse(accounts.get(0).isDefault);
	}
}
