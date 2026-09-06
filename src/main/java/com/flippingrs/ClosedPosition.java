package com.flippingrs;

/**
 * One lot that has been bought and sold: what it made, after tax.
 *
 * <p>The counterpart to {@link Position}, and deliberately a separate shape.
 * An open lot is a question -- what is it worth now, what would break even,
 * has it been held too long -- and every figure on it moves. A closed one is
 * an answer, and none of its figures will ever change again. Sharing a class
 * would mean half its fields being meaningless in either direction.
 */
public class ClosedPosition
{
	/** The lot's id, which the plugin only uses to tell one card from another. */
	String id;
	int itemId;
	String itemName;
	long buyPrice;
	long buyQty;
	long sellPrice;
	long sellQty;
	/** The exchange's cut, which is the difference between gross and net. */
	long taxPaid;
	/** After tax. The one figure worth reading first. */
	long netProfit;
	/** A fraction: 0.02 is 2%. */
	double roi;
	/** Bought to sold, worked out by the server, which has both timestamps. */
	double hoursHeld;
	/**
	 * Whether both ends of the flip were actually watched.
	 *
	 * <p>A leg the plugin recovered after the fact carries the time it was
	 * recovered rather than the time it happened, so the hold is a placeholder
	 * and is left off rather than shown as a duration nobody measured. The
	 * profit is still real; only its timing is not.
	 */
	boolean timesKnown;

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

	public long getBuyQty()
	{
		return buyQty;
	}

	public long getSellPrice()
	{
		return sellPrice;
	}

	public long getSellQty()
	{
		return sellQty;
	}

	public long getTaxPaid()
	{
		return taxPaid;
	}

	public long getNetProfit()
	{
		return netProfit;
	}

	public double getRoi()
	{
		return roi;
	}

	public double getHoursHeld()
	{
		return hoursHeld;
	}

	public boolean isTimesKnown()
	{
		return timesKnown;
	}
}
