package com.flippingrs;

/** One open position in the journal, marked to market by the server. */
public class Position
{
	/** The lot's id, for closing or deleting it. */
	String id;
	int itemId;
	String itemName;
	long buyPrice;
	long buyQty;
	long sellQty;
	long remainingQty;
	long costBasis;
	/** What you would get selling instantly now: the low. */
	long currentSell;
	/** What buyers are paying now: the high, and the price a patient sale lists at. */
	long currentBuy;
	long marketValue;
	long unrealisedPnl;
	/** A fraction: 0.02 is 2%. */
	double unrealisedRoi;
	long breakEvenSell;
	double hoursHeld;
	boolean stale;

	public String getId()
	{
		return id == null ? "" : id;
	}

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

	public long getCurrentBuy()
	{
		return currentBuy;
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
