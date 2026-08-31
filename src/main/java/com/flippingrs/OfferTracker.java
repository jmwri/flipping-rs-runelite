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
 *       to subtract. That progress is <em>adopted</em> as the new baseline and
 *       reported as nothing: we do not know when it happened and it may already
 *       be in the journal. Inventing a fill dated now would be worse than
 *       missing one, because the user cannot tell a wrong entry from a right
 *       one, and a wrong one quietly poisons every average built on it.
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
			// we were not watching, so it becomes the baseline and is not
			// reported. A brand new offer has nothing filled yet, and this is
			// simply where it gets its reference.
			final boolean adopted = offer.getQuantitySold() > 0;
			return new Observation(null, SavedOffer.of(offer, refs.get(), adopted), adopted);
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

		final boolean buy = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;

		final GeTransaction tx = new GeTransaction();
		tx.id = UUID.randomUUID().toString();
		tx.offerRef = previous.offerRef;
		tx.itemId = offer.getItemId();
		tx.itemName = itemName.get();
		tx.side = buy ? "buy" : "sell";
		tx.quantity = filled;
		tx.offerPrice = offer.getPrice();
		tx.offerTotal = offer.getTotalQuantity();
		tx.slot = slot;
		tx.world = world;
		tx.occurredAt = now.toString();
		tx.completed = isTerminal(state);
		tx.cancelled = state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.CANCELLED_SELL;

		final long[] value = grossValue(offer, previous, filled, buy);
		tx.grossValue = value[0];
		tx.estimated = value[1] != 0;

		return new Observation(tx, updated, false);
	}

	/**
	 * The gp that moved in this fill, and whether it had to be estimated.
	 *
	 * <p>The client's running total is an {@code int}, and a large offer can
	 * move more gold than an int holds -- a thousand items at three million each
	 * is well past it. When that wraps, the difference between two observations
	 * is nonsense, and nonsense here becomes a wrong profit figure in somebody's
	 * journal.
	 *
	 * <p>So the difference is sanity checked against a rule the exchange
	 * guarantees: a buy never fills above the price you offered, and a sale
	 * never fills below the price you asked. A difference that breaks that rule
	 * is discarded in favour of price times quantity and flagged, because a
	 * figure that is approximately right and known to be approximate is worth
	 * far more than one that is exactly wrong.
	 *
	 * @return {@code [grossValue, estimatedFlag]}
	 */
	private static long[] grossValue(GrandExchangeOffer offer, SavedOffer previous, long filled, boolean buy)
	{
		final long spent = (long) offer.getSpent() - previous.spent;
		final long price = offer.getPrice();
		final long fallback = filled * price;

		if (spent <= 0)
		{
			return new long[]{fallback, 1};
		}
		// Compare totals rather than a per-item average, so an offer that filled
		// at a mix of prices is not rejected by integer division.
		final long asked = filled * price;
		final boolean plausible = buy ? spent <= asked : spent >= asked;
		return plausible ? new long[]{spent, 0} : new long[]{fallback, 1};
	}

	private static boolean isTerminal(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.SOLD
			|| state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.CANCELLED_SELL;
	}
}
