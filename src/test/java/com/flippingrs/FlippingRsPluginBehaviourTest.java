package com.flippingrs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.mockito.ArgumentCaptor;
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
	 * A refusal on a partial refresh lands on the tabs those parts belong
	 * to, and does not undo the send that triggered it.
	 */
	@Test
	public void aRefusedPartialRefreshIsShownOnItsTabs() throws Exception
	{
		support.profileConfig.put("gameAccountId", "acct-1");
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
		final FlippingRsApi.Panel server = serverPanel();
		server.accounts = Collections.singletonList(account("acct-1", true));
		server.watchlists = Collections.singletonList(watchlist("wl_1", "Plan", 4151));
		final FlippingRsApi.Quote whip = new FlippingRsApi.Quote();
		whip.id = 4151;
		whip.instantSell = 1_480_000;
		whip.instantBuy = 1_520_000;
		server.quotes = Collections.singletonList(whip);

		support.connect();

		assertEquals("Buy 1.48M · Sell 1.52M", support.panel.watchlistPricesForTest(4151));
	}

	@Test
	public void connectingShowsTheRememberedWatchlistWithItsItemsNamed() throws Exception
	{
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
		fire(offer(GrandExchangeOfferState.BUYING, 0, 0));
		fire(offer(GrandExchangeOfferState.BUYING, 4, 4_000_000));
		when(support.api.submit(anyString(), anyString(), anyList()))
			.thenReturn(new FlippingRsApi.IngestResult());

		final ClientShutdown exit = new ClientShutdown();
		support.plugin.onClientShutdown(exit);
		exit.waitForAllConsumers(Duration.ofSeconds(10));

		assertTrue("the last trades must have gone out before the JVM dies", support.queue().isEmpty());
		assertFalse(new TransactionQueue(support.gson, new File(queueDir, "queue-1234.json")).size() > 0);
	}
}
