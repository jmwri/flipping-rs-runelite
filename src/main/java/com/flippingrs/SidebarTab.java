package com.flippingrs;

import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/**
 * One tab of the sidebar: what it shows, and when it is worth drawing.
 *
 * <p>Four of the five tabs are off screen at any moment, and the lists on
 * them are the expensive part of this panel: a row is a card of several
 * labels and, on two of the tabs, a pair of buttons. Two hundred open
 * positions measure at around 98ms to build, and that was once paid on every
 * journal read whichever tab the user was actually looking at.
 *
 * <p>So a tab that is off screen does not draw. It remembers that its data
 * moved on, and draws once, when the user selects it. The setters call
 * {@link #refresh} and never {@link #redraw}, which is what keeps that true
 * as tabs gain new things to show.
 *
 * <p>Swing thread throughout. Nothing here is touched from anywhere else.
 */
abstract class SidebarTab
{
	/** Whether this is the tab whose contents are on screen. */
	private boolean showing;

	/** Whether the data moved on while nobody could see it. */
	private boolean stale;

	/**
	 * When this tab's data last arrived, or 0 for never.
	 *
	 * <p>Wall-clock rather than nanoTime, because what this becomes is words
	 * about how long ago something happened, and the arithmetic is done on the
	 * Swing thread a second at a time. A clock that steps is a line that reads
	 * oddly once; a monotonic one here would buy nothing.
	 */
	private long updatedAt;

	/** Why nothing is being read at all, if that is the case. */
	@Nullable
	private String pausedWhy;

	/**
	 * The line at the foot of the tab saying how fresh what is above it is.
	 *
	 * <p>Every tab here shows the server's answer to a question, and the
	 * answer's age is part of it: a margin from four minutes ago is a
	 * different thing to act on than the same margin from four seconds ago,
	 * and nothing else on the tab says which it is. Dim, because it qualifies
	 * what is above rather than competing with it.
	 */
	private final JLabel refreshLine = FlippingRsPanel.small("");

	{
		refreshLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
	}

	/** The tab's contents. Built once, when the panel is built. */
	abstract JPanel body();

	/**
	 * Draws the tab from whatever the setters have stored.
	 *
	 * <p>Only ever called while this is the tab on screen, so an implementation
	 * never has to ask. Tabs that write straight to their labels -- Account,
	 * whose few lines cost nothing to set -- have nothing to do here.
	 */
	void redraw()
	{
	}

	/**
	 * The panel saying whether this is now the tab on screen. A tab that
	 * becomes visible with data it has not drawn draws it now.
	 */
	final void showing(boolean nowShowing)
	{
		showing = nowShowing;
		if (nowShowing && stale)
		{
			stale = false;
			redraw();
		}
	}

	/**
	 * Whether the plugin is reading from the server at all, and why not.
	 *
	 * <p>A reason arriving means the rows this tab is holding are about to
	 * become a picture of a journal nobody is looking at, so the tabs that
	 * show the server's data drop them. Null means reading has resumed.
	 * Activity has nothing to drop -- it is the one tab that is not the
	 * server's -- and Account is where the reason is explained.
	 *
	 * <p>Final, so that the freshness line goes quiet with the rest of it. A
	 * countdown to a read that is not going to happen is the most confident
	 * thing on a tab that has just said it is not reading.
	 */
	final void paused(@Nullable String why)
	{
		pausedWhy = why;
		onPaused(why);
		tick(System.currentTimeMillis());
	}

	/** What a tab does with that, if anything. */
	void onPaused(@Nullable String why)
	{
	}

	// ------------------------------------------------------------ freshness

	/**
	 * The label to put at the foot of the tab. Built here, placed by the tab,
	 * because where it belongs depends on what is above it.
	 */
	final JLabel refreshLine()
	{
		return refreshLine;
	}

	/**
	 * How often this tab's data is re-read on a timer, or 0 when nothing is
	 * scheduled.
	 *
	 * <p>Only the quotes are on a clock. Everything else is read because
	 * something happened -- a trade was recorded, the sidebar was opened, a
	 * key was entered -- and a countdown to a moment nobody has scheduled
	 * would be a number this plugin made up.
	 */
	long refreshEverySeconds()
	{
		return 0;
	}

	/**
	 * What brings the next read, for a tab that has no timer. Null for a tab
	 * that shows nothing of the server's.
	 */
	@Nullable
	String refreshedBy()
	{
		return null;
	}

	/** This tab's data has just arrived. */
	final void stamp()
	{
		updatedAt = System.currentTimeMillis();
		tick(updatedAt);
	}

	/**
	 * Brings the line up to date. Called a second at a time by the panel, for
	 * the showing tab only: five labels counting down behind a tab nobody is
	 * looking at is work for no reader.
	 */
	final void tick(long nowMs)
	{
		// Wrapped, like every other line here. The sidebar is 205 pixels and
		// this one is a sentence; set plainly it would run off the edge, which
		// is what the width test caught the first time.
		FlippingRsPanel.setWrappedText(refreshLine, freshnessText(nowMs));
	}

	/**
	 * "Updated just now · next in 22s", or "Updated 4m ago · next when a trade
	 * is recorded", or nothing at all.
	 *
	 * <p>Nothing before the first read, because "updated never" is not what
	 * somebody wants to be told about a tab that is loading, and the tab's own
	 * summary already says it has nothing yet.
	 */
	final String freshnessText(long nowMs)
	{
		if (pausedWhy != null || updatedAt == 0 || refreshedBy() == null && refreshEverySeconds() == 0)
		{
			return "";
		}
		final long seconds = Math.max(0, TimeUnit.MILLISECONDS.toSeconds(nowMs - updatedAt));
		final String age = GeOfferText.age(seconds);
		final StringBuilder out = new StringBuilder("Updated ").append(age == null ? "just now" : age);
		final long every = refreshEverySeconds();
		if (every > 0)
		{
			final long left = every - seconds;
			// "Due now" rather than a negative, or a zero that sits there. The
			// read is a request over a network and the tick that starts it is
			// a fixed delay from the last one finishing, so the last second of
			// the count is a moment this cannot be exact about.
			out.append(" · next ").append(left > 0 ? "in " + left + "s" : "due now");
		}
		else
		{
			out.append(" · next ").append(refreshedBy());
		}
		return out.toString();
	}

	/** The line as it now reads, for a test that does not want to wait a second. */
	final String refreshLineForTest()
	{
		return refreshLine.getText();
	}

	/**
	 * The data changed. Draws it if anyone can see it, and otherwise leaves a
	 * note to draw it when they next can.
	 */
	final void refresh()
	{
		if (showing)
		{
			stale = false;
			redraw();
			return;
		}
		stale = true;
	}
}
