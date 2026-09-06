package com.flippingrs;

import java.util.function.IntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;

/**
 * The site's prices on the offer setup screen, added to the screen's own text
 * rather than drawn on top of it or placed beside it.
 *
 * <p>Put on the end of the item's description, which is the line that already
 * sits between what the item is and how much of it you want. Appending to a
 * line the client wrote means the screen positions it, sizes it, wraps it and
 * clips it exactly as it does its own -- none of which has to be worked out
 * here, or kept right as Jagex moves the screen. A widget of this plugin's own
 * had to be placed, and a placement is a guess about somebody else's layout
 * that goes stale without warning.
 *
 * <p>Nothing the game says is lost: the description keeps its text, the guide
 * price and the tax are untouched, and the addition comes off again when the
 * screen closes or the item has no price.
 *
 * <p>Client thread only. Widgets may not be touched from anywhere else.
 */
@Slf4j
class GeSetupText
{
	/**
	 * Past this, the age of the prices is called out rather than merely
	 * stated. Five minutes: the site's data moves on a half-minute cadence, so
	 * ten times that is long enough to mean something is wrong rather than
	 * that the last refresh was a moment ago.
	 */
	private static final long STALE_SECONDS = 300;

	/** Colours, written as the game's own text renderer reads them. */
	private static final String MUTED = "9f9f9f";
	private static final String VALUE = "ffffff";
	private static final String GOOD = "4caf50";
	private static final String BAD = "d32f2f";

	private final Client client;
	private final FlippingRsConfig config;
	private final IntFunction<Quote> quoteFor;

	/** What has been added to the description, and what it said before. */
	private final Appended line = new Appended();

	GeSetupText(Client client, FlippingRsConfig config, IntFunction<Quote> quoteFor)
	{
		this.client = client;
		this.config = config;
		this.quoteFor = quoteFor;
	}

	/**
	 * Brings the addition in line with the item being set up.
	 *
	 * <p>Called every tick the screen is open. The client rewrites the
	 * description whenever it rebuilds the screen, and {@link Appended} is what
	 * tells a rewrite apart from the text this put there; noticing it here
	 * rather than hooking every path that can do it is what stops a rebuild
	 * nobody predicted leaving the line missing or doubled.
	 *
	 * <p>Never throws. It runs from the event bus on the game thread, where an
	 * exception becomes an uncaught plugin error on every tick.
	 */
	void update()
	{
		try
		{
			final Widget description = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
			if (description == null || description.isHidden() || !config.setupOverlay())
			{
				// The screen is gone, or the setting is off. The addition goes
				// with it: prices frozen at whatever they were, on a screen
				// they no longer belong to, are worse than no prices.
				line.clear();
				return;
			}
			final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
			final Quote quote = itemId > 0 ? quoteFor.apply(itemId) : null;
			if (quote == null)
			{
				// No price for this item, which is every item before the first
				// fetch lands. The description goes back to being the game's.
				line.clear();
				return;
			}
			line.to(description, "<br>" + textFor(quote));
		}
		catch (RuntimeException e)
		{
			log.debug("could not put the prices on the offer setup screen", e);
		}
	}

	/**
	 * The client has just rebuilt the setup screen. {@link Appended} copes with
	 * that on its own; doing it here as well is what stops the line flickering
	 * as the screen opens.
	 */
	void rebuilt()
	{
		update();
	}

	/** Puts the description back the way the game had it. */
	void reset()
	{
		line.clear();
	}

	/**
	 * What is added: the two prices and the margin, then the buy limit and how
	 * old the prices are.
	 *
	 * <p>One line. It is going on the end of somebody else's, and the
	 * description's box is only so tall. What qualifies the prices is folded
	 * onto the end rather than dropped: the buy limit and the age are what say
	 * whether the numbers before them can be trusted.
	 *
	 * <p>Coloured with the colours themselves rather than with RuneLite's
	 * {@code <colNORMAL>} tokens, which are only turned into colours for
	 * message types it has a colour configured for and mean nothing at all to
	 * a widget.
	 *
	 * <p>Static, so the wording is pinned by a test rather than by running a
	 * client.
	 */
	static String textFor(Quote quote)
	{
		final StringBuilder out = new StringBuilder();
		out.append(colour("Buy ", MUTED)).append(colour(FlippingRsPanel.exact(quote.getBuyAt()), VALUE))
			.append(colour("  Sell ", MUTED)).append(colour(FlippingRsPanel.exact(quote.getSellAt()), VALUE))
			.append("  ").append(colour(FlippingRsPanel.signedExact(quote.getNetMargin()),
				quote.getNetMargin() >= 0 ? GOOD : BAD));

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
			out.append(colour("  ·  " + under, stale(quote) ? BAD : MUTED));
		}
		return out.toString();
	}

	/** One run of text in one colour, as the game's own text renderer reads it. */
	static String colour(String text, String hex)
	{
		return "<col=" + hex + ">" + text + "</col>";
	}

	/** Whether the prices are old enough that the margin is not worth trusting. */
	static boolean stale(Quote quote)
	{
		return quote.getDataAgeSeconds() > STALE_SECONDS;
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

	/** The description as it now reads, for a test that has a client. */
	@Nullable
	String textForTest()
	{
		return line.textForTest();
	}
}
