package com.flippingrs;

import javax.annotation.Nullable;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * How far your own offer is from what the site says its side is worth.
 *
 * <p>All that is left of a class that used to write this into the offer boxes.
 * The boxes are the size the game made them and are best left that way, so the
 * number itself moved to {@link GeTooltip} -- but working it out is a question
 * of its own, and getting it backwards would call every sensible offer badly
 * priced by exactly the width of the spread.
 */
final class GeSlotText
{
	private GeSlotText()
	{
	}

	/**
	 * How far an offer is from the site's price for the side it is on, or null
	 * if there is no offer, no price, or nothing to compare.
	 *
	 * <p>Positive means the offer is on the side of the price that fills
	 * sooner: a buy at or above the site's buy price, a sale at or below its
	 * sell price. Negative is the patient end of the flip, which is where the
	 * profit is and also where an offer can sit all evening -- so it is stated
	 * rather than judged.
	 */
	@Nullable
	static Long edgeOf(@Nullable GrandExchangeOffer offer, @Nullable Quote quote)
	{
		if (offer == null || quote == null)
		{
			return null;
		}
		final GrandExchangeOfferState state = offer.getState();
		if (state == null || state == GrandExchangeOfferState.EMPTY)
		{
			return null;
		}
		final boolean buying = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.BOUGHT;
		// The price the site says to trade at on this side. The two are not
		// interchangeable: they are the ends of the spread a flip lives in,
		// and measuring an offer against the wrong one would call every
		// sensible offer badly priced by exactly the width of it.
		final long market = buying ? quote.getBuyAt() : quote.getSellAt();
		if (market <= 0)
		{
			return null;
		}
		return buying ? offer.getPrice() - market : market - offer.getPrice();
	}
}
