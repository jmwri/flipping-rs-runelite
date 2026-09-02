package com.flippingrs;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Turns the stream of Grand Exchange slot updates into discrete fills.
 *
 * <p>The client does not tell you "you just bought 25 whips". It tells you what
 * a slot looks like now, over and over, and the trade is the difference between
 * consecutive looks. Everything difficult about this plugin lives here:
 *
 * <ul>
 *   <li><b>Login replay.</b> On every login the client re-fires an event for
 *       each occupied slot, carrying the quantity already sold. Those are not
 *       new trades. The baseline is persisted across sessions so the difference
 *       comes out as zero.
 *   <li><b>Offers we never saw start.</b> Install the plugin mid-flip, or log in
 *       on a fresh machine, and a slot is already part filled with no baseline
 *       to subtract. That progress is <em>adopted</em> as the new baseline, and
 *       reported once as a fill marked {@code adopted} with no time: it is
 *       real, but nobody watched it happen, and it may already be in the
 *       journal from another machine. The server decides whether it is new.
 *       Dating it "now" would be worse than either, because a sale stamped
 *       after the purchase it belongs to turns a real flip into an unmatched
 *       sale.
 *   <li><b>Slot reuse.</b> A finished offer is collected and the slot re-used,
 *       possibly for an identical offer. {@link SavedOffer#isSameOfferAs} draws
 *       the line.
 *   <li><b>A running total that is an int.</b> See {@link #grossValue}.
 * </ul>
 *
 * <p>Deliberately stateless. The previous state of a slot is passed in and the
 * new state comes back out, because the only correct home for it is RuneLite's
 * per-RuneScape-account config, and reading that lazily at event time avoids
 * racing the login burst against a pre-load. It also means the whole of the
 * logic above is testable with no client and no game running.
 */
public class OfferTracker
{
	private final Supplier<String> refs;

	public OfferTracker()
	{
		this(() -> UUID.randomUUID().toString());
	}

	/** Test seam: lets a test pin the generated identifiers. */
	OfferTracker(Supplier<String> refs)
	{
		this.refs = refs;
	}

	/** What one observation of a slot means. */
	public static class Observation
	{
		/** The fill to report, or null when nothing traded. */
		@Nullable
		public final GeTransaction transaction;
		/** The state to persist for this slot, or null to forget the slot. */
		@Nullable
		public final SavedOffer saved;
		/** This offer was part-finished the first time we saw it. */
		public final boolean adopted;

		Observation(@Nullable GeTransaction transaction, @Nullable SavedOffer saved, boolean adopted)
		{
			this.transaction = transaction;
			this.saved = saved;
			this.adopted = adopted;
		}
	}

	/**
	 * Works out what changed in a slot.
	 *
	 * @param previous what was last persisted for this slot, or null if nothing
	 * @param itemName resolves the name, and is consulted only when there is
	 *                 actually a fill to report. Most events are a state change
	 *                 or a login replay, and looking the name up for those is
	 *                 work the game thread does not need to do.
	 * @param world    the world it happened on, for the audit trail
	 * @param now      when the observation was made
	 */
	public Observation observe(
		int slot,
		@Nullable SavedOffer previous,
		GrandExchangeOffer offer,
		Supplier<String> itemName,
		int world,
		Instant now)
	{
		final GrandExchangeOfferState state = offer.getState();

		if (state == GrandExchangeOfferState.EMPTY)
		{
			// The slot was collected and freed. Nothing traded in the act of
			// collecting -- the fills were reported as they happened -- so this
			// only clears the baseline, which is what lets the next offer in
			// this slot be recognised as a new one.
			return new Observation(null, null, false);
		}

		// A baseline with no reference is one written by a version that did not
		// have them, or a half-written config value. Treating it as the same
		// offer would send every later fill with an empty offerRef, and the
		// server groups fills into one purchase by exactly that field -- so an
		// entire buy would arrive as a string of unrelated one-item lots.
		// Re-adopting it instead costs the fills already seen and keeps the
		// rest correct.
		if (previous == null || previous.offerRef == null || previous.offerRef.isEmpty()
			|| !previous.isSameOfferAs(offer))
		{
			// First sight of this offer. Anything already filled happened while
			// we were not watching: it becomes the baseline, and goes out once
			// as a recovered fill with no time on it. A brand new offer has
			// nothing filled yet, and this is simply where it gets its
			// reference.
			final String ref = refs.get();
			final boolean adopted = offer.getQuantitySold() > 0;
			final SavedOffer saved = SavedOffer.of(offer, ref, adopted);
			if (!adopted)
			{
				return new Observation(null, saved, false);
			}
			final GeTransaction recovered = fill(slot, offer, ref, itemName, world,
				offer.getQuantitySold(), 0, null, GeTransaction.SOURCE_ADOPTED);
			return new Observation(recovered, saved, true);
		}

		final long filled = (long) offer.getQuantitySold() - previous.quantitySold;

		// The baseline advances even when nothing filled: a state change with no
		// progress -- BUYING to CANCELLED_BUY, say -- still has to be recorded,
		// or it is re-examined on every later event.
		final SavedOffer updated = SavedOffer.of(offer, previous.offerRef, previous.adopted);

		if (filled <= 0)
		{
			return new Observation(null, updated, false);
		}

		final GeTransaction tx = fill(slot, offer, previous.offerRef, itemName, world,
			filled, previous.spent, now.toString(), GeTransaction.SOURCE_LIVE);
		return new Observation(tx, updated, false);
	}

	/**
	 * One fill on an offer, whether watched or recovered.
	 *
	 * @param filled        how many items this fill covers
	 * @param previousSpent the running total before it, for the exact gp
	 * @param occurredAt    when, or null for a fill nobody watched
	 */
	private static GeTransaction fill(int slot, GrandExchangeOffer offer, String offerRef,
		Supplier<String> itemName, int world, long filled, int previousSpent,
		@Nullable String occurredAt, String source)
	{
		final GrandExchangeOfferState state = offer.getState();
		final boolean buy = SavedOffer.isBuy(state);

		final GeTransaction tx = new GeTransaction();
		tx.id = UUID.randomUUID().toString();
		tx.offerRef = offerRef;
		tx.itemId = offer.getItemId();
		tx.itemName = itemName.get();
		tx.side = buy ? "buy" : "sell";
		tx.quantity = filled;
		tx.offerPrice = offer.getPrice();
		tx.offerTotal = offer.getTotalQuantity();
		tx.slot = slot;
		tx.world = world;
		tx.occurredAt = occurredAt;
		tx.source = source;
		tx.completed = isTerminal(state);
		tx.cancelled = state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.CANCELLED_SELL;

		final long[] value = grossValue(offer, previousSpent, filled, buy);
		tx.grossValue = value[0];
		tx.estimated = value[1] != 0;
		return tx;
	}

	/**
	 * The gp that moved in this fill, and whether it had to be estimated.
	 *
	 * <p>The client's running total is an {@code int}. The exchange caps what a
	 * single offer can move at max cash, which is also the largest int, so in
	 * practice the total cannot wrap -- but a figure that becomes somebody's
	 * profit is not something to leave to "in practice". Three defences:
	 *
	 * <ul>
	 *   <li>If price times quantity for this fill alone is more than an int can
	 *       hold, the total could not have tracked it and is not consulted.
	 *   <li>The difference is taken modulo 2^32, which is exact across a single
	 *       wrap rather than nonsense. (A signed subtraction was exact only when
	 *       the wrap happened to land negative; one that landed positive passed
	 *       the checks below as a plausible, wrong number.)
	 *   <li>The result is checked against rules the exchange guarantees: a buy
	 *       never fills above the offer, a sale never below the ask, and no
	 *       offer moves more than max cash.
	 * </ul>
	 *
	 * <p>Anything that fails falls back to price times quantity and is flagged,
	 * because a figure that is approximately right and known to be approximate
	 * is worth far more than one that is exactly wrong.
	 *
	 * @return {@code [grossValue, estimatedFlag]}
	 */
	private static long[] grossValue(GrandExchangeOffer offer, int previousSpent, long filled, boolean buy)
	{
		final long price = offer.getPrice();
		// Compare totals rather than a per-item average, so an offer that filled
		// at a mix of prices is not rejected by integer division.
		final long asked = filled * price;
		final long[] fallback = {asked, 1};

		if (asked > Integer.MAX_VALUE)
		{
			return fallback;
		}
		final long spent = Integer.toUnsignedLong(offer.getSpent() - previousSpent);
		if (spent == 0 || spent > Integer.MAX_VALUE)
		{
			return fallback;
		}
		final boolean plausible = buy ? spent <= asked : spent >= asked;
		return plausible ? new long[]{spent, 0} : fallback;
	}

	private static boolean isTerminal(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.SOLD
			|| state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.CANCELLED_SELL;
	}
}
