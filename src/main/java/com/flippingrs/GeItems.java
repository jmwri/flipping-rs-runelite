package com.flippingrs;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;

/**
 * Where the Grand Exchange is showing an item, wherever that happens to be.
 *
 * <p>The exchange is not one screen but eight or nine, and an item can be on
 * any of them: the offer boxes, the page you set an offer up on, the page you
 * get when you click one you have already placed, your history, the collection
 * box, the inventory beside it all, another player's offers in a view-only
 * exchange, and the price checker. Anything that wants to say something about
 * an item -- a right-click entry, a price drawn on it -- needs the same answer
 * from all of them, and asking each screen its own way is how one of them ends
 * up quietly missed.
 *
 * <p>So the answer is worked out once, here, in the way that does not depend
 * on a screen's layout: the client tells a widget which item it is drawing,
 * and this reads that off whichever widgets the exchange currently has up. The
 * two exceptions are the ones where no widget carries the item -- the eight
 * offer boxes, where it comes from the client's own record of the offer, and
 * the setup page, where it comes from the varp that page is driven by.
 *
 * <p>Client thread. {@link #onScreen} runs once a frame, so it walks a fixed
 * and short list of containers rather than the whole widget tree.
 */
final class GeItems
{
	/**
	 * How many items to report at once. The price checker can hold a full
	 * inventory and the history a long list, and past a certain point the
	 * screen is too crowded to read anyway -- but the real reason for a bound
	 * is that this list decides how many items a price is asked for.
	 */
	private static final int MAX_SPOTS = 40;

	private GeItems()
	{
	}

	/** One item the exchange is drawing, and the box it is drawn in. */
	static final class Spot
	{
		final int itemId;
		final Rectangle bounds;
		/**
		 * The offer this box is showing, when the box is one of the eight
		 * slots. Null everywhere else, where there is an item but no offer of
		 * yours on it and so nothing to compare a price against.
		 */
		@Nullable
		final GrandExchangeOffer offer;

		/**
		 * The box this one is drawn inside, when it is drawn inside another.
		 *
		 * <p>Some of these screens scroll. A row scrolled out of its list is
		 * not hidden -- the client keeps its position and lets the list clip
		 * it -- so its bounds are a real rectangle somewhere outside the list,
		 * and anything drawn there lands on whatever the exchange has put
		 * above or below. The list's own box is carried along so that whatever
		 * uses a spot can clip to it the way the client does.
		 */
		@Nullable
		final Rectangle clip;

		Spot(int itemId, Rectangle bounds, @Nullable GrandExchangeOffer offer, @Nullable Rectangle clip)
		{
			this(itemId, bounds, offer, clip, true);
		}

		Spot(int itemId, Rectangle bounds, @Nullable GrandExchangeOffer offer, @Nullable Rectangle clip,
			boolean paint)
		{
			this.itemId = itemId;
			this.bounds = bounds;
			this.offer = offer;
			this.clip = clip;
			this.paint = paint;
		}

		/**
		 * Whether this spot is worth drawing on.
		 *
		 * <p>False for the offer setup page, whose prices are written into the
		 * screen itself. It is still a spot, because it is still an item the
		 * exchange is showing and so still an item worth having a price for --
		 * but painting one on it as well would put the same numbers on the
		 * same screen twice.
		 */
		final boolean paint;

		/** Whether a point is on this spot, and inside the list holding it. */
		boolean contains(int x, int y)
		{
			return bounds.contains(x, y) && (clip == null || clip.contains(x, y));
		}
	}

	/**
	 * Every item the exchange has on screen right now.
	 *
	 * <p>The offer boxes come first and carry their offer, because those are
	 * the ones something useful can be said about beyond the price. The rest
	 * follow in whatever order the screens are listed in, which is stable
	 * because the list of screens is -- and that order is what decides the
	 * answer when two boxes overlap, so the more specific screens come first.
	 */
	static List<Spot> onScreen(Client client)
	{
		final List<Spot> spots = new ArrayList<>();
		offerSlots(client, spots);
		setupPage(client, spots);
		for (int container : CONTAINERS)
		{
			collect(client.getWidget(container), spots, false);
		}
		for (int container : ROW_LISTS)
		{
			collect(client.getWidget(container), spots, true);
		}
		return spots;
	}

	/**
	 * The item the mouse is over, or -1.
	 *
	 * <p>For the right-click menu, which needs an answer on screens whose rows
	 * have no menu entry of their own to hang one off: a history row is
	 * display only, so the menu over it says nothing about where it was
	 * opened.
	 */
	static int under(Client client, @Nullable Point mouse)
	{
		if (mouse == null)
		{
			return -1;
		}
		for (Spot spot : onScreen(client))
		{
			if (spot.contains(mouse.getX(), mouse.getY()))
			{
				return spot.itemId;
			}
		}
		return -1;
	}

	/**
	 * The containers walked for items, one per screen the exchange can show.
	 *
	 * <p>Every one of these draws its items on child widgets that carry the
	 * item id, so they need no special handling: a screen added to this list
	 * gets right-click entries and prices at the same time. The offer boxes
	 * and the setup page are not here because on those the client does not put
	 * the item on the widget.
	 */
	private static final int[] CONTAINERS = {
		// The page you get by clicking an offer you have already placed.
		InterfaceID.GeOffers.DETAILS,
		// The collection box, both of its slots.
		InterfaceID.GeCollect.COLLECT_0,
		InterfaceID.GeCollect.COLLECT_1,
		InterfaceID.GeCollect.COLLECT_2,
		InterfaceID.GeCollect.COLLECT_3,
		InterfaceID.GeCollect.COLLECT_4,
		InterfaceID.GeCollect.COLLECT_5,
		InterfaceID.GeCollect.COLLECT_6,
		// Another player's offers, in a view-only exchange.
		InterfaceID.GeViewonly.VIEW_0,
		InterfaceID.GeViewonly.VIEW_1,
		InterfaceID.GeViewonly.VIEW_2,
		InterfaceID.GeViewonly.VIEW_3,
		InterfaceID.GeViewonly.VIEW_4,
		InterfaceID.GeViewonly.VIEW_5,
		InterfaceID.GeViewonly.VIEW_6,
		InterfaceID.GeViewonly.VIEW_7,
		// The price checker.
		InterfaceID.GePricechecker.ITEMS,
		// The inventory beside the exchange.
		InterfaceID.GeOffersSide.ITEMS,
	};

	/**
	 * Containers whose children are rows across the whole width rather than
	 * boxes.
	 *
	 * <p>A history row carries its item on a narrow icon at the left, but the
	 * row is the width of the list: the icon's height is the row's height and
	 * nothing else in the row says which item it is. So the icon's box is
	 * widened to the list's, which is both where a caption has room to be
	 * drawn and the area a right-click has to count as being on that row.
	 * Treating the icon as the whole target meant only a click on the picture
	 * itself found the item.
	 */
	private static final int[] ROW_LISTS = {
		InterfaceID.GeHistory.LIST,
	};

	/**
	 * The eight offer boxes.
	 *
	 * <p>The slot widgets do not carry an item id, so the item comes from the
	 * client's own record of the offer -- which is also what makes these the
	 * only spots that know what you asked for as well as what it is.
	 */
	private static void offerSlots(Client client, List<Spot> into)
	{
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		for (int slot = 0; slot < SLOTS.length && slot < offers.length; slot++)
		{
			final GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == null
				|| offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			final Rectangle bounds = boundsOf(client.getWidget(SLOTS[slot]));
			if (bounds != null)
			{
				// A slot is its own box on a screen that does not scroll.
				into.add(new Spot(offer.getItemId(), bounds, offer, null));
			}
		}
	}

	/** The eight slot widgets, in slot order. */
	static final int[] SLOTS = {
		InterfaceID.GeOffers.INDEX_0,
		InterfaceID.GeOffers.INDEX_1,
		InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3,
		InterfaceID.GeOffers.INDEX_4,
		InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6,
		InterfaceID.GeOffers.INDEX_7,
	};

	/**
	 * The offer setup page, whose item comes from the varp that drives it --
	 * the same one RuneLite's own exchange plugin reads to put the buy limit
	 * on that page.
	 */
	private static void setupPage(Client client, List<Spot> into)
	{
		final Rectangle bounds = boundsOf(client.getWidget(InterfaceID.GeOffers.SETUP));
		if (bounds == null)
		{
			return;
		}
		final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId > 0)
		{
			// Not painted on: GeSetupText writes this screen's prices into the
			// screen. Reported all the same, so a price is fetched for it.
			into.add(new Spot(itemId, bounds, null, null, false));
		}
	}

	/**
	 * Every item drawn inside a container, the container itself included.
	 *
	 * <p>All three kinds of child, because which kind a screen uses is the
	 * screen's business: a history row is a dynamic child, an inventory slot
	 * is a static one, and a nested component can be either. Looking at only
	 * one kind is how a screen ends up silently missed.
	 */
	private static void collect(@Nullable Widget container, List<Spot> into, boolean rows)
	{
		if (container == null || container.isHidden() || into.size() >= MAX_SPOTS)
		{
			return;
		}
		final Rectangle inside = boundsOf(container);
		final Rectangle across = rows ? inside : null;
		// The container itself is not clipped by itself.
		add(container, into, across, null);
		for (Widget[] children : new Widget[][]{
			container.getDynamicChildren(), container.getStaticChildren(), container.getNestedChildren()})
		{
			if (children == null)
			{
				continue;
			}
			for (Widget child : children)
			{
				if (into.size() >= MAX_SPOTS)
				{
					return;
				}
				if (child == null || child.isHidden())
				{
					continue;
				}
				add(child, into, across, inside);
			}
		}
	}

	/**
	 * Keeps a widget that is drawing an item, ignoring one that is not.
	 *
	 * @param across the list this widget is a row of, if it is one, so the
	 *               row's box spans the list's width rather than the icon's
	 * @param clip   the container drawing it, so a row scrolled out of a list
	 *               is not treated as being where its bounds say it is
	 */
	private static void add(Widget widget, List<Spot> into, @Nullable Rectangle across,
		@Nullable Rectangle clip)
	{
		final int itemId = widget.getItemId();
		if (itemId <= 0)
		{
			return;
		}
		final Rectangle bounds = boundsOf(widget);
		if (bounds == null)
		{
			return;
		}
		into.add(new Spot(itemId,
			across == null ? bounds : new Rectangle(across.x, bounds.y, across.width, bounds.height),
			null, clip));
	}

	/** A widget's box on the canvas, or null if it has none worth drawing in. */
	@Nullable
	private static Rectangle boundsOf(@Nullable Widget widget)
	{
		if (widget == null || widget.isHidden())
		{
			return null;
		}
		final Rectangle bounds = widget.getBounds();
		return bounds == null || bounds.width <= 0 || bounds.height <= 0 ? null : bounds;
	}
}
