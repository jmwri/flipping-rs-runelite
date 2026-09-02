package com.flippingrs;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * screen, and two for watchlists. Nothing else. The general API is what the Elite plan sells,
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

	/**
	 * One of the owner's watchlists. The side panel shows one of these as the
	 * player's plan, and keeps no copy: it is read from here, changed here and
	 * read back.
	 */
	public static class Watchlist
	{
		String id;
		String name;
		List<Integer> itemIds;

		public String getId()
		{
			return id;
		}

		public String getName()
		{
			return name == null ? "" : name;
		}

		/** Never null. */
		public List<Integer> getItemIds()
		{
			return itemIds == null ? Collections.emptyList() : itemIds;
		}

		@Override
		public String toString()
		{
			// This is what the combo box renders.
			return getName().isEmpty() ? id : getName();
		}
	}

	/**
	 * The site's post-tax market picture for one item, as the watchlist
	 * shows it. Every number here is the server's: the plugin never works out
	 * a margin or a tax itself, so what it shows is what the site shows.
	 */
	public static class Quote
	{
		int id;
		String name;
		/** What you pay to buy now: the API's "high", and what a flip sells into. */
		long instantBuy;
		/** What you get selling now: the API's "low", and what a flip buys at. */
		long instantSell;
		long spread;
		long tax;
		/** Profit per item after tax, buying at instantSell and selling at instantBuy. */
		long netMargin;
		/** Net margin over the buy price, as a fraction: 0.02 is 2%. */
		double roi;
		int buyLimit;
		long profitPerLimit;
		long volume24h;
		long dataAgeSeconds;

		public int getId()
		{
			return id;
		}

		public long getBuyAt()
		{
			return instantSell;
		}

		public long getSellAt()
		{
			return instantBuy;
		}

		public long getNetMargin()
		{
			return netMargin;
		}

		public double getRoi()
		{
			return roi;
		}

		public int getBuyLimit()
		{
			return buyLimit;
		}

		public long getProfitPerLimit()
		{
			return profitPerLimit;
		}

		public long getVolume24h()
		{
			return volume24h;
		}
	}

	/** One open position in the journal, marked to market by the server. */
	public static class Position
	{
		int itemId;
		String itemName;
		long buyPrice;
		long buyQty;
		long sellQty;
		long remainingQty;
		long costBasis;
		long currentSell;
		long marketValue;
		long unrealisedPnl;
		/** A fraction: 0.02 is 2%. */
		double unrealisedRoi;
		long breakEvenSell;
		double hoursHeld;
		boolean stale;

		public int getItemId()
		{
			return itemId;
		}

		public String getItemName()
		{
			return itemName == null ? "" : itemName;
		}

		public long getBuyPrice()
		{
			return buyPrice;
		}

		public long getRemainingQty()
		{
			return remainingQty;
		}

		public long getCostBasis()
		{
			return costBasis;
		}

		public long getCurrentSell()
		{
			return currentSell;
		}

		public long getUnrealisedPnl()
		{
			return unrealisedPnl;
		}

		public double getUnrealisedRoi()
		{
			return unrealisedRoi;
		}

		public long getBreakEvenSell()
		{
			return breakEvenSell;
		}

		public double getHoursHeld()
		{
			return hoursHeld;
		}

		public boolean isStale()
		{
			return stale;
		}
	}

	/** The open positions and their totals. */
	public static class Positions
	{
		List<Position> positions;
		Summary summary;

		public static class Summary
		{
			int openPositions;
			long costBasis;
			long marketValue;
			long unrealisedPnl;
			boolean marketDataAvailable = true;
		}

		/** Never null. */
		public List<Position> getPositions()
		{
			return withoutNulls(positions);
		}

		/** Never null. */
		public Summary getSummary()
		{
			return summary == null ? new Summary() : summary;
		}
	}

	/** The journal's performance over the last week, as the server works it out. */
	public static class Analytics
	{
		int totalFlips;
		int completedFlips;
		int openFlips;
		long realisedProfit;
		long totalTaxPaid;
		/** A fraction: 0.75 is 75%. */
		double winRate;
		/** A fraction. */
		double averageRoi;
		long gpPerHour;

		public int getCompletedFlips()
		{
			return completedFlips;
		}

		public int getOpenFlips()
		{
			return openFlips;
		}

		public long getRealisedProfit()
		{
			return realisedProfit;
		}

		public double getWinRate()
		{
			return winRate;
		}

		public double getAverageRoi()
		{
			return averageRoi;
		}

		public long getGpPerHour()
		{
			return gpPerHour;
		}
	}

	/** The key's owner, as much as the Account tab shows. */
	public static class Me
	{
		String displayName;
		String effectiveTier;
		boolean onTrial;
		int trialDaysLeft;

		/** "Pro plan", "Pro trial, 5 days left", "Free plan". */
		public String describePlan()
		{
			final String tier = effectiveTier == null || effectiveTier.isEmpty() ? "unknown" : effectiveTier;
			final String name = Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
			if (onTrial)
			{
				return name + " trial, " + trialDaysLeft + (trialDaysLeft == 1 ? " day left" : " days left");
			}
			return name + " plan";
		}
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

	/**
	 * What a tab's read returns. Every tab endpoint answers in this one shape,
	 * filling only its own parts; the rest come back null, and the plugin
	 * leaves what it was already showing alone. That is the difference
	 * between "nothing changed" and "nothing there", and it is why the
	 * getters here return null rather than an empty list.
	 */
	public static class Panel
	{
		@Nullable
		Me me;
		@Nullable
		List<GameAccount> accounts;
		@Nullable
		List<GeTransaction> recentTransactions;
		@Nullable
		Analytics week;
		@Nullable
		Positions positions;
		@Nullable
		List<Watchlist> watchlists;
		@Nullable
		List<Quote> quotes;

		@Nullable
		public Me getMe()
		{
			return me;
		}

		/** Null if the part was not in the reply; otherwise never contains null. */
		@Nullable
		public List<GameAccount> getAccounts()
		{
			return accounts == null ? null : withoutNulls(accounts);
		}

		@Nullable
		public List<GeTransaction> getRecentTransactions()
		{
			return recentTransactions == null ? null : withoutNulls(recentTransactions);
		}

		@Nullable
		public Analytics getWeek()
		{
			return week;
		}

		@Nullable
		public Positions getPositions()
		{
			return positions;
		}

		@Nullable
		public List<Watchlist> getWatchlists()
		{
			return watchlists == null ? null : withoutNulls(watchlists);
		}

		/** The quotes keyed by item id, or null if the part was not in the reply. */
		@Nullable
		public Map<Integer, Quote> getQuotes()
		{
			if (quotes == null)
			{
				return null;
			}
			final Map<Integer, Quote> out = new LinkedHashMap<>();
			for (Quote quote : withoutNulls(quotes))
			{
				out.put(quote.id, quote);
			}
			return out;
		}
	}

	/** One open Grand Exchange slot, as the client reports it, for the server to reconcile against. */
	public static class OfferState
	{
		int slot;
		/** The plugin's reference for this offer, so the server can find its fills. Null if none yet. */
		@Nullable
		String offerRef;
		int itemId;
		String itemName;
		String side;
		long price;
		long totalQuantity;
		long quantitySold;
		/** The client's running total, an int that can wrap; see spentEstimated. */
		long spent;
		boolean spentEstimated;
		String state;
	}

	/** One row of the Grand Exchange history screen, as read off it. No id and no time. */
	public static class HistoryRow
	{
		int position;
		int itemId;
		String itemName;
		String side;
		long quantity;
		long grossValue;
	}

	/** What the server made of a snapshot: how much it already had and how much it took on. */
	public static class Reconciliation
	{
		int reconciled;
		int recovered;
		int matched;
		int added;
		int ignored;

		public int getRecovered()
		{
			return recovered;
		}

		public int getAdded()
		{
			return added;
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
		/**
		 * Held as raw JSON rather than as strings. If the server ever sends
		 * structured problems, a List&lt;String&gt; here would fail to parse a
		 * response for a batch that was in fact accepted, and the batch would
		 * be re-sent every tick with the queue wedged behind it.
		 */
		List<JsonElement> problems;

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
			if (problems == null)
			{
				return Collections.emptyList();
			}
			final List<String> out = new ArrayList<>(problems.size());
			for (JsonElement problem : problems)
			{
				if (problem == null || problem.isJsonNull())
				{
					continue;
				}
				out.add(problem.isJsonPrimitive() ? problem.getAsString() : problem.toString());
			}
			return out;
		}

		/** Rows the server accounted for, one way or another. */
		int acknowledged()
		{
			return accepted + duplicate + rejected;
		}
	}

	private static <T> List<T> withoutNulls(@Nullable List<T> in)
	{
		if (in == null)
		{
			return Collections.emptyList();
		}
		final List<T> out = new ArrayList<>(in.size());
		for (T item : in)
		{
			if (item != null)
			{
				out.add(item);
			}
		}
		return out;
	}

	// ------------------------------------------------------------- the calls

	/**
	 * The Account tab: who the key belongs to, what plan they are on, and the
	 * journals they can file under.
	 *
	 * <p>Doubles as the connection test: it is the cheapest authenticated
	 * read, so a successful one means the key is good and the server is
	 * reachable.
	 */
	public Panel account(String apiKey) throws IOException
	{
		return read(apiKey, url("api", "plugin", "account").newBuilder());
	}

	/** The Trades tab: the journal's newest fills for an account. Null parts without one. */
	public Panel trades(String apiKey, @Nullable String accountId) throws IOException
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
	public Panel journal(String apiKey, @Nullable String accountId, int tzOffset) throws IOException
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
	 */
	public Panel watchlists(String apiKey, @Nullable String watchlistId) throws IOException
	{
		final HttpUrl.Builder url = url("api", "plugin", "watchlists").newBuilder();
		if (watchlistId != null && !watchlistId.isEmpty())
		{
			url.addQueryParameter("watchlistId", watchlistId);
		}
		return read(apiKey, url);
	}

	/**
	 * One tab's read. Every tab endpoint answers with the same {@link Panel}
	 * shape, filling only its own parts, so the plugin draws all of them with
	 * one routine and a part that is absent is left alone.
	 */
	private Panel read(String apiKey, HttpUrl.Builder url) throws IOException
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
			final Panel panel = gson.fromJson(body, Panel.class);
			return panel == null ? new Panel() : panel;
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
	 * A list as JSON. Copied into an ArrayList first: the Gson RuneLite ships
	 * (2.8.5) reflects on the list's own class, and on a modern JDK it cannot
	 * open {@code java.util.Collections$EmptyList} and friends, so a
	 * {@code Collections.emptyList()} or {@code singletonList()} handed
	 * straight to it throws instead of serialising.
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
		throw new IOException(message);
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
