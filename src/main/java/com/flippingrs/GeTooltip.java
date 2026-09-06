package com.flippingrs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * The site's prices inside the exchange's own hover box.
 *
 * <p>The exchange already puts a yellow box under the pointer saying what the
 * thing you are pointing at is. It is the one thing on screen that is about a
 * single item, it appears exactly when somebody wants to know about that item,
 * and it costs no room and no attention until then. So the prices go in it
 * rather than in a second box beside it: two tooltips for one pointer is one
 * too many, and the game's is the one that was already right.
 *
 * <p>Which item it is describing does not come from the box -- it says nothing
 * about that -- but from what the pointer is over, which {@link GeItems}
 * already answers for every screen the exchange has. The two agree because the
 * box is only up while the pointer is on the thing it belongs to.
 *
 * <p>The box does have to be made bigger, which is the one thing this plugin
 * has learned to be careful about. It is a far easier case than the offer
 * boxes that taught the lesson: a tooltip floats, so nothing above it has to
 * be grown to match and nothing below it gets pushed anywhere. What is still
 * true is that a size must never be read back after it has been changed, or
 * the box grows from its own growth -- so the client's own dimensions are
 * taken once, when it builds the box, and every write after is computed from
 * those.
 *
 * <p>Client thread only.
 */
@Slf4j
class GeTooltip
{
	/**
	 * The exchange's hover boxes, one per screen that has one. A screen that
	 * gains one is a constant added here rather than a class.
	 */
	private static final int[] TOOLTIPS = {
		InterfaceID.GeOffers.TOOLTIP,
		InterfaceID.GeCollect.TOOLTIP,
		InterfaceID.GeViewonly.TOOLTIP,
	};

	private final Client client;
	private final FlippingRsConfig config;
	private final IntFunction<Quote> quoteFor;

	/** What has been added to whichever box is up. */
	private final Appended line = new Appended();

	/** The room made for it, and how to give it back. */
	private final Grown box = new Grown();

	GeTooltip(Client client, FlippingRsConfig config, IntFunction<Quote> quoteFor)
	{
		this.client = client;
		this.config = config;
		this.quoteFor = quoteFor;
	}

	/**
	 * Brings the addition in line with what the pointer is over.
	 *
	 * <p>Never throws. It runs from the event bus on the game thread, where an
	 * exception becomes an uncaught plugin error on every frame.
	 */
	void update()
	{
		try
		{
			final Widget tip = config.setupOverlay() ? shownTooltip() : null;
			final Widget text = tip == null ? null : textIn(tip);
			if (text == null)
			{
				reset();
				return;
			}
			final Point mouse = client.getMouseCanvasPosition();
			final int itemId = GeItems.under(client, mouse);
			final Quote quote = itemId > 0 ? quoteFor.apply(itemId) : null;
			if (quote == null)
			{
				// Nothing is added for an item with no price, which is every
				// item until the first fetch lands. The game's box is left
				// exactly as it was rather than made taller and left empty.
				reset();
				return;
			}
			final String extra = textFor(quote, offerOn(itemId));
			line.to(text, "<br>" + extra);
			box.fit(tip, text, extra);
		}
		catch (RuntimeException e)
		{
			log.debug("could not put prices in the exchange's hover box", e);
		}
	}

	/** Puts the box back the way the game had it. */
	void reset()
	{
		// The text first: the box is sized to hold it, so giving the size back
		// while the addition is still in it would clip what is there.
		line.clear();
		box.restore();
	}

	/**
	 * The hover box that is up, or null if none is.
	 *
	 * <p>Hidden is the usual state. The exchange builds these once and shows
	 * and hides them as the pointer moves, so an id that resolves is not by
	 * itself a box on screen.
	 */
	@Nullable
	private Widget shownTooltip()
	{
		for (int id : TOOLTIPS)
		{
			final Widget tooltip = client.getWidget(id);
			if (tooltip != null && !tooltip.isHidden())
			{
				return tooltip;
			}
		}
		return null;
	}

	/**
	 * The line of a hover box to add to: the lowest text in it.
	 *
	 * <p>A box is a fill, a border and its text somewhere inside, and which
	 * child carries the text is the interface's business rather than something
	 * worth knowing here.
	 */
	@Nullable
	private static Widget textIn(Widget tooltip)
	{
		if (RowText.isText(tooltip))
		{
			return tooltip;
		}
		return RowText.lastIn(RowText.under(tooltip), Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	/**
	 * Your own offer on an item, if one of the eight slots has it.
	 *
	 * <p>Only the slots carry one, and only they can say how far your price is
	 * from the market. Everywhere else in the exchange the same item is just
	 * an item.
	 */
	@Nullable
	private GrandExchangeOffer offerOn(int itemId)
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
	 * What the box gains: the two prices, the margin, and -- when the item is
	 * one you have an offer on -- how far your price is from the side you are
	 * trading, then the buy limit and how old the prices are.
	 *
	 * <p>A line each rather than one line of everything, because a price and
	 * its label stop fitting together the moment the price is long: a hundred
	 * million buying and a hundred and ten million selling is most of a line
	 * before anything else is said. A hover box is as tall as it is made, so
	 * the prices are exact to the coin -- these are numbers somebody is about
	 * to type.
	 *
	 * <p>The item's name is not repeated: the game's box already leads with
	 * it, and this goes underneath.
	 *
	 * <p>Static, so the wording is pinned by a test rather than by running a
	 * client.
	 */
	static String textFor(Quote quote, @Nullable GrandExchangeOffer offer)
	{
		final StringBuilder out = new StringBuilder();
		out.append(ColourText.MUTED).append("Buy ").append(ColourText.VALUE)
			.append(FlippingRsPanel.exact(quote.getBuyAt())).append("<br>")
			.append(ColourText.MUTED).append("Sell ").append(ColourText.VALUE)
			.append(FlippingRsPanel.exact(quote.getSellAt())).append("<br>")
			.append(ColourText.MUTED).append("Margin ")
			.append(quote.getNetMargin() >= 0 ? ColourText.GOOD : ColourText.BAD)
			.append(FlippingRsPanel.signedExact(quote.getNetMargin()));

		final Long edge = GeSlotText.edgeOf(offer, quote);
		if (edge != null)
		{
			out.append("<br>").append(ColourText.MUTED).append("Yours ")
				.append(edge >= 0 ? ColourText.GOOD : ColourText.BAD)
				.append(FlippingRsPanel.signedExact(edge));
		}
		if (quote.hasLimitLeft())
		{
			out.append("<br>").append(ColourText.MUTED).append("Limit ")
				.append(ColourText.VALUE).append(GeSetupText.limitLeft(quote));
		}
		final String age = GeSetupText.age(quote.getDataAgeSeconds());
		if (age != null)
		{
			out.append("<br>").append(ColourText.MUTED).append("Priced ").append(age);
		}
		return out.toString();
	}

	/** The colours a line is written in, as the game's own renderer reads them. */
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

	/**
	 * A hover box made big enough for what was put in it.
	 *
	 * <p>The box itself, and whatever inside it is the size of the box -- the
	 * yellow fill and the border round it, which are children rather than the
	 * box's own painting and so do not follow it on their own.
	 *
	 * <p>Every dimension is taken from the client's, once, when the client
	 * builds the box. A size read back after this has changed it is this
	 * plugin's arithmetic being fed its own output, which is how an earlier
	 * attempt at resizing the offer boxes grew a little more on every frame.
	 * What was written last is remembered, so the client rebuilding the box
	 * can be told from the box as this left it.
	 */
	static final class Grown
	{
		/** As wide as a hover box will be taken, so a bad measure cannot fill the screen. */
		private static final int MAX_WIDTH = 400;

		/** Room inside the border, so the longest line does not sit on it. */
		private static final int PAD = 10;

		/** One row of the font the exchange writes these in. */
		private static final int LINE = 14;

		@Nullable
		private Widget widget;

		/** The box as the client built it, read before anything was written to it. */
		private int baseWidth = -1;
		private int baseHeight;
		private int baseWidthMode;
		private int baseHeightMode;

		/** What was written last, so a rebuild by the client can be told from it. */
		private int wroteWidth = -1;
		private int wroteHeight = -1;

		/** The children that are the size of the box, and how they were. */
		private final List<Widget> spans = new ArrayList<>();
		private final List<int[]> spansWere = new ArrayList<>();

		/**
		 * Makes the box hold {@code extra} as well as what the game put in it.
		 *
		 * <p>Safe to call every frame with the same arguments: a size is only
		 * written when it would differ from what is already there.
		 */
		void fit(Widget tooltip, Widget text, String extra)
		{
			if (tooltip != widget
				|| tooltip.getOriginalWidth() != wroteWidth
				|| tooltip.getOriginalHeight() != wroteHeight)
			{
				// A different box, or the same one rebuilt. Either way what is
				// there now is the client's, and this is the last moment it
				// can be read before being written over.
				forget();
				widget = tooltip;
				baseWidth = tooltip.getOriginalWidth();
				baseHeight = tooltip.getOriginalHeight();
				baseWidthMode = tooltip.getWidthMode();
				baseHeightMode = tooltip.getHeightMode();
				takeSpans(tooltip);
			}
			final int rows = 1 + rowsIn(extra);
			final int width = Math.min(MAX_WIDTH,
				Math.max(baseWidth, widest(text, extra) + PAD));
			final int height = baseHeight + rows * LINE;
			if (width == wroteWidth && height == wroteHeight)
			{
				return;
			}
			resize(tooltip, width, height);
			for (int i = 0; i < spans.size(); i++)
			{
				// Each keeps whatever inset it had from the box's edges, which
				// is how a border a pixel outside a fill stays a pixel outside
				// it however far the box is taken.
				final int[] was = spansWere.get(i);
				resize(spans.get(i), width - (baseWidth - was[0]), height - (baseHeight - was[1]));
			}
			tooltip.revalidate();
			wroteWidth = width;
			wroteHeight = height;
		}

		/** Puts the box back the way the game had it, and forgets it. */
		void restore()
		{
			if (widget != null && baseWidth >= 0
				&& widget.getOriginalWidth() == wroteWidth
				&& widget.getOriginalHeight() == wroteHeight)
			{
				// Only if it is still as this left it. A box the client has
				// since rebuilt is already the game's, and writing an old size
				// into it would size it for an item that is no longer there.
				restore(widget, baseWidth, baseHeight, baseWidthMode, baseHeightMode);
				for (int i = 0; i < spans.size(); i++)
				{
					final int[] was = spansWere.get(i);
					restore(spans.get(i), was[0], was[1], was[2], was[3]);
				}
				widget.revalidate();
			}
			forget();
		}

		private void forget()
		{
			widget = null;
			baseWidth = -1;
			wroteWidth = -1;
			wroteHeight = -1;
			spans.clear();
			spansWere.clear();
		}

		/**
		 * The children that are the size of the box: its fill and its border.
		 *
		 * <p>Found by measurement rather than by index, because which child is
		 * which is the interface's business and changes when Jagex rebuilds
		 * it. Anything appreciably smaller than the box is a piece of its
		 * contents and is left exactly where it is.
		 */
		private void takeSpans(Widget tooltip)
		{
			final int width = tooltip.getWidth();
			final int height = tooltip.getHeight();
			for (Widget child : RowText.under(tooltip))
			{
				if (child == null || RowText.isText(child))
				{
					continue;
				}
				if (child.getWidth() < width - PAD || child.getHeight() < height - PAD)
				{
					continue;
				}
				spans.add(child);
				spansWere.add(new int[]{
					child.getOriginalWidth(), child.getOriginalHeight(),
					child.getWidthMode(), child.getHeightMode(),
				});
				// A sprite stretched while it is tiled repeats itself instead
				// of growing, which on a border draws the frame's edge over
				// and over across the middle of the box.
				child.setSpriteTiling(false);
			}
		}

		/**
		 * Sets a size outright.
		 *
		 * <p>Absolute rather than whatever mode the client had, because a size
		 * expressed relative to a parent is not a size this can set. The mode
		 * is put back with the dimension when the box is given up.
		 */
		private static void resize(Widget widget, int width, int height)
		{
			widget.setWidthMode(WidgetSizeMode.ABSOLUTE);
			widget.setHeightMode(WidgetSizeMode.ABSOLUTE);
			widget.setOriginalWidth(width);
			widget.setOriginalHeight(height);
			widget.revalidate();
		}

		private static void restore(Widget widget, int width, int height, int widthMode, int heightMode)
		{
			widget.setWidthMode(widthMode);
			widget.setHeightMode(heightMode);
			widget.setOriginalWidth(width);
			widget.setOriginalHeight(height);
			widget.revalidate();
		}

		/** How many rows an addition takes, over and above its first. */
		static int rowsIn(String extra)
		{
			int rows = 0;
			for (int at = extra.indexOf("<br>"); at >= 0; at = extra.indexOf("<br>", at + 1))
			{
				rows++;
			}
			return rows;
		}

		/**
		 * How wide the widest line of an addition is drawn, in the font the
		 * box writes in.
		 *
		 * <p>Asked of the client's own font rather than guessed at, because
		 * the whole range is in play: "Sell 421" and "Sell 1,600,000,000" are
		 * the same line about two different items, and the box was built to
		 * fit neither.
		 */
		private static int widest(Widget text, String extra)
		{
			final FontTypeFace font = text.getFont();
			if (font == null)
			{
				return 0;
			}
			int widest = 0;
			for (String row : extra.split("<br>"))
			{
				widest = Math.max(widest, font.getTextWidth(plain(row)));
			}
			return widest;
		}

		/** A line without its colour tags, which are instructions rather than text. */
		static String plain(String row)
		{
			return row.replaceAll("<[^>]*>", "");
		}
	}
}
