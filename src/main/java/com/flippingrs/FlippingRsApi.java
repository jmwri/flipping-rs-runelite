package com.flippingrs;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
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
 * <p>Everything the plugin does goes through {@code /api/plugin}: one read
 * per tab of the side panel, writes for fills, open offers and the history
 * screen, two for watchlists, and the two position actions the site's own
 * Positions page has. Nothing else. The general API is what the Elite plan sells,
 * and a plugin key is available on every plan, so the plugin's surface is
 * kept to things that are only ever a picture of the sidebar: nothing that
 * pages, filters or exports. A user who scripts against a plugin key gets
 * their own sidebar back and no more.
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

	/** Generous next to the few kilobytes a real reply is. */
	private static final long MAX_BODY_BYTES = 1 << 20;

	/**
	 * Thrown when the server refused <em>this batch</em> in a way that retrying
	 * will not fix: it is malformed, too large, or fails validation. The queue
	 * sets the batch aside rather than retrying it forever.
	 *
	 * <p>Deliberately narrow. A bad key, a lapsed plan or a missing account are
	 * not properties of the batch, and a user who fixes them wants the trades
	 * that piled up meanwhile to go out, not to have been deleted thirty
	 * seconds at a time while a side panel said "last attempt failed".
	 */
	public static class PermanentException extends IOException
	{
		PermanentException(String message)
		{
			super(message);
		}
	}

	/**
	 * Thrown when the server has no such route.
	 *
	 * <p>Separate from a plain IOException because it is the one failure a
	 * caller can do something sensible about: an older flippingrs.com that
	 * predates a call this plugin makes will answer every one of them the same
	 * way forever, so the caller can stop asking rather than spend a request
	 * every thirty seconds finding out again. Everything the plugin needs to
	 * do its job goes through routes that have always been there; this is only
	 * reached by the extras.
	 */
	public static class NotHereException extends IOException
	{
		NotHereException(String message)
		{
			super(message);
		}
	}

	/**
	 * The one instance this plugin talks to, over TLS, and not configurable in
	 * a normal install. The exception is a client started in developer mode,
	 * which is how the plugin is run against a local server; the plugin
	 * decides that, and this class only takes whatever base it is given.
	 *
	 * <p>It was a setting once. A text box that decides where an API key and
	 * every recorded trade get posted is worth more to whoever talks a user into
	 * changing it than it ever was to the handful of people self-hosting, and it
	 * makes the plugin's network destination something you cannot read off the
	 * source.
	 */
	static final HttpUrl BASE_URL = HttpUrl.get("https://flippingrs.com");

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl base;

	/** The item's page on the site, for the browser. */
	String itemUrl(int itemId)
	{
		return base.newBuilder()
			.addPathSegment("item")
			.addPathSegment(Integer.toString(itemId))
			.build().toString();
	}

	/** The site's flip finder, for the browser. */
	String finderUrl()
	{
		return base.newBuilder().addPathSegment("finder").build().toString();
	}

	public FlippingRsApi(OkHttpClient http, Gson gson)
	{
		this(http, gson, BASE_URL);
	}

	/** Points the client at another server: a test's, or a developer's. */
	FlippingRsApi(OkHttpClient http, Gson gson, HttpUrl base)
	{
		this.http = http;
		this.gson = gson;
		this.base = base;
	}

	// ------------------------------------------------------------ the shapes
	//
	// One class each, beside this one: Watchlist, Quote, Position, Positions,
	// Analytics, Me, GameAccount, PanelData, OfferState, HistoryRow,
	// Reconciliation and IngestResult, with Wire holding what they share.
	// They mirror the server's replies rather than the sidebar's needs, so a
	// field with no getter and no reader is not dead code -- it is the wire
	// contract written down, and Gson would drop it silently if it went.
	// Anything the panel actually draws has a getter. Keeping the unread ones
	// has already paid for itself: the server was found not to be sending a
	// quote's spread, tax or volume at all, which was only noticeable because
	// the fields were here to be compared against.


	// ------------------------------------------------------------- the calls

	/**
	 * The Account tab: who the key belongs to, what plan they are on, and the
	 * journals they can file under.
	 *
	 * <p>Doubles as the connection test: it is the cheapest authenticated
	 * read, so a successful one means the key is good and the server is
	 * reachable.
	 */
	public PanelData account(String apiKey) throws IOException
	{
		return read(apiKey, url("api", "plugin", "account").newBuilder());
	}

	/** The Journal tab: the journal's newest fills for an account. Null parts without one. */
	public PanelData trades(String apiKey, @Nullable String accountId) throws IOException
	{
		final HttpUrl.Builder url = url("api", "plugin", "trades").newBuilder();
		if (accountId != null && !accountId.isEmpty())
		{
			url.addQueryParameter("accountId", accountId);
		}
		return read(apiKey, url);
	}

	/**
	 * The Journal tab: the week's verdict and the open positions for an
	 * account, with the machine's UTC offset in minutes so the week's days
	 * fall on the player's calendar.
	 */
	public PanelData journal(String apiKey, @Nullable String accountId, int tzOffset) throws IOException
	{
		final HttpUrl.Builder url = url("api", "plugin", "journal").newBuilder()
			.addQueryParameter("tzOffset", Integer.toString(tzOffset));
		if (accountId != null && !accountId.isEmpty())
		{
			url.addQueryParameter("accountId", accountId);
		}
		return read(apiKey, url);
	}

	/**
	 * The Watchlists tab: every watchlist the owner has, and the site's
	 * quotes for the items of one of them: the one named, or the first.
	 *
	 * @param accountId the journal to count buy limits against, on the same
	 *                  terms as {@link #quotes}: without one the server has
	 *                  nothing to count and leaves those fields off, and the
	 *                  reply is otherwise identical
	 */
	public PanelData watchlists(String apiKey, @Nullable String watchlistId, @Nullable String accountId)
		throws IOException
	{
		final HttpUrl.Builder url = url("api", "plugin", "watchlists").newBuilder();
		if (watchlistId != null && !watchlistId.isEmpty())
		{
			url.addQueryParameter("watchlistId", watchlistId);
		}
		if (accountId != null && !accountId.isEmpty())
		{
			url.addQueryParameter("accountId", accountId);
		}
		return read(apiKey, url);
	}

	/**
	 * Quotes for particular items, whether or not they are on a watchlist.
	 *
	 * <p>What the watchlist read gives for a curated list, this gives for
	 * whatever the exchange happens to be showing. The moment a price is
	 * actually wanted is the moment one is being typed, and that is for any
	 * item, not only the ones somebody thought to add to a list beforehand.
	 *
	 * <p>Capped by the caller, not here: it is the caller that knows how many
	 * items are on screen.
	 *
	 * @param accountId the journal to count buy limits against, or null for
	 *                  none -- a limit is how much of it <em>this journal</em>
	 *                  has spent, so without one the server has nothing to
	 *                  count and leaves those fields off
	 * @throws NotHereException from a server that does not have this route,
	 *                          which the caller takes as "stop asking"
	 */
	public PanelData quotes(String apiKey, @Nullable String accountId, Collection<Integer> itemIds)
		throws IOException
	{
		final HttpUrl.Builder url = url("api", "plugin", "quote").newBuilder();
		if (accountId != null && !accountId.isEmpty())
		{
			url.addQueryParameter("accountId", accountId);
		}
		for (Integer itemId : itemIds)
		{
			if (itemId != null)
			{
				url.addQueryParameter("itemId", Integer.toString(itemId));
			}
		}
		return read(apiKey, url);
	}

	/**
	 * One tab's read. Every tab endpoint answers with the same {@link PanelData}
	 * shape, filling only its own parts, so the plugin draws all of them with
	 * one routine and a part that is absent is left alone.
	 */
	private PanelData read(String apiKey, HttpUrl.Builder url) throws IOException
	{
		final Request request = new Request.Builder()
			.url(url.build())
			.header("X-Api-Key", apiKey)
			.get()
			.build();

		try (Response response = http.newCall(request).execute())
		{
			final String body = bodyOf(response);
			check(response, body);
			final PanelData panel = gson.fromJson(body, PanelData.class);
			return panel == null ? new PanelData() : panel;
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
		payload.add("transactions", jsonList(batch));

		final Request request = new Request.Builder()
			.url(url("api", "plugin", "transactions"))
			.header("X-Api-Key", apiKey)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		try (Response response = http.newCall(request).execute())
		{
			final String body = bodyOf(response);
			check(response, body);
			final IngestResult result = gson.fromJson(body, IngestResult.class);
			// A 2xx whose body accounts for none of the rows did not come from
			// this API -- a proxy or captive portal answering with an empty
			// object, say. Confirming on it would delete the batch on the word
			// of something that never saw it. Retryable, like any other reply
			// that is not the server's.
			if (!batch.isEmpty() && (result == null || result.acknowledged() == 0))
			{
				throw new IOException("flippingrs.com did not acknowledge the batch");
			}
			return result == null ? new IngestResult() : result;
		}
		catch (JsonParseException e)
		{
			throw new IOException("flippingrs.com returned something that is not JSON", e);
		}
	}

	/**
	 * Records a sale against an open position: the same thing the site's
	 * Positions page does with its Close button. The server folds it into
	 * the position's sale price and caps the quantity at what is left.
	 *
	 * @param sellQty how many were sold, or null for everything still held
	 */
	public void closePosition(String apiKey, String positionId, long sellPrice, @Nullable Long sellQty)
		throws IOException
	{
		final JsonObject payload = new JsonObject();
		payload.addProperty("sellPrice", sellPrice);
		if (sellQty != null)
		{
			payload.addProperty("sellQty", sellQty);
		}
		final Request request = new Request.Builder()
			.url(url("api", "plugin", "positions", positionId, "close"))
			.header("X-Api-Key", apiKey)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();
		try (Response response = http.newCall(request).execute())
		{
			check(response, bodyOf(response));
		}
	}

	/**
	 * Deletes a position that was never a flip -- supplies bought to use,
	 * say -- so the next sale of that item is not matched against it and
	 * journaled as a loss. The fills stay in the ledger; only the lot goes.
	 * This is the one destructive thing the plugin can do, and it is behind
	 * a confirmation in the sidebar.
	 */
	public void deletePosition(String apiKey, String positionId) throws IOException
	{
		final Request request = new Request.Builder()
			.url(url("api", "plugin", "positions", positionId))
			.header("X-Api-Key", apiKey)
			.delete()
			.build();
		try (Response response = http.newCall(request).execute())
		{
			check(response, bodyOf(response));
		}
	}

	/**
	 * Sends the state of every open offer, so the server can compare each
	 * against the fills it holds for that offer and take on any shortfall as
	 * a recovered, untimed fill. The plugin reports; the server decides.
	 */
	public Reconciliation submitOffers(String apiKey, String accountId, List<OfferState> open) throws IOException
	{
		final JsonObject payload = new JsonObject();
		payload.addProperty("accountId", accountId);
		payload.add("offers", jsonList(open));
		return reconcile(apiKey, url("api", "plugin", "offers"), payload);
	}

	/**
	 * Sends what the history screen shows, in its order, for the server to
	 * match against the completed offers it already has and take on the rest.
	 */
	public Reconciliation submitHistory(String apiKey, String accountId, List<HistoryRow> rows) throws IOException
	{
		final JsonObject payload = new JsonObject();
		payload.addProperty("accountId", accountId);
		payload.add("rows", jsonList(rows));
		return reconcile(apiKey, url("api", "plugin", "history"), payload);
	}

	private Reconciliation reconcile(String apiKey, HttpUrl url, JsonObject payload) throws IOException
	{
		final Request request = new Request.Builder()
			.url(url)
			.header("X-Api-Key", apiKey)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		try (Response response = http.newCall(request).execute())
		{
			final String body = bodyOf(response);
			check(response, body);
			final Reconciliation result = gson.fromJson(body, Reconciliation.class);
			return result == null ? new Reconciliation() : result;
		}
		catch (JsonParseException e)
		{
			throw new IOException("flippingrs.com returned something that is not JSON", e);
		}
	}

	/**
	 * Creates a watchlist. The server may refuse with a plan limit, which
	 * arrives as an IOException carrying its message.
	 */
	public Watchlist createWatchlist(String apiKey, String name, List<Integer> itemIds) throws IOException
	{
		final JsonObject payload = new JsonObject();
		payload.addProperty("name", name);
		payload.add("itemIds", jsonList(itemIds));

		final Request request = new Request.Builder()
			.url(url("api", "plugin", "watchlists"))
			.header("X-Api-Key", apiKey)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();
		return watchlistFrom(request);
	}

	/** Replaces a watchlist's items. The name is left alone. */
	public Watchlist updateWatchlist(String apiKey, String id, List<Integer> itemIds) throws IOException
	{
		final JsonObject payload = new JsonObject();
		payload.add("itemIds", jsonList(itemIds));

		final Request request = new Request.Builder()
			.url(url("api", "plugin", "watchlists", id))
			.header("X-Api-Key", apiKey)
			.patch(RequestBody.create(JSON, gson.toJson(payload)))
			.build();
		return watchlistFrom(request);
	}

	private Watchlist watchlistFrom(Request request) throws IOException
	{
		try (Response response = http.newCall(request).execute())
		{
			final String body = bodyOf(response);
			check(response, body);
			final Watchlist watchlist = gson.fromJson(body, Watchlist.class);
			if (watchlist == null || watchlist.id == null || watchlist.id.isEmpty())
			{
				throw new IOException("flippingrs.com did not return the watchlist");
			}
			return watchlist;
		}
		catch (JsonParseException e)
		{
			throw new IOException("flippingrs.com returned something that is not JSON", e);
		}
	}

	// ---------------------------------------------------------------- plumbing

	/**
	 * The API key as it can actually be sent, or empty when there is none.
	 *
	 * <p>Trims, and trims more than {@link String#trim} does. A key copied out
	 * of a web page can arrive with a non-breaking space on the end of it, and
	 * that is whitespace to a reader but not to trim, nor even to strip, since
	 * Character.isWhitespace says no. A header value may hold only printable
	 * ASCII, so the HTTP client refuses the request before it is built: every
	 * call throws, for a key the settings box plainly shows as correct, which
	 * is the least diagnosable shape a wrong key can take.
	 *
	 * <p>So the ends are trimmed of anything that is not a printable ASCII
	 * character other than a space, which covers what trim removed and the
	 * passengers it did not. Only the ends: a character in the middle is a key
	 * that is wrong rather than one that was pasted untidily, and quietly
	 * editing that would send a different key from the one the user set.
	 */
	static String trimmedKey(@Nullable String configured)
	{
		if (configured == null)
		{
			return "";
		}
		int from = 0;
		int to = configured.length();
		while (from < to && !keyable(configured.charAt(from)))
		{
			from++;
		}
		while (to > from && !keyable(configured.charAt(to - 1)))
		{
			to--;
		}
		return configured.substring(from, to);
	}

	/** Whether a character can begin or end a key: printable ASCII, and not a space. */
	private static boolean keyable(char c)
	{
		return c > 0x20 && c < 0x7f;
	}

	/**
	 * Something to show for an exception. Not every IOException carries a
	 * message, and passing null on to the panel made a failed send read as
	 * "Last sent: never", which is the opposite of what happened.
	 */
	static String describe(Throwable e)
	{
		final String message = e.getMessage();
		return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
	}

	/**
	 * A list as JSON. Copied into an ArrayList first, because the Gson RuneLite
	 * ships (2.8.5) reflects on the list's own class and asks a private no-arg
	 * constructor to open itself. A modern JDK refuses -- {@code java.base}
	 * does not open {@code java.util} -- so a {@code Collections.emptyList()}
	 * handed straight to it throws InaccessibleObjectException rather than
	 * serialising.
	 *
	 * <p>Lists with no no-arg constructor to find are not asked, and go
	 * through: {@code singletonList} and {@code Arrays.asList} among them.
	 * That is what makes it worth doing here rather than at the call sites --
	 * the failure waits for whichever call happens to be handed an empty list,
	 * and every other call looks like proof that it cannot happen.
	 */
	private JsonElement jsonList(List<?> list)
	{
		return gson.toJsonTree(new ArrayList<>(list));
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
	 * merely fail a sync. Every real response here is a few kilobytes at most;
	 * a truncated one fails to parse, which is reported as a retryable error
	 * and is the right outcome.
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
	 * <p>The split decides whether a trade is retried until it lands or set
	 * aside, so it is drawn around one question: is the problem with this batch,
	 * or with something else? A malformed, oversized or invalid batch (400, 413,
	 * 422) will be just as bad in five minutes, and holding it would wedge the
	 * queue behind it forever. Everything else -- a revoked key, a lapsed plan,
	 * a moved endpoint, a rate limit, an outage -- is about the key, the
	 * account or the server, and the batch is fine. Those are held: when the
	 * user fixes the key, the trades go out.
	 */
	private void check(Response response, String body) throws IOException
	{
		if (response.isSuccessful())
		{
			return;
		}

		final int code = response.code();
		final String message = messageIn(body, code);

		if (code == 400 || code == 413 || code == 422)
		{
			throw new PermanentException(message);
		}
		if (code == 404)
		{
			throw new NotHereException(message);
		}
		throw new IOException(message);
	}

	/**
	 * How much of the server's message is worth repeating: enough for a
	 * sentence or two, which is all a 205-pixel sidebar can show anyway.
	 *
	 * <p>The body is already capped at a megabyte, and this is what stops the
	 * rest of that megabyte arriving in a Swing label. The panel lays its
	 * messages out as wrapped HTML on the event thread, so a reply that is
	 * long rather than large would freeze the client's interface -- the same
	 * failure {@link #MAX_BODY_BYTES} exists to prevent, one step further in.
	 */
	private static final int MAX_MESSAGE_CHARS = 300;

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
					return shorten(error.get("message").getAsString());
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not parse the error body", e);
		}
		return "flippingrs.com returned HTTP " + code;
	}

	private static String shorten(String message)
	{
		return message.length() <= MAX_MESSAGE_CHARS
			? message
			: message.substring(0, MAX_MESSAGE_CHARS) + "...";
	}
}
