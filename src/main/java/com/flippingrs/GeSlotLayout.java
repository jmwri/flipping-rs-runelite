package com.flippingrs;

import java.util.Arrays;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

/**
 * A little more room in each offer box, for the line added to it.
 *
 * <p>The boxes are sized for what the game puts in them, which is a name, a
 * progress bar and a couple of lines. An extra line has to come from
 * somewhere, and taking it from the box is better than letting the box clip
 * it: a price cut off halfway is worse than no price.
 *
 * <p>This is the one thing here that moves the game's own furniture rather
 * than adding to it, so it is done in the way that survives the client
 * disagreeing. The client lays these out again whenever it rebuilds the
 * screen, and it does not know or care what was changed; so what it last
 * wrote is remembered, anything that does not match is taken as the client's
 * own fresh layout, and the change is made from that rather than from the
 * already-changed value. Without that, every rebuild would add another
 * fourteen pixels until the boxes filled the screen.
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

	private final Client client;
	private final FlippingRsConfig config;

	/** The layout the client last wrote, per slot, or -1 for "not seen yet". */
	private final int[] baseY = new int[GeItems.SLOTS.length];
	private final int[] baseHeight = new int[GeItems.SLOTS.length];

	/** What was last written, so the client's own layout can be told from it. */
	private final int[] wroteY = new int[GeItems.SLOTS.length];
	private final int[] wroteHeight = new int[GeItems.SLOTS.length];

	/** The same for the box holding them all. */
	private int baseParentHeight = -1;
	private int wroteParentHeight = -1;

	GeSlotLayout(Client client, FlippingRsConfig config)
	{
		this.client = client;
		this.config = config;
		forget();
	}

	/**
	 * Grows the boxes, or puts them back if there is nothing to grow them for.
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
			final Widget[] boxes = boxes();
			if (boxes == null)
			{
				forget();
				return;
			}
			capture(boxes);
			final int[] order = rowsOf();
			for (int slot = 0; slot < boxes.length; slot++)
			{
				if (boxes[slot] == null || baseY[slot] < 0)
				{
					continue;
				}
				set(boxes[slot], slot, baseY[slot] + order[slot] * EXTRA, baseHeight[slot] + EXTRA);
			}
			grow(boxes[0].getParent(), order);
		}
		catch (RuntimeException e)
		{
			log.debug("could not make room in the offer boxes", e);
		}
	}

	/**
	 * Puts the layout back the way the game had it, for a screen that is gone
	 * or a plugin that is stopping.
	 */
	void reset()
	{
		try
		{
			final Widget[] boxes = boxes();
			if (boxes == null)
			{
				forget();
				return;
			}
			for (int slot = 0; slot < boxes.length; slot++)
			{
				final Widget box = boxes[slot];
				if (box == null || baseY[slot] < 0)
				{
					continue;
				}
				// Only what this put there. A box the client has since laid out
				// again is already the game's, and writing an old position
				// back over it would move it somewhere it never was.
				if (box.getOriginalY() == wroteY[slot] && box.getOriginalHeight() == wroteHeight[slot])
				{
					box.setOriginalY(baseY[slot]);
					box.setOriginalHeight(baseHeight[slot]);
					box.revalidate();
				}
			}
			final Widget parent = boxes[0] == null ? null : boxes[0].getParent();
			if (parent != null && baseParentHeight >= 0
				&& parent.getOriginalHeight() == wroteParentHeight)
			{
				parent.setOriginalHeight(baseParentHeight);
				parent.revalidate();
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not put the offer boxes back", e);
		}
		forget();
	}

	@Nullable
	private Widget[] boxes()
	{
		final Widget[] boxes = new Widget[GeItems.SLOTS.length];
		boolean any = false;
		for (int slot = 0; slot < boxes.length; slot++)
		{
			final Widget box = client.getWidget(GeItems.SLOTS[slot]);
			if (box != null && !box.isHidden())
			{
				boxes[slot] = box;
				any = true;
			}
		}
		return any ? boxes : null;
	}

	/**
	 * Reads the client's own layout for any box it has laid out again since
	 * this last wrote to it.
	 */
	private void capture(Widget[] boxes)
	{
		for (int slot = 0; slot < boxes.length; slot++)
		{
			final Widget box = boxes[slot];
			if (box == null)
			{
				continue;
			}
			if (box.getOriginalY() != wroteY[slot] || box.getOriginalHeight() != wroteHeight[slot])
			{
				baseY[slot] = box.getOriginalY();
				baseHeight[slot] = box.getOriginalHeight();
			}
		}
		final Widget parent = boxes[0] == null ? null : boxes[0].getParent();
		if (parent != null && parent.getOriginalHeight() != wroteParentHeight)
		{
			baseParentHeight = parent.getOriginalHeight();
		}
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
		final int[] tops = Arrays.stream(baseY).filter(y -> y >= 0).distinct().sorted().toArray();
		final int[] rows = new int[baseY.length];
		for (int slot = 0; slot < baseY.length; slot++)
		{
			rows[slot] = Math.max(0, Arrays.binarySearch(tops, baseY[slot]));
		}
		return rows;
	}

	private void set(Widget box, int slot, int y, int height)
	{
		if (box.getOriginalY() == y && box.getOriginalHeight() == height)
		{
			wroteY[slot] = y;
			wroteHeight[slot] = height;
			return;
		}
		box.setOriginalY(y);
		box.setOriginalHeight(height);
		box.revalidate();
		wroteY[slot] = y;
		wroteHeight[slot] = height;
	}

	/** Grows the box holding the slots, so the last row is not cut off by it. */
	private void grow(@Nullable Widget parent, int[] order)
	{
		if (parent == null || baseParentHeight < 0)
		{
			return;
		}
		int rows = 0;
		for (int row : order)
		{
			rows = Math.max(rows, row + 1);
		}
		final int height = baseParentHeight + rows * EXTRA;
		if (parent.getOriginalHeight() != height)
		{
			parent.setOriginalHeight(height);
			parent.revalidate();
		}
		wroteParentHeight = height;
	}

	/** Forgets the layout, without touching anything. */
	private void forget()
	{
		Arrays.fill(baseY, -1);
		Arrays.fill(baseHeight, -1);
		Arrays.fill(wroteY, Integer.MIN_VALUE);
		Arrays.fill(wroteHeight, Integer.MIN_VALUE);
		baseParentHeight = -1;
		wroteParentHeight = Integer.MIN_VALUE;
	}
}
