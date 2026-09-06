package com.flippingrs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

/**
 * The site's prices on each row of the Grand Exchange history, written into
 * the list rather than drawn on top of it.
 *
 * <p>The history is the second screen worth injecting into, and for a reason
 * the offer setup screen did not have: it scrolls. Paint on a scrolling list
 * has to work out for itself which rows are visible and clip to the viewport,
 * and it is wrong the moment it gets either of those slightly off. A child of
 * the list scrolls and clips because the client does it, which is not an
 * improvement in tidiness -- it is the difference between a caption that
 * follows its row and one that does not.
 *
 * <p>One text widget per row, right-aligned so it sits opposite the item and
 * its quantity rather than over them. A row whose item has no price gets an
 * empty one, not a missing one: the widgets are paired with the rows by
 * position, and a list with gaps in it would have to be rebuilt whenever a
 * price arrived.
 *
 * <p>Client thread only.
 */
@Slf4j
class GeHistoryText
{
	/** Space between the caption and the right edge of the list. */
	private static final int MARGIN = 8;

	private final Client client;
	private final FlippingRsConfig config;
	private final IntFunction<Quote> quoteFor;

	/**
	 * The captions this plugin added, in the order of the rows they belong to.
	 * Emptied whenever the client rebuilds the list out from under them.
	 */
	private final List<Widget> captions = new ArrayList<>();

	GeHistoryText(Client client, FlippingRsConfig config, IntFunction<Quote> quoteFor)
	{
		this.client = client;
		this.config = config;
		this.quoteFor = quoteFor;
	}

	/**
	 * Brings the captions in line with the rows.
	 *
	 * <p>Called every tick the history is open. The list is rebuilt whenever
	 * it is reopened or its contents change, which throws these away; noticing
	 * that here rather than hooking every path that can do it is what stops a
	 * rebuild nobody predicted leaving the screen bare.
	 *
	 * <p>Never throws. It runs from the event bus on the game thread, where an
	 * exception becomes an uncaught plugin error on every tick.
	 */
	void update()
	{
		try
		{
			final Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
			if (list == null || list.isHidden() || !config.setupOverlay())
			{
				captions.clear();
				return;
			}
			final List<Widget> rows = rowsOf(list);
			if (rows.isEmpty())
			{
				captions.clear();
				return;
			}
			if (!ours(list))
			{
				// The client rebuilt the list, so what was made before is no
				// longer in it. Anything held from then points at a widget
				// nothing will draw.
				captions.clear();
			}
			for (int i = 0; i < rows.size(); i++)
			{
				final Widget row = rows.get(i);
				final Widget caption = i < captions.size() ? captions.get(i) : make(list);
				if (caption == null)
				{
					return;
				}
				if (i >= captions.size())
				{
					captions.add(caption);
				}
				draw(list, row, caption);
			}
			// A list that got shorter leaves captions with no row to sit on.
			for (int i = rows.size(); i < captions.size(); i++)
			{
				captions.get(i).setHidden(true);
				captions.get(i).setText("");
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not put prices on the history rows", e);
		}
	}

	/** Forgets the captions, for a screen that has closed or a plugin stopping. */
	void reset()
	{
		for (Widget caption : captions)
		{
			try
			{
				caption.setHidden(true);
				caption.setText("");
			}
			catch (RuntimeException e)
			{
				log.debug("could not clear a history caption", e);
			}
		}
		captions.clear();
	}

	/**
	 * The rows of the list: its children that are drawing an item.
	 *
	 * <p>The captions this class adds are text rather than items, so they are
	 * not mistaken for rows and the two lists stay the same length.
	 */
	private static List<Widget> rowsOf(Widget list)
	{
		final List<Widget> rows = new ArrayList<>();
		final Widget[] children = list.getDynamicChildren();
		if (children == null)
		{
			return rows;
		}
		for (Widget child : children)
		{
			if (child != null && child.getItemId() > 0 && !child.isHidden())
			{
				rows.add(child);
			}
		}
		return rows;
	}

	/** Whether the captions held are still children of the list that is up now. */
	private boolean ours(Widget list)
	{
		return !captions.isEmpty() && captions.get(0).getParent() == list;
	}

	@Nullable
	private Widget make(Widget list)
	{
		final Widget caption = list.createChild(-1, WidgetType.TEXT);
		if (caption == null)
		{
			return null;
		}
		caption.setFontId(FontID.PLAIN_11);
		caption.setTextShadowed(true);
		// Right-aligned against the far edge of the list, opposite the item and
		// its quantity, so it fills the space the row leaves rather than
		// sitting on anything the row is already using.
		caption.setXTextAlignment(WidgetTextAlignment.RIGHT);
		// Along the bottom of the row, not down the middle of it. A row already
		// has its own text through the middle, and a caption centred there
		// lands on top of it.
		caption.setYTextAlignment(WidgetTextAlignment.BOTTOM);
		return caption;
	}

	/** Puts one caption on one row, saying nothing for an item with no price. */
	private void draw(Widget list, Widget row, Widget caption)
	{
		final Quote quote = quoteFor.apply(row.getItemId());
		final String text = quote == null ? "" : textFor(quote);
		caption.setHidden(text.isEmpty());
		caption.setText(text);
		caption.setTextColor(quote == null ? 0 : colourFor(quote));
		// The row's own place in the list, so the caption scrolls with it: the
		// client offsets every child of a scrolling list by the same amount,
		// and clips them all to the same viewport.
		caption.setOriginalX(0);
		caption.setOriginalY(row.getRelativeY());
		caption.setOriginalWidth(Math.max(0, list.getWidth() - MARGIN));
		caption.setOriginalHeight(Math.max(1, row.getHeight()));
		caption.revalidate();
	}

	/**
	 * What one row says: the two prices and the margin, which is what turns a
	 * list of what you did into a list of what it would be worth doing again.
	 *
	 * <p>Static, so the wording is pinned by a test rather than by running a
	 * client.
	 */
	static String textFor(Quote quote)
	{
		return FlippingRsPanel.gp(quote.getBuyAt()) + " / " + FlippingRsPanel.gp(quote.getSellAt())
			+ "  " + FlippingRsPanel.signed(quote.getNetMargin());
	}

	/** Green for a margin worth having, red for one that is not. */
	static int colourFor(Quote quote)
	{
		return quote.getNetMargin() >= 0 ? 0x4caf50 : 0xd32f2f;
	}

	/** How many captions are up, for a test that has a client. */
	int countForTest()
	{
		return captions.size();
	}
}
