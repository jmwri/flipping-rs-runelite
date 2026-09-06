package com.flippingrs;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the plugin writes on an open offer.
 *
 * <p>The caption is a pure function of the offer and the quote, which is the
 * whole reason it is one: the geometry needs a client and a frame, and the
 * thing worth being sure about is the arithmetic. Getting the side backwards
 * would call every sensible offer badly priced by exactly the spread, and it
 * would do it in green.
 */
public class GeItemInfoOverlayTest
{
	private static Quote whip()
	{
		final Quote quote = new Quote();
		quote.id = 4151;
		// The site's two ends of the spread: buy at the low, sell at the high.
		// getBuyAt() reads instantSell and getSellAt() reads instantBuy, which
		// is the right way round for a flipper and the wrong way round for
		// anyone going by the field names, so the test pins both.
		quote.instantSell = 1_480_000;
		quote.instantBuy = 1_520_000;
		quote.netMargin = 40_000;
		return quote;
	}

	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int price)
	{
		final GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(state);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getItemId()).thenReturn(4151);
		return offer;
	}

	/**
	 * A box with no offer of yours on it is not a box with nothing to say: it
	 * is every item on every other screen the exchange has, and the prices are
	 * exactly what is wanted there. An empty slot never reaches this at all --
	 * {@link GeItems} does not report one, because there is no item in it.
	 */
	@Test
	public void aBoxWithNoOfferOfYoursGetsThePricesInstead()
	{
		final GeItemInfoOverlay.Caption caption = GeItemInfoOverlay.captionFor(null, whip());

		assertNotNull(caption);
		assertEquals("1.48M/1.52M", caption.full);
		assertEquals("the margin alone, where there is no room for both", "+40.0K", caption.brief);
	}

	/**
	 * No quote is not an excuse to guess. Every item is unpriced before the
	 * first fetch lands, and on a server that does not price single items
	 * every unwatched item stays that way.
	 */
	@Test
	public void anItemWithNoQuoteSaysNothing()
	{
		assertNull(GeItemInfoOverlay.captionFor(offer(GrandExchangeOfferState.BUYING, 1_480_000), null));
	}

	/**
	 * A buy is measured against the site's buy price. Bidding at or over it is
	 * an offer that will fill sooner, and is said in green.
	 */
	@Test
	public void aBuyIsMeasuredAgainstTheSitesBuyPrice()
	{
		final GeItemInfoOverlay.Caption line =
			GeItemInfoOverlay.captionFor(offer(GrandExchangeOfferState.BUYING, 1_485_000), whip());

		assertNotNull(line);
		assertEquals("Buy 1,480,000  +5,000", line.full);
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, line.colour);
	}

	/** And bidding under it is the patient end: more margin, slower fill. */
	@Test
	public void aBuyUnderTheMarketIsCalledOut()
	{
		final GeItemInfoOverlay.Caption line =
			GeItemInfoOverlay.captionFor(offer(GrandExchangeOfferState.BUYING, 1_470_000), whip());

		assertNotNull(line);
		assertEquals("Buy 1,480,000  -10,000", line.full);
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, line.colour);
	}

	/**
	 * A sale is measured against the site's sell price, and the sign flips
	 * with it: asking less than that is the offer that fills sooner. Measuring
	 * a sale against the buy price instead would call every ordinary flip
	 * badly priced by the width of the spread.
	 */
	@Test
	public void aSaleIsMeasuredAgainstTheSitesSellPrice()
	{
		final GeItemInfoOverlay.Caption good =
			GeItemInfoOverlay.captionFor(offer(GrandExchangeOfferState.SELLING, 1_510_000), whip());
		assertNotNull(good);
		assertEquals("Sell 1,520,000  +10,000", good.full);
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, good.colour);

		final GeItemInfoOverlay.Caption optimistic =
			GeItemInfoOverlay.captionFor(offer(GrandExchangeOfferState.SELLING, 1_600_000), whip());
		assertNotNull(optimistic);
		assertEquals("Sell 1,520,000  -80,000", optimistic.full);
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, optimistic.colour);
	}

	/**
	 * A finished offer is still one of the eight boxes on screen, and its
	 * price is still worth knowing: it is what you will re-list at.
	 */
	@Test
	public void aFinishedOfferIsStillPriced()
	{
		assertNotNull(GeItemInfoOverlay.captionFor(offer(GrandExchangeOfferState.BOUGHT, 1_480_000), whip()));
		assertNotNull(GeItemInfoOverlay.captionFor(offer(GrandExchangeOfferState.CANCELLED_SELL, 1_520_000), whip()));
	}

	/** A quote with no price in it is no quote at all. */
	@Test
	public void aQuoteWithoutAPriceSaysNothing()
	{
		final Quote empty = new Quote();
		empty.id = 4151;
		assertNull(GeItemInfoOverlay.captionFor(offer(GrandExchangeOfferState.BUYING, 1_000), empty));
	}
}
