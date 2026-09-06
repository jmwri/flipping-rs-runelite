package com.flippingrs;

/** One row of the Grand Exchange history screen, as read off it. No id and no time. */
public class HistoryRow
{
	int position;
	int itemId;
	String itemName;
	String side;
	long quantity;
	long grossValue;
}
