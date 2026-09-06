package com.flippingrs;

import java.util.List;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * The site's prices on the exchange's own hover text.
 *
 * <p>The exchange puts a tooltip under the pointer in a few places, and it is
 * the one thing on screen that is already about the single item being pointed
 * at. Adding to it costs no room anywhere else and no attention until it is
 * asked for.
 *
 * <p>Which item that is does not come from the tooltip -- it says nothing
 * about what it is describing -- but from what the pointer is over, which
 * {@link GeItems} already answers for every screen the exchange has. The two
 * agree because the tooltip is only up while the pointer is on the thing it
 * belongs to.
 *
 * <p>Client thread only.
 */
@Slf4j
class GeTooltipText
{
	/**
	 * The exchange's tooltips, one per screen that has one. A screen that
	 * gains one is a constant added here rather than a class.
	 */
	private static final int[] TOOLTIPS = {
		InterfaceID.GeOffers.TOOLTIP,
		InterfaceID.GeCollect.TOOLTIP,
		InterfaceID.GeViewonly.TOOLTIP,
	};

	/** Colours, written as the game's own text renderer reads them. */
	private static final String MUTED = "9f9f9f";
	private static final String GOOD = "4caf50";
	private static final String BAD = "d32f2f";

	private final Client client;
	private final FlippingRsConfig config;
	private final IntFunction<Quote> quoteFor;

	/** What has been added to whichever tooltip is up. */
	private final Appended line = new Appended();

	GeTooltipText(Client client, FlippingRsConfig config, IntFunction<Quote> quoteFor)
	{
		this.client = client;
		this.config = config;
		this.quoteFor = quoteFor;
	}

	/**
	 * Brings the addition in line with what the pointer is over.
	 *
	 * <p>Never throws. It runs from the event bus on the game thread, where an
	 * exception becomes an uncaught plugin error on every tick.
	 */
	void update()
	{
		try
		{
			if (!config.setupOverlay())
			{
				line.clear();
				return;
			}
			final Widget text = shownTooltip();
			if (text == null)
			{
				line.clear();
				return;
			}
			final Point mouse = client.getMouseCanvasPosition();
			final int itemId = GeItems.under(client, mouse);
			final Quote quote = itemId > 0 ? quoteFor.apply(itemId) : null;
			if (quote == null)
			{
				line.clear();
				return;
			}
			line.to(text, "<br>" + textFor(quote));
		}
		catch (RuntimeException e)
		{
			log.debug("could not put prices on the exchange's hover text", e);
		}
	}

	/** Puts the hover text back the way the game had it. */
	void reset()
	{
		line.clear();
	}

	/**
	 * The line of the tooltip that is up, or null if none is.
	 *
	 * <p>A tooltip is a box with its text somewhere inside it, and which of
	 * its children carries that is the screen's business rather than something
	 * worth knowing here.
	 */
	@Nullable
	private Widget shownTooltip()
	{
		for (int id : TOOLTIPS)
		{
			final Widget tooltip = client.getWidget(id);
			if (tooltip == null || tooltip.isHidden())
			{
				continue;
			}
			if (RowText.isText(tooltip))
			{
				return tooltip;
			}
			final List<Widget> children = RowText.under(tooltip);
			final Widget text = RowText.lastIn(children, Integer.MIN_VALUE, Integer.MAX_VALUE);
			if (text != null)
			{
				return text;
			}
		}
		return null;
	}

	/**
	 * What the hover text gains: the two prices and the margin.
	 *
	 * <p>On its own line, because a tooltip is already a sentence about the
	 * item and this is a different thing being said about it. Static, so the
	 * wording is pinned by a test rather than by running a client.
	 */
	static String textFor(Quote quote)
	{
		return colour("Buy ", MUTED) + colour(FlippingRsPanel.gp(quote.getBuyAt()), MUTED)
			+ colour("  Sell ", MUTED) + colour(FlippingRsPanel.gp(quote.getSellAt()), MUTED)
			+ colour("  ", MUTED)
			+ colour(FlippingRsPanel.signed(quote.getNetMargin()),
				quote.getNetMargin() >= 0 ? GOOD : BAD);
	}

	/** One run of text in one colour, as the game's own text renderer reads it. */
	static String colour(String text, String hex)
	{
		return "<col=" + hex + ">" + text + "</col>";
	}
}
