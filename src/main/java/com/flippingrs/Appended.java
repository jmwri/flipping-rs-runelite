package com.flippingrs;

import java.util.Objects;
import javax.annotation.Nullable;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * Text put on the end of a line the game owns.
 *
 * <p>Adding to a widget the client wrote is the least invasive way to say
 * something in an interface: the line is already positioned, already sized,
 * already wrapping and clipping the way that screen does it, and none of that
 * has to be worked out again or kept right as Jagex moves it. A widget of
 * one's own has to be placed, and a placement is a guess about somebody
 * else's layout that goes stale without warning.
 *
 * <p>The whole difficulty is that the client rewrites these lines whenever it
 * likes, and it does not know or care what was appended to them. So this holds
 * what the line said before, and notices when the client has written something
 * new: anything not matching what was last put there is taken as the game's
 * text rather than as something to append to again. Without that, a line grows
 * a second copy of the addition every time it is refreshed.
 *
 * <p>Client thread only.
 */
final class Appended
{
	/** One row of the small font, which is what every addition here is made of. */
	private static final int LINE = 12;

	/** The line being added to, or null if nothing is. */
	@Nullable
	private Widget widget;

	/** The game's own text, without the addition. */
	@Nullable
	private String original;

	/** What was last written, so a rewrite by the client can be told from it. */
	@Nullable
	private String written;

	/** The line's own height before it was given room, or -1 if it was not. */
	private int baseHeight = -1;
	private int baseHeightMode = -1;
	private int wroteHeight = Integer.MIN_VALUE;

	/**
	 * Puts {@code extra} on the end of a widget's text, replacing whatever was
	 * put there before.
	 *
	 * <p>Safe to call every tick with the same arguments: it writes only when
	 * the result would differ from what is already there.
	 */
	void to(Widget target, String extra)
	{
		to(target, extra, 0);
	}

	/**
	 * The same, giving the line room for the rows the addition puts on it.
	 *
	 * <p>A line the game wrote is exactly as tall as what the game put in it.
	 * Adding a row to it with no more height draws that row over whatever is
	 * under the line -- which is what a second line of prices did to the one
	 * below it on an offer box.
	 *
	 * <p>Only ever grown, never shrunk below what the client asked for, and
	 * put back by {@link #clear}.
	 *
	 * @param rows how many extra rows the addition takes, or 0 for none
	 */
	void to(Widget target, String extra, int rows)
	{
		if (target != widget)
		{
			// A different line, or the same one rebuilt into a new widget.
			widget = target;
			original = target.getText();
			written = null;
			baseHeight = -1;
		}
		else if (!Objects.equals(target.getText(), written))
		{
			// The client wrote something new here. Whatever it is, it is the
			// game's text now -- appending to the previous composition instead
			// would keep a copy of the addition forever.
			original = target.getText();
		}
		final String composed = (original == null ? "" : original) + extra;
		if (!composed.equals(target.getText()))
		{
			target.setText(composed);
		}
		written = composed;
		if (rows > 0)
		{
			room(target, rows);
		}
	}

	/**
	 * Makes the line tall enough for the rows added to it.
	 *
	 * <p>The height is read from where the client laid the line out rather
	 * than from what it asked for, and pinned to absolute before it is
	 * written: these widgets are often sized as a proportion of what holds
	 * them, and a pixel height written into one of those means nothing.
	 */
	private void room(Widget target, int rows)
	{
		if (target.getHeight() != wroteHeight)
		{
			// The client laid this out again, so its height is the game's.
			baseHeight = target.getHeight();
			baseHeightMode = target.getHeightMode();
		}
		if (baseHeight < 0)
		{
			return;
		}
		final int wanted = baseHeight + rows * LINE;
		if (target.getHeight() == wanted)
		{
			wroteHeight = wanted;
			return;
		}
		target.setHeightMode(WidgetSizeMode.ABSOLUTE);
		target.setOriginalHeight(wanted);
		target.revalidate();
		wroteHeight = target.getHeight();
	}

	/**
	 * Puts the line back the way the game had it, and forgets it.
	 *
	 * <p>Only if it still says what was written: a line the client has since
	 * rewritten is already the game's, and restoring over it would put back
	 * text belonging to an item that is no longer there.
	 */
	void clear()
	{
		if (widget != null && original != null && Objects.equals(widget.getText(), written))
		{
			widget.setText(original);
			if (baseHeight >= 0 && widget.getHeight() == wroteHeight)
			{
				widget.setHeightMode(baseHeightMode);
				widget.setOriginalHeight(baseHeight);
				widget.revalidate();
			}
		}
		widget = null;
		original = null;
		written = null;
		baseHeight = -1;
		wroteHeight = Integer.MIN_VALUE;
	}

	/** The text as it now stands, or null if nothing is being added to. */
	@Nullable
	String textForTest()
	{
		return widget == null ? null : widget.getText();
	}
}
