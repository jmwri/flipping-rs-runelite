package com.flippingrs;

import java.util.function.IntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;

/**
 * The site's prices on the offer setup screen, written into the screen itself
 * rather than drawn on top of it.
 *
 * <p>This is the one place in the exchange worth injecting into. It is a
 * single fixed layout with a build script to hang off, it is where the number
 * actually gets typed, and it has room below the game's own lines. Everywhere
 * else the plugin paints over the top, because a caption in the wrong place
 * after a game update is only ugly, whereas a widget in the wrong place can
 * cover something the player needed.
 *
 * <p>Nothing the game shows is replaced. The item's description, the guide
 * price and the tax are left exactly as they are and a line is added under
 * them, so a player whose plugin is offline or whose item has no price loses
 * nothing they had before.
 *
 * <p>Client thread only. Widgets may not be touched from anywhere else.
 */
@Slf4j
class GeSetupText
{
	/**
	 * How far below the game's last line to sit, and how tall to be. The
	 * screen's own lines are eleven pixels apart, which is what the small font
	 * gives; two lines and a gap is what this needs.
	 */
	private static final int GAP = 6;
	private static final int LINE = 12;

	/**
	 * Past this, the age of the prices is called out rather than merely
	 * stated. Five minutes: the site's data moves on a half-minute cadence, so
	 * ten times that is long enough to mean something is wrong rather than
	 * that the last refresh was a moment ago.
	 */
	private static final long STALE_SECONDS = 300;

	/** The site's orange, for a line that is plainly not the game's. */
	private static final int BRAND = 0xff981f;
	private static final int GOOD = 0x4caf50;
	private static final int BAD = 0xd32f2f;
	private static final int MUTED = 0x9f9f9f;

	private final Client client;
	private final FlippingRsConfig config;
	private final IntFunction<Quote> quoteFor;

	/**
	 * The line this plugin added, or null if it has not been added or the
	 * client has since rebuilt the screen out from under it.
	 */
	@Nullable
	private Widget line;

	GeSetupText(Client client, FlippingRsConfig config, IntFunction<Quote> quoteFor)
	{
		this.client = client;
		this.config = config;
		this.quoteFor = quoteFor;
	}

	/**
	 * Puts the line where it belongs, making it if it is not there.
	 *
	 * <p>Called on every tick the exchange is open rather than only when the
	 * client rebuilds the screen. The rebuild is hooked too, but hooking it is
	 * an optimisation rather than the guarantee: a rebuild this does not know
	 * about would otherwise leave the line gone for as long as the screen
	 * stays open, and finding out about that needs a client running. Checking
	 * that it is still there costs a widget lookup.
	 *
	 * <p>Never throws. It runs from the event bus on the game thread, where an
	 * exception is logged as an uncaught plugin error on every tick.
	 */
	void update()
	{
		try
		{
			final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
			if (setup == null || setup.isHidden() || !config.setupOverlay())
			{
				// The screen is gone, or the setting is off. The line goes with
				// it: a price frozen at whatever it was, on a screen it no
				// longer belongs to, is worse than no price.
				hide();
				return;
			}
			final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
			final Quote quote = itemId > 0 ? quoteFor.apply(itemId) : null;
			if (quote == null)
			{
				// No price for this item, which is every item before the first
				// fetch lands. An empty line is the honest rendering of that,
				// and it leaves the screen exactly as the game drew it.
				hide();
				return;
			}
			show(setup, quote);
		}
		catch (RuntimeException e)
		{
			log.debug("could not put the prices on the offer setup screen", e);
		}
	}

	/**
	 * The client has just rebuilt the setup screen, which throws away any
	 * child added to it. Rebuilding the line here rather than waiting for the
	 * next tick is what stops it flickering as the screen is opened.
	 */
	void rebuilt()
	{
		line = null;
		update();
	}

	/** Forgets the line, for a screen that has closed or a plugin stopping. */
	void reset()
	{
		hide();
		line = null;
	}

	private void hide()
	{
		if (line != null)
		{
			line.setHidden(true);
			line.setText("");
		}
	}

	/** Writes the two lines, making the widget first if it is not there. */
	private void show(Widget setup, Quote quote)
	{
		final Widget target = lineOn(setup);
		if (target == null)
		{
			return;
		}
		target.setHidden(false);
		target.setText(textFor(quote));
		target.setTextColor(colourFor(quote));
		target.revalidate();
	}

	/**
	 * The line's widget, made if the screen does not have it.
	 *
	 * <p>A widget kept from before is only reused while it is still a child of
	 * the screen that is up now. The client rebuilds this interface freely,
	 * and a reference held across one of those points at a widget that is no
	 * longer in the tree: writing to it does nothing visible and never
	 * recovers.
	 */
	@Nullable
	private Widget lineOn(Widget setup)
	{
		if (line != null && line.getParent() == setup)
		{
			return line;
		}
		line = setup.createChild(-1, WidgetType.TEXT);
		if (line == null)
		{
			return null;
		}
		line.setFontId(FontID.PLAIN_11);
		line.setTextShadowed(true);
		// Placed against the bottom of the screen rather than a fixed offset
		// from the top, so the game's own lines can grow -- a longer item
		// description, another line of tax -- without this landing on them.
		line.setOriginalX(GAP);
		line.setOriginalY(GAP);
		line.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
		line.setOriginalWidth(GAP * 2);
		line.setWidthMode(WidgetSizeMode.MINUS);
		line.setOriginalHeight(LINE * 2);
		line.revalidate();
		return line;
	}

	/**
	 * What the line says: the two prices and the margin, then the buy limit
	 * and how old the prices are.
	 *
	 * <p>Two lines rather than one because the screen is narrow and the first
	 * three are the numbers being acted on, while the second two are what
	 * qualifies them. Static, so the wording is pinned by a test rather than
	 * by running a client.
	 */
	static String textFor(Quote quote)
	{
		final StringBuilder out = new StringBuilder();
		out.append("Buy ").append(FlippingRsPanel.exact(quote.getBuyAt()))
			.append("  Sell ").append(FlippingRsPanel.exact(quote.getSellAt()))
			.append("  ").append(FlippingRsPanel.signedExact(quote.getNetMargin()));
		final StringBuilder under = new StringBuilder();
		if (quote.hasLimitLeft())
		{
			under.append("Limit ").append(limitLeft(quote));
		}
		final String age = age(quote.getDataAgeSeconds());
		if (age != null)
		{
			under.append(under.length() > 0 ? "  ·  " : "").append("Priced ").append(age);
		}
		if (under.length() > 0)
		{
			out.append("<br>").append(under);
		}
		return out.toString();
	}

	/**
	 * The colour of the line: the margin's, unless the prices are old enough
	 * that the margin is not worth trusting.
	 */
	static int colourFor(Quote quote)
	{
		if (quote.getDataAgeSeconds() > STALE_SECONDS)
		{
			return MUTED;
		}
		if (quote.getNetMargin() == 0)
		{
			return BRAND;
		}
		return quote.getNetMargin() > 0 ? GOOD : BAD;
	}

	/**
	 * "just now", "4m ago", "2h ago" -- as much precision as the answer
	 * deserves, and nothing at all when there is no answer.
	 *
	 * <p>A negative age means the item has never traded, which is not a fresh
	 * price and not a stale one: there is no price. Reading that as "just now"
	 * because it is a small number would put the most reassuring words on the
	 * screen in the one case where nothing is known.
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

	/** The line as it currently reads, for a test that has a client. */
	@Nullable
	String textForTest()
	{
		return line == null ? null : line.getText();
	}
}
