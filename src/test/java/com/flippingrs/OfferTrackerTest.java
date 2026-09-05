package com.flippingrs;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
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

	/**
	 * A cancelled offer reports what filled before it stopped, and says it is
	 * finished. Both sides: an offer the server still believes is live is one
	 * it will keep matching later sales against, and a sale can be cancelled
	 * exactly as a purchase can.
	 */
	@Test
	public void aCancelledOfferStillReportsWhatFilled()
	{
		for (boolean buy : new boolean[]{true, false})
		{
			final SavedOffer previous = SavedOffer.of(
				new Offer(buy ? BUYING : SELLING, WHIP, 1000, 10, 2, 2000), "ref-1", false);

			final OfferTracker.Observation seen = observe(previous,
				new Offer(buy ? CANCELLED_BUY : CANCELLED_SELL, WHIP, 1000, 10, 5, 5000));

			final String side = buy ? "a cancelled buy" : "a cancelled sale";
			assertNotNull(side, seen.transaction);
			assertEquals(side, 3, seen.transaction.quantity);
			assertTrue(side + " is cancelled", seen.transaction.cancelled);
			assertTrue(side + " is finished", seen.transaction.completed);
		}
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
	 *
	 * <p>Both shapes of "none" count. A value written by a version that had no
	 * references at all reads back as null; one written by a config save that
	 * did not finish reads back as the empty string, and an empty reference is
	 * exactly the one that would be sent on every later fill.
	 */
	@Test
	public void aBaselineWithNoOfferRefIsReadopted()
	{
		for (String none : new String[]{null, ""})
		{
			final SavedOffer stale = SavedOffer.of(new Offer(BUYING, WHIP, 1000, 10, 4, 4000), none, false);

			final OfferTracker.Observation seen = observe(stale, new Offer(BUYING, WHIP, 1000, 10, 6, 6000));

			final String shape = none == null ? "a null reference" : "an empty reference";
			assertNotNull(shape + ": the progress is reported as recovered under a new one",
				seen.transaction);
			assertEquals(shape, GeTransaction.SOURCE_ADOPTED, seen.transaction.source);
			assertEquals(shape, 6, seen.transaction.quantity);
			assertNotNull(shape, seen.saved);
			assertEquals(shape, "ref-1", seen.saved.offerRef);
		}
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

	/**
	 * A collect that was never seen costs nothing, as long as the two offers
	 * can be told apart at all.
	 *
	 * <p>The other property test collects between every offer, so the slot is
	 * always empty before the next one and the tracker never has to decide.
	 * The interesting case is the collect going unseen -- the event not
	 * arriving, or the plugin not running for it -- which leaves the previous
	 * offer's baseline sitting in the slot when a new offer arrives.
	 *
	 * <p>Two identical finished offers cannot be told apart and never will be.
	 * Everything else can: a different price, a different size, the other side
	 * of the book, or an offer that had finished being followed by one that is
	 * running. So every offer here differs from the one before it in exactly
	 * one of those ways, and the count still has to come out exact.
	 *
	 * <p>The first look at an offer is sometimes mid-flight rather than at
	 * placement, which is what a login burst looks like.
	 */
	@Test
	public void aMissedCollectCostsNothingWhenTheOffersDiffer()
	{
		final java.util.Random random = new java.util.Random(20260906L);
		for (int run = 0; run < 3000; run++)
		{
			SavedOffer saved = null;
			long reported = 0;
			long actual = 0;
			int price = 2 + random.nextInt(1000);
			int total = 2 + random.nextInt(20);
			boolean buy = random.nextBoolean();

			final int offers = 2 + random.nextInt(3);
			for (int o = 0; o < offers; o++)
			{
				// How this offer differs from the one before it. The first
				// offer has nothing to differ from.
				if (o > 0)
				{
					switch (random.nextInt(4))
					{
						case 0:
							price = price + 1 + random.nextInt(100);
							break;
						case 1:
							total = total + 1 + random.nextInt(10);
							break;
						case 2:
							buy = !buy;
							break;
						default:
							// Nothing changes: the previous offer having
							// finished and this one running is the whole of
							// what separates them.
							break;
					}
				}

				final GrandExchangeOfferState running = buy ? BUYING : SELLING;
				final int looks = 2 + random.nextInt(6);
				// Skipping the placement is a login burst: the first sight of
				// the offer already has fills on it.
				if (random.nextBoolean())
				{
					final OfferTracker.Observation placed =
						observe(saved, new Offer(running, WHIP, price, total, 0, 0));
					saved = placed.saved;
					if (placed.transaction != null)
					{
						reported += placed.transaction.quantity;
					}
				}

				int sold = 0;
				int spent = 0;
				for (int look = 1; look <= looks; look++)
				{
					final int more = random.nextInt(Math.max(1, total - sold + 1));
					sold += more;
					spent += more * price;
					final GrandExchangeOfferState state = look < looks
						? running
						: (random.nextBoolean()
							? (buy ? BOUGHT : SOLD)
							: (buy ? CANCELLED_BUY : CANCELLED_SELL));

					final OfferTracker.Observation seen =
						observe(saved, new Offer(state, WHIP, price, total, sold, spent));
					saved = seen.saved;
					if (seen.transaction != null)
					{
						reported += seen.transaction.quantity;
					}
				}
				actual += sold;

				// The collect, half the time. When it is missed the next offer
				// meets this one's baseline still in the slot.
				if (random.nextBoolean())
				{
					saved = observe(saved, new Offer(EMPTY, 0, 0, 0, 0, 0)).saved;
				}
			}

			assertEquals("run " + run + ": every item filled reported exactly once",
				actual, reported);
		}
	}

	/**
	 * An offer that was finished cannot be running again.
	 *
	 * <p>Cancel a buy that is part filled, collect it, and place the same buy
	 * over again -- the same item at the same price for the same number, which
	 * is what flipping is. If the collect between them is missed, the only
	 * things saying these are two purchases are that the first one had
	 * finished and the second is under way. The progress does not say it: the
	 * new offer picks up where the old one left off and passes straight
	 * through the check that the count has not gone backwards.
	 *
	 * <p>Read as one offer, the fills the new one already has are counted as
	 * the difference from the old one's, so most of them are never reported at
	 * all, and the ones that are go out under the finished purchase's
	 * reference.
	 */
	@Test
	public void aFinishedOfferIsNotTheSameAsOneThatIsRunning()
	{
		// Cancelled with three of ten bought, and those three already sent.
		final SavedOffer cancelled = SavedOffer.of(
			new Offer(CANCELLED_BUY, WHIP, 1000, 10, 3, 3000), "ref-old", false);

		// The same buy placed again, and five of ten filled before we look.
		final OfferTracker.Observation seen =
			observe(cancelled, new Offer(BUYING, WHIP, 1000, 10, 5, 5000));

		assertNotNull("a purchase that is under way is not the cancelled one", seen.transaction);
		assertEquals("all five of the new offer's fills, not two",
			5, seen.transaction.quantity);
		assertEquals(GeTransaction.SOURCE_ADOPTED, seen.transaction.source);
		assertNotNull(seen.saved);
		assertEquals("and a reference of its own", "ref-1", seen.saved.offerRef);
	}

	/**
	 * The collect between two identical offers was never seen -- the client was
	 * killed holding a finished purchase, or the event did not arrive -- so the
	 * slot goes straight from a completed offer to an identical new one. The
	 * item, the price and the size all match; the only thing saying this is a
	 * different purchase is that the progress went backwards.
	 *
	 * <p>Without that check the new offer keeps the finished one's reference,
	 * and the server groups fills into one purchase by exactly that field: two
	 * purchases would arrive as one, with the second one's cost folded into the
	 * first.
	 */
	@Test
	public void anIdenticalOfferAfterAMissedCollectIsANewPurchase()
	{
		final SavedOffer finished = SavedOffer.of(
			new Offer(BOUGHT, WHIP, 1000, 10, 10, 10_000), "ref-old", false);

		final OfferTracker.Observation placed =
			observe(finished, new Offer(BUYING, WHIP, 1000, 10, 0, 0));

		assertNull("placing it is not a trade", placed.transaction);
		assertNotNull(placed.saved);
		assertEquals("a second purchase needs a reference of its own", "ref-1", placed.saved.offerRef);

		final OfferTracker.Observation filled =
			observe(placed.saved, new Offer(BUYING, WHIP, 1000, 10, 4, 3_900));

		assertNotNull(filled.transaction);
		assertEquals(4, filled.transaction.quantity);
		assertEquals("its fills must not join the purchase that had already finished",
			"ref-1", filled.transaction.offerRef);
	}

	/**
	 * Each of the things that identify an offer has to count. A finished
	 * purchase collected unseen, then a new one in the same slot that is
	 * already full: the progress cannot tell those apart, so the item, the
	 * price and the size are all that is left. Drop any one of them and the
	 * new purchase's fills are filed under the finished one, because fills are
	 * grouped into a purchase by its reference.
	 */
	@Test
	public void anOfferDifferingInAnyOneThingIsANewPurchase()
	{
		final SavedOffer finished = SavedOffer.of(
			new Offer(BOUGHT, WHIP, 1000, 10, 10, 10_000), "ref-old", false);

		final GrandExchangeOffer[] differing = {
			new Offer(BOUGHT, 11802, 1000, 10, 10, 10_000),   // another item
			new Offer(BOUGHT, WHIP, 2000, 10, 10, 20_000),    // another price
			new Offer(BOUGHT, WHIP, 1000, 20, 20, 20_000),    // another size
		};
		for (GrandExchangeOffer offer : differing)
		{
			final OfferTracker.Observation seen = observe(finished, offer);

			assertNotNull(seen.saved);
			assertEquals("this is not the purchase that finished", "ref-1", seen.saved.offerRef);
			assertNotNull("and what it has already filled is recovered", seen.transaction);
			assertEquals("ref-1", seen.transaction.offerRef);
		}
	}

	/**
	 * Over a randomised life of one slot -- offers placed, partly filled at
	 * mixed prices, completed or cancelled, collected, and the slot reused --
	 * every item the exchange filled is reported exactly once, and the gp
	 * reported adds up to exactly what the client's running total moved by.
	 *
	 * <p>This is the class where a mistake is wrong money rather than a wrong
	 * pixel, and its arithmetic is the kind that can be subtly wrong in a way
	 * no hand-written case happens to hit: an off-by-one on a boundary, a
	 * delta taken against the wrong baseline, a slot reuse counted twice.
	 * Three thousand randomised lives cover ground a person would not think
	 * to write down.
	 *
	 * <p>The offers generated stay inside the rules the exchange guarantees --
	 * an offer never moves more than max cash, a buy fills at or under the
	 * ask, a sale at or over it -- so nothing here should ever come back
	 * flagged as approximate either.
	 */
	@Test
	public void everyItemFilledIsReportedOnceOverTheLifeOfASlot()
	{
		final java.util.Random random = new java.util.Random(20260903L);
		for (int run = 0; run < 3000; run++)
		{
			SavedOffer saved = null;
			long reportedQuantity = 0;
			long reportedGross = 0;
			long actuallyFilled = 0;
			long actuallyMoved = 0;

			final int offers = 1 + random.nextInt(4);
			for (int o = 0; o < offers; o++)
			{
				final boolean buy = random.nextBoolean();
				final GrandExchangeOfferState running = buy ? BUYING : SELLING;
				// Two or more, so a fill can never come to nothing; and small
				// enough that one offer stays well inside max cash, which is
				// what the exchange itself caps an offer at.
				final int price = 2 + random.nextInt(1_000_000);
				final int total = 1 + random.nextInt(Math.max(1, Math.min(1000, Integer.MAX_VALUE / 2 / price)));

				// Placed: nothing filled, so nothing to adopt.
				OfferTracker.Observation seen = observe(saved, new Offer(running, WHIP, price, total, 0, 0));
				saved = seen.saved;
				assertNull("placing an offer is not a trade", seen.transaction);

				int sold = 0;
				int spent = 0;
				final int looks = 1 + random.nextInt(8);
				for (int look = 1; look <= looks; look++)
				{
					final int more = random.nextInt(Math.max(1, total - sold + 1));
					sold += more;
					// A buy fills at or under the asking price and a sale at or
					// over it, which is what the tracker checks the client's
					// total against.
					final long moved = buy
						? (long) more * price - random.nextInt(Math.max(1, more))
						: (long) more * price + random.nextInt(Math.max(1, more));
					// The client's running total is an int, so this one has to
					// be, and the narrowing has to be said out loud: the sizes
					// chosen above keep price times quantity inside half an
					// int, and if that ever stops being true this test would
					// otherwise compare the tracker's answer against a total
					// that quietly wrapped on the way in.
					assertTrue("run " + run + ": the offer has to stay inside an int, as the exchange's own do",
						spent + moved <= Integer.MAX_VALUE);
					spent += (int) moved;

					final GrandExchangeOfferState state;
					if (look < looks)
					{
						state = running;
					}
					else if (random.nextBoolean())
					{
						state = buy ? BOUGHT : SOLD;
					}
					else
					{
						state = buy ? CANCELLED_BUY : CANCELLED_SELL;
					}

					seen = observe(saved, new Offer(state, WHIP, price, total, sold, spent));
					saved = seen.saved;
					if (seen.transaction != null)
					{
						reportedQuantity += seen.transaction.quantity;
						reportedGross += seen.transaction.grossValue;
						assertFalse("run " + run + ": an offer inside the exchange's own rules is not a guess",
							seen.transaction.estimated);
					}
				}
				actuallyFilled += sold;
				actuallyMoved += spent;

				// Collected, freeing the slot for the next offer.
				seen = observe(saved, new Offer(EMPTY, 0, 0, 0, 0, 0));
				saved = seen.saved;
				assertNull("collecting is not a trade", seen.transaction);
				assertNull("and it forgets the slot", saved);
			}

			assertEquals("run " + run + ": every item filled must be reported exactly once",
				actuallyFilled, reportedQuantity);
			assertEquals("run " + run + ": the gp reported must be the gp the client tracked",
				actuallyMoved, reportedGross);
		}
	}
}
