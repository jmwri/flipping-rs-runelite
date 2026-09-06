package com.flippingrs;

import java.awt.Rectangle;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Finding the item the exchange is showing, on every screen it has.
 *
 * <p>The point of the class is that one answer serves the right-click entries
 * and the prices drawn on items alike, so what these pin is the coverage: a
 * screen that is not found here is a screen where both quietly stop working,
 * and nothing else in the plugin would notice.
 */
public class GeItemsTest
{
	private final Client client = mock(Client.class);

	/** A widget drawing one item at a given box. */
	private Widget item(int itemId, int x, int y, int width, int height)
	{
		final Widget widget = mock(Widget.class);
		when(widget.isHidden()).thenReturn(false);
		when(widget.getItemId()).thenReturn(itemId);
		when(widget.getBounds()).thenReturn(new Rectangle(x, y, width, height));
		return widget;
	}

	/** A container drawing nothing itself, with these children. */
	private Widget holding(int x, int y, int width, int height, Widget... children)
	{
		final Widget widget = mock(Widget.class);
		when(widget.isHidden()).thenReturn(false);
		when(widget.getItemId()).thenReturn(-1);
		when(widget.getBounds()).thenReturn(new Rectangle(x, y, width, height));
		when(widget.getDynamicChildren()).thenReturn(children);
		return widget;
	}

	private void noWidgets()
	{
		when(client.getWidget(anyInt())).thenReturn(null);
		when(client.getGrandExchangeOffers()).thenReturn(null);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(0);
	}

	/**
	 * Every screen the exchange can put an item on. One case each, because the
	 * failure this guards against is a screen being forgotten rather than the
	 * walk being wrong.
	 */
	@Test
	public void everyScreenTheExchangeHasIsSearched()
	{
		final int[] screens = {
			InterfaceID.GeOffers.DETAILS,
			InterfaceID.GeCollect.COLLECT_0,
			InterfaceID.GeCollect.COLLECT_1,
			InterfaceID.GeViewonly.VIEW_0,
			InterfaceID.GePricechecker.ITEMS,
			InterfaceID.GeOffersSide.ITEMS,
			InterfaceID.GeHistory.LIST,
		};
		for (int screen : screens)
		{
			noWidgets();
			// Built before the stubbing, not inside it: Mockito counts the
			// when() calls these make as a stub begun and not finished.
			final Widget list = holding(10, 10, 200, 40, item(4151, 12, 12, 32, 32));
			when(client.getWidget(screen)).thenReturn(list);

			final List<GeItems.Spot> spots = GeItems.onScreen(client);

			assertEquals("nothing found on screen " + screen, 1, spots.size());
			assertEquals(4151, spots.get(0).itemId);
		}
	}

	/**
	 * The eight offer boxes are the exception: the client does not put the item
	 * on the widget, so it comes from its own record of the offer. That is also
	 * what makes these the only boxes that carry an offer to compare a price
	 * against.
	 */
	@Test
	public void anOfferBoxTakesItsItemFromTheOfferItself()
	{
		noWidgets();
		final GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(GrandExchangeOfferState.BUYING);
		when(offer.getItemId()).thenReturn(4151);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[]{offer});
		final Widget box = holding(10, 10, 100, 50);
		when(client.getWidget(InterfaceID.GeOffers.INDEX_0)).thenReturn(box);

		final List<GeItems.Spot> spots = GeItems.onScreen(client);

		assertEquals(1, spots.size());
		assertEquals(4151, spots.get(0).itemId);
		assertEquals("and it carries the offer, which no other box does", offer, spots.get(0).offer);
	}

	/** An empty slot has no item in it, so it is not somewhere an item is. */
	@Test
	public void anEmptyOfferBoxIsNotAnItem()
	{
		noWidgets();
		final GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(GrandExchangeOfferState.EMPTY);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[]{offer});
		final Widget box = holding(10, 10, 100, 50);
		when(client.getWidget(InterfaceID.GeOffers.INDEX_0)).thenReturn(box);

		assertTrue(GeItems.onScreen(client).isEmpty());
	}

	/**
	 * The setup page's item comes from the varp that drives it, since nothing
	 * on that page carries it either.
	 */
	@Test
	public void theSetupPageTakesItsItemFromTheVarp()
	{
		noWidgets();
		final Widget page = holding(10, 10, 300, 200);
		when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(page);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(4151);

		final List<GeItems.Spot> spots = GeItems.onScreen(client);

		assertEquals(1, spots.size());
		assertEquals(4151, spots.get(0).itemId);
	}

	/**
	 * A history row carries its item on a narrow icon at the left, but the row
	 * is the width of the list and nothing else in it says which item it is.
	 * Treating the icon as the whole target meant only a right-click on the
	 * picture itself found the item.
	 */
	@Test
	public void aHistoryRowIsTheWidthOfTheList()
	{
		noWidgets();
		final Widget list = holding(10, 10, 400, 90, item(4151, 12, 40, 32, 32));
		when(client.getWidget(InterfaceID.GeHistory.LIST)).thenReturn(list);

		final List<GeItems.Spot> spots = GeItems.onScreen(client);

		assertEquals(1, spots.size());
		assertEquals("the row spans the list", new Rectangle(10, 40, 400, 32), spots.get(0).bounds);

		when(client.getMouseCanvasPosition()).thenReturn(new Point(380, 50));
		assertEquals("so a click well to the right of the icon still finds it",
			4151, GeItems.under(client, client.getMouseCanvasPosition()));
	}

	/**
	 * A row scrolled out of its list is not somewhere the mouse can be.
	 *
	 * <p>The client does not hide a scrolled-out child: it keeps its position
	 * and lets the list clip it, so its bounds are a real rectangle somewhere
	 * outside the list. Going by bounds alone would hand back an item for a
	 * row nobody can see -- and would paint that row's price over whatever the
	 * exchange has drawn above or below the list.
	 */
	@Test
	public void aRowScrolledOutOfItsListIsNotUnderTheMouse()
	{
		noWidgets();
		// The row sits above the top of the list, as one scrolled off does.
		final Widget list = holding(10, 100, 400, 90, item(4151, 12, 40, 32, 32));
		when(client.getWidget(InterfaceID.GeHistory.LIST)).thenReturn(list);

		final List<GeItems.Spot> spots = GeItems.onScreen(client);
		assertEquals("it is still a spot, because it is still a row", 1, spots.size());
		assertEquals("and it carries the list to clip against", new Rectangle(10, 100, 400, 90),
			spots.get(0).clip);

		assertEquals("but the mouse over it is not over the list",
			-1, GeItems.under(client, new Point(200, 50)));
	}

	/** And a click outside every box finds nothing rather than the nearest thing. */
	@Test
	public void aClickOnNothingFindsNothing()
	{
		noWidgets();
		final Widget list = holding(10, 10, 400, 90, item(4151, 12, 40, 32, 32));
		when(client.getWidget(InterfaceID.GeHistory.LIST)).thenReturn(list);

		assertEquals(-1, GeItems.under(client, new Point(5, 5)));
		assertEquals("and no mouse at all is not a crash", -1, GeItems.under(client, null));
	}

	/** A hidden screen is not on screen. */
	@Test
	public void aHiddenScreenIsSkipped()
	{
		noWidgets();
		final Widget hidden = holding(10, 10, 200, 40, item(4151, 12, 12, 32, 32));
		when(hidden.isHidden()).thenReturn(true);
		when(client.getWidget(InterfaceID.GeOffers.DETAILS)).thenReturn(hidden);

		assertTrue(GeItems.onScreen(client).isEmpty());
	}
}
