package com.flippingrs;

/**
 * The site's post-tax market picture for one item, as the watchlist
 * shows it. Every number here is the server's: the plugin never works out
 * a margin or a tax itself, so what it shows is what the site shows.
 */
public class Quote
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
	/**
	 * How much of the four-hour buy limit this journal has left on the item,
	 * and how long until it resets. Both are the server's: it holds the trades
	 * the limit is counted from, and it counts them per journal, which is why
	 * mixing two characters into one journal gives wrong limit timers.
	 *
	 * <p>Boxed, because a server that does not send them yet has to be told
	 * apart from one saying the limit is used up. Absent, nothing that draws
	 * them draws anything.
	 */
	Integer limitRemaining;
	long limitResetsInSeconds;

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

	/**
	 * How old the prices are, in seconds. Worth showing wherever an exact
	 * figure is, because exactness implies a freshness it does not have: a
	 * price to the gp that is forty minutes old, drawn beside the box where a
	 * number gets typed, is worse than no price at all.
	 */
	public long getDataAgeSeconds()
	{
		return dataAgeSeconds;
	}

	/** Whether the server said anything about the buy limit left. */
	public boolean hasLimitLeft()
	{
		return limitRemaining != null;
	}

	/** How many more can be bought before the limit bites. Meaningless unless {@link #hasLimitLeft}. */
	public int getLimitRemaining()
	{
		return limitRemaining == null ? 0 : limitRemaining;
	}

	/** How long until the limit resets, in seconds, or 0 if not known. */
	public long getLimitResetsInSeconds()
	{
		return limitResetsInSeconds;
	}
}
