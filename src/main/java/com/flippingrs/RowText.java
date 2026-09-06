package com.flippingrs;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.widgets.Widget;

/**
 * Finding the line of text that belongs to a thing on screen.
 *
 * <p>An exchange screen is not built out of rows. It is a flat bag of widgets
 * -- an item icon here, a name there, a quantity beside it -- and what makes
 * them a row is only that they were laid out at the same height. So a caption
 * that wants to be part of a row has to work out which of that bag it belongs
 * to, and the answer is geometric rather than structural.
 *
 * <p>Deliberately forgiving about where it looks. Children come in three
 * flavours and which one a screen uses is the screen's business; an earlier
 * version of this looked only at the dynamic ones and found nothing at all on
 * a screen that keeps its text elsewhere, which is indistinguishable from the
 * feature being off.
 *
 * <p>Client thread only.
 */
final class RowText
{
	private RowText()
	{
	}

	/**
	 * Every widget under a container, of whichever kind it keeps them in.
	 *
	 * <p>One level deep, and then one more: a screen that wraps each row in a
	 * layer keeps the text a level below where a flat one does, and looking
	 * only at the top level finds the wrappers rather than their contents.
	 */
	static List<Widget> under(@Nullable Widget container)
	{
		final List<Widget> all = new ArrayList<>();
		if (container == null)
		{
			return all;
		}
		for (Widget child : childrenOf(container))
		{
			all.add(child);
			all.addAll(childrenOf(child));
		}
		return all;
	}

	private static List<Widget> childrenOf(Widget widget)
	{
		final List<Widget> out = new ArrayList<>();
		for (Widget[] children : new Widget[][]{
			widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()})
		{
			if (children == null)
			{
				continue;
			}
			for (Widget child : children)
			{
				if (child != null && !child.isHidden())
				{
					out.add(child);
				}
			}
		}
		return out;
	}

	/**
	 * The line of text to add to, among widgets already gathered.
	 *
	 * <p>The lowest one that sits within the band, and the furthest right of
	 * those -- which is the end of what that row says, and so where something
	 * added to it reads as part of the same sentence rather than as an
	 * interruption of it.
	 *
	 * @param from  top of the band, in the same space as the widgets' relative
	 *              positions
	 * @param to    bottom of the band
	 */
	@Nullable
	static Widget lastIn(List<Widget> widgets, int from, int to)
	{
		Widget best = null;
		for (Widget widget : widgets)
		{
			if (!isText(widget))
			{
				continue;
			}
			final int middle = widget.getRelativeY() + widget.getHeight() / 2;
			if (middle < from || middle >= to)
			{
				continue;
			}
			if (best == null || lower(widget, best))
			{
				best = widget;
			}
		}
		return best;
	}

	/** Whether one widget reads as coming after another on the same row. */
	private static boolean lower(Widget candidate, Widget best)
	{
		if (candidate.getRelativeY() != best.getRelativeY())
		{
			return candidate.getRelativeY() > best.getRelativeY();
		}
		return candidate.getRelativeX() > best.getRelativeX();
	}

	/** Whether a widget is a line of text rather than an item or a picture. */
	static boolean isText(Widget widget)
	{
		if (widget.getItemId() > 0)
		{
			return false;
		}
		final String text = widget.getText();
		return text != null && !text.isEmpty();
	}
}
