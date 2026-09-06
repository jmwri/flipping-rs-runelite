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
 * What the exchange's hover box gains when you point at something in it.
 *
 * <p>The box is the game's own, and it already says which item this is about,
 * so what goes in is only what the game does not know: the site's two prices,
 * the margin, and how your own offer sits against them.
 *
 * <p>The arithmetic is the part worth being sure about. Measuring an offer
 * against the wrong end of the spread would call every sensible offer badly
 * priced by exactly the width of it, and would do it in green.
 */
public class GeTooltipTest
{
	private static final String GOOD = "0b6b1f";
	private static final String BAD = "9b1c1c";

	/** The colour the exchange writes its hover box in: near-black on yellow. */
	private static final int BOX_TEXT = 0x1a1a1a;

	/** The lines without their colours; the colours are asserted separately. */
	private static String[] lines(String text)
	{
		return text.replaceAll("<col=[^>]*>", "").split("<br>");
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
	 * Exact to the coin. The box is made as tall as it needs to be, and these
	 * are prices somebody is deciding what to type from.
	 */
	@Test
	public void anItemWithNoOfferOnItGetsThePrices()
	{
		final String[] lines = lines(GeTooltip.textFor(whip(), null, BOX_TEXT));

		assertEquals(4, lines.length);
		assertEquals("Buy 1,480,000", lines[0]);
		assertEquals("Sell 1,520,000", lines[1]);
		assertEquals("Margin +32,000", lines[2]);
		assertEquals("and how old they are, which a fresh quote still says",
			"Priced just now", lines[3]);
	}

	/** The item's name is the game's line, not one to write again under it. */
	@Test
	public void theNameIsLeftToTheBoxThatAlreadySaysIt()
	{
		assertFalse(GeTooltip.textFor(whip(), null, BOX_TEXT).contains("Abyssal whip"));
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
			offer(GrandExchangeOfferState.BUYING, 1_485_000), BOX_TEXT);
		assertEquals("a buy over the site's buy price fills sooner",
			"Yours +5,000", lines(buying)[3]);
		assertTrue(buying, buying.contains(GOOD));

		final String selling = GeTooltip.textFor(whip(),
			offer(GrandExchangeOfferState.SELLING, 1_600_000), BOX_TEXT);
		assertEquals("and a sale over its sell price will sit",
			"Yours -80,000", lines(selling)[3]);
		assertTrue(selling, selling.contains(BAD));
	}

	/** The buy limit and the price age, when the server sends them. */
	@Test
	public void whatQualifiesThePricesComesLast()
	{
		final Quote quote = whip();
		quote.limitRemaining = 3412;
		quote.dataAgeSeconds = 250;

		final String[] lines = lines(GeTooltip.textFor(quote, null, BOX_TEXT));

		assertEquals(5, lines.length);
		assertEquals("Limit 3.4K", lines[3]);
		assertEquals("Priced 4m ago", lines[4]);
	}

	/** And a server that sends neither leaves both off rather than guessing. */
	@Test
	public void aServerThatSaysNeitherLeavesThemOff()
	{
		final Quote quote = whip();
		quote.dataAgeSeconds = -1;

		final String text = GeTooltip.textFor(quote, null, BOX_TEXT);

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

	/**
	 * How much taller the box has to be made is counted from the addition
	 * itself rather than from how many lines it might have had.
	 *
	 * <p>It is the one number that decides whether the last line is inside the
	 * box or under it, and the addition is four lines, or five, or six
	 * depending on what the server sent.
	 */
	@Test
	public void theRoomMadeIsCountedFromWhatIsActuallyBeingAdded()
	{
		assertEquals("one line takes no extra row",
			0, GeTooltip.Grown.rowsIn("Buy 1"));
		assertEquals(3, GeTooltip.Grown.rowsIn(GeTooltip.textFor(whip(), null, BOX_TEXT)));

		final Quote quote = whip();
		quote.limitRemaining = 3412;
		assertEquals("a limit is another row to find room for",
			4, GeTooltip.Grown.rowsIn(GeTooltip.textFor(quote, null, BOX_TEXT)));
	}

	/**
	 * And how wide is measured from the words, not from the colours round
	 * them: a tag is an instruction to the renderer and takes no space.
	 */
	@Test
	public void aColourTagIsNotPartOfTheLineItColours()
	{
		assertEquals("Margin +32,000",
			GeTooltip.Grown.plain("<col=1a1a1a>Margin <col=0b6b1f>+32,000"));
	}

	/**
	 * The plain lines are written in whatever colour the box itself uses.
	 *
	 * <p>Not the sidebar's palette, which is built for pale text on a dark
	 * panel and is the exact opposite of this box: its white came out
	 * invisible on the yellow, which is a poor showing for the two prices.
	 * Reading the colour off the line being added to also survives Jagex
	 * recolouring the box.
	 */
	@Test
	public void thePlainLinesTakeTheColourTheBoxAlreadyUses()
	{
		final String text = GeTooltip.textFor(whip(), null, 0x1a1a1a);

		assertTrue(text, text.contains("<col=1a1a1a>Buy "));
		assertTrue("and Sell, and everything that is a value rather than a verdict",
			text.contains("<col=1a1a1a>Sell "));
		assertFalse("the sidebar's white is nowhere near it", text.contains("ffffff"));
	}

	/** A colour is written as six hex digits, whatever the client hands over. */
	@Test
	public void aColourIsAlwaysSixDigits()
	{
		assertEquals("black is not the empty string", "<col=000000>", GeTooltip.colour(0));
		assertEquals("<col=0b6b1f>", GeTooltip.colour(0x0b6b1f));
		assertEquals("and whatever the client has in the high byte is not a colour",
			"<col=ffff9b>", GeTooltip.colour(0xff_ffff9b));
	}
}
