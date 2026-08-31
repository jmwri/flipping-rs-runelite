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

	@Test
	public void recentTradesAreCappedAndNewestFirst() throws Exception
	{
		onEdt(() ->
		{
			final FlippingRsPanel panel = new FlippingRsPanel();
			for (int i = 0; i < 20; i++)
			{
				final GeTransaction tx = new GeTransaction();
				tx.side = "buy";
				tx.quantity = i;
				tx.itemName = "Item " + i;
				tx.grossValue = 1000;
				tx.occurredAt = "2026-08-31T12:00:00Z";
				panel.addRecent(tx);
			}

			final List<String> lines = panel.recentForTest();
			assertEquals("an unbounded list would grow for the whole session", 8, lines.size());
			assertTrue("newest first: " + lines.get(0), lines.get(0).contains("Item 19"));
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
			// Fixed instant, well away from now.
			tx.occurredAt = "2026-08-31T04:05:06Z";
			panel.addRecent(tx);

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
