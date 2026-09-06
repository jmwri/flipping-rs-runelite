package com.flippingrs;

import javax.annotation.Nullable;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * How your own offer is priced against what the site says its side is worth.
 *
 * <p>All that is left of a class that used to write this into the offer boxes.
 * The boxes are the size the game made them and are best left that way, so the
 * words themselves moved to {@link GeTooltip} -- but working out which price
 * an offer should be measured against is a question of its own, and getting it
 * backwards would call every sensible offer badly priced by exactly the width
 * of the spread.
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
	 * profit is and also where an offer can sit all evening -- so it decides a
	 * colour rather than a verdict.
	 */
	@Nullable
	static Long edgeOf(@Nullable GrandExchangeOffer offer, @Nullable Quote quote)
	{
		final Long market = marketFor(offer, quote);
		if (market == null)
		{
			return null;
		}
		return buying(offer) ? offer.getPrice() - market : market - offer.getPrice();
	}

	/**
	 * The same comparison in words: "2 over sell", "5,000 under buy", "at
	 * buy". Null when there is nothing to compare.
	 *
	 * <p>A bare signed number was not enough, and it is worth saying why. On
	 * its own, "-2" is a difference from something unnamed, measured in a
	 * direction the reader has to guess, and whether it is good news depends
	 * on which side of the trade they are on -- three questions where there
	 * should be none. Naming the price it is measured against answers all
	 * three at once, and the line sits directly under that price in the box,
	 * so the arithmetic is there to be checked.
	 *
	 * <p>"Over" and "under" rather than a sign, because the sign convention
	 * has to invert between buying and selling and no convention survives
	 * that. Whether over is the good direction is the colour's job.
	 */
	@Nullable
	static String gapFrom(@Nullable GrandExchangeOffer offer, @Nullable Quote quote)
	{
		final Long market = marketFor(offer, quote);
		if (market == null)
		{
			return null;
		}
		final String side = buying(offer) ? "buy" : "sell";
		final long from = offer.getPrice() - market;
		if (from == 0)
		{
			return "at " + side;
		}
		return FlippingRsPanel.exact(Math.abs(from)) + (from > 0 ? " over " : " under ") + side;
	}

	/**
	 * The site's price for the side an offer is on, or null if there is no
	 * live offer or no price.
	 *
	 * <p>The two ends of the spread are not interchangeable: they are what a
	 * flip lives between, and measuring an offer against the wrong one would
	 * call every sensible offer badly priced by exactly the width of it.
	 */
	@Nullable
	private static Long marketFor(@Nullable GrandExchangeOffer offer, @Nullable Quote quote)
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
		final long market = buying(offer) ? quote.getBuyAt() : quote.getSellAt();
		return market <= 0 ? null : market;
	}

	/** Whether an offer is a buy. Anything that is not is a sale. */
	private static boolean buying(GrandExchangeOffer offer)
	{
		final GrandExchangeOfferState state = offer.getState();
		return state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.BOUGHT;
	}
}
