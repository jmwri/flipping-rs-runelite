package com.flippingrs;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Getting a price in front of somebody who is looking at the item.
 *
 * <p>The refresh runs every thirty seconds, which is the right cadence for a
 * price that is already on screen and quite the wrong one for a price that is
 * not. Two things put an item on screen with no price against it -- opening a
 * screen the last refresh knew nothing about, and a fetch that failed -- and
 * in both cases waiting out the tick is half a minute of blank, which is
 * indistinguishable from the plugin not working.
 */
public class WatchlistsQuotesTest
{
	private static final int WHIP = 4151;
	private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

	private FlippingRsApi api;
	private Watchlists watchlists;

	/** How long each retry was asked to wait, in the order they were asked. */
	private final List<Long> waits = new ArrayList<>();

	/** Retries waiting to run, so a test can let time pass on purpose. */
	private final Deque<Runnable> pending = new ArrayDeque<>();

	/** Whether the net thread is still there, which a shutdown makes false. */
	private boolean netThreadAlive = true;

	@Before
	public void setUp()
	{
		final FlippingRsConfig config = mock(FlippingRsConfig.class);
		when(config.setupOverlay()).thenReturn(true);
		when(config.apiKey()).thenReturn("a-key");
		api = mock(FlippingRsApi.class);

		watchlists = new Watchlists(mock(Client.class), mock(ItemManager.class), mock(ClientThread.class),
			config, mock(ProfileStore.class), () -> api, action ->
		{
		}, id -> "Abyssal whip", () ->
		{
		},
			// The net thread, run inline: these tests are about which
			// requests get made and when, not about which thread makes them.
			Runnable::run,
			(wait, work) ->
			{
				waits.add(wait);
				if (netThreadAlive)
				{
					pending.add(work);
				}
				return netThreadAlive;
			},
			id ->
			{
			});
		watchlists.exchangeOpen(true);
	}

	/** Lets every retry that has been scheduled run, and whatever they schedule. */
	private void letTimePass()
	{
		while (!pending.isEmpty())
		{
			pending.poll().run();
		}
	}

	private void showing(int... itemIds)
	{
		final Set<Integer> items = new HashSet<>();
		for (int itemId : itemIds)
		{
			items.add(itemId);
		}
		watchlists.showingOffers(items);
	}

	private void serverAnswers(int itemId) throws IOException
	{
		final Quote quote = new Quote();
		quote.id = itemId;
		quote.instantSell = 1_480_000;
		quote.instantBuy = 1_520_000;
		final PanelData data = new PanelData();
		data.quotes = Collections.singletonList(quote);
		// doReturn rather than when(...), because these tests re-stub a mock
		// that is currently set to throw, and when(...) would call it to find
		// out what to stub -- and be thrown at.
		doReturn(data).when(api).quotes(anyString(), any(), any());
	}

	private void serverIsDown() throws IOException
	{
		doThrow(new IOException("connection reset")).when(api).quotes(anyString(), any(), any());
	}

	/**
	 * A screen with an item nobody has a price for is asked about now rather
	 * than on the next refresh.
	 */
	@Test
	public void openingAScreenWithSomethingUnpricedAsksAtOnce() throws IOException
	{
		serverAnswers(WHIP);

		showing(WHIP);

		verify(api, times(1)).quotes(anyString(), any(), any());
	}

	/**
	 * And a screen whose items are already priced is not, because the price on
	 * it is the one the refresh is already keeping current.
	 */
	@Test
	public void aScreenOfItemsAlreadyPricedCostsNothing() throws IOException
	{
		serverAnswers(WHIP);
		showing(WHIP);

		// The same item again, arrived at from somewhere else in the exchange.
		showing();
		showing(WHIP);

		verify(api, times(1)).quotes(anyString(), any(), any());
	}

	/**
	 * A fetch that fails is tried again, waiting longer each time.
	 *
	 * <p>The failures worth retrying are a dropped connection, which clears in
	 * a second, and a server coming back up, which takes closer to a minute.
	 * Doubling covers both without hammering one that is properly down.
	 */
	@Test
	public void aFailedFetchIsTriedAgain() throws IOException
	{
		serverIsDown();

		showing(WHIP);
		letTimePass();

		assertEquals(Arrays.asList(2 * SECOND, 4 * SECOND, 8 * SECOND), waits);
		verify(api, times(4)).quotes(anyString(), any(), any());
	}

	/**
	 * And then it gives up, leaving the ordinary refresh to it. A server that
	 * is down costs a few requests rather than a client hammering it for the
	 * rest of the session.
	 */
	@Test
	public void andThenLeavesItToTheRefresh() throws IOException
	{
		serverIsDown();
		showing(WHIP);
		letTimePass();
		waits.clear();

		// The refresh comes round, fails, and gets its own tries -- a run of
		// failures must not leave the retry spent for the whole session.
		watchlists.fetchOnDemand();
		letTimePass();

		assertEquals(Arrays.asList(2 * SECOND, 4 * SECOND, 8 * SECOND), waits);
	}

	/** A fetch that works puts the allowance back for whatever fails next. */
	@Test
	public void oneThatWorksRestoresTheAllowance() throws IOException
	{
		serverIsDown();
		showing(WHIP);
		pending.poll().run();
		waits.clear();

		serverAnswers(WHIP);
		watchlists.fetchOnDemand();
		pending.clear();

		serverIsDown();
		watchlists.fetchOnDemand();
		letTimePass();

		assertEquals("three again, not the one that was left",
			Arrays.asList(2 * SECOND, 4 * SECOND, 8 * SECOND), waits);
	}

	/**
	 * A retry scheduled into a thread that has already stopped is a shutdown,
	 * not a failure: nothing is left half-counted for a plugin that is not
	 * going to run again.
	 */
	@Test
	public void aShutdownMidRetryIsNotAFailedTry() throws IOException
	{
		serverIsDown();
		netThreadAlive = false;

		showing(WHIP);

		assertEquals("asked once, and told the thread was gone", 1, waits.size());
		verify(api, times(1)).quotes(anyString(), any(), any());
	}
}
