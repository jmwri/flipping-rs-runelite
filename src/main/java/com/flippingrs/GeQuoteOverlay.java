package com.flippingrs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The site's buy and sell prices, drawn on the Grand Exchange offer setup
 * screen while the item being set up is on the watchlist.
 *
 * <p>The setup screen is where the price gets typed, so it is where the
 * number is needed. It shows the exact gp, not a rounded figure: a flipper
 * types "1,481,000", not "1.48M". It draws nothing for an item that is not
 * watched, because the plugin has no quote for it and a blank box would only
 * be noise.
 *
 * <p>Sits in the bottom-right corner of the setup panel. The bottom-left
 * holds the back button, the price and quantity controls are in the middle
 * and the confirm button is centred along the bottom, which leaves the
 * bottom-right free.
 *
 * <p>It says how old the prices are, because it shows them to the gp and that
 * implies a freshness it does not have. An exact figure that is forty minutes
 * old, drawn beside the box where a number gets typed, is a trap the rounder
 * figure never sets. And it says how much of the buy limit is left, which is
 * the one number a flipper cannot work out in their head and the site can,
 * because it holds the trades it is counted from.
 */
class GeQuoteOverlay extends OverlayPanel
{
	private static final int MARGIN = 4;
	private static final int WIDTH = 150;

	/**
	 * Past this, the age of the prices is called out rather than merely
	 * stated. Five minutes: the site's own data moves on a half-minute
	 * cadence, so ten times that is long enough to mean something is wrong
	 * rather than that the last refresh was a moment ago.
	 */
	private static final long STALE_SECONDS = 300;

	private final Client client;
	private final IntFunction<Quote> watchedQuote;
	/** Told which item is being set up, so a price can be fetched for it. */
	private final java.util.function.IntConsumer showing;
	/** Last drawn height, to place the panel against the bottom edge next frame. */
	private int lastHeight;

	/**
	 * @param watchedQuote the site's quote for an item if it is on the shown
	 *                     watchlist and has one, else null
	 */
	GeQuoteOverlay(Client client, IntFunction<Quote> watchedQuote, java.util.function.IntConsumer showing)
	{
		this.client = client;
		this.watchedQuote = watchedQuote;
		this.showing = showing;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(false);
		setSnappable(false);
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
	}

	/** The quote to draw right now, or null when the setup screen or the item does not call for one. */
	@Nullable
	Quote visibleQuote()
	{
		return quoteOn(client.getWidget(InterfaceID.GeOffers.SETUP));
	}

	/**
	 * The same question, for a setup screen the caller already has. render
	 * needs the widget anyway, to place the panel against its corner, and
	 * looking it up a second time is a widget-tree walk on the frame path.
	 */
	@Nullable
	private Quote quoteOn(@Nullable Widget setup)
	{
		if (setup == null || setup.isHidden())
		{
			showing.accept(0);
			return null;
		}
		final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		// Reported either way, so closing the setup screen takes its item off
		// the list of things worth a price rather than leaving it there for
		// the rest of the session.
		showing.accept(Math.max(itemId, 0));
		if (itemId <= 0)
		{
			return null;
		}
		return watchedQuote.apply(itemId);
	}

	/**
	 * Where the box goes: the bottom-right of the setup panel.
	 *
	 * <p>The bottom-left holds the back button, the price and quantity
	 * controls are in the middle and the confirm button is centred along the
	 * bottom, which leaves this corner free. Both edges are clamped, because a
	 * setup panel narrower or shorter than the box would otherwise put it
	 * outside the screen it belongs to.
	 *
	 * @param height the height the box was last drawn at
	 */
	static Point cornerFor(Rectangle bounds, int height)
	{
		return new Point(
			Math.max(bounds.x + MARGIN, bounds.x + bounds.width - WIDTH - MARGIN),
			Math.max(bounds.y + MARGIN, bounds.y + bounds.height - height - MARGIN));
	}

	/**
	 * "just now", "4m ago", "2h ago" -- as much precision as the answer
	 * deserves, and nothing at all when there is no answer.
	 *
	 * <p>A negative age means the item has never traded, which is not a fresh
	 * price and not a stale one: there is no price. Reading that as "just now"
	 * because it is a small number would put the most reassuring words on the
	 * screen in the one case where nothing is known, which is precisely the
	 * false freshness this line exists to prevent.
	 */
	@Nullable
	static String age(long seconds)
	{
		if (seconds < 0)
		{
			return null;
		}
		if (seconds <= 60)
		{
			return "just now";
		}
		if (seconds < 3600)
		{
			return seconds / 60 + "m ago";
		}
		return seconds / 3600 + "h ago";
	}

	/**
	 * How much of the buy limit is left, and when it comes back if it is
	 * spent. The reset only matters once there is nothing left to buy: while
	 * there is, the number to act on is the number remaining.
	 */
	static String limitLeft(Quote quote)
	{
		final int left = quote.getLimitRemaining();
		if (left > 0)
		{
			return FlippingRsPanel.count(left);
		}
		final long resets = quote.getLimitResetsInSeconds();
		return resets > 0 ? "none for " + until(resets) : "none";
	}

	/** "1h 12m", "12m", "under a minute". */
	static String until(long seconds)
	{
		if (seconds < 60)
		{
			return "under a minute";
		}
		final long hours = seconds / 3600;
		final long minutes = seconds % 3600 / 60;
		if (hours == 0)
		{
			return minutes + "m";
		}
		return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		final Quote quote = quoteOn(setup);
		if (quote == null)
		{
			return null;
		}
		final Rectangle bounds = setup.getBounds();
		if (bounds == null)
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder().text("FlippingRS").build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Buy at").right(FlippingRsPanel.exact(quote.getBuyAt())).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Sell at").right(FlippingRsPanel.exact(quote.getSellAt())).build());
		final Color marginColour = quote.getNetMargin() < 0
			? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR;
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Margin")
			.right(FlippingRsPanel.signedExact(quote.getNetMargin()) + " (" + FlippingRsPanel.pct(quote.getRoi()) + ")")
			.rightColor(marginColour)
			.build());

		// The number a flipper cannot work out for themselves. Only shown by a
		// server that sends it; an older one leaves the line off rather than
		// drawing a limit of nought.
		if (quote.hasLimitLeft())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Limit left")
				.right(limitLeft(quote))
				.rightColor(quote.getLimitRemaining() > 0
					? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR)
				.build());
		}

		final String age = age(quote.getDataAgeSeconds());
		if (age != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Priced")
				.right(age)
				.rightColor(quote.getDataAgeSeconds() > STALE_SECONDS
					? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR)
				.build());
		}

		panelComponent.setPreferredLocation(
			cornerFor(bounds, lastHeight > 0 ? lastHeight : 60));

		final Dimension drawn = super.render(graphics);
		if (drawn != null)
		{
			lastHeight = drawn.height;
		}
		return drawn;
	}
}
