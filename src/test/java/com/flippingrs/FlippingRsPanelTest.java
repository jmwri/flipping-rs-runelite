package com.flippingrs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
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
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;
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

	/**
	 * A journal that has loaded must stop saying it has not. The summary is
	 * written as the reply arrives and then written over by whichever of the
	 * three "nothing to show" lines applies, so the flag saying one has
	 * arrived is what decides which of the two the user reads.
	 */
	@Test
	public void aLoadedJournalStopsSayingNotLoadedYet() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			assertTrue(panel.journalSummaryForTest(), panel.journalSummaryForTest().contains("Not loaded yet"));

			final FlippingRsApi.Analytics week = new FlippingRsApi.Analytics();
			week.completedFlips = 12;
			week.realisedProfit = 1_200_000;
			week.winRate = 0.75;
			final FlippingRsApi.Positions open = new FlippingRsApi.Positions();
			open.positions = new ArrayList<>();
			open.summary = new FlippingRsApi.Positions.Summary();

			panel.setJournal(week, open);

			final String shown = panel.journalSummaryForTest();
			assertTrue("a journal that has arrived must not still say it has not: " + shown,
				!shown.contains("Not loaded"));
			assertTrue(shown, shown.contains("12 flips"));
		});
	}

	/**
	 * The exact points where the shortening changes unit. A formatter goes
	 * wrong on its own boundaries or nowhere, and every one of these is a
	 * figure a person reads as money.
	 */
	@Test
	public void gpChangesUnitAtExactlyTheRightAmounts()
	{
		assertEquals("999gp", FlippingRsPanel.gp(999));
		assertEquals("1.0K", FlippingRsPanel.gp(1_000));
		assertEquals("1.00M", FlippingRsPanel.gp(1_000_000));
		assertEquals("1.00B", FlippingRsPanel.gp(1_000_000_000L));
		// And a count, which shortens on the same boundaries but is not money.
		assertEquals("999", FlippingRsPanel.count(999));
		assertEquals("1.0K", FlippingRsPanel.count(1_000));
		assertEquals("1.0M", FlippingRsPanel.count(1_000_000));
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

	/**
	 * A tab that is off screen does not build its list, and building it is the
	 * expensive part of this panel: every line is a wrapped HTML label that
	 * Swing parses into a document of its own, and a hundred open positions
	 * measured at close to half a second. Selecting the tab builds what it
	 * missed, so nothing is lost by waiting.
	 */
	@Test
	public void aTabOffScreenDoesNotBuildItsListUntilItIsShown() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			assertEquals("Activity", panel.selectedTabForTest());

			final FlippingRsApi.Positions open = new FlippingRsApi.Positions();
			final FlippingRsApi.Position whip = new FlippingRsApi.Position();
			whip.itemId = 4151;
			whip.itemName = "Abyssal whip";
			whip.remainingQty = 10;
			open.positions = Arrays.asList(whip);
			open.summary = new FlippingRsApi.Positions.Summary();

			panel.setJournal(new FlippingRsApi.Analytics(), open);

			assertEquals("the position is held, so the tab has it when it is shown",
				Arrays.asList(4151), panel.positionsForTest());
			assertEquals("but nothing is built for a tab nobody is looking at",
				0, panel.drawnRowsForTest("Journal"));

			panel.selectTabForTest("Journal");

			assertTrue("selecting it builds what it missed",
				panel.drawnRowsForTest("Journal") > 0);

			// And a later change while it is showing is drawn straight away.
			open.positions = new ArrayList<>();
			panel.setJournal(new FlippingRsApi.Analytics(), open);
			assertEquals(0, panel.drawnRowsForTest("Journal"));
		});
	}

	/** The same for the other two lists, since each gates itself. */
	@Test
	public void everyListIsBuiltWhenItsTabIsShown() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();

			final GeTransaction tx = new GeTransaction();
			tx.side = "buy";
			tx.quantity = 4;
			tx.itemName = "Abyssal whip";
			tx.grossValue = 3_800_000;
			panel.setActivity(Arrays.asList(tx));
			panel.setPending(Arrays.asList(tx));
			panel.setWatchlists(Arrays.asList(watchlist("wl_1", "Plan")), "wl_1");
			panel.setWatchlistItems(Arrays.asList(
				new FlippingRsPanel.WatchedItem(4151, "Abyssal whip", null, 1_500_000, 70, 0, null, quote(4151))));

			// Activity is the tab on show, so its buffer list is already built.
			assertTrue(panel.drawnRowsForTest("Activity") > 0);
			assertEquals(0, panel.drawnRowsForTest("Trades"));
			assertEquals(0, panel.drawnRowsForTest("Watchlists"));

			panel.selectTabForTest("Trades");
			assertTrue("the recorded trade appears when its tab does",
				panel.drawnRowsForTest("Trades") > 0);

			panel.selectTabForTest("Watchlists");
			assertTrue("and so does the watched item",
				panel.drawnRowsForTest("Watchlists") > 0);
		});
	}

	/** Gp typed by a person: separators, and the k/m/b the game uses. */
	@Test
	public void typedGpIsReadTheWayPeopleWriteIt()
	{
		assertEquals(1_480_000L, FlippingRsPanel.parseGp("1,480,000"));
		assertEquals(1_480_000L, FlippingRsPanel.parseGp("1480000"));
		assertEquals(1_480_000L, FlippingRsPanel.parseGp("1.48m"));
		assertEquals(1_500L, FlippingRsPanel.parseGp("1.5K"));
		assertEquals(2_000_000_000L, FlippingRsPanel.parseGp("2b"));
		assertEquals(0L, FlippingRsPanel.parseGp("lots"));
		assertEquals(0L, FlippingRsPanel.parseGp(""));
	}

	/** The card's Close and Delete reach the plugin with the position's id. */
	@Test
	public void closingAndDeletingAPositionReachThePlugin() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final List<String> closed = new ArrayList<>();
			final List<String> deleted = new ArrayList<>();
			panel.onClosePosition((id, price, qty) -> closed.add(id + "@" + price + "x" + qty));
			panel.onDeletePosition(deleted::add);

			panel.closePosition("f1", 1_520_000, 4L);
			panel.closePosition("f2", 1_000, null);
			panel.setJournalNotice("Sale recorded.", java.awt.Color.WHITE);

			assertEquals(Arrays.asList("f1@1520000x4", "f2@1000xnull"), closed);
			assertTrue(panel.journalNoticeForTest().contains("Sale recorded"));
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

	/**
	 * Days and hours have to come from one rounding. Rounding the leftover
	 * hours separately reported "2d 24h" for anything held into the last half
	 * hour of a day, which is not a duration.
	 */
	@Test
	public void aDurationNeverRollsOverIntoTwentyFourHours()
	{
		assertEquals("3d 0h", FlippingRsPanel.hours(71.9));
		assertEquals("2d 0h", FlippingRsPanel.hours(48.0));
		assertEquals("2d 2h", FlippingRsPanel.hours(50.4));
		// And the minutes below an hour do the same at their own boundary.
		assertEquals("1h", FlippingRsPanel.hours(0.999));
		assertEquals("59m", FlippingRsPanel.hours(0.99));
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

			panel.setActivityNotice("Recovered 2 trade(s) from your Grand Exchange history.", java.awt.Color.WHITE);
			assertTrue("a notice that is up is counting down", panel.activityNoticeTimerArmedForTest());
			panel.setActivityNotice(null, java.awt.Color.WHITE);
			assertTrue("clearing by hand must stop the countdown, not leave it to fire later",
				!panel.activityNoticeTimerArmedForTest());
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

			// A short line is handed to the label as it stands, rather than
			// being made into a document it does not need, so the guard has to
			// hold for a message too short to wrap as well.
			panel.setStatus("<html><b>x</b>", java.awt.Color.WHITE);
			final String short0 = panel.statusTextForTest();
			assertTrue("a short line must not become markup either: " + short0,
				short0.contains("&lt;html&gt;"));
		});
	}

	/**
	 * And a label is only handed the text as it stands when Swing will read it
	 * as text. Swing reads a label's text as markup if it opens with a tag, so
	 * the one thing the short path must never produce is a string that starts
	 * one.
	 */
	@Test
	public void noLineTheSidebarDrawsCanOpenWithATag() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final GeTransaction tx = new GeTransaction();
			tx.side = "buy";
			tx.quantity = 1;
			tx.itemName = "<html><b>whip</b>";
			tx.grossValue = 5;
			tx.occurredAt = "2026-08-31T16:10:12.482Z";
			panel.setActivity(Collections.singletonList(tx));
			panel.setWatchlistItems(Collections.singletonList(new FlippingRsPanel.WatchedItem(
				4151, "<html><b>whip</b>", null, 10, 70, 5, "<html>buying", quote(4151))));
			panel.setActivityNotice("<html><i>notice</i>", Color.WHITE);

			for (String tab : new String[]{"Activity", "Trades", "Journal", "Watchlists", "Account"})
			{
				panel.selectTabForTest(tab);
				final Container wrapped = panel.getWrappedPanel();
				wrapped.setSize(PluginPanel.PANEL_WIDTH, 4000);
				layOut(wrapped);
				assertNoTagOpensALabel(tab, wrapped);
			}
		});
	}

	private static void assertNoTagOpensALabel(String tab, Component c)
	{
		if (c instanceof JLabel)
		{
			final String text = ((JLabel) c).getText();
			if (text != null && text.startsWith("<") && !text.startsWith("<html><body style="))
			{
				throw new AssertionError(tab + ": this line opens with a tag: " + text);
			}
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				assertNoTagOpensALabel(tab, child);
			}
		}
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
	 * The buffer list is capped the same way the trade list is. Its twin was
	 * pinned and this one was not, which is how the sidebar ends up a row
	 * taller than it was drawn to be.
	 */
	@Test
	public void theBufferListShowsOnlyTheNewestFew() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final List<GeTransaction> many = new ArrayList<>();
			for (int i = 20; i > 0; i--)
			{
				final GeTransaction tx = new GeTransaction();
				tx.side = "buy";
				tx.quantity = i;
				tx.itemName = "Abyssal whip";
				tx.grossValue = 1000;
				many.add(tx);
			}

			panel.setPending(many);

			final List<String> lines = panel.pendingForTest();
			assertEquals(FlippingRsPanel.RECENT_SHOWN, lines.size());
			assertTrue("newest first: " + lines.get(0), lines.get(0).contains("Bought 20 "));
			assertTrue("and it stops there: " + lines.get(7), lines.get(7).contains("Bought 13 "));
		});
	}

	/**
	 * A fill whose name never resolved -- an item the client would not name,
	 * or a row restored from a queue file an older version wrote -- must fall
	 * back to the id in the buffer list the way the trade cards already do.
	 * "1 x null" is not a line to show anyone about their money.
	 */
	@Test
	public void aFillWithNoItemNameFallsBackToItsId() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final GeTransaction unnamed = new GeTransaction();
			unnamed.side = "buy";
			unnamed.quantity = 1;
			unnamed.itemId = 4151;
			unnamed.grossValue = 1000;
			final GeTransaction blank = new GeTransaction();
			blank.side = "sell";
			blank.quantity = 2;
			blank.itemId = 11802;
			blank.itemName = "";
			blank.grossValue = 2000;

			panel.setPending(Arrays.asList(unnamed, blank));

			final List<String> lines = panel.pendingForTest();
			assertTrue(lines.get(0), lines.get(0).contains("1 x Item 4151"));
			assertTrue(lines.get(1), lines.get(1).contains("2 x Item 11802"));
		});
	}

	/**
	 * A stamp that cannot be read is not the current time. The whole of this
	 * plugin's dealings with time rest on never claiming one it does not have,
	 * and the buffer line used to fill an unreadable stamp in with now.
	 */
	@Test
	public void anUnreadableTimeIsSaidToBeUnknownRatherThanNow() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final GeTransaction damaged = new GeTransaction();
			damaged.side = "buy";
			damaged.quantity = 1;
			damaged.itemName = "Abyssal whip";
			damaged.grossValue = 1000;
			damaged.occurredAt = "not a time";
			final GeTransaction recovered = new GeTransaction();
			recovered.side = "buy";
			recovered.quantity = 1;
			recovered.itemName = "Abyssal whip";
			recovered.grossValue = 1000;

			panel.setPending(Arrays.asList(damaged, recovered));

			final List<String> lines = panel.pendingForTest();
			assertTrue(lines.get(0), lines.get(0).startsWith("unknown "));
			assertTrue(lines.get(1), lines.get(1).startsWith("recovered "));
		});
	}

	/** A trade card says what happened in exact gp, with the per-item price. */
	@Test
	public void aTradeCardSaysWhatHappenedToTheCoin()
	{
		final GeTransaction tx = new GeTransaction();
		tx.side = "buy";
		tx.quantity = 4;
		tx.grossValue = 3_800_000;
		assertEquals("Bought 4 for 3,800,000 (950,000 each)", FlippingRsPanel.whatHappened(tx));

		tx.side = "sell";
		tx.quantity = 1;
		tx.grossValue = 1_520_000;
		tx.estimated = true;
		assertEquals("Sold 1 for 1,520,000 (approx)", FlippingRsPanel.whatHappened(tx));
	}

	/** When a trade happened, in words a person uses; recovered trades say so. */
	@Test
	public void aTradeCardSaysWhenInPlainWords()
	{
		final java.time.ZoneId zone = java.time.ZoneId.systemDefault();
		final Instant now = java.time.LocalDate.of(2026, 9, 2).atTime(20, 0).atZone(zone).toInstant();
		final GeTransaction tx = new GeTransaction();

		tx.occurredAt = java.time.LocalDate.of(2026, 9, 2).atTime(12, 0, 1).atZone(zone).toInstant().toString();
		assertEquals("Today 12:00:01", FlippingRsPanel.when(tx, now));

		tx.occurredAt = java.time.LocalDate.of(2026, 9, 1).atTime(18, 32).atZone(zone).toInstant().toString();
		assertEquals("Yesterday 18:32", FlippingRsPanel.when(tx, now));

		tx.occurredAt = java.time.LocalDate.of(2026, 8, 20).atTime(9, 5).atZone(zone).toInstant().toString();
		assertEquals("20 Aug 09:05", FlippingRsPanel.when(tx, now));

		tx.occurredAt = null;
		assertEquals("Recovered, time unknown", FlippingRsPanel.when(tx, now));

		tx.occurredAt = "not a time";
		assertEquals("Time unknown", FlippingRsPanel.when(tx, now));
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

	// ------------------------------------------------------------ visibility

	/**
	 * The quote refresh only runs while something is showing the quotes, and
	 * the sidebar tells the plugin when it is. RuneLite drives these through
	 * the Activatable hooks the panel inherits.
	 */
	@Test
	public void theSidebarReportsWhenItIsShownAndHidden() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final List<String> seen = new ArrayList<>();
			panel.onShown(() -> seen.add("shown"));
			panel.onHidden(() -> seen.add("hidden"));

			panel.onActivate();
			panel.onDeactivate();
			panel.onActivate();

			assertEquals(Arrays.asList("shown", "hidden", "shown"), seen);
		});
	}

	// ----------------------------------------------------------------- width

	/**
	 * Every wrapped line has to fit the width it is given.
	 *
	 * <p>A label that wraps is told how wide to wrap at, in pixels, and the
	 * figure is not one Swing works out -- it is written into the HTML. Get it
	 * too small and every line breaks early and every card is taller than it
	 * needs to be; get it too big and the end of the line is simply cut off,
	 * and these are lines that end in a price. Neither shows up in a test that
	 * only reads the text back, because both render the same string.
	 *
	 * <p>So this lays the panel out at the width RuneLite gives a side panel
	 * and checks the one thing that is silently wrong: a line asking for more
	 * room than it has. It covers every tab, with content wide enough to wrap.
	 */
	@Test
	public void nothingTheSidebarDrawsIsCutOffAtTheWidthItIsGiven() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			fillWithWideContent(panel);

			final List<String> clipped = new ArrayList<>();
			for (String tab : new String[]{"Activity", "Trades", "Journal", "Watchlists", "Account"})
			{
				panel.selectTabForTest(tab);
				final Container wrapped = panel.getWrappedPanel();
				wrapped.setSize(PluginPanel.PANEL_WIDTH, 4000);
				layOut(wrapped);
				collectClipped(tab, wrapped, clipped);
			}

			assertTrue("these lines are wider than the room they are drawn in:\n"
				+ String.join("\n", clipped), clipped.isEmpty());
		});
	}

	/**
	 * And a wrapped line uses most of the room it is given.
	 *
	 * <p>The check above passes just as well if every line wraps at one pixel,
	 * so it cannot be the only one. A card's lines get 193 pixels of the 225
	 * the panel has, and the figure they wrapped at was a flat 150 for every
	 * line on every tab -- which, once Swing has scaled it, was 44 pixels wider
	 * than the title beside a card's sprite and ten short of a full-width line.
	 * One number could not be right for both. This is the other half of the
	 * bound: each line runs nearly the width of its own row.
	 */
	@Test
	public void aWrappedLineUsesMostOfTheRoomItIsGiven() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			fillWithWideContent(panel);

			final List<String> wasteful = new ArrayList<>();
			int checked = 0;
			for (String tab : new String[]{"Activity", "Trades", "Journal", "Watchlists", "Account"})
			{
				panel.selectTabForTest(tab);
				final Container wrapped = panel.getWrappedPanel();
				wrapped.setSize(PluginPanel.PANEL_WIDTH, 4000);
				layOut(wrapped);

				final List<JLabel> wrapping = new ArrayList<>();
				collectWrapping(wrapped, wrapping);
				checked += wrapping.size();
				for (JLabel label : wrapping)
				{
					// A card's lines and a full-width line share one width, and
					// the card is the narrower, so a full-width line is allowed
					// to fall short of its row by that difference.
					final int spare = label.getWidth() - label.getPreferredSize().width;
					if (spare > 20)
					{
						wasteful.add(tab + ": has " + label.getWidth() + " uses only "
							+ label.getPreferredSize().width + "  " + plain(label));
					}
				}
			}

			assertTrue("expected wrapped lines to check, found " + checked, checked > 10);
			assertTrue("these lines wrap well short of the room they have:\n"
				+ String.join("\n", wasteful), wasteful.isEmpty());
		});
	}

	/**
	 * An ordinary watchlist is drawn out of plain labels, not documents.
	 *
	 * <p>Wrapping a label means handing Swing HTML, and Swing builds a document
	 * to hold it -- about half a millisecond each against twenty microseconds
	 * for a plain label. A watchlist card is five or six lines, so drawing one
	 * every thirty seconds out of documents is what made a list of sixty cost a
	 * sixth of a second of the Swing thread.
	 *
	 * <p>Nothing here can time a redraw without being flaky, but it can count
	 * what the redraw is made of, which is the thing that decides the cost. An
	 * ordinary item name and an ordinary price line both fit their row.
	 */
	@Test
	public void anOrdinaryWatchlistIsDrawnWithoutBuildingADocumentPerLine() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			final List<FlippingRsPanel.WatchedItem> items = new ArrayList<>();
			for (String name : new String[]{"Abyssal whip", "Dragon bones", "Magic logs", "Rune platebody"})
			{
				items.add(new FlippingRsPanel.WatchedItem(4151, name, null, 1_500_000, 70, 72_000,
					"Buying 4/10 at 1.50M", quote(4151)));
			}
			panel.setWatchlistItems(items);
			panel.selectTabForTest("Watchlists");
			final Container wrapped = panel.getWrappedPanel();
			wrapped.setSize(PluginPanel.PANEL_WIDTH, 4000);
			layOut(wrapped);

			final List<JLabel> all = new ArrayList<>();
			collectLabels(wrapped, all);
			final List<String> documents = new ArrayList<>();
			for (JLabel label : all)
			{
				final String text = label.getText();
				if (text != null && !text.isEmpty() && text.startsWith("<html>"))
				{
					documents.add(plain(label));
				}
			}

			// A few still have to wrap and should: the tab's hint, and the
			// line of limits, which really is a shade wider than a card.
			assertTrue("expected a drawn watchlist, found " + all.size() + " labels", all.size() > 20);
			assertTrue("most of these lines were built as a document:\n"
				+ String.join("\n", documents), documents.size() * 3 <= all.size());
		});
	}

	private static void collectLabels(Component c, List<JLabel> out)
	{
		if (c instanceof JLabel)
		{
			out.add((JLabel) c);
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				collectLabels(child, out);
			}
		}
	}

	/** Content wide enough that every kind of line has to wrap. */
	private static void fillWithWideContent(FlippingRsPanel panel)
	{
		final GeTransaction tx = new GeTransaction();
		tx.side = "buy";
		tx.quantity = 25;
		tx.itemName = "Ancient ceremonial legs";
		tx.grossValue = 30_864_175;
		tx.occurredAt = "2026-08-31T16:10:12.482Z";
		panel.setActivity(Collections.singletonList(tx));
		panel.setPending(Collections.singletonList(tx));

		final FlippingRsApi.Quote q = quote(4151);
		panel.setWatchlistItems(Collections.singletonList(new FlippingRsPanel.WatchedItem(
			4151, "Ancient ceremonial legs", null, 1_500_000, 70, 72_000,
			"Buying 4/10 at 1.50M", q)));

		final FlippingRsApi.Position pos = new FlippingRsApi.Position();
		pos.id = "p1";
		pos.itemId = 4151;
		pos.itemName = "Ancient ceremonial legs";
		pos.buyPrice = 1_480_000;
		pos.remainingQty = 10;
		pos.currentBuy = 1_520_000;
		pos.currentSell = 1_500_000;
		pos.unrealisedPnl = 96_000;
		pos.breakEvenSell = 1_510_204;
		pos.hoursHeld = 5.5;
		pos.stale = true;
		final FlippingRsApi.Positions open = new FlippingRsApi.Positions();
		open.positions = Collections.singletonList(pos);
		open.summary = new FlippingRsApi.Positions.Summary();
		panel.setJournal(new FlippingRsApi.Analytics(), open);

		panel.setStatus("Could not connect to flippingrs.com: the request timed out.", Color.WHITE);
		panel.setActivityNotice(
			"flippingrs.com couldn't accept 3 trade(s). They have been set aside.", Color.WHITE);
	}

	/** Lays a tree out the way a panel on screen would be. */
	private static void layOut(Component c)
	{
		if (c instanceof Container)
		{
			final Container container = (Container) c;
			container.doLayout();
			for (Component child : container.getComponents())
			{
				layOut(child);
			}
		}
	}

	private static void collectClipped(String tab, Component c, List<String> out)
	{
		if (c instanceof JLabel && c.getWidth() > 0)
		{
			final JLabel label = (JLabel) c;
			final int wanted = label.getPreferredSize().width;
			if (wanted > label.getWidth())
			{
				out.add(tab + ": wants " + wanted + " has " + label.getWidth()
					+ "  " + plain(label));
			}
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				collectClipped(tab, child, out);
			}
		}
	}

	/** The labels that were told a width to wrap at, as opposed to plain ones. */
	private static void collectWrapping(Component c, List<JLabel> out)
	{
		if (c instanceof JLabel)
		{
			final JLabel label = (JLabel) c;
			final String text = label.getText();
			if (text != null && text.contains("width:") && label.getWidth() > 0)
			{
				out.add(label);
			}
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				collectWrapping(child, out);
			}
		}
	}

	private static String plain(JLabel label)
	{
		final String text = label.getText();
		return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
	}
}
