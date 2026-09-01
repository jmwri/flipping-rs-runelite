package com.flippingrs;

import java.util.List;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The orchestration: what the game thread hands off, and what the sender does
 * with it. Each of these covers a decision that silently loses or misfiles a
 * trade when it goes wrong, which is why they are worth pinning even though the
 * setup costs more than the assertions.
 */
public class FlippingRsPluginBehaviourTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private FlippingRsPluginTestSupport support;

	@Before
	public void setUp() throws Exception
	{
		support = new FlippingRsPluginTestSupport(folder.newFolder("queues"));
	}

	@After
	public void tearDown()
	{
		support.close();
	}

	/** A Grand Exchange slot as the client reports it. */
	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int sold, int spent)
	{
		return new GrandExchangeOffer()
		{
			@Override
			public int getQuantitySold()
			{
				return sold;
			}

			@Override
			public int getItemId()
			{
				return 4151;
			}

			@Override
			public int getTotalQuantity()
			{
				return 10;
			}

			@Override
			public int getPrice()
			{
				return 1_000_000;
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
		};
	}

	private void fire(GrandExchangeOffer offer) throws Exception
	{
		final GrandExchangeOfferChanged event = new GrandExchangeOfferChanged();
		event.setSlot(3);
		event.setOffer(offer);
		support.plugin.onGrandExchangeOfferChanged(event);
		support.settle();
	}

	// ------------------------------------------------------------- capture

	@Test
	public void aFillReachesTheQueue() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));          // baseline
		fire(offer(GrandExchangeOfferState.BUYING, 4, 3_800_000));  // 4 filled

		final List<GeTransaction> queued = support.queue().peek(10);
		assertEquals(1, queued.size());
		assertEquals(4, queued.get(0).quantity);
		assertEquals("the exact gp, not price times quantity", 3_800_000, queued.get(0).grossValue);
		assertEquals("Abyssal whip", queued.get(0).itemName);
	}

	@Test
	public void placingAnOfferIsNotATrade() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		assertTrue(support.queue().isEmpty());
	}

	/**
	 * The client clears every slot while logging in or hopping. Acting on that
	 * would throw away the baselines that stop the next login re-reporting
	 * everything still on the exchange.
	 */
	@Test
	public void eventsWhileNotLoggedInAreIgnored() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		when(support.client.getGameState()).thenReturn(GameState.LOGGING_IN);

		fire(offer(GrandExchangeOfferState.BUYING, 8, 8_000_000));

		assertTrue("a fill seen while logging in must not be recorded", support.queue().isEmpty());
	}

	/**
	 * Turning recording off means those trades stay out of the journal, not
	 * that they queue up and arrive when it is turned back on.
	 */
	@Test
	public void recordingOffDiscardsRatherThanQueues() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		when(support.config.enabled()).thenReturn(false);

		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		assertTrue(support.queue().isEmpty());

		// And the baseline still advanced, so switching back on does not then
		// report the fill that was deliberately skipped.
		when(support.config.enabled()).thenReturn(true);
		fire(offer(GrandExchangeOfferState.BOUGHT, 4, 4_000_000));
		assertTrue(support.queue().isEmpty());
	}

	// --------------------------------------------------------------- sending

	@Test
	public void anEmptyQueueSendsNothing() throws Exception
	{
		support.drain();
		verify(support.api, never()).submit(anyString(), anyString(), anyList());
	}

	@Test
	public void withNoJournalChosenTheTradesAreKept() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		// profileConfig has no gameAccountId entry.

		support.drain();

		verify(support.api, never()).submit(anyString(), anyString(), anyList());
		assertEquals("the fill must be kept, not dropped", 1, support.queue().size());
	}

	@Test
	public void aSuccessfulSendClearsTheBatch() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());

		support.drain();

		assertTrue(support.queue().isEmpty());
	}

	@Test
	public void aTemporaryFailureLeavesTheBatchQueued() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenThrow(new java.io.IOException("connection reset"));

		support.drain();

		assertEquals("a network blip must not cost a trade", 1, support.queue().size());
	}

	/**
	 * The bug this exists for: on a permanent refusal the plugin used to
	 * re-read the queue and discard whatever was at the head of it *now*.
	 * Fills arrive on the io thread while a request is in flight, so a refused
	 * batch of one that had since grown to two threw away a trade that had
	 * never been sent.
	 *
	 * <p>The send stub adds a fill before throwing, which is exactly that race
	 * made deterministic.
	 */
	@Test
	public void onlyTheRefusedBatchIsDropped() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		final GeTransaction arrivedDuringTheRequest = new GeTransaction();
		arrivedDuringTheRequest.id = "later";
		arrivedDuringTheRequest.side = "buy";
		arrivedDuringTheRequest.quantity = 1;
		arrivedDuringTheRequest.grossValue = 1000;
		arrivedDuringTheRequest.occurredAt = "2026-08-31T12:00:00Z";

		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenAnswer(inv ->
			{
				support.queue().add(arrivedDuringTheRequest);
				throw new FlippingRsApi.PermanentException("Invalid or revoked API key.");
			});

		support.drain();

		final List<GeTransaction> left = support.queue().peek(10);
		assertEquals("the fill that arrived mid-request must survive", 1, left.size());
		assertEquals("later", left.get(0).id);
	}

	/**
	 * The queue is chosen from the account hash and the journal from whichever
	 * RuneScape profile is active. A hop between those two reads would post one
	 * account's trades into the other's journal -- and ingestion being
	 * idempotent by id means re-sending would not undo it.
	 */
	@Test
	public void aBatchIsNotSentIfTheAccountChangedWhilePreparingIt() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		// Captured while the hash is still the original, because the lookup
		// itself is keyed by it.
		final TransactionQueue queue = support.queue();

		// drain reads the hash twice: once to choose the queue, once to check
		// it still matches the journal it just resolved. The second read sees a
		// different account -- the player hopped or relogged in between.
		when(support.client.getAccountHash()).thenReturn(1234L, 9999L);

		support.drain();

		verify(support.api, never()).submit(anyString(), anyString(), anyList());
		assertEquals("the trade waits for the next tick rather than being misfiled",
			1, queue.size());
	}

	@Test
	public void nothingIsSentWhileLoggedOut() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		when(support.client.getAccountHash()).thenReturn(-1L);

		support.drain();

		verify(support.api, never()).submit(anyString(), anyString(), any());
	}

	/**
	 * "Record trades" off is a promise that the plugin is not talking to
	 * flippingrs.com at all, not merely that it has stopped capturing. What is
	 * already queued has to survive it, though: those fills were captured while
	 * the user did want them recorded.
	 */
	@Test
	public void recordingOffSendsNothingButKeepsWhatIsQueued() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		when(support.config.enabled()).thenReturn(false);
		support.drain();

		verify(support.api, never()).submit(anyString(), anyString(), anyList());
		assertEquals("switching off must not discard what was already captured",
			1, support.queue().size());

		// And it goes out once recording is switched back on.
		when(support.config.enabled()).thenReturn(true);
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());

		support.drain();

		assertTrue(support.queue().isEmpty());
	}

	/** The key is not even checked while recording is off. */
	@Test
	public void recordingOffDoesNotContactTheServerAtAll() throws Exception
	{
		when(support.config.enabled()).thenReturn(false);

		support.connect();

		verify(support.api, never()).accounts(anyString());
	}
}
