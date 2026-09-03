package com.flippingrs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.RuneScapeProfileChanged;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
	private File queueDir;

	@Before
	public void setUp() throws Exception
	{
		queueDir = folder.newFolder("queues");
		support = new FlippingRsPluginTestSupport(queueDir);
	}

	private static FlippingRsApi.GameAccount account(String id, boolean isDefault)
	{
		final FlippingRsApi.GameAccount a = new FlippingRsApi.GameAccount();
		a.id = id;
		a.label = id;
		a.isDefault = isDefault;
		return a;
	}

	private static FlippingRsApi.Watchlist watchlist(String id, String name, Integer... itemIds)
	{
		final FlippingRsApi.Watchlist w = new FlippingRsApi.Watchlist();
		w.id = id;
		w.name = name;
		w.itemIds = new java.util.ArrayList<>(Arrays.asList(itemIds));
		return w;
	}

	/**
	 * What the server holds, for any context; set its parts. Each tab's
	 * endpoint answers with only its own parts, as the real ones do.
	 */
	private FlippingRsApi.Panel serverPanel() throws Exception
	{
		final FlippingRsApi.Panel full = new FlippingRsApi.Panel();
		when(support.api.account(anyString())).thenAnswer(inv ->
		{
			final FlippingRsApi.Panel part = new FlippingRsApi.Panel();
			part.me = full.me;
			part.accounts = full.accounts;
			return part;
		});
		when(support.api.trades(anyString(), any())).thenAnswer(inv ->
		{
			final FlippingRsApi.Panel part = new FlippingRsApi.Panel();
			part.recentTransactions = full.recentTransactions;
			return part;
		});
		when(support.api.journal(anyString(), any(), anyInt())).thenAnswer(inv ->
		{
			final FlippingRsApi.Panel part = new FlippingRsApi.Panel();
			part.week = full.week;
			part.positions = full.positions;
			return part;
		});
		when(support.api.watchlists(anyString(), any())).thenAnswer(inv ->
		{
			final FlippingRsApi.Panel part = new FlippingRsApi.Panel();
			part.watchlists = full.watchlists;
			part.quotes = full.quotes;
			return part;
		});
		return full;
	}

	private static GeTransaction recorded(String id, String side, long quantity)
	{
		final GeTransaction tx = new GeTransaction();
		tx.id = id;
		tx.side = side;
		tx.quantity = quantity;
		tx.itemName = "Abyssal whip";
		tx.grossValue = 1_000_000;
		tx.occurredAt = "2026-08-31T12:00:00Z";
		return tx;
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

	/**
	 * Disabling the plugin stops the io thread, but an offer event already in
	 * flight on the game thread still arrives. Everything else that loses that
	 * race has nothing to lose; this is a trade that happened, so the game
	 * thread writes it through itself rather than drop it.
	 */
	@Test
	public void aFillCapturedAfterTheIoThreadStopsIsStillWrittenThrough() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		support.stopDiskThread();

		final GrandExchangeOfferChanged event = new GrandExchangeOfferChanged();
		event.setSlot(3);
		event.setOffer(offer(GrandExchangeOfferState.BUYING, 4, 3_800_000));
		support.plugin.onGrandExchangeOfferChanged(event);

		final List<GeTransaction> queued = support.queue().peek(10);
		assertEquals("the fill must not be dropped with the io thread", 1, queued.size());
		assertEquals(4, queued.get(0).quantity);
		assertEquals(3_800_000, queued.get(0).grossValue);
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
	public void aClearWhileLoggingInDoesNotTouchTheBaseline() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		when(support.client.getGameState()).thenReturn(GameState.LOGGING_IN);

		fire(offer(GrandExchangeOfferState.EMPTY, 0, 0));

		assertTrue("the baseline must survive the login clear", support.profileConfig.containsKey("offer.3"));

		// So that after login the same offer's progress is a fill, not an
		// adoption of an offer we have supposedly never seen.
		when(support.client.getGameState()).thenReturn(GameState.LOGGED_IN);
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		assertEquals(1, support.queue().size());
	}

	/**
	 * Only the clears are filtered. A fill that arrives while the client is
	 * LOADING a region is real, and ignoring it without advancing the baseline
	 * meant an offer that completed then and was collected lost its last fill
	 * with the slot.
	 */
	@Test
	public void aFillDuringARegionLoadIsRecorded() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		when(support.client.getGameState()).thenReturn(GameState.LOADING);

		fire(offer(GrandExchangeOfferState.BOUGHT, 10, 10_000_000));

		assertEquals(1, support.queue().size());
		assertEquals(10, support.queue().peek(10).get(0).quantity);
	}

	/**
	 * A Deadman or Leagues world has its own prices and its own limits, and
	 * at the end of the season no items at all. Its fills in the main journal
	 * are wrong money in exactly the way an invented trade is.
	 */
	@Test
	public void tradesOnAWorldWithItsOwnEconomyAreNotRecorded() throws Exception
	{
		when(support.client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS, WorldType.DEADMAN));
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));

		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		assertTrue(support.queue().isEmpty());

		// The baseline still advanced, so the same progress is not reported
		// later as if it had happened on a normal world.
		when(support.client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS));
		fire(offer(GrandExchangeOfferState.BOUGHT, 4, 4_000_000));
		assertTrue(support.queue().isEmpty());
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

	/**
	 * drain runs as a fixed-delay task, and an exception escaping one of those
	 * cancels it for good: the plugin stops sending for the rest of the
	 * session with nothing in the log to say why. Its javadoc says in as many
	 * words that it never throws, and reading a setting -- which goes through
	 * a RuneLite config proxy, not a field -- was the first thing it did,
	 * outside the guard.
	 */
	@Test
	public void theSenderSurvivesAConfigReadThatThrows() throws Exception
	{
		when(support.config.enabled()).thenThrow(new IllegalStateException("the config proxy fell over"));
		org.mockito.Mockito.clearInvocations(support.config);

		// A failure here is the exception escaping.
		support.drain();

		// And it really did reach the read that throws, rather than passing by
		// turning back before it.
		verify(support.config, org.mockito.Mockito.atLeastOnce()).enabled();
	}

	/**
	 * Adopting an offer queues a recovered fill, so the very next send is the
	 * one carrying it -- and that send used to clear every notice on its way
	 * to reporting nothing. The sentence explaining why that trade has no
	 * purchase behind it was wiped by the act of sending it.
	 */
	@Test
	public void theAdoptedOfferNoticeSurvivesTheSendThatCarriesIt() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());

		fire(offer(GrandExchangeOfferState.BUYING, 6, 5_900_000));
		support.settleSwing();
		assertTrue(support.panel.activityNoticeForTest().contains("part-way"));

		support.drain();
		support.settleSwing();

		assertTrue("the explanation must outlive the send that carries the trade",
			support.panel.activityNoticeForTest().contains("part-way"));
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
				throw new FlippingRsApi.PermanentException("transactions[0].side must be buy or sell");
			});

		support.drain();

		final List<GeTransaction> left = support.queue().peek(10);
		assertEquals("the fill that arrived mid-request must survive", 1, left.size());
		assertEquals("later", left.get(0).id);
	}

	/**
	 * A refused fill leaves the queue, because retrying cannot help. It does
	 * not leave the disk: the user is told a trade could not be recorded, and
	 * a row they can read and enter by hand keeps that from being a loss.
	 */
	@Test
	public void aRefusedBatchIsSetAsideOnDiskNotDeleted() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		final String id = support.queue().peek(1).get(0).id;

		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenThrow(new FlippingRsApi.PermanentException("transactions[0].quantity must be positive"));

		support.drain();

		assertTrue("it must not wedge the queue", support.queue().isEmpty());
		final File dropped = new File(queueDir, "dropped-1234.json");
		assertTrue("the refused fill must still exist somewhere", dropped.isFile());
		assertTrue(new String(Files.readAllBytes(dropped.toPath()), StandardCharsets.UTF_8).contains(id));
	}

	private static GeTransaction fill(String id)
	{
		final GeTransaction tx = new GeTransaction();
		tx.id = id;
		tx.side = "buy";
		tx.quantity = 1;
		tx.grossValue = 1000;
		tx.itemName = "Abyssal whip";
		tx.occurredAt = "2026-08-31T12:00:00Z";
		return tx;
	}

	private static FlippingRsApi.IngestResult accepted(int rows)
	{
		final FlippingRsApi.IngestResult result = new FlippingRsApi.IngestResult();
		result.accepted = rows;
		return result;
	}

	/**
	 * A 4xx names the batch, not the row. Setting aside the whole batch for
	 * one bad row lost up to five hundred good trades, so a refused batch is
	 * split until the bad row is on its own: the good ones go through and
	 * only the bad one is set aside.
	 */
	@Test
	public void oneBadRowInABatchIsFoundAndTheRestGoThrough() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		for (String id : new String[]{"a", "b", "bad", "c", "d"})
		{
			support.queue().add(fill(id));
		}
		final List<Integer> batchSizes = new ArrayList<>();
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenAnswer(inv ->
			{
				final List<GeTransaction> batch = inv.getArgument(2);
				batchSizes.add(batch.size());
				for (GeTransaction tx : batch)
				{
					if ("bad".equals(tx.id))
					{
						throw new FlippingRsApi.PermanentException("transactions[" + batch.indexOf(tx) + "].quantity must be positive");
					}
				}
				return accepted(batch.size());
			});

		support.drain();
		support.settleSwing();

		assertTrue("every good row was confirmed", support.queue().isEmpty());
		final String setAside = new String(
			Files.readAllBytes(new File(queueDir, "dropped-1234.json").toPath()), StandardCharsets.UTF_8);
		assertTrue(setAside.contains("\"bad\""));
		for (String good : new String[]{"\"a\"", "\"b\"", "\"c\"", "\"d\""})
		{
			assertFalse("a good row must not be set aside: " + good, setAside.contains(good));
		}
		assertEquals("the whole batch was tried first", 5, (int) batchSizes.get(0));
		assertTrue("the search is a handful of requests, got " + batchSizes, batchSizes.size() <= 6);
		assertTrue(support.panel.activityNoticeForTest().contains("1 trade(s)"));
		assertTrue("the good rows count as a send", support.panel.statusTextForTest().contains("Connected"));
	}

	/**
	 * When both halves of a refused batch are refused too, the fault is the
	 * batch as a whole and no split will help. Stop there, at three requests,
	 * rather than probing every row.
	 */
	@Test
	public void aBatchRefusedInBothHalvesIsSetAsideWithoutProbingEveryRow() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		for (String id : new String[]{"a", "b", "c", "d", "e", "f", "g", "h"})
		{
			support.queue().add(fill(id));
		}
		final int[] calls = {0};
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenAnswer(inv ->
			{
				calls[0]++;
				throw new FlippingRsApi.PermanentException("accountId is not one of yours");
			});

		support.drain();
		support.settleSwing();

		assertEquals("the batch, then each half, and no further", 3, calls[0]);
		assertTrue("everything was set aside", support.queue().isEmpty());
		assertTrue(support.panel.activityNoticeForTest().contains("8 trade(s)"));
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

		verify(support.api, never()).account(anyString());
		verify(support.api, never()).watchlists(anyString(), any());
	}

	// ------------------------------------------------------------ the picker

	/**
	 * The journal is remembered per RuneScape account, so logging out of a
	 * main and into an alt has to move the picker with it. It used to keep
	 * showing the main's journal while the alt's trades went elsewhere.
	 */
	@Test
	public void loggingIntoAnotherAccountRepointsThePicker() throws Exception
	{
		serverPanel().accounts = Arrays.asList(account("a1", true), account("a2", false));
		support.profileConfig.put("gameAccountId", "a2");

		support.connect();
		assertEquals("a2", support.panel.selectedAccountId());

		// The alt: remembered journal a1.
		support.profileConfig.put("gameAccountId", "a1");
		support.plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged("main", "alt"));
		support.settleSwing();

		assertEquals("a1", support.panel.selectedAccountId());
		assertEquals("re-pointing the picker is not the user choosing; nothing is rewritten",
			"a1", support.profileConfig.get("gameAccountId"));
	}

	/**
	 * The client is usually started before logging in, so the first connect
	 * has no RuneScape account to attach a choice to. The adoption of the
	 * default has to happen on login instead, or the panel shows a journal
	 * selected while the plugin holds every trade for want of one.
	 */
	@Test
	public void anAccountSeenForTheFirstTimeAdoptsTheDefaultOnLogin() throws Exception
	{
		serverPanel().accounts = Arrays.asList(account("a1", false), account("a2", true));
		when(support.client.getAccountHash()).thenReturn(-1L);

		support.connect();
		assertNull("nothing to attach it to yet", support.profileConfig.get("gameAccountId"));

		when(support.client.getAccountHash()).thenReturn(1234L);
		support.plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged(null, "main"));
		support.settleSwing();

		assertEquals("a2", support.profileConfig.get("gameAccountId"));
		assertEquals("a2", support.panel.selectedAccountId());
	}

	/**
	 * Trades and Journal are read for one journal. Picking a different one has
	 * to re-read both, or the sidebar goes on showing the old journal's rows
	 * under the new journal's name until something else happens to refresh
	 * them -- and whatever was held for want of a journal goes out, because
	 * the panel has been promising the user exactly that.
	 */
	@Test
	public void pickingAJournalRereadsTheTabsAndSendsWhatWasHeld() throws Exception
	{
		serverPanel().accounts = Arrays.asList(account("a1", true), account("a2", false));
		support.profileConfig.put("gameAccountId", "a1");
		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());
		support.connect();
		support.settleNet();

		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		support.chooseAccount("a2");

		assertEquals("a2", support.profileConfig.get("gameAccountId"));
		verify(support.api).trades(anyString(), eq("a2"));
		verify(support.api).journal(anyString(), eq("a2"), anyInt());
		verify(support.api).submit(anyString(), eq("a2"), anyList());
		assertTrue("the held fill goes to the journal just chosen", support.queue().isEmpty());
	}

	/**
	 * And re-selecting the same journal reads nothing. The picker is
	 * repopulated on every reconnect, which re-selects whatever was already
	 * chosen, and two requests per reconnect for no news is a third of a
	 * thirty-a-minute budget.
	 */
	@Test
	public void reSelectingTheSameJournalReadsNothing() throws Exception
	{
		serverPanel().accounts = Arrays.asList(account("a1", true), account("a2", false));
		support.profileConfig.put("gameAccountId", "a2");
		support.connect();
		support.settleNet();

		support.chooseAccount("a2");

		verify(support.api, times(1)).trades(anyString(), eq("a2"));
		verify(support.api, times(1)).journal(anyString(), eq("a2"), anyInt());
	}

	/**
	 * A journal deleted on the site. Sending to it would be refused every
	 * tick, and the picker used to show whichever entry sorted first while
	 * that happened. Forget it, show nothing, hold the trades.
	 */
	@Test
	public void aJournalThatNoLongerExistsIsForgottenAndTradesAreHeld() throws Exception
	{
		serverPanel().accounts = Collections.singletonList(account("a1", true));
		support.profileConfig.put("gameAccountId", "deleted");

		support.connect();

		assertNull(support.profileConfig.get("gameAccountId"));
		assertNull("the panel must not name a journal the plugin is not filing under",
			support.panel.selectedAccountId());

		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.drain();

		verify(support.api, never()).submit(anyString(), anyString(), anyList());
		assertEquals(1, support.queue().size());
	}

	/**
	 * A "Send every" of five seconds with slots filling continuously must
	 * not re-read two tabs on every send, or the plugin scope's rate limit
	 * is hit by the plugin itself. Sends close together share one refresh.
	 */
	@Test
	public void refreshesAfterSendsAreCoalesced() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		// The tabs are only read while somebody can see them, and opening the
		// sidebar reads them once; the sends are what is under test here.
		support.showSidebar();
		support.tabsLastReadLongAgo();
		org.mockito.Mockito.clearInvocations(support.api);
		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());
		for (int i = 0; i < 3; i++)
		{
			fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
			fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
			support.drain();
			fire(offer(GrandExchangeOfferState.EMPTY, 0, 0));
		}

		verify(support.api, times(3)).submit(anyString(), anyString(), anyList());
		verify(support.api, times(1)).trades(anyString(), any());
		verify(support.api, times(1)).journal(anyString(), any(), anyInt());
		// The two sends that came too soon share one deferred re-read between
		// them. Three of those, each firing on its own fifteen seconds later,
		// is the burst this exists to prevent.
		assertEquals("sends inside the window queue one deferred re-read, not one each",
			1, support.deferredReadsForTest());
	}

	// --------------------------------------------------------------- journal

	@Test
	public void theJournalTabLoadsOnConnectAndAfterASend() throws Exception
	{
		final FlippingRsApi.Panel server = serverPanel();
		server.accounts = Collections.singletonList(account("acct-1", true));
		support.profileConfig.put("gameAccountId", "acct-1");
		final FlippingRsApi.Positions open = new FlippingRsApi.Positions();
		final FlippingRsApi.Position whip = new FlippingRsApi.Position();
		whip.itemId = 4151;
		whip.itemName = "Abyssal whip";
		open.positions = Collections.singletonList(whip);
		server.positions = open;
		server.week = new FlippingRsApi.Analytics();

		support.connect();

		assertEquals(Collections.singletonList(4151), support.panel.positionsForTest());
		verify(support.api).journal(eq("frs_key"), eq("acct-1"), anyInt());
	}

	/** The panel read is the connection test, so a refusal on connect is a refused connection. */
	@Test
	public void aRefusedPanelReadOnConnectIsAFailedConnection() throws Exception
	{
		when(support.api.account(anyString()))
			.thenThrow(new java.io.IOException("This API key is scoped to the RuneLite plugin."));
		support.profileConfig.put("gameAccountId", "acct-1");

		support.connect();

		assertTrue(support.panel.statusTextForTest().contains("This API key is scoped to the RuneLite plugin."));
		verify(support.api, never()).trades(anyString(), any());
	}

	/**
	 * A send with the sidebar shut reads neither account tab. Nobody can see
	 * them, and two requests per send against a limit of thirty a minute --
	 * which the sends themselves draw on -- is a lot to spend on redrawing a
	 * panel that is not on screen. The open slots still go to the server,
	 * because that is not a panel read: it is how the server recovers a fill
	 * the plugin never saw.
	 */
	@Test
	public void sendsWithTheSidebarShutDoNotReadTheAccountTabs() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		support.drain();
		support.settleNet();

		verify(support.api).submit(anyString(), anyString(), anyList());
		verify(support.api, never()).trades(anyString(), any());
		verify(support.api, never()).journal(anyString(), any(), anyInt());
		verify(support.api).submitOffers(anyString(), anyString(), anyList());

		// And opening it reads them, because while it was shut nothing was.
		support.showSidebar();

		verify(support.api).trades(anyString(), any());
		verify(support.api).journal(anyString(), any(), anyInt());
	}

	/**
	 * A refusal on a partial refresh lands on the tabs those parts belong
	 * to, and does not undo the send that triggered it.
	 */
	@Test
	public void aRefusedPartialRefreshIsShownOnItsTabs() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		support.showSidebar();
		support.tabsLastReadLongAgo();
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());
		when(support.api.trades(anyString(), any()))
			.thenThrow(new java.io.IOException("This API key is scoped to the RuneLite plugin."));
		when(support.api.journal(anyString(), any(), anyInt()))
			.thenThrow(new java.io.IOException("This API key is scoped to the RuneLite plugin."));

		support.drain();
		support.settleSwing();

		assertEquals("This API key is scoped to the RuneLite plugin.", support.panel.activityProblemForTest());
		assertEquals("This API key is scoped to the RuneLite plugin.", support.panel.journalProblemForTest());
		assertTrue("the send itself went through", support.queue().isEmpty());
	}

	// ------------------------------------------------------------- positions

	@Test
	public void closingAPositionSendsTheSaleAndReloadsTheJournal() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");

		support.closePosition("f1", 1_520_000, 4L);

		verify(support.api).closePosition("frs_key", "f1", 1_520_000, 4L);
		verify(support.api).journal(eq("frs_key"), eq("acct-1"), anyInt());
		assertTrue(support.panel.journalNoticeForTest().contains("Sale recorded"));
	}

	@Test
	public void aRefusedCloseIsShownInTheServersWords() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		org.mockito.Mockito.doThrow(new java.io.IOException("Every item in this flip has already been sold."))
			.when(support.api).closePosition(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), any());

		support.closePosition("f1", 1_520_000, null);

		assertTrue(support.panel.journalNoticeForTest().contains("Every item in this flip has already been sold."));
	}

	@Test
	public void deletingAPositionRemovesItAndReloadsTheJournal() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");

		support.deletePosition("f1");

		verify(support.api).deletePosition("frs_key", "f1");
		verify(support.api).journal(eq("frs_key"), eq("acct-1"), anyInt());
		assertTrue(support.panel.journalNoticeForTest().contains("deleted"));
	}

	/** "Record trades" off is a promise not to contact the server, journal edits included. */
	@Test
	public void recordingOffBlocksPositionEdits() throws Exception
	{
		when(support.config.enabled()).thenReturn(false);

		support.closePosition("f1", 1_000, null);
		support.deletePosition("f1");

		verify(support.api, never()).closePosition(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), any());
		verify(support.api, never()).deletePosition(anyString(), anyString());
	}

	// --------------------------------------------------------------- account

	@Test
	public void theAccountTabShowsThePlanTheKeyIsOn() throws Exception
	{
		final FlippingRsApi.Panel server = serverPanel();
		server.accounts = Collections.singletonList(account("acct-1", true));
		final FlippingRsApi.Me me = new FlippingRsApi.Me();
		me.effectiveTier = "pro";
		me.onTrial = true;
		me.trialDaysLeft = 5;
		server.me = me;

		support.connect();

		assertTrue(support.panel.subscriptionTextForTest().contains("Pro trial, 5 days left"));
	}

	/** A fill sits on the Activity tab until the journal confirms it, then leaves. */
	@Test
	public void bufferedFillsShowOnTheActivityTabUntilSent() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.settleSwing();

		assertEquals(1, support.panel.pendingForTest().size());
		assertTrue(support.panel.pendingForTest().get(0).contains("Bought 4"));

		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());
		support.drain();
		support.settleSwing();

		assertTrue("confirmed fills are the journal's now, not the buffer's", support.panel.pendingForTest().isEmpty());
	}

	/** A refused batch is Activity's news, not the connection's. */
	@Test
	public void aRefusedBatchIsReportedOnTheActivityTab() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenThrow(new FlippingRsApi.PermanentException("transactions[0].quantity must be positive"));

		support.drain();
		support.settleSwing();

		assertTrue(support.panel.activityNoticeForTest().contains("set aside"));
		assertTrue("and it says where, including the folder the file is actually in: "
			+ support.panel.activityNoticeForTest(),
			support.panel.activityNoticeForTest().contains(queueDir.getName() + "/dropped-1234.json"));
	}

	// -------------------------------------------------------------- activity

	/**
	 * The recent trades are read back from the server after a send, not
	 * remembered from what was sent. A fill that never made it must not show
	 * as if it had.
	 */
	@Test
	public void recentTradesAreWhatTheServerRecordedNotWhatWasSent() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		support.showSidebar();
		support.tabsLastReadLongAgo();
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.settleSwing();
		assertTrue("nothing is shown until the server has it", support.panel.recentForTest().isEmpty());

		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());
		final FlippingRsApi.Panel server = new FlippingRsApi.Panel();
		server.recentTransactions = Arrays.asList(recorded("t2", "sell", 1), recorded("t1", "buy", 4));
		when(support.api.trades(eq("frs_key"), eq("acct-1"))).thenReturn(server);

		support.drain();
		support.settleSwing();

		final List<String> lines = support.panel.recentForTest();
		assertEquals(2, lines.size());
		assertTrue(lines.get(0), lines.get(0).contains("Sold 1"));
		assertTrue(lines.get(1), lines.get(1).contains("Bought 4"));
	}

	@Test
	public void recentTradesFollowTheAccountOnLogin() throws Exception
	{
		serverPanel().accounts = Collections.singletonList(account("acct-1", true));
		final FlippingRsApi.Panel alt = new FlippingRsApi.Panel();
		alt.recentTransactions = Collections.singletonList(recorded("t9", "buy", 2));
		when(support.api.trades(eq("frs_key"), eq("acct-2"))).thenReturn(alt);
		support.connect();

		support.profileConfig.put("gameAccountId", "acct-2");
		support.plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged("main", "alt"));
		support.settleNet();
		support.settleSwing();

		assertEquals(1, support.panel.recentForTest().size());
		assertTrue(support.panel.recentForTest().get(0).contains("Bought 2"));
	}

	// ------------------------------------------------------------- watchlist

	/** The card shows the site's prices, read anonymously after the list loads. */
	@Test
	public void watchedItemsCarryTheSitesQuote() throws Exception
	{
		// The cards are only built while the sidebar is open.
		support.showSidebar();
		final FlippingRsApi.Panel server = serverPanel();
		server.accounts = Collections.singletonList(account("acct-1", true));
		server.watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		final FlippingRsApi.Quote whip = new FlippingRsApi.Quote();
		whip.id = 4151;
		whip.instantSell = 1_480_000;
		whip.instantBuy = 1_520_000;
		server.quotes = Collections.singletonList(whip);

		support.connect();

		assertEquals("Buy 1,480,000 · Sell 1,520,000", support.panel.watchlistPricesForTest(4151));
	}

	/**
	 * The offer-screen overlay reads the live caches, so it follows the
	 * watchlist: an item removed in the sidebar stops showing on the offer
	 * screen as soon as the server has confirmed it, and vice versa.
	 */
	@Test
	public void theOfferScreenQuoteFollowsTheWatchlist() throws Exception
	{
		final FlippingRsApi.Panel server = serverPanel();
		server.accounts = Collections.singletonList(account("acct-1", true));
		server.watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		final FlippingRsApi.Quote whip = new FlippingRsApi.Quote();
		whip.id = 4151;
		whip.instantSell = 1_480_000;
		whip.instantBuy = 1_520_000;
		server.quotes = Collections.singletonList(whip);
		support.connect();

		assertNotNull("watched and quoted, so the offer screen shows it", support.watchedQuote(4151));
		assertNull("not watched, so nothing is drawn", support.watchedQuote(11802));

		when(support.api.updateWatchlist(eq("frs_key"), eq("wl_1"), eq(Collections.emptyList())))
			.thenAnswer(inv ->
			{
				server.watchlists = Collections.singletonList(watchlist("wl_1", "Plan"));
				server.quotes = Collections.emptyList();
				return server.watchlists.get(0);
			});
		support.removeFromWatchlist(4151);

		assertNull("removed from the list, gone from the offer screen", support.watchedQuote(4151));

		when(support.config.setupOverlay()).thenReturn(false);
		server.watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		server.quotes = Collections.singletonList(whip);
		support.connect();
		assertNull("the setting turns it off even for a watched item", support.watchedQuote(4151));
	}

	/**
	 * Picking a different watchlist re-points the offer screen at it. The
	 * overlay asks this on every frame it draws, so the answer is held ready
	 * rather than resolved each time -- and something held has to be put down
	 * again when the choice moves.
	 */
	@Test
	public void theOfferScreenQuoteFollowsAChangeOfWatchlist() throws Exception
	{
		final FlippingRsApi.Panel server = serverPanel();
		server.accounts = Collections.singletonList(account("acct-1", true));
		server.watchlists = Arrays.asList(
			watchlist("wl_1", "Plan", 4151),
			watchlist("wl_2", "Bonds", 13190));
		final FlippingRsApi.Quote whip = new FlippingRsApi.Quote();
		whip.id = 4151;
		final FlippingRsApi.Quote bond = new FlippingRsApi.Quote();
		bond.id = 13190;
		server.quotes = Arrays.asList(whip, bond);
		support.connect();

		assertNotNull("the first list is the shown one", support.watchedQuote(4151));
		assertNull("the other list's item is not on it", support.watchedQuote(13190));

		support.chooseWatchlist("wl_2");

		assertNotNull("the newly chosen list's item is now quoted", support.watchedQuote(13190));
		assertNull("and the old list's item is not", support.watchedQuote(4151));
	}

	/**
	 * A fill on a watched item brings that card's live-offer line up to date.
	 * The line is the one thing on a card a fill changes, and it is skipped
	 * altogether while the sidebar is shut, so this is what says the skip has
	 * not swallowed the case it was meant to leave alone.
	 */
	@Test
	public void aFillOnAWatchedItemUpdatesItsCardsOfferLine() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		support.showSidebar();
		support.connect();
		assertNull("no offer on it yet", support.panel.watchlistOfferForTest(4151));

		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		support.settleSwing();

		assertEquals("Buying 4/10 at 1.00M", support.panel.watchlistOfferForTest(4151));
	}

	/**
	 * Switching recording off tells the sidebar that nothing is being read
	 * from flippingrs.com, and the offer screen has to agree. Its quotes stop
	 * refreshing the moment the plugin stops talking to the site, so leaving
	 * them up puts the site's prices, silently frozen, in front of the box
	 * where a price gets typed.
	 */
	@Test
	public void switchingRecordingOffTakesTheQuotesOffTheOfferScreen() throws Exception
	{
		final FlippingRsApi.Panel server = serverPanel();
		server.watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		final FlippingRsApi.Quote whip = new FlippingRsApi.Quote();
		whip.id = 4151;
		whip.instantSell = 1_480_000;
		server.quotes = Collections.singletonList(whip);
		support.connect();
		assertNotNull(support.watchedQuote(4151));

		when(support.config.enabled()).thenReturn(false);
		support.connect();

		assertNull("nothing from the site is shown while the plugin is not reading it",
			support.watchedQuote(4151));
	}

	@Test
	public void connectingShowsTheRememberedWatchlistWithItsItemsNamed() throws Exception
	{
		// The cards are only built while the sidebar is open.
		support.showSidebar();
		final FlippingRsApi.Panel server = serverPanel();
		server.accounts = Collections.singletonList(account("acct-1", true));
		server.watchlists = Arrays.asList(
			watchlist("wl_1", "Plan", 4151),
			watchlist("wl_2", "Bonds", 13190, 4151));
		support.pluginConfig.put("watchlistId", "wl_2");

		support.connect();

		assertEquals("wl_2", support.panel.selectedWatchlistId());
		assertEquals(Arrays.asList(13190, 4151), support.panel.watchlistForTest());
	}

	/** The first add on an account with no watchlist creates one and remembers it. */
	@Test
	public void theFirstAddCreatesAWatchlistOnTheServer() throws Exception
	{
		// The cards are only built while the sidebar is open.
		support.showSidebar();
		when(support.api.createWatchlist(eq("frs_key"), anyString(), eq(Collections.singletonList(4151))))
			.thenReturn(watchlist("wl_new", "Plan", 4151));

		support.addToWatchlist(4151);

		verify(support.api).createWatchlist(eq("frs_key"), anyString(), eq(Collections.singletonList(4151)));
		assertEquals("wl_new", support.pluginConfig.get("watchlistId"));
		assertEquals("wl_new", support.panel.selectedWatchlistId());
		assertEquals(Collections.singletonList(4151), support.panel.watchlistForTest());
	}

	/** Adds and removes go to the server; the panel shows what came back. */
	@Test
	public void addingAndRemovingEditTheChosenWatchlistOnTheServer() throws Exception
	{
		// The cards are only built while the sidebar is open.
		support.showSidebar();
		// The server's list changes when it is edited, and the re-read after
		// an edit sees the change, as it would on the real server.
		final FlippingRsApi.Panel server = serverPanel();
		server.watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		when(support.api.updateWatchlist(eq("frs_key"), eq("wl_1"), eq(Arrays.asList(4151, 11802))))
			.thenAnswer(inv ->
			{
				server.watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151, 11802));
				return server.watchlists.get(0);
			});

		support.addToWatchlist(11802);

		assertEquals(Arrays.asList(4151, 11802), support.panel.watchlistForTest());

		when(support.api.updateWatchlist(eq("frs_key"), eq("wl_1"), eq(Collections.singletonList(11802))))
			.thenAnswer(inv ->
			{
				server.watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 11802));
				return server.watchlists.get(0);
			});

		support.removeFromWatchlist(4151);

		assertEquals(Collections.singletonList(11802), support.panel.watchlistForTest());
	}

	/**
	 * A watchlist already at the server's cap is not asked to take another.
	 * The server would refuse it, so the request would be spent being told
	 * what the plugin already knew -- against a limit of thirty a minute --
	 * and the user would get the site's words for something the sidebar can
	 * say plainly.
	 */
	@Test
	public void addingToAFullWatchlistSendsNothingAndSaysWhy() throws Exception
	{
		final Integer[] full = new Integer[Watchlists.MAX_WATCHLIST_ITEMS];
		for (int i = 0; i < full.length; i++)
		{
			full[i] = 1000 + i;
		}
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", full));
		support.showSidebar();
		support.connect();

		support.addToWatchlist(4151);

		verify(support.api, never()).updateWatchlist(anyString(), anyString(), anyList());
		assertTrue("the sidebar must say why: " + support.panel.watchlistNoticeForTest(),
			support.panel.watchlistNoticeForTest().contains("full"));
	}

	@Test
	public void addingAnItemAlreadyWatchedSendsNothing() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));

		support.addToWatchlist(4151);

		verify(support.api, never()).updateWatchlist(anyString(), anyString(), anyList());
		verify(support.api, never()).createWatchlist(anyString(), anyString(), anyList());
	}

	/** A plan limit is shown in the server's words, and nothing is invented locally. */
	@Test
	public void aPlanLimitIsShownNotWorkedAround() throws Exception
	{
		when(support.api.createWatchlist(anyString(), anyString(), anyList()))
			.thenThrow(new java.io.IOException("This feature requires the Pro plan."));

		support.addToWatchlist(4151);

		assertTrue(support.panel.watchlistNoticeForTest().contains("This feature requires the Pro plan."));
		assertTrue("nothing may be kept locally", support.panel.watchlistForTest().isEmpty());
	}

	/** "Record trades" off means no contact with the server, watchlists included. */
	@Test
	public void recordingOffBlocksWatchlistEdits() throws Exception
	{
		when(support.config.enabled()).thenReturn(false);

		support.addToWatchlist(4151);

		verify(support.api, never()).account(anyString());
		verify(support.api, never()).watchlists(anyString(), any());
		verify(support.api, never()).createWatchlist(anyString(), anyString(), anyList());
	}

	// ------------------------------------------------ catching the server up

	/**
	 * An offer found already part filled is not silently absorbed any more:
	 * the progress goes out once as a recovered fill with no time, and the
	 * rest of the offer follows live under the same reference.
	 */
	@Test
	public void anOfferAlreadyInProgressIsReportedAsRecovered() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 6, 5_900_000));

		final List<GeTransaction> queued = support.queue().peek(10);
		assertEquals(1, queued.size());
		assertEquals("adopted", queued.get(0).source);
		assertEquals(6, queued.get(0).quantity);
		assertEquals(5_900_000, queued.get(0).grossValue);
		assertNull("no time is claimed for fills nobody watched", queued.get(0).occurredAt);

		fire(offer(GrandExchangeOfferState.BOUGHT, 10, 9_900_000));

		final List<GeTransaction> all = support.queue().peek(10);
		assertEquals(2, all.size());
		assertEquals("live", all.get(1).source);
		assertEquals("one purchase, both parts", all.get(0).offerRef, all.get(1).offerRef);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void openingTheExchangeSendsTheOpenOffersForReconciliation() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);
		final FlippingRsApi.Reconciliation result = new FlippingRsApi.Reconciliation();
		result.recovered = 1;
		when(support.api.submitOffers(eq("frs_key"), eq("acct-1"), anyList())).thenReturn(result);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();

		final ArgumentCaptor<List<FlippingRsApi.OfferState>> sent = ArgumentCaptor.forClass(List.class);
		verify(support.api).submitOffers(eq("frs_key"), eq("acct-1"), sent.capture());
		assertEquals(1, sent.getValue().size());
		assertEquals(3, sent.getValue().get(0).slot);
		assertNotNull("the offer's reference, so the server can find its fills", sent.getValue().get(0).offerRef);
		assertEquals(4L, sent.getValue().get(0).quantitySold);
		assertEquals("BUYING", sent.getValue().get(0).state);
		assertTrue(support.panel.activityNoticeForTest().contains("Recovered 1"));
	}

	/**
	 * The slots are read on the client thread; the journal they would be filed
	 * under is read on the net thread, from whichever character is logged in
	 * then, and a drain that can block for a whole call timeout sits between
	 * the two. Pairing a mismatched two would hand the main's open offers to
	 * the alt's journal -- and the server's answer to a shortfall is to take it
	 * on as a recovered trade, so it would not be ignored, it would be written
	 * in.
	 */
	@Test
	public void anOfferSnapshotIsNotSentUnderAnotherCharactersJournal() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());

		// Between the slots being read and the snapshot being sent, the client
		// is on another character.
		when(support.client.getAccountHash()).thenReturn(9999L);
		support.settleNet();
		support.settleSwing();

		verify(support.api, never()).submitOffers(anyString(), anyString(), anyList());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void openingTheHistorySendsWhatItShows() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		final Widget icon = mock(Widget.class);
		when(icon.getItemId()).thenReturn(4151);
		when(icon.getItemQuantity()).thenReturn(10);
		when(icon.getText()).thenReturn("");
		final Widget side = mock(Widget.class);
		when(side.getItemId()).thenReturn(-1);
		when(side.getText()).thenReturn("Bought");
		final Widget price = mock(Widget.class);
		when(price.getItemId()).thenReturn(-1);
		when(price.getText()).thenReturn("15,000,000 coins");
		final Widget list = mock(Widget.class);
		when(list.getDynamicChildren()).thenReturn(new Widget[]{icon, side, price});
		when(support.client.getWidget(InterfaceID.GeHistory.LIST)).thenReturn(list);
		final FlippingRsApi.Reconciliation result = new FlippingRsApi.Reconciliation();
		result.added = 1;
		when(support.api.submitHistory(eq("frs_key"), eq("acct-1"), anyList())).thenReturn(result);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_HISTORY);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();

		final ArgumentCaptor<List<FlippingRsApi.HistoryRow>> sent = ArgumentCaptor.forClass(List.class);
		verify(support.api).submitHistory(eq("frs_key"), eq("acct-1"), sent.capture());
		assertEquals(1, sent.getValue().size());
		assertEquals("buy", sent.getValue().get(0).side);
		assertEquals(10L, sent.getValue().get(0).quantity);
		assertEquals(15_000_000L, sent.getValue().get(0).grossValue);
		assertEquals("Abyssal whip", sent.getValue().get(0).itemName);
		assertTrue(support.panel.activityNoticeForTest().contains("history"));

		// Opening it again with the same screen on it sends nothing. It is a
		// click, and there is no gap between one open and the next, so without
		// this a user flicking between their offers and their history spent a
		// request on each one against a limit of thirty a minute.
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(10);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();

		verify(support.api, times(1)).submitHistory(anyString(), anyString(), anyList());

		// A completed trade changes the screen, and that is sent.
		when(price.getText()).thenReturn("16,000,000 coins");
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(20);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();

		verify(support.api, times(2)).submitHistory(anyString(), anyString(), anyList());
	}

	/** The history list fills a tick or two after the screen opens; an empty first look is retried. */
	@Test
	public void anEmptyHistoryScreenIsLookedAtAgainThenLetGo() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.client.getWidget(InterfaceID.GeHistory.LIST)).thenReturn(null);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_HISTORY);
		support.plugin.onWidgetLoaded(opened);
		for (int tick = 2; tick < 20; tick++)
		{
			when(support.client.getTickCount()).thenReturn(tick);
			support.plugin.onGameTick(new GameTick());
		}
		support.settleNet();

		verify(support.api, never()).submitHistory(anyString(), anyString(), anyList());
	}

	/** A plan cap on reconciliation is shown in the server's words, on Activity. */
	@Test
	public void aRefusedOfferSnapshotIsShownOnActivity() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[8]);
		when(support.api.submitOffers(anyString(), anyString(), anyList()))
			.thenThrow(new java.io.IOException("This feature requires the Pro plan."));

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();

		assertTrue(support.panel.activityNoticeForTest().contains("This feature requires the Pro plan."));
	}

	/** "Record trades" off is a promise not to contact the server, snapshots included. */
	@Test
	public void recordingOffSendsNoSnapshots() throws Exception
	{
		when(support.config.enabled()).thenReturn(false);
		when(support.client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[8]);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();

		verify(support.api, never()).submitOffers(anyString(), anyString(), anyList());
	}

	/**
	 * The client replays each offer's state in the first ticks after login.
	 * A delta there happened while nobody was watching, so it goes out with
	 * no time rather than stamped with the login time, which could put a
	 * sale ahead of its purchase.
	 */
	@Test
	public void fillsReplayedJustAfterLoginAreSentUntimed() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));

		final GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		when(support.client.getTickCount()).thenReturn(100);
		support.plugin.onGameStateChanged(loggedIn);
		when(support.client.getTickCount()).thenReturn(101);
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		when(support.client.getTickCount()).thenReturn(110);
		fire(offer(GrandExchangeOfferState.BOUGHT, 10, 10_000_000));

		final List<GeTransaction> queued = support.queue().peek(10);
		assertEquals(2, queued.size());
		assertEquals("adopted", queued.get(0).source);
		assertNull(queued.get(0).occurredAt);
		assertEquals("live", queued.get(1).source);
		assertNotNull(queued.get(1).occurredAt);
	}

	/**
	 * Walking across a map region is not logging in. The client drops to
	 * LOADING and back to LOGGED_IN every time it loads one, and treating each
	 * of those as a login threw away the time of any fill that landed in the
	 * second afterwards -- a fill the plugin had watched happen and knew the
	 * time of exactly.
	 */
	@Test
	public void aFillJustAfterAMapLoadKeepsItsTime() throws Exception
	{
		when(support.client.getTickCount()).thenReturn(100);
		support.plugin.onGameStateChanged(state(GameState.LOGGED_IN));
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));

		// A region boundary: LOGGED_IN -> LOADING -> LOGGED_IN, without ever
		// leaving the world.
		when(support.client.getTickCount()).thenReturn(500);
		support.plugin.onGameStateChanged(state(GameState.LOADING));
		support.plugin.onGameStateChanged(state(GameState.LOGGED_IN));
		when(support.client.getTickCount()).thenReturn(501);
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		final List<GeTransaction> queued = support.queue().peek(10);
		assertEquals(1, queued.size());
		assertEquals("live", queued.get(0).source);
		assertNotNull("the plugin watched this happen and knows when", queued.get(0).occurredAt);
	}

	/** A hop does replay the slots, so the burst window still applies after one. */
	@Test
	public void aFillJustAfterAHopIsStillSentUntimed() throws Exception
	{
		when(support.client.getTickCount()).thenReturn(100);
		support.plugin.onGameStateChanged(state(GameState.LOGGED_IN));
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));

		when(support.client.getTickCount()).thenReturn(500);
		support.plugin.onGameStateChanged(state(GameState.HOPPING));
		support.plugin.onGameStateChanged(state(GameState.LOADING));
		support.plugin.onGameStateChanged(state(GameState.LOGGED_IN));
		when(support.client.getTickCount()).thenReturn(501);
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		final List<GeTransaction> queued = support.queue().peek(10);
		assertEquals(1, queued.size());
		assertEquals("adopted", queued.get(0).source);
		assertNull("no time is claimed for what the exchange replayed", queued.get(0).occurredAt);
	}

	private static GameStateChanged state(GameState state)
	{
		final GameStateChanged event = new GameStateChanged();
		event.setGameState(state);
		return event;
	}

	/**
	 * The quote timer runs while something can show a quote and not otherwise:
	 * it is a request every thirty seconds against a thirty-a-minute limit the
	 * sends also draw on. Logging out tears the widget tree down without a
	 * WidgetClosed for each interface, so the exchange has to be closed on the
	 * game state or the timer runs for the rest of the client's life for an
	 * offer screen that went away with the last session.
	 */
	@Test
	public void theQuoteTimerStopsWhenTheClientLeavesTheWorld() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		support.connect();

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);

		support.quotesTick();
		verify(support.api, times(2)).watchlists(anyString(), any());

		support.plugin.onGameStateChanged(state(GameState.LOGIN_SCREEN));
		support.quotesTick();

		verify(support.api, times(2)).watchlists(anyString(), any());
	}

	/**
	 * The buffer goes out before a snapshot. An adopted fill still queued is
	 * the shortfall the server would recover from the snapshot, and it would
	 * then count the queued fill too.
	 */
	@Test
	public void theBufferIsSentBeforeAnOfferSnapshot() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 6, 5_900_000));
		assertEquals("the adopted fill is waiting", 1, support.queue().size());
		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());
		when(support.client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[8]);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();

		final InOrder order = inOrder(support.api);
		order.verify(support.api).submit(anyString(), anyString(), anyList());
		order.verify(support.api).submitOffers(anyString(), anyString(), anyList());
		assertTrue(support.queue().isEmpty());
	}

	/** "Record trades" off empties the server-fed tabs and says why. */
	@Test
	public void recordingOffPausesTheServerTabs() throws Exception
	{
		when(support.config.enabled()).thenReturn(false);

		support.connect();

		assertTrue(support.panel.pausedForTest().contains("Recording is off"));
	}

	// ----------------------------------------------------------- client exit

	/**
	 * RuneLite does not shut plugins down on exit; it posts one event and
	 * waits a bounded time for whatever is handed to it. The fill just handed
	 * to the io thread and a last send are both worth that wait.
	 */
	@Test
	public void closingTheClientFlushesAndSendsWhatIsWaiting() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		// The sidebar is open, as it would be for someone who was looking at it
		// when they closed the client -- otherwise the tabs are not read anyway
		// and this would prove nothing.
		support.showSidebar();
		support.tabsLastReadLongAgo();
		org.mockito.Mockito.clearInvocations(support.api);
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());

		final ClientShutdown exit = new ClientShutdown();
		support.plugin.onClientShutdown(exit);
		exit.waitForAllConsumers(Duration.ofSeconds(10));

		assertTrue("the last trades must have gone out before the JVM dies", support.queue().isEmpty());
		verify(support.api, never()).trades(anyString(), any());
		verify(support.api, never()).journal(anyString(), any(), anyInt());
		assertFalse(new TransactionQueue(support.gson, new File(queueDir, "queue-1234.json")).size() > 0);
	}
}
