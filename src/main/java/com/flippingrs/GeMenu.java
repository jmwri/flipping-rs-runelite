package com.flippingrs;

import java.awt.Rectangle;
import java.util.function.IntConsumer;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.game.ItemManager;

/**
 * Right-click entries on items in the Grand Exchange interface.
 *
 * <p>Two entries, wherever the exchange shows an item: the eight offer slots,
 * the inventory beside them, the offer setup page, and the rows of the
 * history view. One opens the item's page on the site in the browser, the
 * other adds it to the watchlist shown in the side panel. Both are
 * {@link MenuAction#RUNELITE} entries, which is the line Jagex's third-party
 * client guidelines draw: a menu entry may not cause an action to be sent to
 * the game server, and these never touch it. RuneLite's own Grand Exchange
 * plugin adds its "Search Grand Exchange" entry the same way.
 *
 * <p>Hooked on the menu opening rather than on each entry being added,
 * because history rows have no options of their own and so never raise an
 * entry to hang off. The row is found from the mouse position instead.
 *
 * <p>Runs on the client thread, because menu events do. The browser and the
 * watchlist are handed to it as callbacks so that this can be tested without
 * opening anything.
 */
class GeMenu
{
	static final String VIEW = "View item";
	static final String WATCH = "Add to watchlist";

	private final Client client;
	private final ItemManager itemManager;
	private final IntConsumer openItem;
	private final IntConsumer addToWatchlist;

	GeMenu(Client client, ItemManager itemManager, IntConsumer openItem, IntConsumer addToWatchlist)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.openItem = openItem;
		this.addToWatchlist = addToWatchlist;
	}

	void onMenuOpened(MenuOpened event)
	{
		int itemId = itemUnderMenu(event.getFirstEntry());
		if (itemId <= 0)
		{
			itemId = itemInHistoryRowUnderMouse();
		}
		if (itemId <= 0)
		{
			return;
		}

		final int canonical = itemManager.canonicalize(itemId);
		final String name = itemManager.getItemComposition(canonical).getName();

		// Added in reverse so that "View" ends up above "Add to watchlist":
		// each createMenuEntry(-1) goes on the top of the menu.
		client.getMenu().createMenuEntry(-1)
			.setOption(WATCH)
			.setTarget(name)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> addToWatchlist.accept(canonical));
		client.getMenu().createMenuEntry(-1)
			.setOption(VIEW)
			.setTarget(name)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> openItem.accept(canonical));
	}

	/** The item the menu's leading entry is on, if it is an exchange widget. */
	private int itemUnderMenu(MenuEntry first)
	{
		if (first == null)
		{
			return -1;
		}
		final int widgetId = first.getParam1();
		switch (WidgetUtil.componentToInterface(widgetId))
		{
			case InterfaceID.GE_OFFERS:
				if (widgetId >= InterfaceID.GeOffers.SETUP && widgetId <= InterfaceID.GeOffers.SETUP_GRAPHIC4)
				{
					// The item being set up to buy or sell. The same varp
					// RuneLite's own plugin reads to put the buy limit on
					// that page.
					return client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
				}
				return itemInOfferSlot(widgetId);
			case InterfaceID.GE_OFFERS_SIDE:
				return itemOnEntry(first);
			default:
				return -1;
		}
	}

	/**
	 * The item in one of the eight offer slots, from the client's own record of
	 * the offer. The slot widgets do not carry an item id themselves.
	 */
	private int itemInOfferSlot(int widgetId)
	{
		final int slot = widgetId - InterfaceID.GeOffers.INDEX_0;
		if (slot < 0 || slot > InterfaceID.GeOffers.INDEX_7 - InterfaceID.GeOffers.INDEX_0)
		{
			return -1;
		}
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null || slot >= offers.length || offers[slot] == null)
		{
			return -1;
		}
		final GrandExchangeOffer offer = offers[slot];
		return offer.getState() == GrandExchangeOfferState.EMPTY ? -1 : offer.getItemId();
	}

	/** The item an inventory-style entry is on. */
	private static int itemOnEntry(MenuEntry entry)
	{
		if (entry.getItemId() > 0)
		{
			return entry.getItemId();
		}
		final Widget widget = entry.getWidget();
		return widget == null ? -1 : widget.getItemId();
	}

	/**
	 * The item in the history row the mouse is over.
	 *
	 * <p>History rows are display only, so the menu over one is just "Cancel"
	 * and says nothing about where it was opened. Each row does carry an item
	 * icon, though, and the icon's vertical extent is the row's. The mouse
	 * only has to be somewhere in the list at that height.
	 */
	private int itemInHistoryRowUnderMouse()
	{
		final Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		if (list == null || list.isHidden())
		{
			return -1;
		}
		final Point mouse = client.getMouseCanvasPosition();
		final Rectangle area = list.getBounds();
		if (mouse == null || area == null || !area.contains(mouse.getX(), mouse.getY()))
		{
			return -1;
		}
		final Widget[] rows = list.getDynamicChildren();
		if (rows == null)
		{
			return -1;
		}
		for (Widget child : rows)
		{
			if (child == null || child.getItemId() <= 0 || child.isHidden())
			{
				continue;
			}
			final Rectangle bounds = child.getBounds();
			if (bounds != null && mouse.getY() >= bounds.y && mouse.getY() < bounds.y + bounds.height)
			{
				return child.getItemId();
			}
		}
		return -1;
	}
}
