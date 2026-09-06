package com.flippingrs;

import java.util.List;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;

/**
 * How your open offers are priced, added to each slot's own text.
 *
 * <p>The slot screen is where a flipper actually spends their time, and what a
 * slot cannot tell you on its own is whether the number you asked for is still
 * the right one. It knows what you offered; the site knows what the item is
 * going for. The two together are the only thing on that screen worth adding.
 *
 * <p>On the slot's own text rather than painted over it, so the box positions
 * and clips it exactly as it does its own lines, and a game update that moves
 * the slot moves this with it.
 *
 * <p>Client thread only.
 */
@Slf4j
class GeSlotText
{
	/** Colours, written as the game's own text renderer reads them. */
	/** How many rows the prices take, over and above the line they go on. */
	static final int LINES = 3;

	private static final String MUTED = "9f9f9f";
	private static final String VALUE = "ffffff";
	private static final String GOOD = "4caf50";
	private static final String BAD = "d32f2f";

	private final Client client;
	private final FlippingRsConfig config;
	private final IntFunction<Quote> quoteFor;

	/** One per slot, holding what its line said before. */
	private final Appended[] slots = new Appended[GeItems.SLOTS.length];

	/** How many rows of text the boxes have been made room for. */
	private final java.util.function.IntSupplier rows;

	GeSlotText(Client client, FlippingRsConfig config, IntFunction<Quote> quoteFor,
		java.util.function.IntSupplier rows)
	{
		this.client = client;
		this.config = config;
		this.quoteFor = quoteFor;
		this.rows = rows;
		for (int i = 0; i < slots.length; i++)
		{
			slots[i] = new Appended();
		}
	}

	/**
	 * Brings each slot's addition in line with the offer in it.
	 *
	 * <p>Never throws. It runs from the event bus on the game thread, where an
	 * exception becomes an uncaught plugin error on every tick.
	 */
	void update()
	{
		try
		{
			final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			if (offers == null || !config.setupOverlay())
			{
				reset();
				return;
			}
			for (int slot = 0; slot < slots.length; slot++)
			{
				final Widget box = client.getWidget(GeItems.SLOTS[slot]);
				final GrandExchangeOffer offer = slot < offers.length ? offers[slot] : null;
				final int lines = rows.getAsInt();
				final String text = box == null || box.isHidden()
					? null : textFor(offer, quoteOn(offer), lines);
				if (text == null)
				{
					slots[slot].clear();
					continue;
				}
				final Widget line = lineIn(box);
				if (line == null)
				{
					slots[slot].clear();
					continue;
				}
				// Exactly as many rows as were made room for. Saying more than
				// the box was grown to hold is how this drew over the line
				// under it.
				slots[slot].to(line, lines > 0 ? "<br>" + text : text, lines);
			}
		}
		catch (RuntimeException e)
		{
			log.debug("could not put prices on the offer slots", e);
		}
	}

	/** Puts every slot back the way the game had it. */
	void reset()
	{
		for (Appended slot : slots)
		{
			try
			{
				slot.clear();
			}
			catch (RuntimeException e)
			{
				log.debug("could not clear a slot line", e);
			}
		}
	}

	@Nullable
	private Quote quoteOn(@Nullable GrandExchangeOffer offer)
	{
		return offer == null ? null : quoteFor.apply(offer.getItemId());
	}

	/**
	 * The line of text in a slot to add to: the lowest one in the box, which
	 * is the end of what the slot has to say.
	 */
	@Nullable
	private static Widget lineIn(Widget box)
	{
		final List<Widget> children = RowText.under(box);
		// The whole box, because a slot is one thing rather than a list of
		// rows: anything in it belongs to the offer it is showing.
		return RowText.lastIn(children, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	/**
	 * What one slot gains: both of the site's prices, and how far your own
	 * offer is from the one that applies to it.
	 *
	 * <p>A line each, because a price and its difference on one line stop being
	 * two things the moment either of them is long: a hundred million buying
	 * and a hundred and ten million selling is most of a line before anything
	 * is said about it. A column reads at any size.
	 *
	 * <p>As many lines as there is room for, and no more. The window cannot
	 * grow past the screen, so on a small client there may be room for two, or
	 * one, or none -- and what gets dropped is decided here rather than by the
	 * box clipping it. Your own side goes first and is never the thing
	 * dropped: it is the only price yours can be measured against.
	 *
	 * <p>The side being traded is in white and carries how far your own offer
	 * is from it, since that is the only price yours can be measured against.
	 * The other side is grey: it is not what you are doing now, but it is what
	 * you will do next, and a buy that has filled is a sale about to be listed.
	 * The margin underneath is what the pair of them come to after tax.
	 *
	 * <p>The comparison is still against your own side, because that is the
	 * only one your offer can be measured against.
	 *
	 * <p>Null for a slot with nothing in it, or one whose item nobody has a
	 * price for -- which is every item before the first fetch lands. Static, so
	 * the wording is pinned by a test rather than by running a client.
	 */
	@Nullable
	static String textFor(@Nullable GrandExchangeOffer offer, @Nullable Quote quote, int rows)
	{
		if (offer == null || quote == null)
		{
			return null;
		}
		final GrandExchangeOfferState state = offer.getState();
		if (state == null || state == GrandExchangeOfferState.EMPTY)
		{
			return null;
		}
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
		// Positive means the offer is on the side of the price that fills
		// sooner: a buy at or above the site's buy price, a sale at or below
		// its sell price. Negative is the patient end of the flip, which is
		// where the profit is and also where an offer can sit all evening --
		// so it is stated rather than judged.
		final long edge = buying ? offer.getPrice() - market : market - offer.getPrice();
		// Short: an offer box is the smallest space any of this goes in, and it
		// already says what the item is and which way you are trading it. The
		// words "buy" and "sell" would be repeating the box back at itself, so
		// the side being traded is picked out in white instead and the two
		// prices stand on their own.
		final String yours = line(buying ? "Buy" : "Sell",
			buying ? quote.getBuyAt() : quote.getSellAt(), true, edge);
		if (rows <= 0)
		{
			// No room was made, so this goes on the end of the line that is
			// already there and says only what will fit: how far off you are.
			return colour("  ", MUTED)
				+ colour(FlippingRsPanel.signed(edge), edge >= 0 ? GOOD : BAD);
		}
		if (rows == 1)
		{
			return yours;
		}
		final String other = line(buying ? "Sell" : "Buy",
			buying ? quote.getSellAt() : quote.getBuyAt(), false, null);
		if (rows == 2)
		{
			// Your side first: the other one is what you will do next, not now.
			return yours + "<br>" + other;
		}
		return yours + "<br>" + other + "<br>"
			+ colour(FlippingRsPanel.signed(quote.getNetMargin()),
				quote.getNetMargin() >= 0 ? GOOD : BAD);
	}

	/**
	 * One of the two price lines: what the site says that side is worth, and --
	 * on the side you are trading -- how far your own offer is from it.
	 *
	 * @param yours whether this is the side being traded, which is said in
	 *              white rather than grey
	 * @param edge  how far your offer is from this price, or null on the side
	 *              you are not trading, where there is nothing to compare
	 */
	private static String line(String side, long price, boolean yours, @Nullable Long edge)
	{
		final String colour = yours ? VALUE : MUTED;
		final String out = colour(side + " ", MUTED) + colour(FlippingRsPanel.gp(price), colour);
		if (edge == null)
		{
			return out;
		}
		return out + colour("  ", MUTED)
			+ colour(FlippingRsPanel.signed(edge), edge >= 0 ? GOOD : BAD);
	}

	/** One run of text in one colour, as the game's own text renderer reads it. */
	static String colour(String text, String hex)
	{
		return "<col=" + hex + ">" + text + "</col>";
	}
}
