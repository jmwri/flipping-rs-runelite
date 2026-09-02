package com.flippingrs;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The panel is where a wrong answer is silent: it is the only thing telling the
 * user which journal their trades are going to, so a selection that quietly
 * moves is worse than one that visibly fails.
 */
public class FlippingRsPanelTest
{
	private static FlippingRsApi.GameAccount account(String id, String label, boolean isDefault)
	{
		final FlippingRsApi.GameAccount a = new FlippingRsApi.GameAccount();
		a.id = id;
		a.label = label;
		a.isDefault = isDefault;
		return a;
	}

	/** Swing state must be touched on the Swing thread, including in a test. */
	private static void onEdt(Runnable body) throws InterruptedException, InvocationTargetException
	{
		SwingUtilities.invokeAndWait(body);
	}

	// ------------------------------------------------------------ gp format

	/**
	 * String.format follows the JVM's locale. On a machine set to most of
	 * Europe this produced "1,50M", and a decimal comma reads as a thousands
	 * separator -- 1.5M looks like 150M at a glance, in a panel whose entire
	 * job is reporting money.
	 */
	@Test
	public void gpIsFormattedTheSameInEveryLocale()
	{
		final Locale original = Locale.getDefault();
		try
		{
			for (Locale locale : new Locale[]{Locale.UK, Locale.GERMANY, Locale.FRANCE})
			{
				Locale.setDefault(locale);
				assertEquals(locale.toString(), "1.50M", FlippingRsPanel.gp(1_500_000));
				assertEquals(locale.toString(), "2.40B", FlippingRsPanel.gp(2_400_000_000L));
				assertEquals(locale.toString(), "1.5K", FlippingRsPanel.gp(1_500));
			}
		}
		finally
		{
			Locale.setDefault(original);
		}
	}

	@Test
	public void gpKeepsSmallAmountsExact()
	{
		assertEquals("999gp", FlippingRsPanel.gp(999));
		assertEquals("0gp", FlippingRsPanel.gp(0));
		assertEquals("-500gp", FlippingRsPanel.gp(-500));
		// Losses are money too, and the sign has to survive the shortening.
		assertTrue(FlippingRsPanel.gp(-1_500_000).startsWith("-"));
	}

	// ------------------------------------------------------- account picker

	@Test
	public void theCurrentSelectionSurvivesAReload() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final List<FlippingRsApi.GameAccount> accounts = Arrays.asList(
				account("a1", "Main", true),
				account("a2", "Alt", false));

			panel.setAccounts(accounts, "a2");

			assertEquals("a reconnect must not silently move somebody's journal",
				"a2", panel.selectedAccountId());
		});
	}

	@Test
	public void theDefaultIsChosenWhenNothingIsRemembered() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();

			panel.setAccounts(Arrays.asList(
				account("a1", "Main", false),
				account("a2", "Alt", true)), null);

			assertEquals("a2", panel.selectedAccountId());
		});
	}

	/**
	 * The remembered journal is gone from the list. Selecting the first entry
	 * instead would have the panel naming a journal the plugin is not filing
	 * under, which is the one lie this panel exists to not tell.
	 */
	@Test
	public void aRememberedJournalThatIsGoneSelectsNothing() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			panel.setAccounts(Arrays.asList(
				account("a1", "Main", true),
				account("a2", "Alt", false)), "deleted");
			assertNull(panel.selectedAccountId());
		});
	}

	@Test
	public void anEmptyListSelectsNothingRatherThanGuessing() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			panel.setAccounts(Collections.emptyList(), "a1");
			assertNull(panel.selectedAccountId());
		});
	}

	/**
	 * A malformed row from the server must not take the panel down, and must
	 * not become a selectable entry that cannot be filed against.
	 */
	@Test
	public void rowsWithoutAnIdAreIgnored() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final List<FlippingRsApi.GameAccount> accounts = new ArrayList<>();
			accounts.add(account(null, "No id", false));
			accounts.add(null);
			accounts.add(account("a2", "Real", false));

			panel.setAccounts(accounts, null);

			assertEquals("a2", panel.selectedAccountId());
		});
	}

	/**
	 * Repopulating a combo box fires a selection event. If that reaches the
	 * listener it looks exactly like the user picking an account, and the
	 * plugin writes the choice back to config on every reconnect -- which is
	 * how a remembered journal silently becomes whichever one sorted first.
	 */
	@Test
	public void reloadingTheListDoesNotLookLikeTheUserChoosing() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final AtomicInteger chosen = new AtomicInteger();
			panel.onAccountChosen(chosen::incrementAndGet);

			panel.setAccounts(Arrays.asList(
				account("a1", "Main", true),
				account("a2", "Alt", false)), "a2");

			assertEquals("repopulating is not a user choice", 0, chosen.get());
		});
	}

	@Test
	public void theListenerStillFiresForARealChoice() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			panel.setAccounts(Arrays.asList(
				account("a1", "Main", true),
				account("a2", "Alt", false)), "a1");

			final AtomicInteger chosen = new AtomicInteger();
			panel.onAccountChosen(chosen::incrementAndGet);
			panel.setSelectedForTest("a2");

			assertEquals(1, chosen.get());
			assertEquals("a2", panel.selectedAccountId());
		});
	}

	// ------------------------------------------------------------- watchlist

	private static FlippingRsApi.Watchlist watchlist(String id, String name)
	{
		final FlippingRsApi.Watchlist w = new FlippingRsApi.Watchlist();
		w.id = id;
		w.name = name;
		return w;
	}

	@Test
	public void theWatchlistRendersTheServersOrder() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();

			panel.setWatchlists(Arrays.asList(watchlist("wl_1", "Plan"), watchlist("wl_2", "Bonds")), "wl_2");
			panel.setWatchlistItems(Arrays.asList(
				new FlippingRsPanel.WatchedItem(11802, "Armadyl godsword", null, 12_000_000, 8, 0, "Buying 1/1 at 12.00M", null),
				new FlippingRsPanel.WatchedItem(4151, "Abyssal whip", null, 1_500_000, 70, 72_000, null, quote(4151))));

			assertEquals("wl_2", panel.selectedWatchlistId());
			assertEquals(Arrays.asList(11802, 4151), panel.watchlistForTest());

			panel.setWatchlistItems(Collections.emptyList());
			assertTrue(panel.watchlistForTest().isEmpty());
		});
	}

	/** A fill on a watched item touches only that card's offer line. */
	@Test
	public void aWatchedItemsOfferLineIsUpdatedInPlace() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			panel.setWatchlists(Arrays.asList(watchlist("wl_1", "Plan")), "wl_1");
			panel.setWatchlistItems(Arrays.asList(
				new FlippingRsPanel.WatchedItem(4151, "Abyssal whip", null, 0, 0, 0, "Buying 4/10 at 1.50M", null),
				new FlippingRsPanel.WatchedItem(11802, "Armadyl godsword", null, 0, 0, 0, null, null)));

			panel.updateWatchedOffer(4151, "Buying 6/10 at 1.50M");
			assertEquals("Buying 6/10 at 1.50M", panel.watchlistOfferForTest(4151));

			panel.updateWatchedOffer(11802, "Selling 1/1 at 12.00M");
			assertEquals("Selling 1/1 at 12.00M", panel.watchlistOfferForTest(11802));

			panel.updateWatchedOffer(4151, null);
			assertNull(panel.watchlistOfferForTest(4151));
			assertEquals("the list itself is untouched", Arrays.asList(4151, 11802), panel.watchlistForTest());
		});
	}

	/** Recording off shows why the tabs are empty, until data arrives again. */
	@Test
	public void pausingShowsTheReasonUntilDataArrives() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			panel.setPaused("Recording is off.");
			assertEquals("Recording is off.", panel.pausedForTest());

			panel.setActivity(Collections.emptyList());
			assertNull(panel.pausedForTest());
		});
	}

	/** The facts line says what is known and nothing about what is not. */
	@Test
	public void theFactsLineLeavesOutWhatIsUnknown()
	{
		assertEquals("1.50M · limit 70 · alch 72.0K",
			FlippingRsPanel.facts(new FlippingRsPanel.WatchedItem(4151, "Abyssal whip", null, 1_500_000, 70, 72_000, null, null)));
		assertEquals("limit 8",
			FlippingRsPanel.facts(new FlippingRsPanel.WatchedItem(1, "x", null, 0, 8, 0, null, null)));
		assertEquals("No price known",
			FlippingRsPanel.facts(new FlippingRsPanel.WatchedItem(1, "x", null, 0, 0, 0, null, null)));
	}

	/** A quote from the site, as the watchlist card renders it. */
	private static FlippingRsApi.Quote quote(int id)
	{
		final FlippingRsApi.Quote q = new FlippingRsApi.Quote();
		q.id = id;
		q.instantSell = 1_480_000;
		q.instantBuy = 1_520_000;
		q.netMargin = 9_600;
		q.roi = 0.0065;
		q.buyLimit = 70;
		q.profitPerLimit = 672_000;
		q.volume24h = 1234;
		return q;
	}

	/**
	 * The site's numbers, formatted and nothing more. A flip buys at the
	 * instant-sell price and sells at the instant-buy price, which is the
	 * way round the site labels them, so the card must say "Buy" for the
	 * lower figure.
	 */
	@Test
	public void theQuoteLinesShowTheSitesNumbersTheRightWayRound()
	{
		final FlippingRsApi.Quote q = quote(4151);
		assertEquals("Buy 1,480,000 · Sell 1,520,000", FlippingRsPanel.pricesLine(q));
		assertEquals("Margin +9,600 · ROI 0.7%", FlippingRsPanel.marginLine(q));
		assertEquals("Limit 70 · +672.0K per limit · 1.2K traded/24h",
			FlippingRsPanel.limitLine(new FlippingRsPanel.WatchedItem(4151, "Abyssal whip", null, 0, 0, 0, null, q)));

		final FlippingRsApi.Quote losing = quote(1);
		losing.netMargin = -500;
		losing.roi = -0.01;
		assertEquals("Margin -500 · ROI -1.0%", FlippingRsPanel.marginLine(losing));
	}

	@Test
	public void theWatchlistCardShowsTheSitesPricesWhenItHasThem() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			panel.setWatchlists(Arrays.asList(watchlist("wl_1", "Plan")), "wl_1");
			panel.setWatchlistItems(Arrays.asList(
				new FlippingRsPanel.WatchedItem(4151, "Abyssal whip", null, 1_500_000, 70, 0, null, quote(4151)),
				new FlippingRsPanel.WatchedItem(11802, "Armadyl godsword", null, 12_000_000, 8, 0, null, null)));

			assertEquals("Buy 1,480,000 · Sell 1,520,000", panel.watchlistPricesForTest(4151));
			assertNull("no quote, so the client's own price line is used instead", panel.watchlistPricesForTest(11802));
		});
	}

	// --------------------------------------------------------------- journal

	@Test
	public void theJournalSummaryReadsAsASentence()
	{
		final FlippingRsApi.Analytics week = new FlippingRsApi.Analytics();
		assertEquals("No flips closed this week.", FlippingRsPanel.summarise(week));

		week.completedFlips = 12;
		week.realisedProfit = 1_200_000;
		week.winRate = 0.75;
		week.gpPerHour = 45_000;
		assertEquals("+1.20M from 12 flips · 75.0% wins · 45.0K gp/h", FlippingRsPanel.summarise(week));

		week.completedFlips = 1;
		week.realisedProfit = -3_000;
		week.gpPerHour = 0;
		assertEquals("-3.0K from 1 flip · 75.0% wins", FlippingRsPanel.summarise(week));
	}

	/** A position shows what was paid and both sides of the sale, to the coin. */
	@Test
	public void aPositionShowsBothSidesOfTheSaleExactly()
	{
		final FlippingRsApi.Position p = new FlippingRsApi.Position();
		p.buyPrice = 1_480_000;
		p.currentBuy = 1_520_000;
		p.currentSell = 1_500_000;
		assertEquals("Bought 1,480,000 · Sell 1,520,000 (now 1,500,000)", FlippingRsPanel.positionPrices(p));

		p.currentSell = 1_520_000;
		assertEquals("no bracket when both sides agree", "Bought 1,480,000 · Sell 1,520,000",
			FlippingRsPanel.positionPrices(p));

		p.currentBuy = 0;
		p.currentSell = 1_500_000;
		assertEquals("Bought 1,480,000 · Sell 1,500,000", FlippingRsPanel.positionPrices(p));
	}

	@Test
	public void theJournalTabRendersOpenPositionsAndTheirTotals() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final FlippingRsApi.Positions open = new FlippingRsApi.Positions();
			final FlippingRsApi.Position whip = new FlippingRsApi.Position();
			whip.itemId = 4151;
			whip.itemName = "Abyssal whip";
			whip.remainingQty = 10;
			whip.buyPrice = 1_480_000;
			whip.currentSell = 1_520_000;
			whip.unrealisedPnl = 96_000;
			whip.unrealisedRoi = 0.0065;
			whip.hoursHeld = 5;
			open.positions = Arrays.asList(whip);
			open.summary = new FlippingRsApi.Positions.Summary();
			open.summary.openPositions = 1;

			panel.setJournal(new FlippingRsApi.Analytics(), open);

			assertEquals(Arrays.asList(4151), panel.positionsForTest());
			assertNull(panel.journalProblemForTest());

			panel.setJournalProblem("This API key is scoped to the RuneLite plugin.");
			assertTrue(panel.positionsForTest().isEmpty());
			assertTrue(panel.journalSummaryForTest().contains("scoped"));
		});
	}

	@Test
	public void theSmallFormattersAreExact()
	{
		assertEquals("+30.0K", FlippingRsPanel.signed(30_000));
		assertEquals("-1.2K", FlippingRsPanel.signed(-1_200));
		assertEquals("0gp", FlippingRsPanel.signed(0));
		assertEquals("2.1%", FlippingRsPanel.pct(0.0213));
		assertEquals("1.2K", FlippingRsPanel.count(1_234));
		assertEquals("340", FlippingRsPanel.count(340));
		assertEquals("40m", FlippingRsPanel.hours(0.66));
		assertEquals("5h", FlippingRsPanel.hours(5.2));
		assertEquals("2d 3h", FlippingRsPanel.hours(51));
	}

	// ------------------------------------------------------------------ tabs

	@Test
	public void thePanelOpensOnActivityAndHasTheFiveTabs() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			assertEquals("Activity", panel.selectedTabForTest());

			for (String tab : new String[]{"Trades", "Journal", "Watchlists", "Account", "Activity"})
			{
				panel.selectTabForTest(tab);
				assertEquals(tab, panel.selectedTabForTest());
			}
		});
	}

	/**
	 * Four text tabs are wider than the sidebar in one row. The layout that
	 * wrapped them silently hid the last two, so the strip's width is pinned
	 * to what the panel actually has.
	 */
	@Test
	public void allFourTabsFitTheSidebar() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final int available = net.runelite.client.ui.PluginPanel.PANEL_WIDTH - 20;
			assertTrue("tab strip is " + panel.tabStripWidthForTest() + "px, sidebar content is " + available,
				panel.tabStripWidthForTest() <= available);
		});
	}

	/**
	 * A notice is news, and news that never leaves stops being read. Both
	 * notices clear themselves after a while, and a new one restarts the
	 * clock.
	 */
	@Test
	public void noticesExpireOnTheirOwn() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			panel.setActivityNotice("Recovered 2 trade(s) from your Grand Exchange history.", java.awt.Color.WHITE);
			panel.setWatchlistNotice("Removed from Plan.", java.awt.Color.WHITE);
			assertTrue(panel.activityNoticeShowingForTest());
			assertTrue(panel.watchlistNoticeShowingForTest());

			panel.expireNoticesForTest();

			assertTrue("gone after the interval", !panel.activityNoticeShowingForTest());
			assertTrue("gone after the interval", !panel.watchlistNoticeShowingForTest());

			panel.setActivityNotice(null, java.awt.Color.WHITE);
			panel.expireNoticesForTest();
			assertTrue("clearing by hand does not leave a timer armed", !panel.activityNoticeShowingForTest());
		});
	}

	/** Each tab keeps its own message; one tab's news must not overwrite another's. */
	@Test
	public void noticesStayOnTheirOwnTab() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			panel.setStatus("Connected and recording.", java.awt.Color.WHITE);
			panel.setActivityNotice("3 trade(s) were refused", java.awt.Color.WHITE);
			panel.setWatchlistNotice("Added to Plan.", java.awt.Color.WHITE);

			assertTrue(panel.statusTextForTest().contains("Connected"));
			assertTrue(panel.activityNoticeForTest().contains("refused"));
			assertTrue(panel.watchlistNoticeForTest().contains("Added"));
		});
	}

	/** Repopulating the picker must not look like the user choosing a list. */
	@Test
	public void reloadingTheWatchlistsDoesNotLookLikeTheUserChoosing() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final AtomicInteger chosen = new AtomicInteger();
			panel.onWatchlistChosen(chosen::incrementAndGet);

			panel.setWatchlists(Arrays.asList(watchlist("wl_1", "Plan"), watchlist("wl_2", "Bonds")), "wl_1");

			assertEquals(0, chosen.get());
		});
	}

	// ------------------------------------------------------------- rendering

	/**
	 * Status text is rendered as HTML so it can wrap. Server messages and item
	 * names are not ours, so a stray angle bracket must not be interpreted --
	 * at best it swallows the rest of the message, at worst it renders markup
	 * from a server the user pointed at by mistake.
	 */
	@Test
	public void statusTextIsEscapedNotInterpreted() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			panel.setStatus("<b>bold</b> & <script>", java.awt.Color.WHITE);

			final String rendered = panel.statusTextForTest();
			assertTrue(rendered.contains("&lt;b&gt;"));
			assertTrue(rendered.contains("&amp;"));
			assertTrue("the raw tag must not survive", !rendered.contains("<script>"));
		});
	}

	/**
	 * The recent trades are whatever the server sent, in its order, capped
	 * to what fits. The panel keeps no list of its own between reads.
	 */
	@Test
	public void recentTradesAreTheServersCappedToWhatFits() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final List<GeTransaction> rows = new ArrayList<>();
			for (int i = 19; i >= 0; i--)
			{
				final GeTransaction tx = new GeTransaction();
				tx.side = "buy";
				tx.quantity = i;
				tx.itemName = "Item " + i;
				tx.grossValue = 1000;
				tx.occurredAt = "2026-08-31T12:00:00Z";
				rows.add(tx);
			}
			panel.setActivity(rows);

			final List<String> lines = panel.recentForTest();
			assertEquals(FlippingRsPanel.RECENT_SHOWN, lines.size());
			assertTrue("the server's first row is the top line: " + lines.get(0), lines.get(0).contains("Item 19"));

			// A later read replaces, rather than accumulates.
			panel.setActivity(Collections.emptyList());
			assertTrue(panel.recentForTest().isEmpty());
		});
	}

	/**
	 * The displayed time is the fill's own, not the clock. A queue draining
	 * after a spell offline would otherwise stamp every recovered trade with
	 * the moment the panel happened to redraw.
	 */
	@Test
	public void aRecentLineIsStampedWithWhenItHappened() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final GeTransaction tx = new GeTransaction();
			tx.side = "sell";
			tx.quantity = 1;
			tx.itemName = "Bond";
			tx.grossValue = 1000;
			// Fixed instant, well away from now, with the nanoseconds a Go
			// server writes.
			tx.occurredAt = "2026-08-31T04:05:06.123456789Z";
			panel.setActivity(Collections.singletonList(tx));

			// Compare against the same instant rendered in this machine's zone,
			// rather than a hardcoded hour -- otherwise the test passes or
			// fails depending on where it runs.
			final String expected = DateTimeFormatter.ofPattern("HH:mm:ss")
				.withZone(ZoneId.systemDefault())
				.format(Instant.parse("2026-08-31T04:05:06Z"));

			final String line = panel.recentForTest().get(0);
			assertTrue("expected the fill's own time (" + expected + "), got: " + line,
				line.startsWith(expected));
			assertTrue(line.contains("Bond"));
		});
	}
}
