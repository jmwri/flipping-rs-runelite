package com.flippingrs;

import java.util.function.IntFunction;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * The site's prices, shown when you point at something in the exchange.
 *
 * <p>A tooltip is where this belongs, and it took a while to get there. The
 * offer boxes are the size the game made them; six attempts at making one
 * taller found six separate things that had to be made taller with it -- the
 * layer, the containers above it, the window, the frame sprite, the tiling of
 * that sprite -- and each was found by somebody looking at their screen rather
 * than by anything here. A tooltip needs none of it: the exchange is left
 * exactly as the game drew it, and the prices have as much room as they need
 * because they are not inside anything.
 *
 * <p>It also answers a question rather than announcing one. Eight boxes each
 * carrying three lines is a wall of numbers about seven items nobody asked
 * about; pointing at one is the moment somebody wants to know.
 *
 * <p>Client thread. Built on every frame the pointer is over an item, which is
 * why the lines are assembled rather than formatted afresh.
 */
class GeTooltip
{
	private final Client client;
	private final FlippingRsConfig config;
	private final IntFunction<Quote> quoteFor;
	private final TooltipManager tooltips;

	GeTooltip(Client client, FlippingRsConfig config, IntFunction<Quote> quoteFor,
		TooltipManager tooltips)
	{
		this.client = client;
		this.config = config;
		this.quoteFor = quoteFor;
		this.tooltips = tooltips;
	}

	/**
	 * Offers a tooltip for whatever the pointer is on.
	 *
	 * <p>Nothing is shown for an item with no price, which is every item
	 * before the first fetch lands: an empty box under the pointer is worse
	 * than no box.
	 */
	void update()
	{
		if (!config.setupOverlay())
		{
			return;
		}
		final Point mouse = client.getMouseCanvasPosition();
		final int itemId = GeItems.under(client, mouse);
		if (itemId <= 0)
		{
			return;
		}
		final Quote quote = quoteFor.apply(itemId);
		if (quote == null)
		{
			return;
		}
		tooltips.add(new Tooltip(textFor(quote, offerOn(itemId))));
	}

	/**
	 * Your own offer on an item, if one of the eight slots has it.
	 *
	 * <p>Only the slots carry one, and only they can say how far your price is
	 * from the market. Everywhere else in the exchange the same item is just
	 * an item.
	 */
	@Nullable
	private net.runelite.api.GrandExchangeOffer offerOn(int itemId)
	{
		for (GeItems.Spot spot : GeItems.onScreen(client))
		{
			if (spot.offer != null && spot.itemId == itemId)
			{
				return spot.offer;
			}
		}
		return null;
	}

	/**
	 * What the tooltip says: the two prices, the margin, and -- when the item
	 * is one you have an offer on -- how far your price is from the side you
	 * are trading.
	 *
	 * <p>Buy above sell, as everywhere else. Static, so the wording is pinned
	 * by a test rather than by running a client.
	 */
	static String textFor(Quote quote, @Nullable net.runelite.api.GrandExchangeOffer offer)
	{
		final StringBuilder out = new StringBuilder();
		// The item's own name first, since a tooltip can be over any of eight
		// boxes and the pointer is the only thing saying which.
		if (quote.name != null && !quote.name.isEmpty())
		{
			out.append(ColourText.VALUE).append(quote.name).append("</br>");
		}
		out.append(ColourText.MUTED).append("Buy ").append(ColourText.VALUE)
			.append(FlippingRsPanel.exact(quote.getBuyAt())).append("</br>")
			.append(ColourText.MUTED).append("Sell ").append(ColourText.VALUE)
			.append(FlippingRsPanel.exact(quote.getSellAt())).append("</br>")
			.append(ColourText.MUTED).append("Margin ")
			.append(quote.getNetMargin() >= 0 ? ColourText.GOOD : ColourText.BAD)
			.append(FlippingRsPanel.signedExact(quote.getNetMargin()));

		final Long edge = GeSlotText.edgeOf(offer, quote);
		if (edge != null)
		{
			out.append("</br>").append(ColourText.MUTED).append("Yours ")
				.append(edge >= 0 ? ColourText.GOOD : ColourText.BAD)
				.append(FlippingRsPanel.signedExact(edge));
		}
		if (quote.hasLimitLeft())
		{
			out.append("</br>").append(ColourText.MUTED).append("Limit ")
				.append(ColourText.VALUE).append(GeSetupText.limitLeft(quote));
		}
		final String age = GeSetupText.age(quote.getDataAgeSeconds());
		if (age != null)
		{
			out.append("</br>").append(ColourText.MUTED).append("Priced ").append(age);
		}
		return out.toString();
	}

	/** The colours a tooltip line is written in, as the renderer reads them. */
	static final class ColourText
	{
		static final String MUTED = "<col=9f9f9f>";
		static final String VALUE = "<col=ffffff>";
		static final String GOOD = "<col=4caf50>";
		static final String BAD = "<col=d32f2f>";

		private ColourText()
		{
		}
	}
}
