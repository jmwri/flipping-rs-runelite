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

	/** The lines the box is given, without their colours. */
	private static String[] lines(String text)
	{
		return plain(text).split("<br>");
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
		assertNull(GeSlotText.textFor(offer(GrandExchangeOfferState.EMPTY, 0), whip(), 3));
		assertNull("nor does a slot with no offer at all", GeSlotText.textFor(null, whip(), 3));
	}

	/**
	 * No quote is not an excuse to guess. Every item is unpriced before the
	 * first fetch lands, and on a server that does not price single items
	 * every unwatched item stays that way.
	 */
	@Test
	public void anItemWithNoQuoteSaysNothing()
	{
		assertNull(GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_480_000), null, 3));
	}

	/**
	 * The difference is measured against the price for your own side. A buy at
	 * or over the site's buy price is an offer that will fill sooner, and is
	 * said in green.
	 *
	 * <p>A line each, because a price and its difference on one line stop being
	 * two things the moment either is long: a hundred million buying and a
	 * hundred and ten million selling is most of a line before anything is
	 * said about it.
	 */
	@Test
	public void aBuyIsMeasuredAgainstTheSitesBuyPrice()
	{
		final String text = GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_485_000), whip(), 3);

		assertNotNull(text);
		final String[] lines = lines(text);
		assertEquals("a line each, and the margin under them", 3, lines.length);
		assertEquals("your side carries how far you are from it",
			"Buy 1.48M  +5.0K", lines[0]);
		assertEquals("the side you are not on is just the price", "Sell 1.52M", lines[1]);
		assertEquals("+32.0K", lines[2]);
		assertTrue(text, text.contains(GOOD));
	}

	/** And bidding under it is the patient end: more margin, slower fill. */
	@Test
	public void aBuyUnderTheMarketIsCalledOut()
	{
		final String text = GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_470_000), whip(), 3);

		assertNotNull(text);
		assertEquals("Buy 1.48M  -10.0K", lines(text)[0]);
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
		final String good = GeSlotText.textFor(offer(GrandExchangeOfferState.SELLING, 1_510_000), whip(), 3);
		assertNotNull(good);
		assertEquals("buy is above sell whichever way you are trading",
			"Buy 1.48M", lines(good)[0]);
		assertEquals("and the sale's own side carries the difference",
			"Sell 1.52M  +10.0K", lines(good)[1]);
		assertTrue(good, good.contains(GOOD));

		final String optimistic =
			GeSlotText.textFor(offer(GrandExchangeOfferState.SELLING, 1_600_000), whip(), 3);
		assertNotNull(optimistic);
		assertEquals("Sell 1.52M  -80.0K", lines(optimistic)[1]);
		assertTrue(optimistic, optimistic.contains(BAD));
	}

	/**
	 * Prices in the hundreds of millions, which is what a single line could
	 * not hold. Each figure has a line, so the length of one stops mattering.
	 */
	@Test
	public void largePricesEachGetALine()
	{
		final Quote big = new Quote();
		big.id = 4151;
		big.instantSell = 100_000_000;
		big.instantBuy = 110_000_000;
		big.netMargin = 8_800_000;

		final String[] lines = lines(GeSlotText.textFor(
			offer(GrandExchangeOfferState.BUYING, 99_000_000), big, 3));

		assertEquals(3, lines.length);
		assertEquals("Buy 100.00M  -1.00M", lines[0]);
		assertEquals("Sell 110.00M", lines[1]);
		assertEquals("+8.80M", lines[2]);
	}

	/**
	 * A window with no room to spare still says the one thing that fits, on
	 * the line that is already there.
	 *
	 * <p>What gets dropped is decided here rather than by the box clipping it,
	 * and your own side is never what goes: it is the only price yours can be
	 * measured against.
	 */
	@Test
	public void lessRoomSaysLess()
	{
		final GrandExchangeOffer selling = offer(GrandExchangeOfferState.SELLING, 1_510_000);

		assertEquals("nothing but the difference, inline",
			"  +10.0K", plain(GeSlotText.textFor(selling, whip(), 0)));
		assertEquals("one row: your own side, with it",
			"Sell 1.52M  +10.0K", plain(GeSlotText.textFor(selling, whip(), 1)));

		final String[] two = lines(GeSlotText.textFor(selling, whip(), 2));
		assertEquals(2, two.length);
		assertEquals("buy above sell, even when you are selling", "Buy 1.48M", two[0]);
		assertEquals("Sell 1.52M  +10.0K", two[1]);
	}

	/**
	 * A finished offer is still one of the eight boxes on screen, and its
	 * price is still worth knowing: it is what you will re-list at.
	 */
	@Test
	public void aFinishedOfferIsStillPriced()
	{
		assertNotNull(GeSlotText.textFor(offer(GrandExchangeOfferState.BOUGHT, 1_480_000), whip(), 3));
		assertNotNull(GeSlotText.textFor(offer(GrandExchangeOfferState.CANCELLED_SELL, 1_520_000), whip(), 3));
	}

	/** A quote with no price in it is no quote at all. */
	@Test
	public void aQuoteWithoutAPriceSaysNothing()
	{
		final Quote empty = new Quote();
		empty.id = 4151;
		assertNull(GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_000), empty, 3));
	}

	/**
	 * The colours are colours, not RuneLite's own tokens. Those mean nothing
	 * at all to a widget and went to the screen as their own text.
	 */
	@Test
	public void theColoursAreColoursRatherThanTokens()
	{
		final String text = GeSlotText.textFor(offer(GrandExchangeOfferState.BUYING, 1_485_000), whip(), 3);

		assertNotNull(text);
		assertTrue(text, text.contains("<col=") && text.contains("</col>"));
		assertTrue(text, !text.contains("<colNORMAL>") && !text.contains("<colHIGHLIGHT>"));
	}
}
