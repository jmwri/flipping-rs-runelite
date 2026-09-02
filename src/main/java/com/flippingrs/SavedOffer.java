package com.flippingrs;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * The last thing we saw in a Grand Exchange slot.
 *
 * <p>This is persisted, and persisting it is the whole reason the plugin does
 * not double-count. The client re-fires an offer-changed event for every
 * occupied slot on login, carrying the quantity already sold. Without a
 * remembered baseline to subtract, every login would look like a fresh burst of
 * trading and re-report trades that were recorded days ago.
 */
public class SavedOffer
{
	int itemId;
	int price;
	int totalQuantity;
	int quantitySold;
	/** Cumulative gp moved. See {@link OfferTracker} on why this can be wrong. */
	int spent;
	GrandExchangeOfferState state;

	/**
	 * Identifies the one exchange offer this slot is running, for as long as it
	 * runs. Minted when the offer is first seen and repeated on every fill of
	 * it, so the server can tell that a thousand partial fills are one purchase.
	 */
	String offerRef;

	/**
	 * True when the offer was already part-filled the first time we saw it, so
	 * its history happened somewhere we were not watching. Reported in the panel
	 * so a user who installs the plugin mid-flip understands why the first sale
	 * out of that slot has no purchase behind it.
	 */
	boolean adopted;

	public SavedOffer()
	{
	}

	static SavedOffer of(GrandExchangeOffer offer, String offerRef, boolean adopted)
	{
		SavedOffer s = new SavedOffer();
		s.itemId = offer.getItemId();
		s.price = offer.getPrice();
		s.totalQuantity = offer.getTotalQuantity();
		s.quantitySold = offer.getQuantitySold();
		s.spent = offer.getSpent();
		s.state = offer.getState();
		s.offerRef = offerRef;
		s.adopted = adopted;
		return s;
	}

	/**
	 * Whether an incoming event is the same offer we are already tracking,
	 * rather than a new one that happens to be in the same slot.
	 *
	 * <p>The quantity check is not redundant with the other three. Placing the
	 * same offer again after collecting a finished one gives an identical item,
	 * price and size, and the only thing that says it is a different purchase is
	 * that the progress went backwards.
	 *
	 * <p>The side check covers the one case progress cannot: a buy placed and
	 * cancelled untouched, then a sell of the same item at the same price and
	 * size, with the collect in between never observed. Both sit at zero, and
	 * without this they would share one offer reference.
	 */
	boolean isSameOfferAs(GrandExchangeOffer offer)
	{
		return itemId == offer.getItemId()
			&& price == offer.getPrice()
			&& totalQuantity == offer.getTotalQuantity()
			&& offer.getQuantitySold() >= quantitySold
			&& (state == null || isBuy(state) == isBuy(offer.getState()));
	}

	static boolean isBuy(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
	}
}
