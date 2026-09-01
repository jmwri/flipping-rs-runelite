package com.flippingrs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The FlippingRS HTTP API, as much of it as the plugin needs.
 *
 * <p>Every call is synchronous and every call is made off the client thread by
 * the caller. Blocking the game for a network round trip would drop frames, and
 * a flipper who sees the client stutter every time a slot fills will uninstall
 * the plugin long before they read a journal.
 */
@Slf4j
public class FlippingRsApi
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** Generous next to the few hundred bytes a real reply is. */
	private static final long MAX_BODY_BYTES = 1 << 20;

	/**
	 * Thrown when the server refused in a way that retrying will not fix: a bad
	 * key, a batch it will never accept, a tier limit. The queue drops the batch
	 * rather than retrying it forever.
	 */
	public static class PermanentException extends IOException
	{
		PermanentException(String message)
		{
			super(message);
		}
	}

	/**
	 * The one instance this plugin talks to, over TLS, and not configurable.
	 *
	 * <p>It was a setting once. A text box that decides where an API key and
	 * every recorded trade get posted is worth more to whoever talks a user into
	 * changing it than it ever was to the handful of people self-hosting, and it
	 * makes the plugin's network destination something you cannot read off the
	 * source. Fixing it here also retires a whole category of failure: a
	 * mistyped URL that wedged the queue behind a batch that could never send,
	 * and a plaintext one that put the key on the wire in the clear.
	 */
	private static final HttpUrl BASE_URL = HttpUrl.get("https://flippingrs.com");

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl base;

	public FlippingRsApi(OkHttpClient http, Gson gson)
	{
		this(http, gson, BASE_URL);
	}

	/** Test seam: lets a test point the client at a local server. */
	FlippingRsApi(OkHttpClient http, Gson gson, HttpUrl base)
	{
		this.http = http;
		this.gson = gson;
		this.base = base;
	}

	/** One row of the game-account picker. */
	public static class GameAccount
	{
		String id;
		String label;
		boolean isDefault;

		@Override
		public String toString()
		{
			// This is what the combo box renders.
			return label == null || label.isEmpty() ? id : label;
		}
	}

	/** What the server did with a batch, so the panel can say something true. */
	public static class IngestResult
	{
		int accepted;
		int duplicate;
		int rejected;
		int flipsOpened;
		int flipsClosed;
		long unmatchedSellQty;
		List<String> problems;

		public int getFlipsOpened()
		{
			return flipsOpened;
		}

		public int getFlipsClosed()
		{
			return flipsClosed;
		}

		public long getUnmatchedSellQty()
		{
			return unmatchedSellQty;
		}

		/**
		 * Fills the server recorded nothing for. They are gone: the batch is
		 * confirmed and dropped from the queue either way, so this is the only
		 * chance anyone has to learn a trade did not make it.
		 */
		public int getRejected()
		{
			return rejected;
		}

		/** Why, one line per refused row. Never null. */
		public List<String> getProblems()
		{
			return problems == null ? Collections.emptyList() : problems;
		}
	}

	/**
	 * Lists the game accounts the key's owner has, for the picker.
	 *
	 * <p>Doubles as the connection test: it is the cheapest authenticated call
	 * on the API, so a successful one means the key is good, the instance is
	 * reachable, and the owner has somewhere to file trades.
	 */
	public List<GameAccount> accounts(String apiKey) throws IOException
	{
		final Request request = new Request.Builder()
			.url(url("api", "journal", "accounts"))
			.header("X-Api-Key", apiKey)
			.get()
			.build();

		try (Response response = http.newCall(request).execute())
		{
			final String body = bodyOf(response);
			check(response, body);
			final JsonObject root = gson.fromJson(body, JsonObject.class);
			if (root == null || !root.has("accounts"))
			{
				return Collections.emptyList();
			}
			final GameAccount[] accounts = gson.fromJson(root.get("accounts"), GameAccount[].class);
			if (accounts == null)
			{
				return Collections.emptyList();
			}
			final List<GameAccount> out = new ArrayList<>();
			Collections.addAll(out, accounts);
			return out;
		}
		catch (JsonParseException e)
		{
			throw new IOException("flippingrs.com returned something that is not JSON", e);
		}
	}

	/** Sends a batch of fills. Safe to repeat: the server drops ids it has seen. */
	public IngestResult submit(String apiKey, String accountId, List<GeTransaction> batch)
		throws IOException
	{
		final JsonObject payload = new JsonObject();
		payload.addProperty("accountId", accountId);
		payload.add("transactions", gson.toJsonTree(batch));

		final Request request = new Request.Builder()
			.url(url("api", "journal", "transactions"))
			.header("X-Api-Key", apiKey)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		try (Response response = http.newCall(request).execute())
		{
			final String body = bodyOf(response);
			check(response, body);
			final IngestResult result = gson.fromJson(body, IngestResult.class);
			return result == null ? new IngestResult() : result;
		}
		catch (JsonParseException e)
		{
			throw new IOException("flippingrs.com returned something that is not JSON", e);
		}
	}

	/** Appends path segments to the fixed base. */
	private HttpUrl url(String... segments)
	{
		final HttpUrl.Builder builder = base.newBuilder();
		for (String segment : segments)
		{
			builder.addPathSegment(segment);
		}
		return builder.build();
	}

	/**
	 * Reads the response, refusing to read an unbounded amount of it.
	 *
	 * <p>body.string() buffers the entire response into memory with no ceiling,
	 * and the client runs with -Xmx768m. A server having a bad day, or anything
	 * sitting between here and it, can answer with far more than this API ever
	 * would -- and a large enough reply would take the game down rather than
	 * merely fail a sync. Every real response here is a few hundred bytes; a
	 * truncated one fails to parse, which is reported as a retryable error and
	 * is the right outcome.
	 */
	private static String bodyOf(Response response) throws IOException
	{
		if (response.body() == null)
		{
			return "";
		}
		return response.peekBody(MAX_BODY_BYTES).string();
	}

	/**
	 * Turns a failure response into the right kind of exception.
	 *
	 * <p>The split matters more than it looks. A 5xx or a dropped connection is
	 * worth retrying and the queue holds the batch. A 401 or a 400 is not: a
	 * revoked key does not un-revoke itself, and a batch the server calls
	 * malformed will be just as malformed in five minutes. Retrying those
	 * forever would hammer the API and, worse, wedge the queue behind a batch
	 * that can never drain, silently losing every trade after it.
	 */
	private void check(Response response, String body) throws IOException
	{
		if (response.isSuccessful())
		{
			return;
		}

		final String message = messageIn(body, response.code());
		final int code = response.code();

		// 429 is a rate limit and 408 a timeout: both mean "later", not "never".
		if (code == 429 || code == 408 || code >= 500)
		{
			throw new IOException(message);
		}
		throw new PermanentException(message);
	}

	/** Digs the human-readable message out of the API's error envelope. */
	private String messageIn(String body, int code)
	{
		try
		{
			final JsonObject root = gson.fromJson(body, JsonObject.class);
			if (root != null && root.has("error"))
			{
				final JsonObject error = root.getAsJsonObject("error");
				if (error.has("message"))
				{
					return error.get("message").getAsString();
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not parse the error body", e);
		}
		return "flippingrs.com returned HTTP " + code;
	}
}
