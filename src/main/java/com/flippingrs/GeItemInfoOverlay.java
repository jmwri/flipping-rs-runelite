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
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The site's prices, drawn on the items the exchange is showing -- on whatever
 * screen it is showing them.
 *
 * <p>The exchange spends most of its time on the eight-slot screen, and until
 * now that was the one place the plugin said nothing. A slot tells you what
 * you asked for and how much of it has happened; what it cannot tell you is
 * whether what you asked for is still the right number. The same goes for the
 * page you get by clicking an offer, your history, the collection box and the
 * rest: the item is right there and the price is not.
 *
 * <p>Two kinds of line. On one of your own offers, where the plugin knows what
 * you asked for as well as what the item is, it draws the price for the side
 * you are on and how far your offer is from it -- green when the offer is
 * priced to fill sooner, red when it is priced to sit. Everywhere else there
 * is nothing to compare against, so it draws the two ends of the spread, and
 * falls back to the margin alone in a box too narrow for both.
 *
 * <p>Nothing is drawn for an item with no quote, which is every item before
 * the first fetch lands and every unwatched item on a server that does not
 * price single items. A blank box is the honest rendering of not knowing.
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
	static Caption captionFor(@Nullable GrandExchangeOffer offer, @Nullable Quote quote)
	{
		if (quote == null)
		{
			return null;
		}
		if (offer != null && offer.getState() != null && offer.getState() != GrandExchangeOfferState.EMPTY)
		{
			return againstYourOffer(offer, quote);
		}
		return justThePrices(quote);
	}

	/**
	 * An item you have an offer on: the price for the side you are on, and how
	 * far your offer is from it.
	 */
	@Nullable
	private static Caption againstYourOffer(GrandExchangeOffer offer, Quote quote)
	{
		final GrandExchangeOfferState state = offer.getState();
		final boolean buying = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.BOUGHT;
		// The price the site says to trade at on this side. The two are not
		// interchangeable: they are the ends of the spread a flip lives in,
		// and measuring an offer against the wrong one would call every
		// sensible offer badly priced by exactly the width of it.
		final long market = buying ? quote.getBuyAt() : quote.getSellAt();
		if (market <= 0)
		{
			return null;
		}
		final long asked = offer.getPrice();
		// Positive means the offer is on the side of the price that fills
		// sooner: a buy at or above the site's buy price, a sale at or below
		// its sell price. Negative is the patient end of the flip, which is
		// where the profit is and also where an offer can sit all evening --
		// so it is called out rather than judged.
		final long edge = buying ? asked - market : market - asked;
		final Color colour = edge >= 0
			? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR;
		return new Caption(
			(buying ? "Buy " : "Sell ") + FlippingRsPanel.exact(market) + "  " + FlippingRsPanel.signedExact(edge),
			FlippingRsPanel.signedExact(edge),
			colour);
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
			final Caption caption = captionFor(spot.offer, quoteFor.apply(spot.itemId));
			if (caption != null)
			{
				draw(graphics, metrics, spot.bounds, caption);
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
	private static void draw(Graphics2D graphics, FontMetrics metrics, Rectangle bounds, Caption caption)
	{
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

		graphics.setColor(new Color(0, 0, 0, 160));
		graphics.fillRect(x - 2, y - metrics.getAscent() - 1, width + 4, height);

		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(caption.colour);
		graphics.drawString(text, x, y);
	}
}
