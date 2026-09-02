package com.flippingrs;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The right-click entries. What matters is that they appear on the right
 * items, wherever the exchange shows one, and never on anything that would
 * touch the game server.
 */
public class GeMenuTest
{
	private static final int WHIP = 4151;
	private static final int WHIP_NOTED = 4152;
	private static final int AGS = 11802;

	/** One created entry, remembering what was set on it. */
	private static class Entry
	{
		String option;
		String target;
		MenuAction type;
		Consumer<MenuEntry> click;
		final MenuEntry mock = mock(MenuEntry.class);

		Entry()
		{
			when(mock.setOption(anyString())).thenAnswer(inv ->
			{
				option = inv.getArgument(0);
				return mock;
			});
			when(mock.setTarget(anyString())).thenAnswer(inv ->
			{
				target = inv.getArgument(0);
				return mock;
			});
			when(mock.setType(any())).thenAnswer(inv ->
			{
				type = inv.getArgument(0);
				return mock;
			});
			when(mock.onClick(any())).thenAnswer(inv ->
			{
				click = inv.getArgument(0);
				return mock;
			});
		}
	}

	private final Client client = mock(Client.class);
	private final Menu menu = mock(Menu.class);
	private final ItemManager itemManager = mock(ItemManager.class);
	private final List<Entry> created = new ArrayList<>();
	private final List<Integer> opened = new ArrayList<>();
	private final List<Integer> watched = new ArrayList<>();
	private final GrandExchangeOffer[] offers = new GrandExchangeOffer[8];

	private GeMenu geMenu;

	@Before
	public void setUp()
	{
		when(client.getMenu()).thenReturn(menu);
		when(client.getGrandExchangeOffers()).thenReturn(offers);
		when(menu.createMenuEntry(anyInt())).thenAnswer(inv ->
		{
			final Entry e = new Entry();
			created.add(e);
			return e.mock;
		});
		when(itemManager.canonicalize(anyInt())).thenAnswer(inv ->
			inv.getArgument(0, Integer.class) == WHIP_NOTED ? WHIP : inv.getArgument(0, Integer.class));
		name(WHIP, "Abyssal whip");
		name(AGS, "Armadyl godsword");

		geMenu = new GeMenu(client, itemManager, opened::add, watched::add);
	}

	private void name(int itemId, String name)
	{
		final ItemComposition composition = mock(ItemComposition.class);
		when(composition.getName()).thenReturn(name);
		when(itemManager.getItemComposition(itemId)).thenReturn(composition);
	}

	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int itemId)
	{
		final GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(state);
		when(offer.getItemId()).thenReturn(itemId);
		return offer;
	}

	/** A menu opened over a widget: "Cancel" at the bottom, the widget's op on top. */
	private static MenuOpened openedOver(int widgetId, int itemId)
	{
		final MenuEntry cancel = mock(MenuEntry.class);
		final MenuEntry op = mock(MenuEntry.class);
		when(op.getParam1()).thenReturn(widgetId);
		when(op.getItemId()).thenReturn(itemId);
		final MenuOpened event = new MenuOpened();
		event.setMenuEntries(new MenuEntry[]{cancel, op});
		return event;
	}

	/** A menu opened over nothing with options: just "Cancel". */
	private static MenuOpened openedOverNothing()
	{
		final MenuEntry cancel = mock(MenuEntry.class);
		final MenuOpened event = new MenuOpened();
		event.setMenuEntries(new MenuEntry[]{cancel});
		return event;
	}

	private Entry entry(String option)
	{
		for (Entry e : created)
		{
			if (option.equals(e.option))
			{
				return e;
			}
		}
		return null;
	}

	@Test
	public void anOfferSlotGetsBothEntriesForItsItem()
	{
		offers[3] = offer(GrandExchangeOfferState.BUYING, WHIP);

		geMenu.onMenuOpened(openedOver(InterfaceID.GeOffers.INDEX_3, -1));

		final Entry view = entry(GeMenu.VIEW);
		final Entry watch = entry(GeMenu.WATCH);
		assertNotNull(view);
		assertNotNull(watch);
		assertEquals("Abyssal whip", view.target);
		assertEquals("Abyssal whip", watch.target);

		view.click.accept(view.mock);
		assertEquals(Integer.valueOf(WHIP), opened.get(0));

		watch.click.accept(watch.mock);
		assertEquals(Integer.valueOf(WHIP), watched.get(0));
	}

	/** The labels are generic; the plugin's name is on the panel already. */
	@Test
	public void theLabelsDoNotNameTheSite()
	{
		for (String label : new String[]{GeMenu.VIEW, GeMenu.WATCH})
		{
			assertTrue(label, !label.toLowerCase().contains("flipping"));
		}
	}

	/**
	 * Jagex's guidelines forbid menu entries that send an action to the game
	 * server. RUNELITE is the type that never does.
	 */
	@Test
	public void theEntriesAreClientSideOnly()
	{
		offers[0] = offer(GrandExchangeOfferState.SELLING, WHIP);

		geMenu.onMenuOpened(openedOver(InterfaceID.GeOffers.INDEX_0, -1));

		assertEquals(2, created.size());
		for (Entry e : created)
		{
			assertEquals(MenuAction.RUNELITE, e.type);
		}
	}

	@Test
	public void anEmptySlotGetsNothing()
	{
		offers[5] = offer(GrandExchangeOfferState.EMPTY, 0);

		geMenu.onMenuOpened(openedOver(InterfaceID.GeOffers.INDEX_5, -1));

		assertTrue(created.isEmpty());
	}

	@Test
	public void theSideInventoryUsesTheItemOnTheEntry()
	{
		geMenu.onMenuOpened(openedOver(InterfaceID.GeOffersSide.ITEMS, WHIP_NOTED));

		final Entry view = entry(GeMenu.VIEW);
		assertNotNull(view);
		view.click.accept(view.mock);
		assertEquals("noted items resolve to the real item", Integer.valueOf(WHIP), opened.get(0));
	}

	/** The offer setup page: the item being bought or sold comes from a varp. */
	@Test
	public void theSetupPageUsesTheItemBeingSetUp()
	{
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(AGS);

		geMenu.onMenuOpened(openedOver(InterfaceID.GeOffers.SETUP_DESC, -1));

		final Entry view = entry(GeMenu.VIEW);
		assertNotNull(view);
		assertEquals("Armadyl godsword", view.target);
		view.click.accept(view.mock);
		assertEquals(Integer.valueOf(AGS), opened.get(0));
	}

	/**
	 * History rows have no options, so the menu over one is only "Cancel" and
	 * the row has to be found from where the mouse is.
	 */
	@Test
	public void aHistoryRowIsFoundUnderTheMouse()
	{
		final Widget list = mock(Widget.class);
		when(list.getBounds()).thenReturn(new Rectangle(100, 100, 400, 300));
		final Widget whipIcon = historyIcon(WHIP, 120);
		final Widget agsIcon = historyIcon(AGS, 160);
		when(list.getDynamicChildren()).thenReturn(new Widget[]{whipIcon, null, agsIcon});
		when(client.getWidget(InterfaceID.GeHistory.LIST)).thenReturn(list);
		// Well to the right of the icons, on the second row's line.
		when(client.getMouseCanvasPosition()).thenReturn(new Point(400, 170));

		geMenu.onMenuOpened(openedOverNothing());

		final Entry view = entry(GeMenu.VIEW);
		assertNotNull(view);
		assertEquals("Armadyl godsword", view.target);
	}

	@Test
	public void theHistoryListIsIgnoredWhenTheMouseIsOutsideIt()
	{
		final Widget list = mock(Widget.class);
		when(list.getBounds()).thenReturn(new Rectangle(100, 100, 400, 300));
		final Widget whipIcon = historyIcon(WHIP, 120);
		when(list.getDynamicChildren()).thenReturn(new Widget[]{whipIcon});
		when(client.getWidget(InterfaceID.GeHistory.LIST)).thenReturn(list);
		when(client.getMouseCanvasPosition()).thenReturn(new Point(20, 120));

		geMenu.onMenuOpened(openedOverNothing());

		assertTrue(created.isEmpty());
	}

	private static Widget historyIcon(int itemId, int y)
	{
		final Widget icon = mock(Widget.class);
		when(icon.getItemId()).thenReturn(itemId);
		when(icon.getBounds()).thenReturn(new Rectangle(110, y, 32, 32));
		return icon;
	}

	@Test
	public void otherInterfacesAreLeftAlone()
	{
		geMenu.onMenuOpened(openedOver(InterfaceID.Inventory.ITEMS, WHIP));
		geMenu.onMenuOpened(openedOver(InterfaceID.Bankmain.ITEMS, WHIP));
		geMenu.onMenuOpened(openedOverNothing());

		assertTrue("the plugin's entries belong in the exchange, not everywhere", created.isEmpty());
	}
}
