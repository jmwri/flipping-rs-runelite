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
import java.util.TimeZone;
import java.util.Set;
import java.util.HashSet;
import java.util.Random;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.WidgetClosed;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.timeout;
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
		return offerFor(4151, state, sold, spent);
	}

	/** The same, on another item, for the slots a watched item shares with. */
	private static GrandExchangeOffer offerFor(int itemId, GrandExchangeOfferState state, int sold, int spent)
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
				return itemId;
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
		fire(3, offer);
	}

	/** The same, on a named slot: the exchange has eight of them. */
	private void fire(int slot, GrandExchangeOffer offer) throws Exception
	{
		final GrandExchangeOfferChanged event = new GrandExchangeOfferChanged();
		event.setSlot(slot);
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
	 * Collecting a finished offer frees the slot, and the baseline has to go
	 * with it. An offer placed and cancelled untouched, then collected and
	 * placed again, is identical in every way the tracker compares -- the
	 * progress is nought both times -- so the baseline having been cleared is
	 * the only thing left saying the second one is a second purchase.
	 */
	@Test
	public void collectingAnOfferForgetsItsSlot() throws Exception
	{
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		assertTrue("the offer was baselined", support.profileConfig.containsKey("offer.3"));

		fire(offer(GrandExchangeOfferState.EMPTY, 0, 0));

		assertFalse("a freed slot must be forgotten, or the next offer in it inherits this one",
			support.profileConfig.containsKey("offer.3"));
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
		// Every world type with its own economy, not just the first one. A
		// Leagues season is played by a great many people for a month at a
		// time, and its gp in the main journal is wrong money in exactly the
		// way an invented trade is.
		final WorldType[] economies = {
			WorldType.DEADMAN, WorldType.SEASONAL, WorldType.BETA_WORLD, WorldType.NOSAVE_MODE,
			WorldType.TOURNAMENT_WORLD, WorldType.QUEST_SPEEDRUNNING, WorldType.PVP_ARENA,
			WorldType.FRESH_START_WORLD};

		for (WorldType economy : economies)
		{
			when(support.client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS, economy));
			fire(offer(GrandExchangeOfferState.BUYING, 0, 0));

			fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

			assertTrue(economy + ": nothing may be recorded", support.queue().isEmpty());

			// The baseline still advanced, so the same progress is not reported
			// later as if it had happened on a normal world.
			when(support.client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS));
			fire(offer(GrandExchangeOfferState.BOUGHT, 4, 4_000_000));
			assertTrue(economy + ": and not re-reported back on a normal world",
				support.queue().isEmpty());

			// Collected, so the next world type starts from an empty slot.
			fire(offer(GrandExchangeOfferState.EMPTY, 0, 0));
		}

		// And the same fill on an ordinary world is recorded, so none of the
		// above passed on nothing ever being recorded at all.
		when(support.client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS));
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		assertEquals("an ordinary world still records", 1, support.queue().size());
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

	/**
	 * A send that went through moves "Last sent". Leaving it saying "never" is
	 * the opposite of what happened, and it is what the panel would go on
	 * saying for the rest of the session.
	 */
	@Test
	public void aSuccessfulSendMovesTheLastSentTime() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		support.drain();
		support.settleSwing();

		final String shown = support.panel.lastSyncTextForTest();
		assertFalse("the panel must not still say never: " + shown, shown.contains("never"));
		assertTrue(shown, shown.contains("Last sent"));
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
	 * Whatever the search does with a refused batch, every row is accounted
	 * for exactly once.
	 *
	 * <p>How much the search saves depends on where the bad rows fall, and
	 * narrow says why. What must hold whatever it decides is narrower and more
	 * important: nothing is left queued to wedge the rows behind it, no bad
	 * row is confirmed as sent, and no row is both confirmed and set aside --
	 * the halves are views onto the batch, so a row counted twice would be a
	 * trade in the journal that is also in the set-aside file, or one dropped
	 * from the queue without being either.
	 *
	 * <p>Forty random shapes, because a search that halves and recurses is not
	 * described by one or two worked examples.
	 */
	@Test
	public void everyRowOfARefusedBatchIsAccountedForExactlyOnce() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		final File dropped = new File(queueDir, "dropped-1234.json");
		final Random random = new Random(20260904L);
		final Set<String> confirmed = new HashSet<>();
		final int[] calls = {0};
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenAnswer(inv ->
			{
				calls[0]++;
				final List<GeTransaction> batch = inv.getArgument(2);
				for (GeTransaction tx : batch)
				{
					if (tx.id.startsWith("b"))
					{
						throw new FlippingRsApi.PermanentException("transactions[0].quantity must be positive");
					}
				}
				for (GeTransaction tx : batch)
				{
					confirmed.add(tx.id);
				}
				return accepted(batch.size());
			});

		for (int trial = 0; trial < 40; trial++)
		{
			Files.deleteIfExists(dropped.toPath());
			confirmed.clear();
			final int size = 2 + random.nextInt(11);
			final int bad = 1 + random.nextInt(size);
			final List<String> ids = new ArrayList<>();
			for (int i = 0; i < size; i++)
			{
				ids.add((i < bad ? "b" : "g") + trial + "_" + i);
			}
			Collections.shuffle(ids, random);
			for (String id : ids)
			{
				support.queue().add(fill(id));
			}

			calls[0] = 0;
			support.drain();
			support.settleSwing();

			final String aside = dropped.exists()
				? new String(Files.readAllBytes(dropped.toPath()), StandardCharsets.UTF_8) : "";
			final String shape = "trial " + trial + ", " + bad + " bad of " + size + ": " + ids;
			assertTrue("nothing may be left queued, " + shape, support.queue().isEmpty());
			for (String id : ids)
			{
				final boolean setAside = aside.contains("\"" + id + "\"");
				final boolean sent = confirmed.contains(id);
				assertTrue("every row is confirmed or set aside: " + id + ", " + shape, setAside || sent);
				assertFalse("no row is both: " + id + ", " + shape, setAside && sent);
				if (id.startsWith("b"))
				{
					assertFalse("a bad row must never count as sent: " + id + ", " + shape, sent);
				}
			}
			assertTrue("the search took " + calls[0] + " requests for " + shape, calls[0] <= 4 * size);
		}
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
	 * An empty list of journals is not evidence that the one this character
	 * files under has been deleted. Forgetting it on that would stop the
	 * recording and make the user pick again, over a reply that may simply
	 * have come back without them.
	 */
	@Test
	public void anEmptyJournalListDoesNotForgetTheOneInUse() throws Exception
	{
		serverPanel().accounts = Collections.emptyList();
		support.profileConfig.put("gameAccountId", "acct-1");

		support.connect();

		assertEquals("a reply with no journals in it is not a deletion",
			"acct-1", support.profileConfig.get("gameAccountId"));
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

	/**
	 * A batch the site takes in and then refuses part of. Its reply says how
	 * many rows it would not record, not which, so there is nothing to set
	 * aside and nothing to retry -- the whole batch leaves the queue either
	 * way. This notice is the only time anyone is told those trades did not
	 * make it, so it has to say they are gone rather than merely that
	 * something went wrong.
	 *
	 * <p>And it goes on Activity, which is where what the plugin did with a
	 * trade is reported. The connection is fine: the site answered.
	 */
	@Test
	public void rowsRefusedInsideAGoodReplyAreReportedAsGone() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		final FlippingRsApi.IngestResult refused = new FlippingRsApi.IngestResult();
		refused.accepted = 0;
		refused.rejected = 1;
		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(refused);

		support.drain();
		support.settleSwing();

		assertTrue("the queue is cleared either way, so this is the only telling",
			support.queue().isEmpty());
		final String notice = support.panel.activityNoticeForTest();
		assertTrue(notice, notice.contains("1 trade(s)"));
		assertTrue("it must say they are not coming back: " + notice,
			notice.contains("won't be sent again"));
	}

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
		// Open, because the picker is only filled in while it is, which is also
		// the only time anyone can pick from it.
		support.showSidebar();
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

	/**
	 * A card needs the item's name and its alch value, and one composition
	 * carries both. Asking for it twice was two of the game thread's heavier
	 * lookups for every item on the watchlist -- fifty of them at a time,
	 * every half minute the sidebar is open.
	 */
	@Test
	public void aWatchlistCardReadsItsItemsCompositionOnce() throws Exception
	{
		support.showSidebar();
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		clearInvocations(support.itemManager);

		support.connect();

		// The buy limit is asked for exactly once per card and from nowhere
		// else, so it counts the cards however many times the tab was drawn.
		assertTrue("a card was built at all", callsTo("getItemStats") > 0);
		assertEquals("one composition read per card, not two",
			callsTo("getItemStats"), callsTo("getItemComposition"));
	}

	/** How many times the item manager was asked for something, by method name. */
	private long callsTo(String method)
	{
		return org.mockito.Mockito.mockingDetails(support.itemManager).getInvocations().stream()
			.filter(invocation -> invocation.getMethod().getName().equals(method))
			.count();
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

	/**
	 * And taking off an item that is not on it sends nothing either.
	 *
	 * <p>The other half of the same guard, and the half that was missing. The
	 * Remove button only appears on a card for something watched, but the
	 * cards are what was on the list when they were drawn -- so a list that
	 * changed since then, on this client or another, offers Remove for an item
	 * the list no longer has. Sending the list back unchanged spends one of
	 * thirty requests a minute, and says "Removed from Plan" for something
	 * that was not.
	 */
	@Test
	public void removingAnItemThatIsNotWatchedSendsNothing() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));

		support.removeFromWatchlist(13190);

		verify(support.api, never()).updateWatchlist(anyString(), anyString(), anyList());
		verify(support.api, never()).createWatchlist(anyString(), anyString(), anyList());
		assertFalse("and says nothing about it", support.panel.watchlistNoticeShowingForTest());
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
		// Answered from a field rather than restubbed later: the net thread
		// asks the same mock, and stubbing one while another thread calls it
		// is not something Mockito supports.
		final AtomicLong account = new AtomicLong(1234L);
		when(support.client.getAccountHash()).thenAnswer(inv -> account.get());
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		// Held so the character really does change between the slots being
		// read and the snapshot being sent, rather than whenever the two
		// threads happen to interleave.
		final java.util.concurrent.CountDownLatch held = support.holdNet();
		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());

		// Between the slots being read and the snapshot being sent, the client
		// is on another character.
		account.set(9999L);
		held.countDown();
		support.settleNet();
		support.settleSwing();

		verify(support.api, never()).submitOffers(anyString(), anyString(), anyList());
	}

	/**
	 * A slot the client still reports after it was collected is not an open
	 * offer, and sending it would have the site reconcile against an offer of
	 * nothing.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void emptySlotsAreNotSentAsOpenOffers() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		slots[5] = offer(GrandExchangeOfferState.EMPTY, 0, 0);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();

		final ArgumentCaptor<List<FlippingRsApi.OfferState>> sent = ArgumentCaptor.forClass(List.class);
		verify(support.api).submitOffers(anyString(), anyString(), sent.capture());
		assertEquals("only the slot with an offer in it", 1, sent.getValue().size());
		assertEquals(3, sent.getValue().get(0).slot);
	}

	/**
	 * A malformed row from the server must not take the tab down with it. The
	 * account picker already drops rows without an id; the watchlists are read
	 * from the same reply and walked before the picker ever sees them.
	 */
	@Test
	public void aWatchlistWithoutAnIdDoesNotBreakTheTab() throws Exception
	{
		final FlippingRsApi.Watchlist broken = new FlippingRsApi.Watchlist();
		broken.name = "No id";
		serverPanel().watchlists = Arrays.asList(broken, watchlist("wl_1", "Plan", 4151));
		support.pluginConfig.put("watchlistId", "wl_1");
		support.showSidebar();

		support.connect();

		assertEquals("wl_1", support.panel.selectedWatchlistId());
		assertEquals(Collections.singletonList(4151), support.panel.watchlistForTest());
	}

	/**
	 * And with nothing remembered it is not the one the tab settles on either.
	 *
	 * <p>The picker drops it, so a tab that settled on it named the next list
	 * along while listing this one's items -- and the next add would have gone
	 * out as a PATCH to a watchlist with no id.
	 */
	@Test
	public void anIdLessWatchlistIsNeverTheOneOnShow() throws Exception
	{
		final FlippingRsApi.Watchlist broken = new FlippingRsApi.Watchlist();
		broken.name = "No id";
		broken.itemIds = Collections.singletonList(11802);
		serverPanel().watchlists = Arrays.asList(broken, watchlist("wl_1", "Plan", 4151));
		support.showSidebar();

		support.connect();

		assertEquals("wl_1", support.panel.selectedWatchlistId());
		assertEquals("the items shown are the ones the picker names",
			Collections.singletonList(4151), support.panel.watchlistForTest());
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
		// Everything the plugin reads off the client comes through a holder, so
		// that nothing is stubbed while it is running. Once a read succeeds the
		// net thread is calling this same mock, and stubbing one from the test
		// thread while another invokes it is not something Mockito supports --
		// it fails perhaps one run in three, a long way from the cause.
		final java.util.concurrent.atomic.AtomicInteger tick =
			new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.atomic.AtomicReference<Widget> screen =
			new java.util.concurrent.atomic.AtomicReference<>();
		when(support.client.getTickCount()).thenAnswer(inv -> tick.get());
		when(support.client.getWidget(InterfaceID.GeHistory.LIST)).thenAnswer(inv -> screen.get());

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
		final Widget filled = mock(Widget.class);
		when(filled.getDynamicChildren()).thenReturn(new Widget[]{icon, side, price});

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_HISTORY);

		// Opened on a screen that never fills: looked at a few times, let go.
		support.plugin.onWidgetLoaded(opened);
		for (int t = 2; t < 20; t++)
		{
			tick.set(t);
			support.plugin.onGameTick(new GameTick());
		}
		support.settleNet();

		verify(support.api, never()).submitHistory(anyString(), anyString(), anyList());

		// Giving up on one screenful must not give up for the session. Opened
		// again, and slow to fill again -- which is the case that needs the
		// looks to start over, because a screen that is ready on the first
		// look never asks how many looks are left.
		support.plugin.onWidgetLoaded(opened);
		for (int t = 20; t <= 21; t++)
		{
			tick.set(t);
			support.plugin.onGameTick(new GameTick());
		}
		verify(support.api, never()).submitHistory(anyString(), anyString(), anyList());

		screen.set(filled);
		for (int t = 22; t < 30; t++)
		{
			tick.set(t);
			support.plugin.onGameTick(new GameTick());
		}
		support.settleNet();
		support.settleSwing();

		verify(support.api).submitHistory(anyString(), anyString(), anyList());
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

	/**
	 * "Record trades" off is a promise not to contact the server, snapshots
	 * included.
	 *
	 * <p>The snapshot is the one send that is not a trade, so it is the easy
	 * one to forget: it reports which items the character has offers on, and
	 * at what price, while the user believes they turned reporting off. Set up
	 * so that it would go out -- a journal picked and a real offer in a slot --
	 * or the test cannot tell the promise being kept from the send having
	 * nothing to say.
	 */
	@Test
	public void recordingOffSendsNoSnapshots() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.config.enabled()).thenReturn(false);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

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
	 * The window is two ticks wide, which is what RuneLite's own Grand Exchange
	 * plugin uses for the same burst. A fill on the second tick is still the
	 * replay; one on the third is the player trading, and stripping its time
	 * would throw away something the plugin watched happen.
	 */
	@Test
	public void theLoginBurstWindowIsExactlyTwoTicksWide() throws Exception
	{
		when(support.client.getTickCount()).thenReturn(100);
		support.plugin.onGameStateChanged(state(GameState.LOGGED_IN));
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));

		when(support.client.getTickCount()).thenReturn(102);
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		when(support.client.getTickCount()).thenReturn(103);
		fire(offer(GrandExchangeOfferState.BUYING, 6, 6_000_000));

		final List<GeTransaction> queued = support.queue().peek(10);
		assertEquals(2, queued.size());
		assertEquals("two ticks after arriving is still the replay", "adopted", queued.get(0).source);
		assertNull(queued.get(0).occurredAt);
		assertEquals("three ticks after is the player trading", "live", queued.get(1).source);
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

	/**
	 * A slot whose running total has wrapped is reported as an estimate.
	 *
	 * <p>The client holds what an offer has spent in a signed int, and a big
	 * offer runs past what that can hold. The figure is still sent, because
	 * the server reconciles against it, but it has to arrive marked as
	 * approximate -- a number known to be rough is useful, and the same number
	 * believed to be exact is a wrong journal entry.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void aSlotWhoseRunningTotalHasWrappedIsReportedAsAnEstimate() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[2] = offer(GrandExchangeOfferState.BUYING, 3, Integer.MIN_VALUE + 1000);
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();

		final ArgumentCaptor<List<FlippingRsApi.OfferState>> sent = ArgumentCaptor.forClass(List.class);
		verify(support.api).submitOffers(anyString(), anyString(), sent.capture());
		assertTrue("the wrapped slot must be flagged", reported(sent.getValue(), 2).spentEstimated);
		assertTrue("and one that fits must not be", !reported(sent.getValue(), 3).spentEstimated);
	}

	/**
	 * A snapshot the server recovered nothing from says nothing.
	 *
	 * <p>Every open offer is snapshotted whenever the exchange is opened, and
	 * almost every one of those tells the server nothing it did not have. A
	 * notice on each would be a notice on nearly all of them, and it would
	 * also re-read the account tabs against a thirty-a-minute limit for no
	 * news.
	 */
	@Test
	public void aSnapshotThatRecoveredNothingSaysNothing() throws Exception
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
		support.settleNet();
		support.settleSwing();

		verify(support.api).submitOffers(anyString(), anyString(), anyList());
		assertTrue("nothing was recovered, so there is nothing to say",
			!support.panel.activityNoticeShowingForTest());
	}

	/** The state reported for one slot. */
	private static FlippingRsApi.OfferState reported(List<FlippingRsApi.OfferState> sent, int slot)
	{
		for (FlippingRsApi.OfferState state : sent)
		{
			if (state.slot == slot)
			{
				return state;
			}
		}
		throw new AssertionError("slot " + slot + " was not reported");
	}

	/**
	 * The snapshot after a login waits for the client's offer burst to settle.
	 *
	 * <p>The client replays every slot in the first ticks after login, and a
	 * snapshot taken in the middle of that reports the slots half-replayed --
	 * which the server would reconcile against, and recover a shortfall that
	 * is only the burst not having finished. So the read waits, and the wait
	 * is the point: it has to be late enough to be after the burst and it has
	 * to actually happen.
	 */
	@Test
	public void theSnapshotAfterLoginWaitsForTheOfferBurstToSettle() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		when(support.client.getTickCount()).thenReturn(100);
		support.plugin.onGameStateChanged(state(GameState.LOGGED_IN));

		for (int tick = 100; tick < 103; tick++)
		{
			when(support.client.getTickCount()).thenReturn(tick);
			support.plugin.onGameTick(new GameTick());
		}
		support.settleNet();
		support.settleSwing();
		verify(support.api, never()).submitOffers(anyString(), anyString(), anyList());

		when(support.client.getTickCount()).thenReturn(103);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();
		verify(support.api).submitOffers(anyString(), anyString(), anyList());
	}

	/**
	 * Opening the exchange twice over does not snapshot twice over.
	 *
	 * <p>Every open schedules one, and a flipper opens the exchange constantly.
	 * The plugin gets thirty requests a minute for everything it does, so a
	 * snapshot whose answer cannot have changed is one that has to be skipped
	 * rather than spent.
	 */
	@Test
	public void openingTheExchangeAgainStraightAwayDoesNotSnapshotAgain() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		for (int tick : new int[]{5, 10})
		{
			support.plugin.onWidgetLoaded(opened);
			when(support.client.getTickCount()).thenReturn(tick);
			support.plugin.onGameTick(new GameTick());
			support.settleNet();
			support.settleSwing();
		}

		verify(support.api).submitOffers(anyString(), anyString(), anyList());
	}

	/**
	 * Once the gap has run out, a snapshot still only happens when something
	 * asked for one.
	 *
	 * <p>The gap between snapshots is a floor, not a schedule. What decides
	 * that a snapshot is wanted is the exchange opening or a login settling,
	 * and that request is spent when it is served -- otherwise every tick from
	 * then on would ask again and the gap alone would decide, snapshotting for
	 * ever at six requests a minute out of the thirty the plugin has.
	 */
	@Test
	public void aSnapshotOnlyHappensWhenSomethingAskedForOne() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();

		// The gap has run out, and nothing has opened the exchange since.
		support.offersLastSnapshotSecondsAgo(30);
		for (int tick = 6; tick < 12; tick++)
		{
			when(support.client.getTickCount()).thenReturn(tick);
			support.plugin.onGameTick(new GameTick());
		}
		support.settleNet();
		support.settleSwing();

		verify(support.api).submitOffers(anyString(), anyString(), anyList());
	}

	/**
	 * A send does not drag a snapshot along behind it.
	 *
	 * <p>A snapshot after a send is a safety net, not the record: the send has
	 * just told the server what happened. Trades come in bursts, so taking one
	 * per send would put the plugin over the thirty requests a minute it is
	 * allowed, which is why the gap after a send is a long one.
	 */
	@Test
	public void aSendSoonAfterASnapshotDoesNotTakeAnother() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList())).thenReturn(new FlippingRsApi.IngestResult());
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();

		// Well past the gap between ordinary snapshots, and nowhere near the
		// longer one a send has to wait out.
		support.offersLastSnapshotSecondsAgo(20);
		fire(offer(GrandExchangeOfferState.BUYING, 6, 5_900_000));
		support.drain();
		support.settleNet();
		support.settleSwing();
		verify(support.api, times(1)).submitOffers(anyString(), anyString(), anyList());

		// And once that longer gap has run out, the safety net does go up.
		support.offersLastSnapshotSecondsAgo(120);
		fire(offer(GrandExchangeOfferState.BUYING, 8, 7_900_000));
		support.drain();
		support.settleNet();
		support.settleSwing();
		verify(support.api, times(2)).submitOffers(anyString(), anyString(), anyList());
	}

	/**
	 * The same screenful goes again when it would be filed somewhere else.
	 *
	 * <p>A screen already sent is not sent twice, which is what keeps a user
	 * flicking between their offers and their history from spending a request
	 * on every click. But "already sent" has to mean sent to this journal, for
	 * this character. Pick a different journal and the rows have not been
	 * anywhere near it, and the second send is the one that puts them there.
	 */
	@Test
	public void theSameHistoryScreenIsSentAgainToADifferentJournal() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		historyScreen("15,000,000 coins");
		when(support.api.submitHistory(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.Reconciliation());

		openHistory(5);
		verify(support.api).submitHistory(eq("frs_key"), eq("acct-1"), anyList());

		support.profileConfig.put("gameAccountId", "acct-2");
		openHistory(10);

		verify(support.api).submitHistory(eq("frs_key"), eq("acct-2"), anyList());
	}

	/**
	 * And again on a different character, whose journal has not seen it
	 * either. Two characters can easily show a history that reads the same --
	 * an alt that has only ever bought the one thing, most obviously.
	 */
	@Test
	public void theSameHistoryScreenIsSentAgainOnAnotherCharacter() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		final AtomicLong account = new AtomicLong(1111L);
		when(support.client.getAccountHash()).thenAnswer(inv -> account.get());
		historyScreen("15,000,000 coins");
		when(support.api.submitHistory(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.Reconciliation());

		openHistory(5);
		verify(support.api, times(1)).submitHistory(anyString(), anyString(), anyList());

		account.set(2222L);
		openHistory(10);

		verify(support.api, times(2)).submitHistory(anyString(), anyString(), anyList());
	}

	/**
	 * A history screen read on one character is not sent for another.
	 *
	 * <p>The rows come off the client thread and the journal they would go
	 * into is read on the net thread, with a drain in between that can block
	 * for a whole call timeout. The server's answer to a shortfall is to take
	 * it on as a recovered trade, so pairing a mismatched two would write one
	 * character's trades into another's journal, untimed. The open slots are
	 * guarded this way already; the history screen is the same hazard.
	 */
	@Test
	public void aHistoryScreenIsNotSentForACharacterWhoDidNotShowIt() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		final AtomicLong account = new AtomicLong(1234L);
		when(support.client.getAccountHash()).thenAnswer(inv -> account.get());
		historyScreen("15,000,000 coins");

		// Held for the same reason as its twin for the open slots: the change
		// has to land between the read and the send, not wherever the two
		// threads happen to meet.
		final java.util.concurrent.CountDownLatch held = support.holdNet();
		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_HISTORY);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(5);
		support.plugin.onGameTick(new GameTick());

		// Between the screen being read and the rows being sent, the client is
		// on another character.
		account.set(9999L);
		held.countDown();
		support.settleNet();
		support.settleSwing();

		verify(support.api, never()).submitHistory(anyString(), anyString(), anyList());
	}

	/**
	 * A history send the server took nothing from says nothing. Opening the
	 * history is a click, and almost every screenful is one the server already
	 * has.
	 */
	@Test
	public void aHistorySendThatRecoveredNothingSaysNothing() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		historyScreen("15,000,000 coins");
		when(support.api.submitHistory(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.Reconciliation());

		openHistory(5);

		verify(support.api).submitHistory(anyString(), anyString(), anyList());
		assertTrue("nothing was recovered, so there is nothing to say",
			!support.panel.activityNoticeShowingForTest());
	}

	/**
	 * "Record trades" off is a promise not to contact the server, and the
	 * history screen is the sharpest test of it.
	 *
	 * <p>Opening it is a click, not a trade, and what it shows is a record of
	 * what this character has already bought and sold. Sending that while the
	 * user believes reporting is off would hand over the very thing they
	 * turned it off to keep back.
	 */
	@Test
	public void recordingOffSendsNoHistory() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		historyScreen("15,000,000 coins");
		when(support.config.enabled()).thenReturn(false);

		openHistory(5);

		verify(support.api, never()).submitHistory(anyString(), anyString(), anyList());
	}

	/**
	 * A blank journal id is no journal, not a journal named "".
	 *
	 * <p>The setting is absent when nothing has been picked, but a config can
	 * come back holding an empty string just as easily -- unset and written
	 * back, or edited by hand. Treating that as a choice sends the trades with
	 * nothing to file them under, and the answer comes back as trades the site
	 * could not record. Held is the right answer: the fills keep, and the panel
	 * asks for a journal.
	 */
	@Test
	public void anEmptyStoredJournalIdIsTreatedAsNoJournal() throws Exception
	{
		support.profileConfig.put("gameAccountId", "");
		fire(offer(GrandExchangeOfferState.BUYING, 6, 5_900_000));
		support.drain();
		support.settleNet();
		support.settleSwing();

		verify(support.api, never()).submit(anyString(), anyString(), anyList());
		assertEquals("the fill is kept until there is a journal to file it under",
			1, support.queue().size());
	}

	/**
	 * Editing the journal without a key says so, rather than asking the server.
	 *
	 * <p>Recording a sale and deleting a lot both go straight to the site, so
	 * without a key they can only fail. Failing at the server turns a missing
	 * setting into "Couldn't record the sale: HTTP 401", which tells the user
	 * nothing they can act on. A key of nothing but spaces is the same case:
	 * that is what a paste with a stray newline leaves behind, and it has to
	 * be read as no key rather than sent as one.
	 */
	@Test
	public void aJournalEditWithoutAKeyAsksForOneInsteadOfAskingTheServer() throws Exception
	{
		for (String key : new String[]{"", "   "})
		{
			when(support.config.apiKey()).thenReturn(key);
			support.closePosition("p1", 1_500_000, 5L);
			support.settleNet();
			support.settleSwing();

			assertTrue("a key of [" + key + "] must be treated as no key at all: "
					+ support.panel.journalNoticeForTest(),
				support.panel.journalNoticeForTest().contains("Add your API key"));
		}
		verify(support.api, never()).closePosition(anyString(), anyString(),
			org.mockito.ArgumentMatchers.anyLong(), any());
	}

	/**
	 * Picking the watchlist that is already picked costs nothing.
	 *
	 * <p>A combo box fires its action on any pick, including re-picking what
	 * was already showing. Treating that as a change writes the setting again,
	 * rebuilds every card and spends one of thirty requests a minute to arrive
	 * back where it started.
	 */
	@Test
	public void pickingTheWatchlistAlreadyShowingDoesNothing() throws Exception
	{
		serverPanel().watchlists = Arrays.asList(
			watchlist("wl_1", "Plan", 4151), watchlist("wl_2", "Other", 13190));
		// The picker is only filled in while the sidebar is open, which is also
		// the only time anyone can pick from it.
		support.showSidebar();
		support.connect();

		support.chooseWatchlist("wl_2");
		clearInvocations(support.api);

		support.chooseWatchlist("wl_2");

		verify(support.api, never()).watchlists(anyString(), any());
	}

	/**
	 * The quote timer does not run for an empty watchlist.
	 *
	 * <p>It runs while the sidebar or an offer screen is showing quotes, every
	 * thirty seconds, for as long as that lasts. With nothing on the list
	 * there is nothing to quote, and the request would be spent to be handed
	 * back the same empty answer.
	 */
	@Test
	public void theQuoteTimerDoesNotRunForAnEmptyWatchlist() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan"));
		support.connect();
		support.showSidebar();
		clearInvocations(support.api);

		support.quotesTick();

		verify(support.api, never()).watchlists(anyString(), any());
	}

	/**
	 * Taking an item off a watchlist that does not exist does not make one.
	 *
	 * <p>The first add creates the owner's first watchlist, which is the right
	 * thing for an add and exactly the wrong thing for a remove: it would
	 * answer "take this off my list" by creating a list with that item on it.
	 */
	@Test
	public void removingAnItemWithNoWatchlistCreatesNothing() throws Exception
	{
		serverPanel().watchlists = Collections.emptyList();
		support.connect();

		support.removeFromWatchlist(4151);

		verify(support.api, never()).createWatchlist(anyString(), anyString(), anyList());
		verify(support.api, never()).updateWatchlist(anyString(), anyString(), anyList());
	}

	/**
	 * The "send every" setting decides how often the sender runs, and cannot
	 * be set low enough to hurt.
	 *
	 * <p>The plugin is allowed thirty requests a minute for everything it
	 * does. A setting of one second would spend two a second on its own, so
	 * the figure is floored, and the floor only matters if the setting is
	 * being read at all.
	 */
	@Test
	public void theSendIntervalIsTheUsersUntilItWouldBreachTheRateLimit() throws Exception
	{
		when(support.config.syncSeconds()).thenReturn(60);
		support.plugin.onConfigChanged(configChanged("syncSeconds"));
		assertEquals("the user's figure", 60, support.syncPeriodSecondsForTest());

		when(support.config.syncSeconds()).thenReturn(1);
		support.plugin.onConfigChanged(configChanged("syncSeconds"));
		assertEquals("floored, not honoured", 5, support.syncPeriodSecondsForTest());

		when(support.config.syncSeconds()).thenReturn(0);
		support.plugin.onConfigChanged(configChanged("syncSeconds"));
		assertEquals("and zero is not a schedule at all", 5, support.syncPeriodSecondsForTest());
	}

	/**
	 * Turning the Grand Exchange menu entries off turns them off.
	 *
	 * <p>They are added to every right-click inside the exchange, so a setting
	 * that does nothing is two entries the user asked to be rid of on every
	 * menu they open.
	 */
	@Test
	public void theExchangeMenuEntriesCanBeTurnedOff() throws Exception
	{
		support.wireExchangeMenu();
		when(support.config.geMenuEntries()).thenReturn(false);

		// A right-click that would otherwise get both entries: over an item on
		// the exchange's side panel, which carries its own id.
		final MenuEntry cancel = mock(MenuEntry.class);
		final MenuEntry over = mock(MenuEntry.class);
		when(over.getParam1()).thenReturn(InterfaceID.GeOffersSide.ITEMS);
		when(over.getItemId()).thenReturn(4151);
		final MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[]{cancel, over});

		support.plugin.onMenuOpened(opened);

		verify(support.client, never()).getMenu();
	}

	private static ConfigChanged configChanged(String key)
	{
		final ConfigChanged event = new ConfigChanged();
		event.setGroup(FlippingRsConfig.GROUP);
		event.setKey(key);
		return event;
	}

	/**
	 * Opening the sidebar while recording is off reads nothing.
	 *
	 * <p>Opening it asks the site for all three tabs, and that is contact --
	 * the same promise the sends keep. It is the easiest one to miss, because
	 * nothing was traded and nothing was sent: the user simply clicked the
	 * icon.
	 */
	@Test
	public void openingTheSidebarWhileRecordingIsOffReadsNothing() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.config.enabled()).thenReturn(false);

		support.showSidebar();

		verify(support.api, never()).trades(anyString(), any());
		verify(support.api, never()).journal(anyString(), any(), anyInt());
		verify(support.api, never()).watchlists(anyString(), any());
	}

	/**
	 * With no journal picked, the two tabs that belong to one are not read.
	 *
	 * <p>Both reads take the journal to read, and the site falls back to the
	 * owner's default when it is not told one. So asking anyway does not fail:
	 * it quietly answers with some other journal's trades and holdings, and
	 * the sidebar shows them as this character's while the Account tab is
	 * still asking which journal to use.
	 *
	 * <p>The watchlists are a person's rather than a character's and are read
	 * either way, which is also what says this test is asking anything at all.
	 */
	@Test
	public void withNoJournalPickedTheCharactersTabsAreNotRead() throws Exception
	{
		support.showSidebar();

		verify(support.api, never()).trades(anyString(), any());
		verify(support.api, never()).journal(anyString(), any(), anyInt());
		verify(support.api).watchlists(anyString(), any());
	}

	/**
	 * And with no key there is nothing to ask with, so nothing is asked.
	 *
	 * <p>Every read would come back 401. The panel already says to add a key;
	 * spending three requests a minute to be told so again is worth skipping.
	 */
	@Test
	public void withNoKeyNothingIsRead() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.config.apiKey()).thenReturn("   ");

		support.showSidebar();

		verify(support.api, never()).trades(anyString(), any());
		verify(support.api, never()).journal(anyString(), any(), anyInt());
		verify(support.api, never()).watchlists(anyString(), any());
	}

	/**
	 * Closing the client does not wait on a send that can never run.
	 *
	 * <p>The plugin asks RuneLite to hold the shutdown until the last trades
	 * have gone out, which means promising to say when that is. If the net
	 * thread is already stopped the send never happens, and a promise nobody
	 * keeps leaves the client unable to close at all -- a worse outcome by far
	 * than the few trades, which are on disk either way and go out next time.
	 */
	@Test
	public void closingTheClientDoesNotHangWhenTheSenderIsAlreadyStopped() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.stopSendThread();

		final ClientShutdown exit = new ClientShutdown();
		final long start = System.nanoTime();
		support.plugin.onClientShutdown(exit);
		exit.waitForAllConsumers(Duration.ofSeconds(5));
		final long waitedMs = (System.nanoTime() - start) / 1_000_000L;

		assertTrue("closing the client waited " + waitedMs + "ms on a send that cannot run",
			waitedMs < 2000);
	}

	/**
	 * Disabling the plugin mid-session still tries one last send.
	 *
	 * <p>Otherwise the evening's last few trades sit on disk until the plugin
	 * is next enabled, which for someone turning it off at the end of a
	 * session means until the next one.
	 */
	@Test
	public void disablingThePluginTriesOneLastSend() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.settleNet();
		clearInvocations(support.api);

		support.plugin.shutDown();

		verify(support.api, timeout(5000)).submit(anyString(), anyString(), anyList());
	}

	/**
	 * The buffer on the Activity tab follows the character too.
	 *
	 * <p>Unsent fills are kept per character, because which journal they go
	 * into is remembered per character. The tab that lists them is not: log
	 * into an alt and it would still be showing the main's trades waiting to
	 * send, under a heading that says they are this character's.
	 */
	@Test
	public void theWaitingBufferFollowsTheCharacter() throws Exception
	{
		serverPanel().accounts = Collections.singletonList(account("acct-1", true));
		support.profileConfig.put("gameAccountId", "acct-1");
		support.connect();
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		support.plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged(null, "main"));
		support.settle();
		support.settleSwing();
		assertEquals("this character has a fill waiting", 1, support.panel.pendingForTest().size());

		// The same client, now logged in as someone else, who has none.
		when(support.client.getAccountHash()).thenReturn(9999L);
		support.plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged("main", "alt"));
		support.settle();
		support.settleSwing();

		assertTrue("the other character's fill is not this one's: "
			+ support.panel.pendingForTest(), support.panel.pendingForTest().isEmpty());
	}

	/**
	 * Being told the journal is gone is not overwritten by being told the
	 * connection is fine.
	 *
	 * <p>Both are true at once, and only one of them explains why nothing is
	 * being sent. A journal deleted on the site, or a key that now belongs to
	 * a different flippingrs.com account, leaves the trades held until another
	 * is picked -- and "Connected and recording." is exactly the wrong thing
	 * to be reading while that happens.
	 */
	@Test
	public void theWarningThatAJournalIsGoneSurvivesConnecting() throws Exception
	{
		serverPanel().accounts = Collections.singletonList(account("acct-1", true));
		// Remembered for this character, and no longer on the site.
		support.profileConfig.put("gameAccountId", "acct-9");

		support.connect();

		final String status = support.panel.statusTextForTest();
		assertTrue("the reason the trades are held has to survive: " + status,
			status.contains("no longer exists"));
	}

	/**
	 * Closing the sidebar stops the two tabs being read after every send.
	 *
	 * <p>They are only read so that someone looking at them sees the trade
	 * arrive. A flipper keeps the exchange open and the sidebar shut, and
	 * reading them anyway is two requests per send, out of thirty a minute
	 * that the sends themselves draw on, to redraw a panel nobody can see.
	 *
	 * <p>The open case was covered and the close was not, so a flag that stuck
	 * on after the first look would have gone unnoticed.
	 */
	@Test
	public void closingTheSidebarStopsTheTabsBeingReadAfterEverySend() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());
		support.showSidebar();
		support.hideSidebar();
		support.tabsLastReadLongAgo();
		clearInvocations(support.api);

		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.drain();
		support.settleNet();
		support.settleSwing();

		verify(support.api).submit(anyString(), anyString(), anyList());
		verify(support.api, never()).trades(anyString(), any());
		verify(support.api, never()).journal(anyString(), any(), anyInt());
	}

	/**
	 * A fill on an item nobody is watching does not go looking for its card.
	 *
	 * <p>There is no card to update, so the eight-slot scan and the hop to the
	 * Swing thread that follows it are both pure waste -- and a flipper fills
	 * offers on unwatched items all day.
	 */
	@Test
	public void aFillOnAnUnwatchedItemDoesNotScanTheSlots() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		support.showSidebar();
		support.connect();
		clearInvocations(support.client);

		fire(offerFor(9999, GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.settleSwing();

		verify(support.client, never()).getGrandExchangeOffers();
	}

	/**
	 * A card's offer line is that card's offer.
	 *
	 * <p>The line is found by walking the eight exchange slots, and a flipper
	 * has eight of them going at once. Taking the first slot with anything in
	 * it would put another item's progress on this item's card -- "Selling
	 * 6/10" against a whip that is being bought.
	 */
	@Test
	public void aCardsOfferLineIsItsOwnOfferNotAnotherSlots() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		support.showSidebar();
		support.connect();

		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		// A different item, earlier in the slots than the watched one.
		slots[1] = offerFor(9999, GrandExchangeOfferState.SELLING, 6, 6_000_000);
		slots[3] = offer(GrandExchangeOfferState.BUYING, 4, 4_000_000);
		when(support.client.getGrandExchangeOffers()).thenReturn(slots);

		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		support.settleSwing();

		assertEquals("Buying 4/10 at 1.00M", support.panel.watchlistOfferForTest(4151));
	}

	/**
	 * And it says what the offer is actually doing.
	 *
	 * <p>Six states reach the card, and the word is the whole of what the line
	 * tells a flipper at a glance. A sale reading as a purchase is worse than
	 * no line at all.
	 */
	@Test
	public void theOfferLineNamesWhatTheOfferIsDoing() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		support.showSidebar();
		support.connect();

		final GrandExchangeOfferState[] states = {
			GrandExchangeOfferState.SELLING, GrandExchangeOfferState.BOUGHT,
			GrandExchangeOfferState.SOLD, GrandExchangeOfferState.CANCELLED_BUY,
			GrandExchangeOfferState.CANCELLED_SELL, GrandExchangeOfferState.BUYING};
		final String[] words = {"Selling", "Bought", "Sold", "Buy cancelled", "Sell cancelled", "Buying"};

		for (int i = 0; i < states.length; i++)
		{
			final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
			slots[3] = offerFor(4151, states[i], i + 1, (i + 1) * 1_000_000);
			when(support.client.getGrandExchangeOffers()).thenReturn(slots);

			fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
			support.settleSwing();

			final String line = support.panel.watchlistOfferForTest(4151);
			assertTrue(states[i] + " should read as \"" + words[i] + "\", got: " + line,
				line != null && line.startsWith(words[i] + " "));
		}
	}

	/**
	 * "Send now" sends now.
	 *
	 * <p>It is the button someone presses when they do not want to wait out
	 * the interval -- checking that a trade landed before closing the client,
	 * usually. There is nothing else on the sidebar that says whether it did
	 * anything, so a button wired to nothing would look exactly like a button
	 * wired to a send that had nothing to do.
	 */
	@Test
	public void sendNowSendsWhatIsWaiting() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		assertEquals("a fill is waiting", 1, support.queue().size());
		clearInvocations(support.api);

		support.pressSendNow();

		verify(support.api).submit(anyString(), anyString(), anyList());
		assertTrue("and it went out", support.queue().isEmpty());
	}

	/**
	 * The server URL setting is only honoured in developer mode.
	 *
	 * <p>It exists so a developer can point the plugin at a server of their
	 * own. What goes to that server is the API key and every trade, so the
	 * developer-mode check is the whole of what keeps an ordinary client
	 * talking to flippingrs.com -- a value left in the config from a
	 * development build, or put there by hand, must not move anyone's trades.
	 */
	@Test
	public void theServerUrlSettingIsOnlyHonouredInDeveloperMode() throws Exception
	{
		when(support.config.baseUrl()).thenReturn("http://localhost:8080");

		support.setDeveloperMode(false);
		assertEquals("https://flippingrs.com/", support.serverUrl().toString());

		support.setDeveloperMode(true);
		assertEquals("http://localhost:8080/", support.serverUrl().toString());
	}

	/**
	 * And a setting that is not a URL leaves the plugin where it was.
	 *
	 * <p>Blank, spaces, or something that is not a URL at all: the answer to
	 * each is flippingrs.com, not a failure to reach anywhere. A developer
	 * clearing the box is asking for the real server back.
	 */
	@Test
	public void aServerUrlThatIsNotAUrlFallsBackToTheRealOne() throws Exception
	{
		support.setDeveloperMode(true);
		for (String setting : new String[]{null, "", "   ", "not a url", "://nonsense"})
		{
			when(support.config.baseUrl()).thenReturn(setting);
			assertEquals("[" + setting + "] should leave it alone",
				"https://flippingrs.com/", support.serverUrl().toString());
		}
		// And one that is a URL, with spaces round it, is still that URL.
		when(support.config.baseUrl()).thenReturn("  http://localhost:9999  ");
		assertEquals("http://localhost:9999/", support.serverUrl().toString());
	}

	/**
	 * A slot's baseline comes back out of the config exactly as it went in.
	 *
	 * <p>It does not live in memory between events: every offer the client
	 * reports is compared against a baseline read back from RuneLite's config,
	 * so anything that does not survive that trip is a field the comparison
	 * never gets to use. Two of them decide whether an offer in a slot is
	 * still the same offer -- the side it was on, and whether it had finished
	 * -- and both read null as "cannot tell" and wave the offer through. A
	 * third, the running total, is what makes a fill's gp exact rather than an
	 * estimate.
	 *
	 * <p>None of that fails loudly. The offer is accepted as the same one, its
	 * fills are counted against the previous purchase, and the plugin carries
	 * on.
	 */
	@Test
	public void aSlotBaselineComesBackOutOfTheConfigWholeAlways() throws Exception
	{
		final ProfileStore store = new ProfileStore(support.configManager, support.gson);
		final SavedOffer saved = SavedOffer.of(
			offerFor(4151, GrandExchangeOfferState.CANCELLED_BUY, 3, 3000), "ref-1", true);

		store.saveOffer(3, saved);
		final SavedOffer back = store.loadOffer(3);

		assertNotNull("the baseline must be readable back", back);
		assertEquals("item", saved.itemId, back.itemId);
		assertEquals("price", saved.price, back.price);
		assertEquals("size", saved.totalQuantity, back.totalQuantity);
		assertEquals("progress", saved.quantitySold, back.quantitySold);
		assertEquals("the running total, which makes the gp exact", saved.spent, back.spent);
		assertEquals("the state, which says the side and whether it had finished",
			saved.state, back.state);
		assertEquals("the reference the site groups a purchase by", saved.offerRef, back.offerRef);
		assertEquals("whether its history was adopted", saved.adopted, back.adopted);
	}

	/**
	 * A plugin turned off and on again keeps coalescing its tab reads.
	 *
	 * <p>Two reads close together are coalesced into one deferred read, and
	 * the flag saying "one is already on its way" is cleared by that deferred
	 * read when it runs. Disabling the plugin stops the thread it was queued
	 * on, so it never runs and never clears the flag -- and every later pair
	 * of reads is then swallowed, for the rest of the client's life, because
	 * nothing else ever clears it.
	 */
	@Test
	public void aPluginTurnedOffAndOnAgainStillCoalescesItsTabReads() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		support.showSidebar();
		// Two reads inside the window: the second is deferred, and the flag is
		// left set because the deferred read is on a thread about to stop.
		support.tabsLastReadLongAgo();
		support.refreshAccountTabs();
		support.refreshAccountTabs();

		support.reEnable();

		support.showSidebar();
		support.tabsLastReadLongAgo();
		support.refreshAccountTabs();
		final int before = support.deferredReadsForTest();
		support.refreshAccountTabs();

		assertTrue("the second read of the new session must still be deferred, not dropped",
			support.deferredReadsForTest() > before);
	}

	/**
	 * And it forgets what belonged to the last one.
	 *
	 * <p>It does not fail loudly: the sidebar goes on showing a count of
	 * trades from a session that has ended, under a heading that says this
	 * session.
	 */
	@Test
	public void aPluginTurnedOffAndOnAgainForgetsTheLastSession() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());
		support.showSidebar();
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.drain();
		support.settleNet();
		support.settleSwing();
		assertTrue("a trade was recorded in the old session",
			support.panel.recordedTextForTest().contains("1"));

		support.reEnable();
		support.settleSwing();

		// The count reaches the sidebar with the next thing that pushes it.
		support.refreshPending();

		assertTrue("the new session has recorded nothing: "
				+ support.panel.recordedTextForTest(),
			support.panel.recordedTextForTest().contains("0"));
	}

	/**
	 * A re-enabled plugin still catches up its open offers after a send, and
	 * still leaves the two tabs alone while the sidebar is shut.
	 *
	 * <p>Two flags decide those, and both belong to the run that ended. Left
	 * as they were, a plugin that was shutting down when it was disabled never
	 * snapshots its open offers again -- which is how the site learns about
	 * fills the plugin never saw -- and one that had the sidebar open reads
	 * two tabs after every send for a sidebar that is shut.
	 */
	@Test
	public void aReEnabledPluginSnapshotsAgainAndLeavesTheShutTabsAlone() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());
		when(support.client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[8]);
		// The state the last run ended in: sidebar open, client closing.
		support.showSidebar();
		support.markShuttingDown();

		support.reEnable();

		support.tabsLastReadLongAgo();
		clearInvocations(support.api);
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.drain();
		support.settleNet();
		support.settleSwing();

		verify(support.api).submitOffers(anyString(), anyString(), anyList());
		verify(support.api, never()).trades(anyString(), any());
		verify(support.api, never()).journal(anyString(), any(), anyInt());
	}

	/**
	 * And the next login after a re-enable is still a login.
	 *
	 * <p>The client replays every open offer in the first ticks after one, and
	 * those replays go out untimed because they did not happen just now. What
	 * says a login is a login is a flag set when the client leaves the world,
	 * and it belongs to the run that ended: left as it was, the next login is
	 * not noticed, the replayed fills are stamped with the time the client
	 * started, and a sale can land ahead of the purchase it came from.
	 */
	@Test
	public void aReEnabledPluginStillNoticesTheNextLogin() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		// A login in the run that ended, which is what leaves the flag down.
		when(support.client.getTickCount()).thenReturn(10);
		support.plugin.onGameStateChanged(state(GameState.LOGGED_IN));

		support.reEnable();

		when(support.client.getTickCount()).thenReturn(100);
		support.plugin.onGameStateChanged(state(GameState.LOGGED_IN));
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		support.settle();

		final List<GeTransaction> waiting = support.queue().peek(10);
		assertEquals(1, waiting.size());
		assertNull("a fill replayed just after a login has no time on it",
			waiting.get(0).occurredAt);
		assertEquals(GeTransaction.SOURCE_ADOPTED, waiting.get(0).source);
	}

	/**
	 * Eight slots run at once, and each one is its own offer.
	 *
	 * <p>A flipper keeps all eight going. Everything about a slot is kept
	 * under its number -- the baseline that says what has already been
	 * reported, and the slot written on each fill for the audit trail -- and
	 * every other test here drives one slot, so a number that went astray
	 * would look exactly right.
	 */
	@Test
	public void everySlotIsItsOwnOffer() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		final int[] slots = {0, 1, 7};
		final int[] items = {4151, 11802, 13190};

		// Placed on three slots at once, each a different item at its own price.
		for (int i = 0; i < slots.length; i++)
		{
			fire(slots[i], offerFor(items[i], GrandExchangeOfferState.BUYING, 0, 0));
		}
		// Then each fills a different amount.
		for (int i = 0; i < slots.length; i++)
		{
			fire(slots[i], offerFor(items[i], GrandExchangeOfferState.BUYING, i + 1, (i + 1) * 1_000_000));
		}

		final List<GeTransaction> queued = support.queue().peek(20);
		assertEquals("one fill from each slot", 3, queued.size());
		for (GeTransaction tx : queued)
		{
			int at = -1;
			for (int i = 0; i < slots.length; i++)
			{
				if (slots[i] == tx.slot)
				{
					at = i;
				}
			}
			assertTrue("a fill from a slot nothing was fired on: " + tx.slot, at >= 0);
			assertEquals("slot " + tx.slot + " holds its own item", items[at], tx.itemId);
			assertEquals("and reports only its own progress", at + 1, tx.quantity);
		}
	}

	/**
	 * A quote refresh that falls over does not stop the quote refreshes.
	 *
	 * <p>The quote tick is scheduled to repeat, and a scheduled repeat that
	 * throws is cancelled -- not retried, not logged as fatal, just never run
	 * again. The watchlist prices would stop moving for the rest of the
	 * session with nothing on screen saying so, and the offer screen would go
	 * on showing whatever it had.
	 *
	 * <p>Reading a setting goes through a config proxy, which is the piece
	 * most likely to throw something nobody expected.
	 */
	@Test
	public void aQuoteTickThatFallsOverDoesNotStopTheQuotes() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		support.showSidebar();
		support.connect();
		when(support.config.enabled()).thenThrow(new IllegalStateException("the config proxy fell over"));

		support.quotesTick();

		// Nothing escaped, so the schedule is still alive: with the setting
		// readable again, the next tick reads the watchlists as usual. Stubbed
		// the other way round, because asking the mock for the old answer is
		// itself a call that throws.
		doReturn(true).when(support.config).enabled();
		clearInvocations(support.api);
		support.quotesTick();

		verify(support.api).watchlists(anyString(), any());
	}

	/**
	 * A fill is recorded even when the client will not name the item.
	 *
	 * <p>The name is looked up from the item manager on the game thread, for
	 * the sidebar to read. The site works the real name out from the id, so a
	 * lookup that throws costs a less readable line -- and letting it escape
	 * costs the trade, out of an event handler, on every fill.
	 */
	@Test
	public void aFillIsRecordedEvenWhenTheClientWillNotNameTheItem() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.itemManager.getItemComposition(anyInt()))
			.thenThrow(new IllegalStateException("no such item"));

		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		final List<GeTransaction> queued = support.queue().peek(10);
		assertEquals("the trade is worth more than its name", 1, queued.size());
		assertEquals(4151, queued.get(0).itemId);
		assertEquals("", queued.get(0).itemName);
	}

	/**
	 * The journal is read for this machine's day, not for UTC.
	 *
	 * <p>The Journal tab's week and its daily figures are worked out by the
	 * site, which needs to know where this player's days begin. The plugin
	 * tells it, in minutes, and getting that wrong moves every boundary: a
	 * flip closed late on Sunday lands in the wrong week and the numbers on
	 * the tab are for a period nobody asked about.
	 *
	 * <p>Checked at half past the hour, and in a zone that does not keep
	 * summer time, so the figure is the same all year and an answer in hours
	 * or in milliseconds cannot pass for it.
	 */
	@Test
	public void theJournalIsReadForThisMachinesDay() throws Exception
	{
		final TimeZone was = TimeZone.getDefault();
		try
		{
			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
			assertEquals("five and a half hours ahead, in minutes", 330,
				support.timezoneOffsetMinutes());

			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Marquesas"));
			assertEquals("and nine and a half behind", -570, support.timezoneOffsetMinutes());

			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			assertEquals(0, support.timezoneOffsetMinutes());
		}
		finally
		{
			TimeZone.setDefault(was);
		}
	}

	/**
	 * A backlog goes out five hundred at a time, oldest first.
	 *
	 * <p>The queue holds ten thousand, which is what a long outage leaves
	 * behind, and the whole of it in one request is a payload the site
	 * refuses outright -- the one kind of refusal that sets a batch aside
	 * instead of retrying it. So a send takes the oldest five hundred and
	 * stops, and the next send takes the next five hundred.
	 *
	 * <p>Nothing checked the cap or the order. At two requests a minute
	 * against a limit of thirty, going a batch per send is a slow way to
	 * clear a full queue -- about ten minutes -- and a cheap one; what
	 * matters is that it clears and that nothing jumps the line.
	 */
	@Test
	public void aBacklogGoesOutInBatchesOldestFirst() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());
		for (int i = 0; i < 501; i++)
		{
			support.queue().add(fill("q" + i));
		}

		support.drain();
		support.settleNet();

		final ArgumentCaptor<List<GeTransaction>> first = ArgumentCaptor.forClass(List.class);
		verify(support.api).submit(anyString(), anyString(), first.capture());
		assertEquals("five hundred and no more", 500, first.getValue().size());
		assertEquals("the oldest first", "q0", first.getValue().get(0).id);
		assertEquals("q499", first.getValue().get(499).id);
		assertEquals("and the rest are still waiting", 1, support.queue().size());

		clearInvocations(support.api);
		support.drain();
		support.settleNet();

		final ArgumentCaptor<List<GeTransaction>> second = ArgumentCaptor.forClass(List.class);
		verify(support.api).submit(anyString(), anyString(), second.capture());
		assertEquals(1, second.getValue().size());
		assertEquals("carrying on where it left off", "q500", second.getValue().get(0).id);
		assertTrue("and the queue is clear", support.queue().isEmpty());
	}

	/**
	 * Closing the Grand Exchange stops the quote refreshes.
	 *
	 * <p>The quotes are refreshed every thirty seconds while something is
	 * showing them: the sidebar, or the offer screen's overlay. With the
	 * sidebar shut, the exchange being open is the whole of what keeps them
	 * running, and nothing was telling the plugin it had closed -- that
	 * handler was called by nothing at all.
	 *
	 * <p>Left running, it is a request every thirty seconds for the rest of
	 * the session, against a limit of thirty a minute, for an offer screen
	 * that is not on the screen. The same thing happened once already on
	 * logout, which is why that path closes it too.
	 */
	@Test
	public void closingTheExchangeStopsTheQuoteRefreshes() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		// The sidebar stays shut, so the exchange is the only thing that can
		// be asking for quotes.
		support.connect();

		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_OFFERS);
		support.plugin.onWidgetLoaded(opened);
		clearInvocations(support.api);
		support.quotesTick();
		verify(support.api).watchlists(anyString(), any());

		// Some other interface closing is not the exchange closing: the offer
		// screen is still up and its overlay still wants prices.
		support.plugin.onWidgetClosed(new WidgetClosed(InterfaceID.GE_HISTORY, 0, true));
		clearInvocations(support.api);
		support.quotesTick();
		verify(support.api).watchlists(anyString(), any());

		support.plugin.onWidgetClosed(new WidgetClosed(InterfaceID.GE_OFFERS, 0, true));
		clearInvocations(support.api);
		support.quotesTick();

		verify(support.api, never()).watchlists(anyString(), any());
	}

	/**
	 * A key or a switch changed here reconnects; one changed elsewhere does
	 * not.
	 *
	 * <p>Pasting a key that works, or turning recording back on, has to
	 * re-check the key and reload the journals, because nothing was contacted
	 * while it was wrong or off. Without that the sidebar goes on saying it
	 * cannot connect until something else happens to ask.
	 *
	 * <p>And the settings of every other plugin come through the same handler.
	 * "enabled" is about as common a setting name as there is, so without the
	 * check on which plugin the change belongs to, somebody else toggling
	 * theirs spends this plugin's requests -- against a limit of thirty a
	 * minute that its own sends draw on.
	 */
	@Test
	public void aSettingChangedHereReconnectsAndOneChangedElsewhereDoesNot() throws Exception
	{
		support.plugin.onConfigChanged(configChanged("apiKey"));
		support.settleNet();
		support.settleSwing();
		verify(support.api).account(anyString());

		clearInvocations(support.api);
		support.plugin.onConfigChanged(configChanged("enabled"));
		support.settleNet();
		support.settleSwing();
		verify(support.api).account(anyString());

		clearInvocations(support.api);
		final ConfigChanged somebodyElse = new ConfigChanged();
		somebodyElse.setGroup("someotherplugin");
		somebodyElse.setKey("enabled");
		support.plugin.onConfigChanged(somebodyElse);
		support.settleNet();
		support.settleSwing();

		verify(support.api, never()).account(anyString());
	}

	/**
	 * A refused watchlist read is shown on the watchlist tab.
	 *
	 * <p>Each of the three tabs says why it could not load, and two of them
	 * were checked. The watchlists were not, and a watchlist tab that says
	 * nothing looks like a watchlist with nothing on it -- which is a
	 * reasonable thing to see and entirely the wrong conclusion.
	 */
	@Test
	public void aRefusedWatchlistReadIsShownOnItsTab() throws Exception
	{
		serverPanel().watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		support.showSidebar();
		support.connect();
		when(support.api.watchlists(anyString(), any()))
			.thenThrow(new java.io.IOException("flippingrs.com is having a moment."));

		support.quotesTick();
		support.settleSwing();

		assertEquals("flippingrs.com is having a moment.",
			support.panel.watchlistProblemForTest());
	}

	/**
	 * Editing one watchlist leaves the others alone.
	 *
	 * <p>The site answers an edit with the whole list as it now stands, and
	 * that answer goes back into the copy the plugin keeps -- in place of the
	 * one it belongs to, and not in place of the others. That copy is what
	 * says which list the next edit belongs to, so putting the answer
	 * everywhere makes every later edit go to the list that was edited first.
	 *
	 * <p>An edit is normally followed by a read that replaces the whole copy,
	 * which would cover the mistake up. The reads here do not come back, which
	 * is when the copy is all the plugin has.
	 */
	@Test
	public void editingOneWatchlistLeavesTheOthersAlone() throws Exception
	{
		serverPanel().watchlists = Arrays.asList(
			watchlist("wl_1", "Plan", 4151), watchlist("wl_2", "Bonds", 13190));
		support.showSidebar();
		support.connect();
		when(support.api.updateWatchlist(anyString(), eq("wl_1"), anyList()))
			.thenReturn(watchlist("wl_1", "Plan", 4151, 11802));
		when(support.api.updateWatchlist(anyString(), eq("wl_2"), anyList()))
			.thenReturn(watchlist("wl_2", "Bonds", 13190, 4151));
		when(support.api.watchlists(anyString(), any()))
			.thenThrow(new java.io.IOException("flippingrs.com is having a moment."));

		support.addToWatchlist(11802);
		support.chooseWatchlist("wl_2");
		clearInvocations(support.api);
		support.addToWatchlist(4151);

		verify(support.api).updateWatchlist(anyString(), eq("wl_2"), anyList());
		verify(support.api, never()).updateWatchlist(anyString(), eq("wl_1"), anyList());
	}

	/**
	 * Nothing is recorded, sent or shown while nobody is logged in.
	 *
	 * <p>A trade belongs to a character, and the per-character baseline that
	 * says what has already been reported is kept under that character too.
	 * The client hands out offer events either side of a login, when it has no
	 * character to give -- and a fill taken then has nowhere to be filed from
	 * or written to.
	 *
	 * <p>Recording it anyway is not a harmless mistake. It goes into a queue
	 * belonging to no account, which the sender then refuses to send for the
	 * same reason, so the trade sits in a file nothing will ever drain: a real
	 * trade, lost quietly, while the sidebar counts it as waiting.
	 */
	@Test
	public void nothingIsRecordedWhileNobodyIsLoggedIn() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		when(support.client.getAccountHash()).thenReturn(-1L);

		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));

		assertTrue("no fill belongs to no-one", support.queue().isEmpty());

		support.drain();
		support.settleNet();
		verify(support.api, never()).submit(anyString(), anyString(), anyList());

		support.refreshPending();
		assertTrue("and the sidebar has nothing waiting",
			support.panel.pendingForTest().isEmpty());
		assertTrue("shown as none: " + support.panel.recordedTextForTest(),
			support.panel.recordedTextForTest().contains("0"));
	}

	/**
	 * Fills arriving while a send is in flight are neither lost nor sent
	 * twice.
	 *
	 * <p>Four threads share this plugin. The client's thread turns offer
	 * events into fills, the io thread writes them down, the net thread sends
	 * them and confirms what the site took, and Swing draws the result. Every
	 * other test here settles one before starting the next, which is what
	 * makes them readable and also means none of them has ever had two of
	 * those running at once.
	 *
	 * <p>The promise underneath is that a trade is recorded once and only
	 * once. So this fires fills from one thread while another sends and
	 * confirms, and then asks the only question that matters: every fill is
	 * either sitting in the queue or has gone to the site, exactly one of the
	 * two, and no id was sent twice.
	 */
	@Test
	public void fillsArrivingDuringASendAreNeitherLostNorSentTwice() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
		final List<String> sentIds = Collections.synchronizedList(new ArrayList<>());
		when(support.api.submit(anyString(), anyString(), anyList())).thenAnswer(inv ->
		{
			final List<GeTransaction> batch = inv.getArgument(2);
			for (GeTransaction tx : batch)
			{
				sentIds.add(tx.id);
			}
			return new FlippingRsApi.IngestResult();
		});

		final int slots = 4;
		final int fillsPerSlot = 30;
		final java.util.concurrent.atomic.AtomicBoolean sending =
			new java.util.concurrent.atomic.AtomicBoolean(true);
		// Two of them, because one cannot overlap with itself: what keeps a
		// batch from going twice is that only one send runs at a time, and a
		// single sender never asks that question.
		final List<Thread> senders = new ArrayList<>();
		for (int i = 0; i < 2; i++)
		{
			final Thread sender = new Thread(() ->
			{
				while (sending.get())
				{
					try
					{
						support.drain();
					}
					catch (Exception e)
					{
						throw new IllegalStateException(e);
					}
				}
			}, "test-sender-" + i);
			senders.add(sender);
			sender.start();
		}

		// The client thread, meanwhile, keeps turning offer events into fills.
		for (int slot = 0; slot < slots; slot++)
		{
			fire(slot, offerFor(4151 + slot, GrandExchangeOfferState.BUYING, 0, 0));
		}
		for (int i = 1; i <= fillsPerSlot; i++)
		{
			for (int slot = 0; slot < slots; slot++)
			{
				fire(slot, offerFor(4151 + slot, GrandExchangeOfferState.BUYING, i, i * 1000));
			}
		}

		sending.set(false);
		for (Thread sender : senders)
		{
			sender.join(30_000);
			assertFalse("a sending thread did not stop", sender.isAlive());
		}
		support.settle();
		support.settleNet();
		support.drain();
		support.settleNet();

		final List<String> waiting = new ArrayList<>();
		for (GeTransaction tx : support.queue().peek(10_000))
		{
			waiting.add(tx.id);
		}
		final Set<String> everywhere = new HashSet<>(sentIds);
		assertEquals("no id was sent twice", sentIds.size(), everywhere.size());
		for (String id : waiting)
		{
			assertTrue("a fill both sent and still waiting: " + id, everywhere.add(id));
		}
		assertEquals("every fill is in exactly one of the two places",
			slots * fillsPerSlot, everywhere.size());
	}

	/**
	 * Switching recording off empties the journal picker.
	 *
	 * <p>The Account tab says nothing is being read from the site. A dropdown
	 * still offering journals beside that is a picture of something the plugin
	 * is not looking at -- and one that can still be used: picking from it
	 * writes the setting for this character while nothing is being recorded
	 * against it.
	 */
	@Test
	public void switchingRecordingOffEmptiesTheJournalPicker() throws Exception
	{
		serverPanel().accounts = Collections.singletonList(account("acct-1", true));
		support.connect();
		assertNotNull("a journal is on offer while recording is on",
			support.panel.selectedAccountId());

		when(support.config.enabled()).thenReturn(false);
		support.connect();

		assertNull("and none is once it is off", support.panel.selectedAccountId());
	}

	/**
	 * And it stays empty when a character changes under it.
	 *
	 * <p>The picker is re-pointed on login from the journals last loaded, so
	 * that an alt lands on its own without another round trip. While recording
	 * is off there are no journals to re-point it from: the list was loaded
	 * for a connection the plugin has since given up, and putting it back on
	 * screen names a journal on a tab that has just said nothing is being read
	 * from flippingrs.com.
	 */
	@Test
	public void switchingCharacterWhileRecordingIsOffLeavesThePickerEmpty() throws Exception
	{
		serverPanel().accounts = Collections.singletonList(account("acct-1", true));
		support.connect();
		when(support.config.enabled()).thenReturn(false);
		support.connect();

		support.plugin.onRuneScapeProfileChanged(new RuneScapeProfileChanged("main", "alt"));
		support.settleSwing();

		assertNull("still none", support.panel.selectedAccountId());
	}

	/** One bought row on the Grand Exchange history screen. */
	private void historyScreen(String priceText)
	{
		final Widget icon = mock(Widget.class);
		when(icon.getItemId()).thenReturn(4151);
		when(icon.getItemQuantity()).thenReturn(10);
		when(icon.getText()).thenReturn("");
		final Widget side = mock(Widget.class);
		when(side.getItemId()).thenReturn(-1);
		when(side.getText()).thenReturn("Bought");
		final Widget price = mock(Widget.class);
		when(price.getItemId()).thenReturn(-1);
		when(price.getText()).thenReturn(priceText);
		final Widget list = mock(Widget.class);
		when(list.getDynamicChildren()).thenReturn(new Widget[]{icon, side, price});
		when(support.client.getWidget(InterfaceID.GeHistory.LIST)).thenReturn(list);
	}

	/** Opens the history screen and lets the read and the send run. */
	private void openHistory(int tick) throws Exception
	{
		final WidgetLoaded opened = new WidgetLoaded();
		opened.setGroupId(InterfaceID.GE_HISTORY);
		support.plugin.onWidgetLoaded(opened);
		when(support.client.getTickCount()).thenReturn(tick);
		support.plugin.onGameTick(new GameTick());
		support.settleNet();
		support.settleSwing();
	}
}
