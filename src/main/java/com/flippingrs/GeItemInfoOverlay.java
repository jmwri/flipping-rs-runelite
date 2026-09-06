package com.flippingrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The site's prices, drawn on items the exchange shows without a line of text
 * to put them on.
 *
 * <p>Everywhere the exchange writes a sentence about an item -- the offer
 * setup screen, an open offer, a row of your history -- the prices go on the
 * end of that sentence, because a line the client wrote is already positioned,
 * sized, wrapped and clipped by the screen that owns it. What is left is the
 * screens that are grids of pictures and nothing else: the collection box, a
 * view-only exchange, the price checker, the inventory beside it all. There is
 * nothing there to add to, so those are painted on.
 *
 * <p>The two ends of the spread, and the margin alone in a box too narrow for
 * both. Nothing at all for an item with no quote, which is every item before
 * the first fetch lands: a blank box is the honest rendering of not knowing.
 */
class GeItemInfoOverlay extends Overlay
{
	/** Inset from a box's edges, so the text does not sit on the border. */
	private static final int MARGIN = 3;

	private final Client client;
	private final FlippingRsConfig config;
	private final IntFunction<Quote> quoteFor;
	/** Told which items are on screen, so their prices can be fetched. */
	private final Consumer<Set<Integer>> showing;

	GeItemInfoOverlay(Client client, FlippingRsConfig config, IntFunction<Quote> quoteFor,
		Consumer<Set<Integer>> showing)
	{
		this.client = client;
		this.config = config;
		this.quoteFor = quoteFor;
		this.showing = showing;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(false);
		setSnappable(false);
	}

	/** One box's caption, and the two forms it can take if the box is narrow. */
	static final class Caption
	{
		/** What to draw when there is room. */
		final String full;
		/** What to draw when there is not. Never longer than {@link #full}. */
		final String brief;
		final Color colour;

		Caption(String full, String brief, Color colour)
		{
			this.full = full;
			this.brief = brief;
			this.colour = colour;
		}
	}

	/**
	 * What to write on one item, or null when there is nothing worth saying.
	 *
	 * <p>Static and taking everything it needs, so the wording and the colour
	 * can be pinned by a test without a client running.
	 */
	@Nullable
	static Caption captionFor(@Nullable Quote quote)
	{
		return quote == null ? null : justThePrices(quote);
	}

	/**
	 * An item you have no offer on: the two ends of the spread, and the margin
	 * alone where there is no room for them.
	 */
	@Nullable
	private static Caption justThePrices(Quote quote)
	{
		if (quote.getBuyAt() <= 0 && quote.getSellAt() <= 0)
		{
			return null;
		}
		final Color colour = quote.getNetMargin() < 0
			? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR;
		return new Caption(
			FlippingRsPanel.gp(quote.getBuyAt()) + "/" + FlippingRsPanel.gp(quote.getSellAt()),
			FlippingRsPanel.signed(quote.getNetMargin()),
			colour);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.setupOverlay())
		{
			// The setting is off, so nothing is drawn and nothing is worth
			// fetching a price for. Said rather than assumed, because the list
			// would otherwise keep whatever was on screen when it was switched
			// off for the rest of the session.
			showing.accept(java.util.Collections.emptySet());
			return null;
		}

		final List<GeItems.Spot> spots = GeItems.onScreen(client);

		graphics.setFont(FontManager.getRunescapeSmallFont());
		final FontMetrics metrics = graphics.getFontMetrics();

		// Whatever is in front of the player is what is worth having a price
		// for. Ordered, so the request is the same from one frame to the next
		// while the screen has not changed.
		final Set<Integer> onScreen = new LinkedHashSet<>();
		for (GeItems.Spot spot : spots)
		{
			onScreen.add(spot.itemId);
			if (!spot.paint)
			{
				// The setup screen carries its own; see GeSetupText.
				continue;
			}
			final Caption caption = captionFor(quoteFor.apply(spot.itemId));
			if (caption != null)
			{
				draw(graphics, metrics, spot, caption);
			}
		}
		showing.accept(onScreen);
		return null;
	}

	/**
	 * Puts one caption along the bottom of its box, over a backing so it stays
	 * readable against whatever the box is drawing underneath.
	 *
	 * <p>The shorter form is used when the longer one does not fit, and
	 * neither is drawn when even that would spill out of the box. An item slot
	 * is thirty-odd pixels wide and a history row is most of the screen; the
	 * same caption cannot serve both, and half a number is worse than none.
	 */
	private static void draw(Graphics2D graphics, FontMetrics metrics, GeItems.Spot spot, Caption caption)
	{
		final Rectangle bounds = spot.bounds;
		final int room = bounds.width - 2 * MARGIN;
		String text = caption.full;
		if (metrics.stringWidth(text) > room)
		{
			text = caption.brief;
		}
		final int width = metrics.stringWidth(text);
		if (width > room)
		{
			return;
		}
		final int height = metrics.getHeight();
		final int x = bounds.x + bounds.width - width - MARGIN;
		final int y = bounds.y + bounds.height - MARGIN;

		// Clipped to the list holding the row, the way the client clips the
		// row itself. Some of these screens scroll, and a row scrolled out of
		// one is not hidden -- its bounds are a real rectangle outside the
		// list -- so without this the caption for a row nobody can see is
		// painted over whatever the exchange has put above or below it.
		final java.awt.Shape was = graphics.getClip();
		if (spot.clip != null)
		{
			graphics.clipRect(spot.clip.x, spot.clip.y, spot.clip.width, spot.clip.height);
		}
		try
		{
			graphics.setColor(new Color(0, 0, 0, 160));
			graphics.fillRect(x - 2, y - metrics.getAscent() - 1, width + 4, height);

			graphics.setColor(Color.BLACK);
			graphics.drawString(text, x + 1, y + 1);
			graphics.setColor(caption.colour);
			graphics.drawString(text, x, y);
		}
		finally
		{
			graphics.setClip(was);
		}
	}
}
