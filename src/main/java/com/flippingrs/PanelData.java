package com.flippingrs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * What a tab's read returns. Every tab endpoint answers in this one shape,
 * filling only its own parts; the rest come back null, and the plugin
 * leaves what it was already showing alone. That is the difference
 * between "nothing changed" and "nothing there", and it is why the
 * getters here return null rather than an empty list.
 */
public class PanelData
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
		return accounts == null ? null : Wire.withoutNulls(accounts);
	}

	@Nullable
	public List<GeTransaction> getRecentTransactions()
	{
		return recentTransactions == null ? null : Wire.withoutNulls(recentTransactions);
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
		return watchlists == null ? null : Wire.withoutNulls(watchlists);
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
		for (Quote quote : Wire.withoutNulls(quotes))
		{
			out.put(quote.id, quote);
		}
		return out;
	}
}
