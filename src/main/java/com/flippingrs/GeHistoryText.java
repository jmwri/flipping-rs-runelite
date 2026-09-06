package com.flippingrs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * The site's prices on each row of the Grand Exchange history, added to the
 * row's own text.
 *
 * <p>On the row's text rather than in a widget of this plugin's own, for the
 * same reason as the offer setup screen: a line the client wrote is already
 * placed, sized, scrolled and clipped by the list that owns it. A caption
 * placed alongside had to have its position worked out from the row's, which
 * is a guess about somebody else's layout -- and on a scrolling list it is a
 * guess that has to stay right through every scroll.
 *
 * <p>A row whose item has no price is left exactly as the game wrote it.
 *
 * <p>Client thread only.
 */
@Slf4j
class GeHistoryText
{
	/** How many rows to price. The history shows far fewer than this. */
	private static final int MAX_ROWS = 64;

	/** Colours, written as the game's own text renderer reads them. */
	private static final String MUTED = "9f9f9f";
	private static final String GOOD = "4caf50";
	private static final String BAD = "d32f2f";

	private final Client client;
	private final FlippingRsConfig config;
	private final IntFunction<Quote> quoteFor;

	/**
	 * One per row, in the order the rows come in. Each remembers the line it
	 * is adding to and what that line said before, so a list the client
	 * rewrites is added to once rather than again and again.
	 */
	private final List<Appended> rows = new ArrayList<>();

	GeHistoryText(Client client, FlippingRsConfig config, IntFunction<Quote> quoteFor)
	{
		this.client = client;
		this.config = config;
		this.quoteFor = quoteFor;
	}

	/**
	 * Brings the additions in line with the rows.
	 *
	 * <p>Called every tick the history is open, because the client rebuilds
	 * and rewrites this list freely and nothing announces it.
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
				reset();
				return;
			}
			final java.util.List<Widget> children = RowText.under(list);
			if (children.isEmpty())
			{
				reset();
				return;
			}
			int index = 0;
			for (Widget icon : children)
			{
				if (index >= MAX_ROWS)
				{
					break;
				}
				if (icon.getItemId() <= 0)
				{
					continue;
				}
				final Widget text = textOn(children, icon);
				if (text == null)
				{
					continue;
				}
				final Quote quote = quoteFor.apply(icon.getItemId());
				final Appended row = index < rows.size() ? rows.get(index) : add();
				if (quote == null)
				{
					// Nothing to say about this item, so its row is the game's.
					row.clear();
				}
				else
				{
					row.to(text, "  " + textFor(quote));
				}
				index++;
			}
			// A list that got shorter leaves rows with nothing to sit on.
			for (int i = index; i < rows.size(); i++)
			{
				rows.get(i).clear();
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not put prices on the history rows", e);
		}
	}

	/** Puts every row back the way the game had it. */
	void reset()
	{
		for (Appended row : rows)
		{
			try
			{
				row.clear();
			}
			catch (RuntimeException e)
			{
				log.debug("could not clear a history row", e);
			}
		}
	}

	private Appended add()
	{
		final Appended row = new Appended();
		rows.add(row);
		return row;
	}

	/**
	 * The line of text belonging to the row an item icon is on.
	 *
	 * <p>A row is not one widget: it is an icon and one or more pieces of text
	 * laid out at the same height, and only their positions say which belong
	 * together. The band is the icon's own height grown by half again, because
	 * a row's text is not always boxed to the same height as its picture --
	 * requiring it to start inside the icon exactly is what made this find
	 * nothing at all.
	 */
	@Nullable
	private static Widget textOn(java.util.List<Widget> children, Widget icon)
	{
		final int height = Math.max(1, icon.getHeight());
		final int slack = height / 2;
		return RowText.lastIn(children, icon.getRelativeY() - slack,
			icon.getRelativeY() + height + slack);
	}

	/**
	 * What one row gains: the two prices and the margin, which is what turns a
	 * list of what you did into a list of what would be worth doing again.
	 *
	 * <p>Rounded rather than exact. A history row is already a sentence and
	 * these go on the end of it; the exact figures belong on the screen where
	 * a price is being typed, not on the one being read back.
	 *
	 * <p>Static, so the wording is pinned by a test rather than by running a
	 * client.
	 */
	static String textFor(Quote quote)
	{
		return colour("(", MUTED) + colour(FlippingRsPanel.gp(quote.getBuyAt()), MUTED)
			+ colour(" / ", MUTED) + colour(FlippingRsPanel.gp(quote.getSellAt()), MUTED)
			+ colour("  ", MUTED)
			+ colour(FlippingRsPanel.signed(quote.getNetMargin()),
				quote.getNetMargin() >= 0 ? GOOD : BAD)
			+ colour(")", MUTED);
	}

	/** One run of text in one colour, as the game's own text renderer reads it. */
	static String colour(String text, String hex)
	{
		return "<col=" + hex + ">" + text + "</col>";
	}

	/** How many rows are being added to, for a test that has a client. */
	int countForTest()
	{
		return rows.size();
	}
}
