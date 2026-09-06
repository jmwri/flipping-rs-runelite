package com.flippingrs;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What gets added to an open offer.
 *
 * <p>The arithmetic is the part worth being sure about: getting the side
 * backwards would call every sensible offer badly priced by exactly the width
 * of the spread, and it would do it in green.
 */
public class GeSlotTextTest
{
	/** Green and red, as the class writes them. */
	private static final String GOOD = "4caf50";
	private static final String BAD = "d32f2f";

	/** The text without its colour tags; the tags are asserted separately. */
	private static String plain(String text)
	{
		return text.replaceAll("</?col[^>]*>", "");
	}

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
		quote.netMargin = 32_000;
		return quote;
	}

	private static GrandExchangeOffer offerOn(int itemId, GrandExchangeOfferState state, int price)
	{
		final GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(state);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getItemId()).thenReturn(itemId);
		return offer;
	}

	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int price)
	{
		final GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(state);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getItemId()).thenReturn(4151);
		return offer;
	}

	@Test
	public void anEmptySlotSaysNothing()
	{
		assertNull(GeSlotText.textFor(offer(GrandExchangeOfferState.EMPTY, 0), whip()));
		assertNull("nor does a slot with no offer at all", GeSlotText.textFor(null, whip()));
	}

	/**
	 * No quote is not an excuse to guess. Every item is unpriced before the
	 * first fetch lands, and on a server that does not price single items
	 * every unwatched item stays that way.
	 */
	@Test
	public void anItemWithNoQuoteSaysNothing()
	{
		assertNull(GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_480_000), null));
	}

	/**
	 * Both prices are shown, and the difference is measured against the one
	 * that applies to your side. A buy at or over the site's buy price is an
	 * offer that will fill sooner, and is said in green.
	 *
	 * <p>Showing only the side being traded left the slot unable to answer the
	 * question after the one it was answering: a buy that has filled is a sale
	 * about to be listed, and the price to list it at was the number missing.
	 *
	 * <p>Written short, because a box that already says what the item is and
	 * which way it is going does not need the words "buy" and "sell" repeated
	 * back at it. Which side is which is said in colour instead.
	 */
	@Test
	public void aBuyIsMeasuredAgainstTheSitesBuyPrice()
	{
		final String text = GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_485_000), whip());

		assertNotNull(text);
		assertEquals("both prices, and the difference against yours",
			"1.48M/1.52M +5.0K", plain(text));
		assertTrue(text, text.contains(GOOD));
	}

	/** And bidding under it is the patient end: more margin, slower fill. */
	@Test
	public void aBuyUnderTheMarketIsCalledOut()
	{
		final String text = GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_470_000), whip());

		assertNotNull(text);
		assertEquals("1.48M/1.52M -10.0K", plain(text));
		assertTrue(text, text.contains(BAD));
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
		final String good = GeSlotText.textFor(offer(GrandExchangeOfferState.SELLING, 1_510_000), whip());
		assertNotNull(good);
		assertEquals("1.48M/1.52M +10.0K", plain(good));
		assertTrue(good, good.contains(GOOD));

		final String optimistic =
			GeSlotText.textFor(offer(GrandExchangeOfferState.SELLING, 1_600_000), whip());
		assertNotNull(optimistic);
		assertEquals("1.48M/1.52M -80.0K", plain(optimistic));
		assertTrue(optimistic, optimistic.contains(BAD));
	}

	/**
	 * A cheap item whose two ends round to the same words shows one figure.
	 *
	 * <p>"434gp/434gp -2gp" reads as a contradiction: the same number twice
	 * beside a difference that says they are not the same. They are not -- the
	 * spread is a coin or two and the words cannot show it -- so the words
	 * stop trying.
	 */
	@Test
	public void oneFigureWhenBothEndsReadTheSame()
	{
		final Quote karambwan = new Quote();
		karambwan.id = 3144;
		karambwan.instantSell = 434;
		karambwan.instantBuy = 434;

		final String text = GeSlotText.textFor(offerOn(3144, GrandExchangeOfferState.SELLING, 436), karambwan);

		assertNotNull(text);
		assertEquals("434gp -2gp", plain(text));
	}

	/**
	 * A finished offer is still one of the eight boxes on screen, and its
	 * price is still worth knowing: it is what you will re-list at.
	 */
	@Test
	public void aFinishedOfferIsStillPriced()
	{
		assertNotNull(GeSlotText.textFor(offer(GrandExchangeOfferState.BOUGHT, 1_480_000), whip()));
		assertNotNull(GeSlotText.textFor(offer(GrandExchangeOfferState.CANCELLED_SELL, 1_520_000), whip()));
	}

	/** A quote with no price in it is no quote at all. */
	@Test
	public void aQuoteWithoutAPriceSaysNothing()
	{
		final Quote empty = new Quote();
		empty.id = 4151;
		assertNull(GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_000), empty));
	}

	/**
	 * The colours are colours, not RuneLite's own tokens. Those mean nothing
	 * at all to a widget and went to the screen as their own text.
	 */
	@Test
	public void theColoursAreColoursRatherThanTokens()
	{
		final String text = GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_485_000), whip());

		assertNotNull(text);
		assertTrue(text, text.contains("<col=") && text.contains("</col>"));
		assertTrue(text, !text.contains("<colNORMAL>") && !text.contains("<colHIGHLIGHT>"));
	}
}
