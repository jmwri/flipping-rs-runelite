package com.flippingrs;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY;
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The tracker is where a bug turns into wrong money in somebody's journal, and
 * it is pure, so it gets the tests.
 */
public class OfferTrackerTest
{
	private static final int WHIP = 4151;
	private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

	/** A Grand Exchange slot as the client would report it. */
	private static class Offer implements GrandExchangeOffer
	{
		private final GrandExchangeOfferState state;
		private final int itemId;
		private final int price;
		private final int total;
		private final int sold;
		private final int spent;

		Offer(GrandExchangeOfferState state, int itemId, int price, int total, int sold, int spent)
		{
			this.state = state;
			this.itemId = itemId;
			this.price = price;
			this.total = total;
			this.sold = sold;
			this.spent = spent;
		}

		@Override
		public int getQuantitySold()
		{
			return sold;
		}

		@Override
		public int getItemId()
		{
			return itemId;
		}

		@Override
		public int getTotalQuantity()
		{
			return total;
		}

		@Override
		public int getPrice()
		{
			return price;
		}

		@Override
		public int getSpent()
		{
			return spent;
		}

		@Override
		public GrandExchangeOfferState getState()
		{
			return state;
		}
	}

	private final OfferTracker tracker = new OfferTracker(() -> "ref-1");

	/** Counts how often the item name was actually asked for. */
	private final AtomicInteger nameLookups = new AtomicInteger();

	private OfferTracker.Observation observe(SavedOffer previous, GrandExchangeOffer offer)
	{
		return tracker.observe(3, previous, offer,
			() ->
			{
				nameLookups.incrementAndGet();
				return "Abyssal whip";
			},
			302, NOW);
	}

	@Test
	public void newOfferIsBaselinedAndReportsNothing()
	{
		final OfferTracker.Observation seen = observe(null, new Offer(BUYING, WHIP, 1000, 10, 0, 0));

		assertNull("placing an offer is not a trade", seen.transaction);
		assertNotNull(seen.saved);
		assertEquals("ref-1", seen.saved.offerRef);
		assertFalse("an empty offer has no history to adopt", seen.adopted);
	}

	@Test
	public void partialFillIsReportedAsTheDifference()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 0, 0), "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(BUYING, WHIP, 1000, 10, 4, 3800));

		assertNotNull(seen.transaction);
		assertEquals(4, seen.transaction.quantity);
		// 3800, not 4 * 1000: a buy fills at or under the price offered, and the
		// difference is the user's, not a rounding artefact.
		assertEquals(3800, seen.transaction.grossValue);
		assertEquals("buy", seen.transaction.side);
		assertEquals("ref-1", seen.transaction.offerRef);
		assertFalse(seen.transaction.completed);
		assertFalse(seen.transaction.estimated);
	}

	@Test
	public void onlyTheNewQuantityIsReportedOnASecondFill()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 4, 3800), "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(BOUGHT, WHIP, 1000, 10, 10, 9800));

		assertNotNull(seen.transaction);
		assertEquals("6 more, not the running total of 10", 6, seen.transaction.quantity);
		assertEquals(6000, seen.transaction.grossValue);
		assertTrue("BOUGHT is terminal", seen.transaction.completed);
		assertEquals("both fills belong to one purchase", "ref-1", seen.transaction.offerRef);
	}

	/**
	 * The login burst. The client re-fires every occupied slot on login with the
	 * quantity already sold; with a persisted baseline the difference is zero.
	 */
	@Test
	public void replayingTheSameStateReportsNothing()
	{
		final Offer offer = new Offer(BUYING, WHIP, 1000, 10, 4, 3800);
		final SavedOffer previous = SavedOffer.of(offer, "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, offer);

		assertNull("a login replay is not a new trade", seen.transaction);
		assertNotNull(seen.saved);
	}

	/**
	 * Installing the plugin mid-flip. The progress happened unobserved, so it
	 * is adopted as the baseline and reported once as a recovered fill with
	 * no time on it, for the server to decide whether it is new. It is never
	 * backdated to now.
	 */
	@Test
	public void anOfferAlreadyInProgressIsRecoveredNotInvented()
	{
		final OfferTracker.Observation seen = observe(null, new Offer(BUYING, WHIP, 1000, 10, 6, 5900));

		assertNotNull("what had filled is reported, flagged, for the server to judge", seen.transaction);
		assertEquals(GeTransaction.SOURCE_ADOPTED, seen.transaction.source);
		assertEquals(6, seen.transaction.quantity);
		assertEquals("the exact gp it found, not price times quantity", 5900, seen.transaction.grossValue);
		assertNull("but no time is claimed for it", seen.transaction.occurredAt);
		assertEquals("ref-1", seen.transaction.offerRef);
		assertFalse(seen.transaction.completed);
		assertTrue(seen.adopted);
		assertNotNull(seen.saved);
		assertEquals(6, seen.saved.quantitySold);

		// From the baseline on, further fills are reported normally, and live.
		final OfferTracker.Observation next = observe(seen.saved, new Offer(BOUGHT, WHIP, 1000, 10, 10, 9900));
		assertNotNull(next.transaction);
		assertEquals(4, next.transaction.quantity);
		assertEquals(GeTransaction.SOURCE_LIVE, next.transaction.source);
		assertEquals("one purchase, both parts", "ref-1", next.transaction.offerRef);
	}

	/** An offer found already finished is recovered whole, and marked complete. */
	@Test
	public void anOfferFoundAlreadyCompleteIsRecoveredAsComplete()
	{
		final OfferTracker.Observation seen = observe(null, new Offer(SOLD, WHIP, 1000, 10, 10, 10_500));

		assertNotNull(seen.transaction);
		assertEquals("sell", seen.transaction.side);
		assertEquals(10, seen.transaction.quantity);
		assertEquals(10_500, seen.transaction.grossValue);
		assertTrue(seen.transaction.completed);
		assertNull(seen.transaction.occurredAt);
	}

	@Test
	public void collectingASlotClearsTheBaseline()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BOUGHT, WHIP, 1000, 10, 10, 9800), "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(EMPTY, 0, 0, 0, 0, 0));

		assertNull(seen.transaction);
		assertNull("an emptied slot must forget its offer, or the next one is mistaken for it", seen.saved);
	}

	/**
	 * Re-placing an identical offer after collecting. Item, price and size all
	 * match the old one, and only the progress going backwards says otherwise.
	 */
	@Test
	public void anIdenticalReplacementOfferIsANewOffer()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BOUGHT, WHIP, 1000, 10, 10, 9800), "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(BUYING, WHIP, 1000, 10, 0, 0));

		assertNull("nothing has traded on the new offer yet", seen.transaction);
		assertNotNull(seen.saved);
		assertEquals(0, seen.saved.quantitySold);
	}

	/**
	 * A buy placed and cancelled untouched, then a sell of the same item at the
	 * same price and size, with the collect between them never observed. Both
	 * sit at zero progress, and only the side says they are different offers.
	 */
	@Test
	public void aBuyAndASellOfTheSameSizeAreDifferentOffers()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(CANCELLED_BUY, WHIP, 1000, 10, 0, 0), "old", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(SELLING, WHIP, 1000, 10, 0, 0));

		assertNull(seen.transaction);
		assertNotNull(seen.saved);
		assertEquals("a new offer gets its own reference", "ref-1", seen.saved.offerRef);
	}

	@Test
	public void aCancelledBuyStillReportsWhatFilled()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 2, 2000), "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(CANCELLED_BUY, WHIP, 1000, 10, 5, 5000));

		assertNotNull(seen.transaction);
		assertEquals(3, seen.transaction.quantity);
		assertTrue(seen.transaction.cancelled);
		assertTrue(seen.transaction.completed);
	}

	@Test
	public void aCancellationWithNoFillReportsNothing()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 0, 0), "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(CANCELLED_BUY, WHIP, 1000, 10, 0, 0));

		assertNull("cancelling an untouched offer is not a trade", seen.transaction);
		assertNotNull("but the cancellation still has to be remembered", seen.saved);
		assertEquals(CANCELLED_BUY, seen.saved.state);
	}

	@Test
	public void sellsAreReportedAsSells()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(SELLING, WHIP, 1000, 10, 0, 0), "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(SOLD, WHIP, 1000, 10, 10, 10_500));

		assertNotNull(seen.transaction);
		assertEquals("sell", seen.transaction.side);
		// A sale fills at the asking price or better, so more than asked is
		// normal and must not be mistaken for a corrupt total.
		assertEquals(10_500, seen.transaction.grossValue);
		assertFalse(seen.transaction.estimated);
	}

	/**
	 * The client's running total is an int, and a big enough offer overflows it.
	 * A wrapped total must not become a wrong profit.
	 */
	@Test
	public void anImpossibleRunningTotalFallsBackToPriceTimesQuantity()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BUYING, WHIP, 3_000_000, 1000, 0, 0), "ref-1", false);
		// 800 whips at 3M is 2.4 billion, past what an int holds, so the client
		// reports a wrapped negative.
		final int wrapped = (int) (800L * 3_000_000L);

		final OfferTracker.Observation seen =
			observe(previous, new Offer(BUYING, WHIP, 3_000_000, 1000, 800, wrapped));

		assertNotNull(seen.transaction);
		assertEquals(800, seen.transaction.quantity);
		assertEquals(800L * 3_000_000L, seen.transaction.grossValue);
		assertTrue("the figure is approximate and says so", seen.transaction.estimated);
	}

	/**
	 * A total that wrapped once between two observations. The difference
	 * modulo 2^32 is exact, and it used to be discarded as negative.
	 */
	@Test
	public void aTotalThatWrappedOnceIsRecoveredExactly()
	{
		final SavedOffer previous = SavedOffer.of(
			new Offer(BUYING, WHIP, 2_000_000, 1100, 1000, 2_000_000_000), "ref-1", false);
		final int wrapped = (int) 2_200_000_000L;

		final OfferTracker.Observation seen =
			observe(previous, new Offer(BOUGHT, WHIP, 2_000_000, 1100, 1100, wrapped));

		assertNotNull(seen.transaction);
		assertEquals(100, seen.transaction.quantity);
		assertEquals(200_000_000L, seen.transaction.grossValue);
		assertFalse("exact, so not flagged", seen.transaction.estimated);
	}

	/**
	 * A wrap that lands positive. 2000 at 3M is 6.0B; the int reads 1.7B,
	 * which is under the 6.0B asked and used to pass as a plausible buy. The
	 * fill alone is bigger than the total could ever have tracked, so the
	 * total is not consulted.
	 */
	@Test
	public void aFillTooLargeForTheRunningTotalIsEstimatedEvenWhenTheWrapLandsPositive()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BUYING, WHIP, 3_000_000, 2000, 0, 0), "ref-1", false);
		final int wrapped = (int) 6_000_000_000L;
		assertTrue("the premise: the wrap lands positive", wrapped > 0);

		final OfferTracker.Observation seen =
			observe(previous, new Offer(BOUGHT, WHIP, 3_000_000, 2000, 2000, wrapped));

		assertNotNull(seen.transaction);
		assertEquals(6_000_000_000L, seen.transaction.grossValue);
		assertTrue(seen.transaction.estimated);
	}

	@Test
	public void aBuyCostingMoreThanOfferedIsRejectedAsImpossible()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 0, 0), "ref-1", false);

		// The exchange never fills a buy above the offered price, so 12,000 for
		// 10 at 1,000 cannot be right.
		final OfferTracker.Observation seen = observe(previous, new Offer(BUYING, WHIP, 1000, 10, 10, 12_000));

		assertNotNull(seen.transaction);
		assertEquals(10_000, seen.transaction.grossValue);
		assertTrue(seen.transaction.estimated);
	}

	@Test
	public void aSaleYieldingLessThanAskedIsRejectedAsImpossible()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(SELLING, WHIP, 1000, 10, 0, 0), "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(SELLING, WHIP, 1000, 10, 10, 8_000));

		assertNotNull(seen.transaction);
		assertEquals(10_000, seen.transaction.grossValue);
		assertTrue(seen.transaction.estimated);
	}

	@Test
	public void everyFillCarriesTheContextTheServerNeeds()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 0, 0), "ref-1", false);

		final OfferTracker.Observation seen = observe(previous, new Offer(BUYING, WHIP, 1000, 10, 4, 4000));

		assertNotNull(seen.transaction);
		assertEquals(WHIP, seen.transaction.itemId);
		assertEquals("Abyssal whip", seen.transaction.itemName);
		assertEquals(3, seen.transaction.slot);
		assertEquals(302, seen.transaction.world);
		assertEquals(1000, seen.transaction.offerPrice);
		assertEquals(10, seen.transaction.offerTotal);
		assertEquals(NOW.toString(), seen.transaction.occurredAt);
		assertFalse("every fill needs an idempotency key", seen.transaction.id.isEmpty());
	}

	/**
	 * A baseline with no offer reference -- an older config value, or a
	 * half-written one. Carrying it forward would send every later fill with an
	 * empty offerRef, and the server groups fills into one purchase by exactly
	 * that field, so a single buy would land as a string of unrelated lots.
	 */
	@Test
	public void aBaselineWithNoOfferRefIsReadopted()
	{
		final SavedOffer stale = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 4, 4000), null, false);

		final OfferTracker.Observation seen = observe(stale, new Offer(BUYING, WHIP, 1000, 10, 6, 6000));

		assertNotNull("the progress is reported as recovered under the new reference", seen.transaction);
		assertEquals(GeTransaction.SOURCE_ADOPTED, seen.transaction.source);
		assertEquals(6, seen.transaction.quantity);
		assertNotNull(seen.saved);
		assertEquals("ref-1", seen.saved.offerRef);
	}

	@Test
	public void eachFillGetsItsOwnIdempotencyKey()
	{
		final SavedOffer first = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 0, 0), "ref-1", false);
		final OfferTracker.Observation a = observe(first, new Offer(BUYING, WHIP, 1000, 10, 4, 4000));

		assertNotNull(a.transaction);
		final OfferTracker.Observation b = observe(a.saved, new Offer(BOUGHT, WHIP, 1000, 10, 10, 10_000));

		assertNotNull(b.transaction);
		assertFalse("two fills sharing an id would silently drop one",
			a.transaction.id.equals(b.transaction.id));
	}

	/**
	 * Resolving the item name means asking the client's item manager, and most
	 * events are a state change or a login replay with nothing to report. The
	 * lookup belongs on the path that produces a fill, not on every event the
	 * game thread hands over.
	 */
	@Test
	public void theItemNameIsOnlyLookedUpWhenSomethingFilled()
	{
		final SavedOffer previous = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 4, 4000), "ref-1", false);

		observe(null, new Offer(BUYING, WHIP, 1000, 10, 0, 0));      // a new offer
		observe(previous, new Offer(BUYING, WHIP, 1000, 10, 4, 4000)); // a replay
		observe(previous, new Offer(CANCELLED_BUY, WHIP, 1000, 10, 4, 4000)); // no progress
		observe(previous, new Offer(EMPTY, 0, 0, 0, 0, 0));           // collected

		assertEquals("none of those reported a fill", 0, nameLookups.get());

        observe(previous, new Offer(BOUGHT, WHIP, 1000, 10, 10, 10_000));

		assertEquals("and this one did", 1, nameLookups.get());
	}
}
