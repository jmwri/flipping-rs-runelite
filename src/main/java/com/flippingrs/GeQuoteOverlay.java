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
 * <p>Sits in the bottom-left corner of the setup panel, which the game
 * leaves empty; the price and quantity controls are in the middle and the
 * confirm button is centred along the bottom.
 */
class GeQuoteOverlay extends OverlayPanel
{
	private static final int MARGIN = 4;
	private static final int WIDTH = 150;

	private final Client client;
	private final IntFunction<FlippingRsApi.Quote> watchedQuote;
	/** Last drawn height, to place the panel against the bottom edge next frame. */
	private int lastHeight;

	/**
	 * @param watchedQuote the site's quote for an item if it is on the shown
	 *                     watchlist and has one, else null
	 */
	GeQuoteOverlay(Client client, IntFunction<FlippingRsApi.Quote> watchedQuote)
	{
		this.client = client;
		this.watchedQuote = watchedQuote;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(false);
		setSnappable(false);
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
	}

	/** The quote to draw right now, or null when the setup screen or the item does not call for one. */
	@Nullable
	FlippingRsApi.Quote visibleQuote()
	{
		final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null || setup.isHidden())
		{
			return null;
		}
		final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		return itemId <= 0 ? null : watchedQuote.apply(itemId);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final FlippingRsApi.Quote quote = visibleQuote();
		if (quote == null)
		{
			return null;
		}
		final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		final Rectangle bounds = setup == null ? null : setup.getBounds();
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

		final int height = lastHeight > 0 ? lastHeight : 60;
		panelComponent.setPreferredLocation(new Point(
			bounds.x + MARGIN,
			Math.max(bounds.y + MARGIN, bounds.y + bounds.height - height - MARGIN)));

		final Dimension drawn = super.render(graphics);
		if (drawn != null)
		{
			lastHeight = drawn.height;
		}
		return drawn;
	}
}
