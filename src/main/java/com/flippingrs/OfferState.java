package com.flippingrs;

import javax.annotation.Nullable;

/** One open Grand Exchange slot, as the client reports it, for the server to reconcile against. */
public class OfferState
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
