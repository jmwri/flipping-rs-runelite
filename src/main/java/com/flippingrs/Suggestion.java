package com.flippingrs;

/**
 * One item from the server's suggestions, as it sent it. Every number is the
 * server's, post-tax, and any of them may be missing, which reads as zero.
 */
public class Suggestion
{
	int id;
	String name;
	/** The price to place a buy offer at. */
	long buy;
	/** The price to list at. */
	long sell;
	/** Profit per item after tax. */
	long margin;
	/** Margin over the buy price, as a fraction: 0.02 is 2%. */
	double roi;
	int buyLimit;
	long volume24h;
	int confidence;
	/** Profit if the affordable quantity, up to the buy limit, is bought and sold. */
	long profitPerLimit;

	public int getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public long getBuy()
	{
		return buy;
	}

	public long getSell()
	{
		return sell;
	}

	public long getMargin()
	{
		return margin;
	}

	public double getRoi()
	{
		return roi;
	}

	public int getBuyLimit()
	{
		return buyLimit;
	}

	public long getVolume24h()
	{
		return volume24h;
	}

	public int getConfidence()
	{
		return confidence;
	}

	public long getProfitPerLimit()
	{
		return profitPerLimit;
	}
}
