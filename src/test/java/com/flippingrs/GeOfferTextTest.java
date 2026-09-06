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
public class GeOfferTextTest
{
	/**
	 * The text without its colour tags.
	 *
	 * <p>What is on screen is words; the tags are how the game is told to
	 * colour them. A test that asserted on the tagged string would break every
	 * time a colour moved, and would read as though the colours were the
	 * point.
	 */
	private static String plain(String text)
	{
		return text.replaceAll("</?col[^>]*>", "");
	}

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
		final String text = plain(GeOfferText.textFor(whip()));

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

		final String text = plain(GeOfferText.textFor(quote));

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
		final String text = plain(GeOfferText.textFor(quote));
		assertFalse(text, text.contains("Limit"));
		assertFalse(text, text.contains("Priced"));
		assertTrue("the prices are still there", text.contains("Buy 1,480,000"));
	}

	/**
	 * Old prices are said to be old rather than coloured as if they were not.
	 * A confident green on a stale number is the wrong thing to say.
	 */
	@Test
	public void oldPricesAreMarkedAsOld()
	{
		final Quote fresh = whip();
		fresh.dataAgeSeconds = 30;
		final Quote stale = whip();
		stale.dataAgeSeconds = 4000;

		assertFalse(GeOfferText.stale(fresh));
		assertTrue(GeOfferText.stale(stale));
	}

	/**
	 * The colours are written as colours, not as RuneLite's own tokens.
	 *
	 * <p>{@code <colNORMAL>} and friends are turned into colours only for the
	 * message types RuneLite has one configured for, and mean nothing at all
	 * to a widget -- so they went to the screen as their own text. This is
	 * what that bug looked like, and what it must not look like again.
	 */
	@Test
	public void theColoursAreColoursRatherThanTokens()
	{
		final String text = GeOfferText.textFor(whip());

		assertFalse(text, text.contains("<colNORMAL>"));
		assertFalse(text, text.contains("<colHIGHLIGHT>"));
		assertTrue(text, text.contains("<col=") && text.contains("</col>"));
	}

	@Test
	public void theAgeOfThePricesIsSaidPlainly()
	{
		assertEquals("just now", GeOfferText.age(0));
		assertEquals("a minute is still now", "just now", GeOfferText.age(60));
		assertEquals("4m ago", GeOfferText.age(4 * 60 + 30));
		assertEquals("59m ago", GeOfferText.age(59 * 60));
		assertEquals("1h ago", GeOfferText.age(3600));
		assertEquals("2h ago", GeOfferText.age(2 * 3600 + 1800));
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
		assertNull(GeOfferText.age(-1));
		assertNotNull("and a real age still reads", GeOfferText.age(0));
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
		assertEquals("3.4K", GeOfferText.limitLeft(quote));

		quote.limitRemaining = 0;
		quote.limitResetsInSeconds = 2 * 3600 + 14 * 60;
		assertEquals("none for 2h 14m", GeOfferText.limitLeft(quote));

		quote.limitResetsInSeconds = 0;
		assertEquals("a reset it does not know about is not invented",
			"none", GeOfferText.limitLeft(quote));
	}

	@Test
	public void howLongUntilTheLimitResets()
	{
		assertEquals("under a minute", GeOfferText.until(30));
		assertEquals("12m", GeOfferText.until(12 * 60 + 59));
		assertEquals("1h 12m", GeOfferText.until(3600 + 12 * 60));
		assertEquals("an exact number of hours does not trail a 0m", "4h", GeOfferText.until(4 * 3600));
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
		assertFalse(GeOfferText.textFor(whip()).contains("Limit"));
	}

	/**
	 * The offer screens carry the volume too, shortened.
	 *
	 * <p>"Vol" rather than "Volume" here alone: this line goes on the end of
	 * the game's own description and has to stay one line, which is the whole
	 * reason it was put there.
	 */
	@Test
	public void theOfferLineCarriesTheVolumeShortened()
	{
		final Quote quote = whip();
		quote.volume24h = 1234;
		quote.dataAgeSeconds = 250;

		final String text = GeOfferText.textFor(quote).replaceAll("<[^>]*>", "");

		assertTrue(text, text.contains("Vol 1.2K/24h"));
		assertTrue("and it qualifies the margin, so it comes before the age: " + text,
			text.indexOf("Vol ") < text.indexOf("Priced "));
	}

	/** And says nothing when the site has no volume for the item. */
	@Test
	public void anOfferLineWithoutVolumeDoesNotMentionIt()
	{
		final String text = GeOfferText.textFor(whip());

		assertFalse(text, text.contains("Vol"));
	}
}
