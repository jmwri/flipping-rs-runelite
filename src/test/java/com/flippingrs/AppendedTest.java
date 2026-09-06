package com.flippingrs;

import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Adding to a line the game owns, and giving it back.
 *
 * <p>The whole difficulty is that the client rewrites these lines whenever it
 * likes and knows nothing about what was appended to them. Every failure here
 * is one somebody would see: a line that grows another copy of its prices
 * every tick, one that keeps prices for an item that is no longer on it, or
 * one that never goes back to what the game wrote.
 */
public class AppendedTest
{
	/** A text widget that behaves like the client's: setText is what getText returns. */
	private static Widget line(String text)
	{
		final Widget widget = mock(Widget.class);
		final String[] value = {text};
		when(widget.getText()).thenAnswer(inv -> value[0]);
		when(widget.setText(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv ->
		{
			value[0] = inv.getArgument(0);
			return widget;
		});
		return widget;
	}

	@Test
	public void theAdditionGoesOnTheEnd()
	{
		final Widget widget = line("Bought 100 x Fire rune");
		final Appended appended = new Appended();

		appended.to(widget, " (5gp)");

		assertEquals("Bought 100 x Fire rune (5gp)", widget.getText());
	}

	/**
	 * The same call every tick leaves one copy, not one per tick. This is the
	 * failure the class exists to prevent, and the one that would be most
	 * obvious on screen.
	 */
	@Test
	public void writingTheSameThingAgainDoesNotDoubleIt()
	{
		final Widget widget = line("Bought 100 x Fire rune");
		final Appended appended = new Appended();

		for (int i = 0; i < 5; i++)
		{
			appended.to(widget, " (5gp)");
		}

		assertEquals("Bought 100 x Fire rune (5gp)", widget.getText());
	}

	/** A changed addition replaces the old one rather than following it. */
	@Test
	public void aNewAdditionReplacesTheOldOne()
	{
		final Widget widget = line("Bought 100 x Fire rune");
		final Appended appended = new Appended();

		appended.to(widget, " (5gp)");
		appended.to(widget, " (6gp)");

		assertEquals("Bought 100 x Fire rune (6gp)", widget.getText());
	}

	/**
	 * The client rewriting the line is taken as the game's new text, not as
	 * something to append to again.
	 *
	 * <p>This is the case that separates a working version from one that looks
	 * like it works: without it the row keeps the previous item's prices
	 * inside its "original" forever, and every rewrite buries them deeper.
	 */
	@Test
	public void aRewriteByTheClientBecomesTheNewOriginal()
	{
		final Widget widget = line("Bought 100 x Fire rune");
		final Appended appended = new Appended();
		appended.to(widget, " (5gp)");

		// The list scrolled and the client put a different trade on this row.
		widget.setText("Sold 1 x Abyssal whip");
		appended.to(widget, " (1.5M)");

		assertEquals("Sold 1 x Abyssal whip (1.5M)", widget.getText());
	}

	/** Moving to a different widget leaves the first one alone. */
	@Test
	public void movingToAnotherLineStartsAgain()
	{
		final Widget first = line("Bought 100 x Fire rune");
		final Widget second = line("Sold 1 x Abyssal whip");
		final Appended appended = new Appended();

		appended.to(first, " (5gp)");
		appended.to(second, " (1.5M)");

		assertEquals("Sold 1 x Abyssal whip (1.5M)", second.getText());
	}

	@Test
	public void clearingPutsTheGamesTextBack()
	{
		final Widget widget = line("Bought 100 x Fire rune");
		final Appended appended = new Appended();
		appended.to(widget, " (5gp)");

		appended.clear();

		assertEquals("Bought 100 x Fire rune", widget.getText());
		assertNull("and it is holding nothing", appended.textForTest());
	}

	/**
	 * A line the client has since rewritten is already the game's, and is left
	 * as it found it. Restoring over it would put back text belonging to an
	 * item that is no longer on that row.
	 */
	@Test
	public void clearingDoesNotUndoTheClientsOwnRewrite()
	{
		final Widget widget = line("Bought 100 x Fire rune");
		final Appended appended = new Appended();
		appended.to(widget, " (5gp)");

		widget.setText("Sold 1 x Abyssal whip");
		appended.clear();

		assertEquals("Sold 1 x Abyssal whip", widget.getText());
	}

	/** Clearing something that was never added to is not a crash. */
	@Test
	public void clearingNothingIsFine()
	{
		new Appended().clear();
	}
}
