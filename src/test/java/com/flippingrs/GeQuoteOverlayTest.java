package com.flippingrs;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The overlay draws only on the offer setup screen, only for a watched item
 * with a quote. Everything else must draw nothing rather than a blank box.
 */
public class GeQuoteOverlayTest
{
	private final Client client = mock(Client.class);
	private final Widget setup = mock(Widget.class);
	private final Map<Integer, Quote> watched = new HashMap<>();
	private GeQuoteOverlay overlay;
	/** The items the overlay said were on screen, so a price can be fetched. */
	private final java.util.List<Integer> asked = new java.util.ArrayList<>();

	@Before
	public void setUp()
	{
		overlay = new GeQuoteOverlay(client, watched::get, itemId -> asked.add(itemId));
		final Quote whip = new Quote();
		whip.id = 4151;
		whip.instantSell = 1_480_000;
		whip.instantBuy = 1_520_000;
		watched.put(4151, whip);
	}

	/**
	 * The box sits in the bottom-right of the setup panel.
	 *
	 * <p>Not anywhere: the bottom-left holds the back button, the price and
	 * quantity boxes are in the middle, and the confirm button runs along the
	 * bottom. This corner is the one that is free, and covering any of the
	 * others makes the screen harder to use than having no quote at all.
	 */
	@Test
	public void theQuoteSitsInTheFreeCornerOfTheSetupScreen()
	{
		final java.awt.Rectangle bounds = new java.awt.Rectangle(100, 50, 400, 300);

		final java.awt.Point at = GeQuoteOverlay.cornerFor(bounds, 60);

		assertEquals("hard against the right edge, less the margin", 346, at.x);
		assertEquals("and the bottom edge, less its own height", 286, at.y);
		assertTrue("inside the panel it belongs to",
			at.x >= bounds.x && at.y >= bounds.y
				&& at.x < bounds.x + bounds.width && at.y < bounds.y + bounds.height);
	}

	/**
	 * And stays inside a setup panel too small to hold it.
	 *
	 * <p>The corner is worked out by subtracting the box from the far edge, so
	 * a panel narrower or shorter than the box puts it off the left or the top
	 * of the screen it belongs to unless both are clamped.
	 */
	@Test
	public void theQuoteStaysInsideASetupScreenTooSmallForIt()
	{
		final java.awt.Rectangle tiny = new java.awt.Rectangle(100, 50, 40, 20);

		final java.awt.Point at = GeQuoteOverlay.cornerFor(tiny, 60);

		assertEquals("not off the left of it", 104, at.x);
		assertEquals("nor off the top", 54, at.y);
	}

	@Test
	public void showsTheQuoteForAWatchedItemBeingSetUp()
	{
		when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(setup);
		when(setup.isHidden()).thenReturn(false);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(4151);

		assertSame(watched.get(4151), overlay.visibleQuote());
	}

	@Test
	public void drawsNothingWhenTheSetupScreenIsNotOpen()
	{
		when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(null);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(4151);
		assertNull(overlay.visibleQuote());

		when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(setup);
		when(setup.isHidden()).thenReturn(true);
		assertNull(overlay.visibleQuote());
	}

	@Test
	public void drawsNothingForAnItemThatIsNotWatched()
	{
		when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(setup);
		when(setup.isHidden()).thenReturn(false);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(11802);

		assertNull(overlay.visibleQuote());
	}

	@Test
	public void thePricesAreExactToTheCoin()
	{
		assertEquals("1,480,000", FlippingRsPanel.exact(1_480_000));
		assertEquals("+9,600", FlippingRsPanel.signedExact(9_600));
		assertEquals("-500", FlippingRsPanel.signedExact(-500));
		assertEquals("0", FlippingRsPanel.signedExact(0));
	}

	/**
	 * The box shows prices to the gp, and exactness implies a freshness it
	 * does not have. A figure that is forty minutes old, drawn beside the box
	 * where a number gets typed, is a trap a rounder figure never sets -- so
	 * the age is said, in as much precision as the answer deserves.
	 */
	@Test
	public void theAgeOfThePricesIsSaidPlainly()
	{
		assertEquals("just now", GeQuoteOverlay.age(0));
		assertEquals("a minute is still now", "just now", GeQuoteOverlay.age(60));
		assertEquals("4m ago", GeQuoteOverlay.age(4 * 60 + 30));
		assertEquals("59m ago", GeQuoteOverlay.age(59 * 60));
		assertEquals("1h ago", GeQuoteOverlay.age(3600));
		assertEquals("2h ago", GeQuoteOverlay.age(2 * 3600 + 1800));
	}

	/**
	 * An item that has never traded has no age, and the server says so with a
	 * negative one.
	 *
	 * <p>Reading that as "just now" because it is a small number would put the
	 * most reassuring words on the screen in the one case where nothing at all
	 * is known -- which is the exact false freshness this line was added to
	 * prevent. Nothing is drawn instead.
	 */
	@Test
	public void anItemThatHasNeverTradedHasNoAge()
	{
		assertNull(GeQuoteOverlay.age(-1));
		assertNotNull("and a real age still reads", GeQuoteOverlay.age(0));
	}

	/**
	 * The buy limit is the one number a flipper cannot work out in their head,
	 * and the reset only matters once there is nothing left to buy: while
	 * there is, the number to act on is what is remaining.
	 */
	@Test
	public void theBuyLimitLeftIsWhatThereIsToActOn()
	{
		final Quote quote = new Quote();
		quote.limitRemaining = 3412;
		assertEquals("3.4K", GeQuoteOverlay.limitLeft(quote));

		quote.limitRemaining = 0;
		quote.limitResetsInSeconds = 2 * 3600 + 14 * 60;
		assertEquals("none for 2h 14m", GeQuoteOverlay.limitLeft(quote));

		quote.limitResetsInSeconds = 0;
		assertEquals("a reset it does not know about is not invented",
			"none", GeQuoteOverlay.limitLeft(quote));
	}

	@Test
	public void howLongUntilTheLimitResets()
	{
		assertEquals("under a minute", GeQuoteOverlay.until(30));
		assertEquals("12m", GeQuoteOverlay.until(12 * 60 + 59));
		assertEquals("1h 12m", GeQuoteOverlay.until(3600 + 12 * 60));
		assertEquals("an exact number of hours does not trail a 0m", "4h", GeQuoteOverlay.until(4 * 3600));
	}

	/**
	 * A server that does not send the limit is not the same as one saying
	 * there is none left, and drawing "0" for the first would tell a user to
	 * stop buying an item they can buy.
	 */
	@Test
	public void aServerThatSaysNothingAboutTheLimitDrawsNoLimitLine()
	{
		final Quote quote = new Quote();
		assertFalse(quote.hasLimitLeft());
	}
}
