package com.flippingrs;

import java.awt.Rectangle;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the offer setup screen is told to say.
 *
 * <p>The line is written into the game's own interface, so where it lands and
 * how it survives a rebuild need a running client. What it says does not, and
 * that is the part worth pinning: these are the numbers somebody types an
 * offer from.
 */
public class GeSetupTextTest
{
	private static Quote whip()
	{
		final Quote quote = new Quote();
		quote.id = 4151;
		// The site's two ends of the spread: buy at the low, sell at the high.
		quote.instantSell = 1_480_000;
		quote.instantBuy = 1_520_000;
		quote.netMargin = 32_000;
		return quote;
	}

	/**
	 * Exact to the coin, because a price on this screen is a number somebody
	 * is about to type. "1.48M" is not typeable.
	 */
	@Test
	public void thePricesAreExactToTheCoin()
	{
		final String text = GeSetupText.textFor(whip());

		assertTrue(text, text.contains("Buy 1,480,000"));
		assertTrue(text, text.contains("Sell 1,520,000"));
		assertTrue("and the margin carries its sign", text.contains("+32,000"));
	}

	/**
	 * One line, always. It goes into a gap in somebody else's layout, and a
	 * gap that takes one line is a much safer thing to assume than one that
	 * takes two.
	 *
	 * <p>What qualifies the prices is folded onto the end rather than dropped:
	 * the buy limit and the age are what say whether the numbers before them
	 * can be trusted.
	 */
	@Test
	public void everythingIsOnOneLine()
	{
		final Quote quote = whip();
		quote.limitRemaining = 3412;
		quote.dataAgeSeconds = 250;

		final String text = GeSetupText.textFor(quote);

		assertFalse("one line", text.contains("<br>"));
		assertTrue(text, text.startsWith("Buy "));
		assertTrue(text, text.contains("Limit 3.4K"));
		assertTrue(text, text.contains("Priced 4m ago"));
	}

	/**
	 * A server that sends neither leaves both off rather than writing an empty
	 * separator after the prices.
	 */
	@Test
	public void aServerThatSaysNeitherLeavesThemOff()
	{
		final Quote quote = whip();
		quote.dataAgeSeconds = -1;

		assertFalse(quote.hasLimitLeft());
		final String text = GeSetupText.textFor(quote);
		assertFalse(text, text.contains("Limit"));
		assertFalse(text, text.contains("Priced"));
		assertTrue("the prices are still there", text.contains("Buy 1,480,000"));
	}

	/**
	 * The line is coloured by the margin, unless the prices are old enough
	 * that the margin is not worth trusting -- in which case it is greyed,
	 * because a confident green on a stale number is the wrong thing to say.
	 */
	@Test
	public void staleNumbersAreNotColouredConfidently()
	{
		final Quote fresh = whip();
		fresh.dataAgeSeconds = 30;
		final Quote stale = whip();
		stale.dataAgeSeconds = 4000;

		assertFalse("a fresh profit is not greyed",
			GeSetupText.colourFor(fresh) == GeSetupText.colourFor(stale));

		final Quote losing = whip();
		losing.netMargin = -5_000;
		losing.dataAgeSeconds = 30;
		assertFalse("and a loss is not the colour of a profit",
			GeSetupText.colourFor(losing) == GeSetupText.colourFor(fresh));
	}

	/**
	 * Where the line begins: directly under the description, wherever that
	 * ended up.
	 *
	 * <p>Measured against the description every frame rather than fixed,
	 * because the description is the one part of this screen whose height is
	 * not: it wraps, so a long name or a long examine pushes what follows
	 * down. A remembered offset would sit on top of the description for
	 * exactly the items whose description is worth reading.
	 */
	@Test
	public void theLineBeginsUnderTheDescription()
	{
		final Rectangle page = new Rectangle(100, 200, 300, 250);
		final Rectangle shortDesc = new Rectangle(110, 240, 280, 12);
		final Rectangle wrapped = new Rectangle(110, 240, 280, 36);

		// The description ends 52px into the page (240 + 12 - 200), so the line
		// begins a gap below that.
		assertEquals("just under a one-line description",
			52 + 4, GeSetupText.under(page, shortDesc, 4));
		assertEquals("and further down when it wrapped to three",
			76 + 4, GeSetupText.under(page, wrapped, 4));
	}

	/** A description above the page cannot push the line off the top of it. */
	@Test
	public void theLineNeverStartsAboveThePage()
	{
		final Rectangle page = new Rectangle(100, 200, 300, 250);
		final Rectangle above = new Rectangle(110, 100, 280, 12);

		assertEquals(0, GeSetupText.under(page, above, 4));
	}

	@Test
	public void theAgeOfThePricesIsSaidPlainly()
	{
		assertEquals("just now", GeSetupText.age(0));
		assertEquals("a minute is still now", "just now", GeSetupText.age(60));
		assertEquals("4m ago", GeSetupText.age(4 * 60 + 30));
		assertEquals("59m ago", GeSetupText.age(59 * 60));
		assertEquals("1h ago", GeSetupText.age(3600));
		assertEquals("2h ago", GeSetupText.age(2 * 3600 + 1800));
	}

	/**
	 * An item that has never traded has no age, and the server says so with a
	 * negative one. Reading that as "just now" because it is a small number
	 * would put the most reassuring words on the screen in the one case where
	 * nothing at all is known.
	 */
	@Test
	public void anItemThatHasNeverTradedHasNoAge()
	{
		assertNull(GeSetupText.age(-1));
		assertNotNull("and a real age still reads", GeSetupText.age(0));
	}

	/**
	 * The buy limit is the one number a flipper cannot work out in their head,
	 * and the reset only matters once there is nothing left to buy.
	 */
	@Test
	public void theBuyLimitLeftIsWhatThereIsToActOn()
	{
		final Quote quote = new Quote();
		quote.limitRemaining = 3412;
		assertEquals("3.4K", GeSetupText.limitLeft(quote));

		quote.limitRemaining = 0;
		quote.limitResetsInSeconds = 2 * 3600 + 14 * 60;
		assertEquals("none for 2h 14m", GeSetupText.limitLeft(quote));

		quote.limitResetsInSeconds = 0;
		assertEquals("a reset it does not know about is not invented",
			"none", GeSetupText.limitLeft(quote));
	}

	@Test
	public void howLongUntilTheLimitResets()
	{
		assertEquals("under a minute", GeSetupText.until(30));
		assertEquals("12m", GeSetupText.until(12 * 60 + 59));
		assertEquals("1h 12m", GeSetupText.until(3600 + 12 * 60));
		assertEquals("an exact number of hours does not trail a 0m", "4h", GeSetupText.until(4 * 3600));
	}

	/**
	 * A server that does not send the limit is not the same as one saying
	 * there is none left, and drawing "0" for the first would tell somebody to
	 * stop buying an item they can freely buy.
	 */
	@Test
	public void aServerThatSaysNothingAboutTheLimitSaysNothing()
	{
		assertFalse(new Quote().hasLimitLeft());
		assertFalse(GeSetupText.textFor(whip()).contains("Limit"));
	}
}
