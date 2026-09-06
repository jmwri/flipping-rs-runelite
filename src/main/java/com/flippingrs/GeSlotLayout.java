package com.flippingrs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * Room in each offer box for the lines added to it, and in everything holding
 * them.
 *
 * <p>An offer box is sized for what the game puts in it. Three lines of prices
 * have to come from somewhere, and a box that will not grow simply draws them
 * over whatever is under it.
 *
 * <p>Nothing here moves the exchange. Only heights change, and only downward
 * from where the game already put things; the window stays where it was, and
 * if the room for a line is not there the line is not drawn.
 *
 * <p>This is the one thing the plugin resizes rather than adds to, and the way
 * it goes wrong is worth naming, because a previous version did. Changing a
 * layout and then reading that same layout back as the baseline cannot work:
 * growing the container moves the boxes inside it, and on the next look there
 * is no way to tell that from the client having laid them out afresh. It grows
 * from its own growth, or gives up on it, and which of those happens depends
 * on the order two things ran in.
 *
 * <p>So the pristine layout is read exactly once, when the screen opens, and
 * every write after that is {@code base + extra} computed from it. Writing the
 * same absolute numbers again changes nothing, so a tick that runs twice, or a
 * client that relays a box out underneath, both end at the same place. Nothing
 * is ever measured after it has been moved.
 *
 * <p>Client thread only.
 */
@Slf4j
class GeSlotLayout
{
	/** One row of the small font. */
	private static final int LINE = 12;

	/** The most rows worth asking for. More than this is not worth the room. */
	private static final int WANTED = 3;

	/**
	 * Room kept between the window and the edge of the screen. The exchange
	 * does not sit flush against it and neither should this.
	 */
	private static final int SPARE = 8;

	/**
	 * How far up the tree to follow a box looking for whatever is cutting it
	 * off. Four or five deep is the whole exchange; the cap is only there so a
	 * tree that loops cannot take the game thread with it.
	 */
	private static final int ANCESTORS = 8;

	private final Client client;
	private final FlippingRsConfig config;

	/** The layout as the client had it, or null before it has been read. */
	@Nullable
	private int[] baseY;
	@Nullable
	private int[] baseHeight;
	@Nullable
	private int[] baseYMode;
	@Nullable
	private int[] baseHeightMode;
	/**
	 * The widgets above a box, and how much spare height each already had.
	 *
	 * <p>Read once with everything else. A container with room in it absorbs
	 * the boxes growing and never passes it on, so what the window is finally
	 * asked for is the growth less whatever the chain soaked up -- which is
	 * usually far less than the growth itself, and is the difference between
	 * three lines fitting and none of them fitting.
	 */
	private final List<Integer> chainSlack = new ArrayList<>();

	/**
	 * The widgets above a box that had to grow, and what they were.
	 *
	 * <p>Which ones those are is not known in advance and is not worth
	 * guessing: a widget clips its children to itself, so the one that cuts a
	 * taller box off is whichever ancestor is too short for it, and a list
	 * written here is a list that is wrong the first time Jagex adds a layer.
	 * They are found by following the box up instead.
	 */
	private final List<Widget> grown = new ArrayList<>();
	private final List<Integer> grownHeight = new ArrayList<>();
	private final List<Integer> grownMode = new ArrayList<>();

	/**
	 * The pieces of each box that are the box: its background and its border.
	 *
	 * <p>Making a slot taller makes the layer taller, and a layer draws
	 * nothing. What is actually seen is a sprite inside it, and that sprite
	 * keeps the height it was given -- so the box grows, the border does not,
	 * and the extra lines end up outside a frame that is still the old size.
	 * These are grown with the box they belong to.
	 *
	 * <p>Which children those are is decided by shape rather than by name: the
	 * ones that span the box are the box. A label or an icon sitting inside it
	 * is left exactly where the game put it, so nothing the exchange draws
	 * moves; the room simply appears underneath it, which is where the added
	 * lines go.
	 */
	private final List<Widget> skin = new ArrayList<>();

	/**
	 * The sprites whose tiling was switched off to stretch them, so it can be
	 * switched back on with everything else.
	 */
	private final List<Widget> tiled = new ArrayList<>();

	private final List<Integer> skinHeight = new ArrayList<>();
	private final List<Integer> skinMode = new ArrayList<>();

	/**
	 * How many rows of text the boxes were last made room for. Read by
	 * {@link GeSlotText}, which says exactly that much and no more.
	 */
	private int rowsAfforded;

	GeSlotLayout(Client client, FlippingRsConfig config)
	{
		this.client = client;
		this.config = config;
	}

	/**
	 * The exchange has just opened, so the next look reads the layout afresh.
	 *
	 * <p>The only moment it is safe to read: the client has just built the
	 * screen and nothing here has touched it yet. Reading at any other time
	 * risks measuring this class's own work.
	 */
	void screenOpened()
	{
		forget();
	}

	/**
	 * Grows the boxes and everything holding them.
	 *
	 * <p>Never throws. It runs from the event bus on the game thread, where an
	 * exception becomes an uncaught plugin error on every tick.
	 */
	void update()
	{
		try
		{
			if (!config.setupOverlay())
			{
				reset();
				return;
			}
			final Widget[] widgets = slotWidgets();
			if (widgets == null)
			{
				return;
			}
			if (baseY == null && !read(widgets))
			{
				return;
			}
			final int[] rows = rowsOf();
			int deepest = 0;
			for (int slot = 0; slot < widgets.length; slot++)
			{
				if (widgets[slot] != null && baseHeight[slot] >= 0)
				{
					deepest = Math.max(deepest, rows[slot] + 1);
				}
			}
			final int afforded = afford(deepest);
			final int extra = afforded * LINE;
			Widget lowest = null;
			for (int slot = 0; slot < widgets.length; slot++)
			{
				if (widgets[slot] == null || baseHeight[slot] < 0)
				{
					continue;
				}
				put(widgets[slot], baseY[slot] + rows[slot] * extra, baseHeight[slot] + extra);
				// And the sprite that is the visible box, which is a separate
				// thing from the layer holding it.
				stretch(widgets[slot], baseHeight[slot], extra);
				lowest = widgets[slot];
			}
			// Whatever the boxes now stick out of, all the way up until
			// something already has the room. That is the fix for the cutting
			// off: it is never the box that clips, it is whatever holds it.
			openOut(lowest);
			rowsAfforded = afforded;
		}
		catch (RuntimeException e)
		{
			log.debug("could not make room in the offer boxes", e);
		}
	}

	/**
	 * Grows everything above a box that is now too short for what is in it.
	 *
	 * <p>A widget clips its children to its own bounds, so a box made taller
	 * than the thing holding it is simply cut off at the bottom of it -- and
	 * the same again one level up. Following the chain and giving each one
	 * exactly the height its children now need fixes that wherever it is,
	 * rather than where a list here guessed it would be.
	 *
	 * <p>It stops at the first one that already has the room, which is also
	 * what keeps the window from growing when it does not have to: if a
	 * container had slack in it, nothing outside that container ever hears
	 * about this.
	 */
	private void openOut(@Nullable Widget from)
	{
		Widget child = from;
		for (int depth = 0; depth < ANCESTORS && child != null; depth++)
		{
			final Widget parent = child.getParent();
			if (parent == null || parent.getParent() == null)
			{
				// The root is the screen. Nothing here is worth doing to it.
				return;
			}
			final int needed = extentOf(parent);
			if (needed <= parent.getHeight())
			{
				// This one already holds what is in it, so nothing above it
				// can be cutting anything off either.
				return;
			}
			remember(parent);
			parent.setHeightMode(WidgetSizeMode.ABSOLUTE);
			parent.setOriginalHeight(needed);
			if (parent.getScrollHeight() > 0)
			{
				// A container that scrolls clips to its scroll extent as well
				// as to itself, and leaving that behind cuts off exactly the
				// rows the extra height was for.
				parent.setScrollHeight(Math.max(parent.getScrollHeight(), needed));
				parent.revalidateScroll();
			}
			else
			{
				parent.revalidate();
			}
			child = parent;
		}
	}

	/**
	 * Grows the parts of a box that are drawn as the box.
	 *
	 * <p>A child that spans the height of the slot is its background or its
	 * border, and has to grow with it. Anything shorter is something the
	 * exchange put inside the slot and is left alone.
	 */
	private void stretch(Widget box, int boxBase, int extra)
	{
		for (Widget child : RowText.under(box))
		{
			final int base = baseOf(child);
			if (base < 0 || base * 3 < boxBase * 2)
			{
				// Not tall enough to be the box itself.
				continue;
			}
			final int wanted = base + extra;
			if (child.getHeight() != wanted)
			{
				// A sprite that tiles repeats to fill the height it is given,
				// and the sprite that draws a slot is the slot's frame -- so a
				// taller one drew the frame again in the middle of the box.
				// Off, it is one frame at the size asked for.
				if (child.getSpriteId() > 0 && child.getSpriteTiling())
				{
					tiled.add(child);
					child.setSpriteTiling(false);
				}
				child.setHeightMode(WidgetSizeMode.ABSOLUTE);
				child.setOriginalHeight(wanted);
				child.revalidate();
			}
		}
	}

	/**
	 * The height a box's child had before any of this touched it, remembering
	 * it the first time it is asked for.
	 */
	private int baseOf(Widget child)
	{
		final int index = skin.indexOf(child);
		if (index >= 0)
		{
			return skinHeight.get(index);
		}
		final int height = child.getHeight();
		if (height <= 0)
		{
			return -1;
		}
		skin.add(child);
		skinHeight.add(height);
		skinMode.add(child.getHeightMode());
		return height;
	}

	/** How far down its own children reach, which is the height it needs. */
	private static int extentOf(Widget parent)
	{
		int extent = 0;
		for (Widget child : RowText.under(parent))
		{
			extent = Math.max(extent, child.getRelativeY() + child.getHeight());
		}
		return extent;
	}

	/** Keeps what a widget was, the first time it is touched. */
	private void remember(Widget widget)
	{
		if (grown.contains(widget))
		{
			return;
		}
		grown.add(widget);
		grownHeight.add(widget.getHeight());
		grownMode.add(widget.getHeightMode());
	}

	/**
	 * How many rows of text there is actually room to add.
	 *
	 * <p>Asked rather than assumed, because the window cannot grow past the
	 * screen it is on. Three rows on two rows of boxes is seventy-two pixels
	 * of window, and a client with fifty to spare answers that by cutting the
	 * title off the top and the bottom row of offers off the bottom -- which
	 * is worse than saying less.
	 *
	 * <p>The window is centred, so height added to it goes half above and half
	 * below: what can be afforded is twice the smaller of the two gaps.
	 *
	 * @param rows how many rows of boxes there are, since each of them has to
	 *             be grown and the window carries the total
	 */
	private int afford(int rows)
	{
		if (rows <= 0)
		{
			return 0;
		}
		final int room = screenRoom();
		for (int lines = WANTED; lines >= 1; lines--)
		{
			if (reachesTheWindow(rows * lines * LINE) <= room)
			{
				return lines;
			}
		}
		return 0;
	}

	/**
	 * How much of a growth the window is actually asked for, once the
	 * containers between have taken what they can hold.
	 *
	 * <p>This is what makes the difference between three lines and none: the
	 * exchange has spare height inside it, and growth that a container absorbs
	 * never reaches the window at all.
	 */
	private int reachesTheWindow(int growth)
	{
		int remaining = growth;
		for (int slack : chainSlack)
		{
			remaining -= slack;
			if (remaining <= 0)
			{
				return 0;
			}
		}
		return remaining;
	}

	/**
	 * How much taller the window can get before it runs off the screen.
	 *
	 * <p>The window stays where the game put it, so height added to it goes
	 * half above and half below and what can be afforded is twice the smaller
	 * of the two gaps. Moving it down would buy more room, and it is not
	 * worth having: a window that jumps somewhere else the moment an offer is
	 * placed is a worse thing than a line of prices this plugin did not
	 * manage to fit.
	 */
	private int screenRoom()
	{
		final Widget frame = client.getWidget(InterfaceID.GeOffers.FRAME);
		final java.awt.Rectangle bounds = frame == null ? null : frame.getBounds();
		if (bounds == null || bounds.height <= 0)
		{
			return 0;
		}
		// The frame as the game had it, not as this may have already grown it.
		final int index = grown.indexOf(frame);
		final int height = index >= 0 ? grownHeight.get(index) : bounds.height;
		final int top = bounds.y + (bounds.height - height) / 2;
		final int below = client.getCanvasHeight() - (top + height);
		return Math.max(0, 2 * Math.min(top, below) - SPARE);
	}

	/**
	 * How many rows of text the boxes have been made room for, which is what
	 * there is room to say. Zero means the window had nothing to spare.
	 */
	int rowsAfforded()
	{
		return rowsAfforded;
	}

	/** Puts the layout back the way the game had it, and forgets it. */
	void reset()
	{
		try
		{
			if (baseY == null)
			{
				return;
			}
			final Widget[] widgets = slotWidgets();
			if (widgets != null)
			{
				for (int slot = 0; slot < widgets.length; slot++)
				{
					final Widget box = widgets[slot];
					if (box == null || baseHeight[slot] < 0)
					{
						continue;
					}
					box.setYPositionMode(baseYMode[slot]);
					box.setHeightMode(baseHeightMode[slot]);
					box.setOriginalY(baseY[slot]);
					box.setOriginalHeight(baseHeight[slot]);
					box.revalidate();
				}
			}
			for (Widget sprite : tiled)
			{
				sprite.setSpriteTiling(true);
			}
			for (int i = 0; i < skin.size(); i++)
			{
				final Widget child = skin.get(i);
				child.setHeightMode(skinMode.get(i));
				child.setOriginalHeight(skinHeight.get(i));
				child.revalidate();
			}
			// Outermost first, so each is put back into something still big
			// enough to hold it.
			for (int i = grown.size() - 1; i >= 0; i--)
			{
				final Widget holder = grown.get(i);
				holder.setHeightMode(grownMode.get(i));
				holder.setOriginalHeight(grownHeight.get(i));
				holder.revalidate();
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not put the offer boxes back", e);
		}
		forget();
	}

	/**
	 * Reads the client's layout, once.
	 *
	 * <p>From where the widgets ended up rather than from what they asked for:
	 * an offer box is sized as a proportion of what holds it, so its declared
	 * height is not a number of pixels and adding pixels to it does nothing.
	 *
	 * @return whether there was a laid-out screen to read
	 */
	private boolean read(Widget[] widgets)
	{
		final int slots = widgets.length;
		final int[] y = new int[slots];
		final int[] height = new int[slots];
		final int[] yMode = new int[slots];
		final int[] heightMode = new int[slots];
		Arrays.fill(y, -1);
		Arrays.fill(height, -1);
		boolean any = false;
		for (int slot = 0; slot < slots; slot++)
		{
			final Widget box = widgets[slot];
			if (box == null || box.getHeight() <= 0)
			{
				continue;
			}
			y[slot] = box.getRelativeY();
			height[slot] = box.getHeight();
			yMode[slot] = box.getYPositionMode();
			heightMode[slot] = box.getHeightMode();
			any = true;
		}
		if (!any)
		{
			// Nothing has been laid out yet. The next look tries again rather
			// than remembering a screen of zeroes as the way it should be.
			return false;
		}
		baseY = y;
		baseHeight = height;
		baseYMode = yMode;
		baseHeightMode = heightMode;
		chainSlack.clear();
		Widget child = widgets[0];
		for (int depth = 0; depth < ANCESTORS && child != null; depth++)
		{
			final Widget parent = child.getParent();
			if (parent == null || parent.getParent() == null)
			{
				break;
			}
			chainSlack.add(Math.max(0, parent.getHeight() - extentOf(parent)));
			child = parent;
		}
		return true;
	}

	@Nullable
	private Widget[] slotWidgets()
	{
		final Widget[] widgets = new Widget[GeItems.SLOTS.length];
		boolean any = false;
		for (int slot = 0; slot < widgets.length; slot++)
		{
			final Widget box = client.getWidget(GeItems.SLOTS[slot]);
			if (box != null && !box.isHidden())
			{
				widgets[slot] = box;
				any = true;
			}
		}
		return any ? widgets : null;
	}

	/**
	 * Puts a box at a pixel position and height, pinning both to absolute so
	 * the numbers mean what they say. Writing the same numbers again is not a
	 * change, which is what makes running this every tick harmless.
	 */
	private static void put(Widget box, int y, int height)
	{
		if (box.getRelativeY() == y && box.getHeight() == height)
		{
			return;
		}
		box.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		box.setHeightMode(WidgetSizeMode.ABSOLUTE);
		box.setOriginalY(y);
		box.setOriginalHeight(height);
		box.revalidate();
	}

	/**
	 * Which row each box is on, counting from the top.
	 *
	 * <p>Worked out from where the client put them rather than assumed to be
	 * two rows of four: the exchange is laid out differently in fixed and
	 * resizable mode, and a box pushed down by the wrong number of rows lands
	 * on the one under it.
	 */
	int[] rowsOf()
	{
		final List<Integer> seen = new ArrayList<>();
		for (int y : baseY)
		{
			if (y >= 0 && !seen.contains(y))
			{
				seen.add(y);
			}
		}
		final int[] tops = seen.stream().mapToInt(Integer::intValue).sorted().toArray();
		final int[] rows = new int[baseY.length];
		for (int slot = 0; slot < baseY.length; slot++)
		{
			rows[slot] = Math.max(0, Arrays.binarySearch(tops, baseY[slot]));
		}
		return rows;
	}

	private void forget()
	{
		baseY = null;
		baseHeight = null;
		baseYMode = null;
		baseHeightMode = null;
		grown.clear();
		grownHeight.clear();
		grownMode.clear();
		chainSlack.clear();
		skin.clear();
		skinHeight.clear();
		skinMode.clear();
		tiled.clear();
		rowsAfforded = 0;
	}
}
