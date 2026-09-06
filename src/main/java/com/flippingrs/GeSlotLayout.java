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
 * A little more room in each offer box, and in everything holding them.
 *
 * <p>The boxes are sized for what the game puts in them. An extra line has to
 * come from somewhere, and taking it from the box is better than letting the
 * box clip it: a price cut off halfway is worse than no price. But a taller
 * box needs a taller container, and that container needs a taller window, or
 * the last row simply disappears behind the frame -- so the whole stack grows
 * together.
 *
 * <p>This is the one thing the plugin moves rather than adds to, and it is
 * done in the way that survives the client disagreeing.
 *
 * <p>The sizes are read from where the client actually laid the widgets out,
 * not from what they asked for. An offer box is sized as a proportion of its
 * container rather than in pixels, so writing a pixel height into it changed
 * nothing at all -- the container grew and the boxes did not, which is exactly
 * what that looks like on screen. Position and size are pinned to absolute
 * before anything is written, and put back afterwards.
 *
 * <p>Client thread only.
 */
@Slf4j
class GeSlotLayout
{
	/**
	 * How much taller each box gets: one line of the small font, plus a
	 * little. Zero here switches the whole thing off and gives the exchange
	 * back exactly the layout it shipped with.
	 */
	private static final int EXTRA = 14;

	/**
	 * Everything between a box and the window, outermost last. Each grows by
	 * the total the rows gained, or the last row of boxes ends up behind the
	 * frame rather than inside it.
	 */
	private static final int[] AROUND = {
		InterfaceID.GeOffers.INDEX,
		InterfaceID.GeOffers.CONTENTS,
		InterfaceID.GeOffers.FRAME,
		InterfaceID.GeOffers.UNIVERSE,
	};

	private final Client client;
	private final FlippingRsConfig config;

	/** What was done to one widget, and what it was before. */
	private static final class Change
	{
		int baseY = -1;
		int baseHeight = -1;
		int baseYMode = -1;
		int baseHeightMode = -1;
		int wroteY = Integer.MIN_VALUE;
		int wroteHeight = Integer.MIN_VALUE;
	}

	private final Change[] boxes = new Change[GeItems.SLOTS.length];
	private final Change[] around = new Change[AROUND.length];

	GeSlotLayout(Client client, FlippingRsConfig config)
	{
		this.client = client;
		this.config = config;
		for (int i = 0; i < boxes.length; i++)
		{
			boxes[i] = new Change();
		}
		for (int i = 0; i < around.length; i++)
		{
			around[i] = new Change();
		}
	}

	/**
	 * Grows the boxes and everything holding them, or puts them back if there
	 * is nothing to grow them for.
	 *
	 * <p>Never throws. It runs from the event bus on the game thread, where an
	 * exception becomes an uncaught plugin error on every tick.
	 */
	void update()
	{
		try
		{
			if (EXTRA <= 0 || !config.setupOverlay())
			{
				reset();
				return;
			}
			final Widget[] widgets = slotWidgets();
			if (widgets == null)
			{
				return;
			}
			for (int slot = 0; slot < widgets.length; slot++)
			{
				capture(widgets[slot], boxes[slot]);
			}
			final int[] rows = rowsOf();
			int deepest = 0;
			for (int slot = 0; slot < widgets.length; slot++)
			{
				if (widgets[slot] == null || boxes[slot].baseY < 0)
				{
					continue;
				}
				deepest = Math.max(deepest, rows[slot] + 1);
				apply(widgets[slot], boxes[slot],
					boxes[slot].baseY + rows[slot] * EXTRA, boxes[slot].baseHeight + EXTRA);
			}
			for (int i = 0; i < AROUND.length; i++)
			{
				final Widget holder = client.getWidget(AROUND[i]);
				if (holder == null || holder.isHidden())
				{
					continue;
				}
				capture(holder, around[i]);
				if (around[i].baseHeight >= 0)
				{
					apply(holder, around[i], around[i].baseY, around[i].baseHeight + deepest * EXTRA);
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not make room in the offer boxes", e);
		}
	}

	/**
	 * Puts the layout back the way the game had it.
	 *
	 * <p>Only what this put there. A widget the client has since laid out
	 * again is already the game's, and writing an old size back over it would
	 * make it the wrong shape rather than the right one.
	 */
	void reset()
	{
		try
		{
			final Widget[] widgets = slotWidgets();
			if (widgets != null)
			{
				for (int slot = 0; slot < widgets.length; slot++)
				{
					restore(widgets[slot], boxes[slot]);
				}
			}
			for (int i = 0; i < AROUND.length; i++)
			{
				restore(client.getWidget(AROUND[i]), around[i]);
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not put the offer boxes back", e);
		}
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
	 * Reads the client's own layout for a widget it has laid out again since
	 * this last wrote to it.
	 *
	 * <p>From where it ended up rather than from what it asked for. These
	 * widgets are sized and placed as proportions of what holds them, so their
	 * declared height is not a number of pixels and adding pixels to it means
	 * nothing.
	 */
	private static void capture(@Nullable Widget widget, Change change)
	{
		if (widget == null)
		{
			return;
		}
		if (widget.getRelativeY() != change.wroteY || widget.getHeight() != change.wroteHeight)
		{
			change.baseY = widget.getRelativeY();
			change.baseHeight = widget.getHeight();
			change.baseYMode = widget.getYPositionMode();
			change.baseHeightMode = widget.getHeightMode();
		}
	}

	/**
	 * Puts a widget at a pixel position and a pixel height, pinning both to
	 * absolute so the numbers mean what they say.
	 */
	private static void apply(Widget widget, Change change, int y, int height)
	{
		if (widget.getRelativeY() == y && widget.getHeight() == height)
		{
			change.wroteY = y;
			change.wroteHeight = height;
			return;
		}
		widget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		widget.setHeightMode(WidgetSizeMode.ABSOLUTE);
		widget.setOriginalY(y);
		widget.setOriginalHeight(height);
		widget.revalidate();
		change.wroteY = widget.getRelativeY();
		change.wroteHeight = widget.getHeight();
	}

	private static void restore(@Nullable Widget widget, Change change)
	{
		if (widget == null || change.baseHeight < 0)
		{
			return;
		}
		if (widget.getRelativeY() == change.wroteY && widget.getHeight() == change.wroteHeight)
		{
			widget.setYPositionMode(change.baseYMode);
			widget.setHeightMode(change.baseHeightMode);
			widget.setOriginalY(change.baseY);
			widget.setOriginalHeight(change.baseHeight);
			widget.revalidate();
		}
		change.baseY = -1;
		change.baseHeight = -1;
		change.wroteY = Integer.MIN_VALUE;
		change.wroteHeight = Integer.MIN_VALUE;
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
		for (Change change : boxes)
		{
			if (change.baseY >= 0 && !seen.contains(change.baseY))
			{
				seen.add(change.baseY);
			}
		}
		final int[] tops = seen.stream().mapToInt(Integer::intValue).sorted().toArray();
		final int[] rows = new int[boxes.length];
		for (int slot = 0; slot < boxes.length; slot++)
		{
			rows[slot] = Math.max(0, Arrays.binarySearch(tops, boxes[slot].baseY));
		}
		return rows;
	}
}
