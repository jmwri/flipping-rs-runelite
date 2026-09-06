package com.flippingrs;

// The panel's shared vocabulary: the layout helpers every tab builds
// rows out of, and the static text of a row, which the tests pin there.
import static com.flippingrs.FlippingRsPanel.*;

import java.awt.Component;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.annotation.Nullable;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The Analytics tab: what the last seven days came to.
 *
 * <p>Its own tab because it is its own page on flippingrs.com, and the two
 * questions are genuinely different: what you are holding right now is
 * Positions, and whether the week went well is this. They used to share a tab
 * here, which meant scrolling past the verdict to reach the lots and past the
 * lots to reach the verdict.
 *
 * <p>Every figure is the server's. The plugin does not add a column up.
 *
 * <p>A few labels and no list, so there is nothing here worth deferring: it
 * draws as the setters are called, whether or not this is the tab on screen.
 */
final class AnalyticsTab extends SidebarTab
{
	private final JLabel week = new JLabel();
	private boolean loaded;
	@Nullable
	private String problem;
	/** Why nothing is being read at all, if that is the case. The panel sets it. */
	@Nullable
	private String paused;
	private final JPanel body;

	AnalyticsTab()
	{
		final JPanel body = column();
		body.add(hint("How your last seven days went, worked out by flippingrs.com."));
		body.add(header("Last 7 days"));
		body.add(Box.createVerticalStrut(4));
		week.setFont(FontManager.getRunescapeSmallFont());
		week.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		week.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(week);
		this.body = body;
		draw();
	}

	@Override
	JPanel body()
	{
		return body;
	}

	@Override
	void paused(@Nullable String why)
	{
		paused = why;
		if (why != null)
		{
			loaded = false;
			problem = null;
		}
		draw();
	}

	/** The week's verdict, as the server works it out. */
	void setWeek(Analytics figures)
	{
		problem = null;
		loaded = true;
		setWrappedText(week, summarise(figures));
		// A losing week is worth saying in the colour of a loss. The server
		// decides whether it was one; this only paints it.
		week.setForeground(figures.getRealisedProfit() < 0
			? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** The journal could not be read. Shown in the tab itself. */
	void setProblem(String why)
	{
		problem = why;
		loaded = false;
		draw();
	}

	/**
	 * The line, for the states that are not a loaded week: nothing being read,
	 * a read that failed, or nothing read yet. A week that did load writes the
	 * line itself, in {@link #setWeek}.
	 */
	private void draw()
	{
		if (paused != null)
		{
			setWrappedText(week, paused);
			week.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else if (problem != null)
		{
			setWrappedText(week, "Couldn't load your journal: " + problem);
			week.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		}
		else if (!loaded)
		{
			setWrappedText(week, "Not loaded yet.");
			week.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
	}

	// ---------------------------------------------------------- test seams

	String summaryForTest()
	{
		return week.getText();
	}

	@Nullable
	String problemForTest()
	{
		return problem;
	}
}
