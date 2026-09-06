package com.flippingrs;

import javax.annotation.Nullable;
import javax.swing.JPanel;

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
	 */
	void paused(@Nullable String why)
	{
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
