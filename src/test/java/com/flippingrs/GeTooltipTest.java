package com.flippingrs;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the exchange says when you point at something in it.
 *
 * <p>The tooltip is where these numbers ended up after the offer boxes turned
 * out not to be resizable, and it is a better place for them than the boxes
 * were: it has as much room as it needs, and it answers a question rather than
 * putting three lines about seven items nobody asked about on the screen.
 *
 * <p>The arithmetic is still the part worth being sure about. Measuring an
 * offer against the wrong end of the spread would call every sensible offer
 * badly priced by exactly the width of it, and would do it in green.
 */
public class GeTooltipTest
{
	private static final String GOOD = "4caf50";
	private static final String BAD = "d32f2f";

	/** The lines without their colours; the colours are asserted separately. */
	private static String[] lines(String text)
	{
		return text.replaceAll("<col=[^>]*>", "").split("</br>");
	}

	private static Quote whip()
	{
		final Quote quote = new Quote();
		quote.id = 4151;
		quote.name = "Abyssal whip";
		// The site's two ends of the spread: buy at the low, sell at the high.
		quote.instantSell = 1_480_000;
		quote.instantBuy = 1_520_000;
		quote.netMargin = 32_000;
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
	 * Exact to the coin. A tooltip has the room, and these are prices somebody
	 * is deciding what to type from.
	 */
	@Test
	public void anItemWithNoOfferOnItGetsThePrices()
	{
		final String[] lines = lines(GeTooltip.textFor(whip(), null));

		assertEquals(5, lines.length);
		assertEquals("the item, since the pointer is the only thing saying which box",
			"Abyssal whip", lines[0]);
		assertEquals("Buy 1,480,000", lines[1]);
		assertEquals("Sell 1,520,000", lines[2]);
		assertEquals("Margin +32,000", lines[3]);
		assertEquals("and how old they are, which a fresh quote still says",
			"Priced just now", lines[4]);
	}

	/**
	 * An item you have an offer on gains how far your price is from the side
	 * you are trading. Only the eight slots can say this; everywhere else the
	 * same item is just an item.
	 */
	@Test
	public void yourOwnOfferIsMeasuredAgainstYourOwnSide()
	{
		final String buying = GeTooltip.textFor(whip(),
			offer(GrandExchangeOfferState.BUYING, 1_485_000));
		assertEquals("a buy over the site's buy price fills sooner",
			"Yours +5,000", lines(buying)[4]);
		assertTrue(buying, buying.contains(GOOD));

		final String selling = GeTooltip.textFor(whip(),
			offer(GrandExchangeOfferState.SELLING, 1_600_000));
		assertEquals("and a sale over its sell price will sit",
			"Yours -80,000", lines(selling)[4]);
		assertTrue(selling, selling.contains(BAD));
	}

	/** The buy limit and the price age, when the server sends them. */
	@Test
	public void whatQualifiesThePricesComesLast()
	{
		final Quote quote = whip();
		quote.limitRemaining = 3412;
		quote.dataAgeSeconds = 250;

		final String[] lines = lines(GeTooltip.textFor(quote, null));

		assertEquals(6, lines.length);
		assertEquals("Limit 3.4K", lines[4]);
		assertEquals("Priced 4m ago", lines[5]);
	}

	/** And a server that sends neither leaves both off rather than guessing. */
	@Test
	public void aServerThatSaysNeitherLeavesThemOff()
	{
		final Quote quote = whip();
		quote.dataAgeSeconds = -1;

		final String text = GeTooltip.textFor(quote, null);

		assertFalse(text, text.contains("Limit"));
		assertFalse(text, text.contains("Priced"));
	}

	/** An empty slot is not an offer, so there is nothing of yours to compare. */
	@Test
	public void anEmptySlotHasNoOfferToMeasure()
	{
		assertNull(GeSlotText.edgeOf(offer(GrandExchangeOfferState.EMPTY, 0), whip()));
		assertNull(GeSlotText.edgeOf(null, whip()));
		assertNull("and an item with no price has nothing to measure against",
			GeSlotText.edgeOf(offer(GrandExchangeOfferState.BUYING, 1), null));
	}
}
