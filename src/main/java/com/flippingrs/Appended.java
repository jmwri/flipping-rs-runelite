package com.flippingrs;

import java.util.Objects;
import javax.annotation.Nullable;
import net.runelite.api.widgets.Widget;

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
	/** The line being added to, or null if nothing is. */
	@Nullable
	private Widget widget;

	/** The game's own text, without the addition. */
	@Nullable
	private String original;

	/** What was last written, so a rewrite by the client can be told from it. */
	@Nullable
	private String written;

	/**
	 * Puts {@code extra} on the end of a widget's text, replacing whatever was
	 * put there before.
	 *
	 * <p>Safe to call every tick with the same arguments: it writes only when
	 * the result would differ from what is already there.
	 */
	void to(Widget target, String extra)
	{
		if (target != widget)
		{
			// A different line, or the same one rebuilt into a new widget.
			widget = target;
			original = target.getText();
			written = null;
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
		}
		widget = null;
		original = null;
		written = null;
	}

	/** The text as it now stands, or null if nothing is being added to. */
	@Nullable
	String textForTest()
	{
		return widget == null ? null : widget.getText();
	}
}
