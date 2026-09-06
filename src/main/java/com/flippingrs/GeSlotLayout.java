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
 * <p>This is the one thing the plugin moves rather than adds to, and the way
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

	/** How many rows the prices take. */
	private static final int LINES = 3;

	/** How much taller each box gets. Zero switches the whole thing off. */
	private static final int EXTRA = LINE * LINES;

	/**
	 * Everything between a box and the window, innermost first. Each grows by
	 * the total the rows gained, or the last row of boxes ends up behind the
	 * frame instead of inside it.
	 */
	private static final int[] AROUND = {
		InterfaceID.GeOffers.INDEX,
		InterfaceID.GeOffers.CONTENTS,
		InterfaceID.GeOffers.FRAME,
		InterfaceID.GeOffers.UNIVERSE,
	};

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
	@Nullable
	private int[] aroundHeight;
	@Nullable
	private int[] aroundHeightMode;

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
			if (baseY == null && !read(widgets))
			{
				return;
			}
			final int[] rows = rowsOf();
			int deepest = 0;
			for (int slot = 0; slot < widgets.length; slot++)
			{
				if (widgets[slot] == null || baseHeight[slot] < 0)
				{
					continue;
				}
				deepest = Math.max(deepest, rows[slot] + 1);
				put(widgets[slot], baseY[slot] + rows[slot] * EXTRA, baseHeight[slot] + EXTRA);
			}
			for (int i = 0; i < AROUND.length; i++)
			{
				final Widget holder = client.getWidget(AROUND[i]);
				if (holder != null && !holder.isHidden() && aroundHeight[i] >= 0)
				{
					grow(holder, aroundHeight[i] + deepest * EXTRA);
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not make room in the offer boxes", e);
		}
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
			for (int i = 0; i < AROUND.length; i++)
			{
				final Widget holder = client.getWidget(AROUND[i]);
				if (holder != null && aroundHeight[i] >= 0)
				{
					holder.setHeightMode(aroundHeightMode[i]);
					holder.setOriginalHeight(aroundHeight[i]);
					holder.revalidate();
				}
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
		final int[] holders = new int[AROUND.length];
		final int[] holderModes = new int[AROUND.length];
		Arrays.fill(holders, -1);
		for (int i = 0; i < AROUND.length; i++)
		{
			final Widget holder = client.getWidget(AROUND[i]);
			if (holder != null && holder.getHeight() > 0)
			{
				holders[i] = holder.getHeight();
				holderModes[i] = holder.getHeightMode();
			}
		}
		baseY = y;
		baseHeight = height;
		baseYMode = yMode;
		baseHeightMode = heightMode;
		aroundHeight = holders;
		aroundHeightMode = holderModes;
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

	private static void grow(Widget holder, int height)
	{
		if (holder.getHeight() == height)
		{
			return;
		}
		holder.setHeightMode(WidgetSizeMode.ABSOLUTE);
		holder.setOriginalHeight(height);
		holder.revalidate();
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
		aroundHeight = null;
		aroundHeightMode = null;
	}
}
