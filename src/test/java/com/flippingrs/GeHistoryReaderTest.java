package com.flippingrs;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The history screen is read off widgets with no ids and no times, so what
 * matters is that a row yields all four facts or nothing, never a half-read
 * trade.
 */
public class GeHistoryReaderTest
{
	private final List<Widget> children = new ArrayList<>();

	private Widget item(int y, int itemId, int quantity)
	{
		final Widget w = mock(Widget.class);
		when(w.getRelativeY()).thenReturn(y);
		when(w.getItemId()).thenReturn(itemId);
		when(w.getItemQuantity()).thenReturn(quantity);
		when(w.getText()).thenReturn("");
		children.add(w);
		return w;
	}

	private Widget text(int y, String text)
	{
		final Widget w = mock(Widget.class);
		when(w.getRelativeY()).thenReturn(y);
		when(w.getItemId()).thenReturn(-1);
		when(w.getText()).thenReturn(text);
		children.add(w);
		return w;
	}

	private Widget list()
	{
		final Widget list = mock(Widget.class);
		when(list.getDynamicChildren()).thenReturn(children.toArray(new Widget[0]));
		return list;
	}

	private static String name(int id)
	{
		return id == 4151 ? "Abyssal whip" : "Item " + id;
	}

	/**
	 * A row keeps its own sprite when another row's has come loose.
	 *
	 * <p>A row whose texts are all hidden leaves its sprite with no line of
	 * its own, and the nearest line it can find is the next row's. That row's
	 * own sprite is nearer still, and is the one it must keep -- taking the
	 * loose one instead reports a trade against an item the player never
	 * touched, which is worse than the row being skipped.
	 */
	@Test
	public void aRowKeepsItsOwnSpriteWhenAnotherHasComeLoose()
	{
		// Whichever order the two sprites arrive in: the widget list is the
		// client's, and which of them comes first is not something to rely on.
		for (boolean looseFirst : new boolean[]{true, false})
		{
			children.clear();
			if (looseFirst)
			{
				// This row's texts are all hidden, so there is no line at 0 for
				// its sprite to belong to.
				item(10, 5280, 1);
			}
			// And this row is whole, sprite and all.
			text(40, "Bought:");
			text(40, "Toadflax seedx 8");
			text(40, "8,760 coins= 1,095 each");
			item(50, 5297, 1);
			if (!looseFirst)
			{
				item(10, 5280, 1);
			}

			final List<FlippingRsApi.HistoryRow> rows =
				GeHistoryReader.read(list(), GeHistoryReaderTest::name);

			final String order = looseFirst ? "loose sprite first" : "loose sprite last";
			assertEquals(order, 1, rows.size());
			assertEquals(order + ": the row's own sprite, not the loose one",
				5297, rows.get(0).itemId);
			assertEquals(order, 8L, rows.get(0).quantity);
		}
	}

	/**
	 * The screen as a live client actually lays it out: the icon on its own
	 * line, then "Sold:", the name with "x N" glued on, and a price with the
	 * tax breakdown. The gp sent is the figure before tax.
	 */
	@Test
	public void theRealLayoutIsReadWithTheGrossBeforeTax()
	{
		item(10, 5280, 1);
		text(0, "Sold:");
		text(0, "Irit seedx 6");
		text(0, "438 coins(444 - 6)= 73 each");
		item(50, 5297, 1);
		text(40, "Bought:");
		text(40, "Toadflax seedx 8");
		text(40, "8,760 coins= 1,095 each");

		final List<FlippingRsApi.HistoryRow> rows = GeHistoryReader.read(list(), GeHistoryReaderTest::name);

		assertEquals(2, rows.size());
		assertEquals(5280, rows.get(0).itemId);
		assertEquals("sell", rows.get(0).side);
		assertEquals(6L, rows.get(0).quantity);
		assertEquals("before tax: 444, not the 438 collected", 444L, rows.get(0).grossValue);
		assertEquals(5297, rows.get(1).itemId);
		assertEquals("buy", rows.get(1).side);
		assertEquals(8L, rows.get(1).quantity);
		assertEquals(8_760L, rows.get(1).grossValue);
	}

	/**
	 * The second thing a live client showed: an icon listed after its texts,
	 * and a single item with no count on its name at all. Neither order nor
	 * the count may be assumed.
	 */
	@Test
	public void iconsMayFollowTheirTextsAndASingleItemHasNoCount()
	{
		text(0, "Sold:");
		text(0, "Ruby bolts (e)");
		item(8, 9242, 1);
		text(0, "1,500 coins(1,530 - 30)= 1,530 each");
		item(48, 5296, 1);
		text(40, "Sold:");
		text(40, "Toadflax seedx 8");
		text(40, "8,760 coins(8,936 - 176)= 1,095 each");

		final List<FlippingRsApi.HistoryRow> rows = GeHistoryReader.read(list(), GeHistoryReaderTest::name);

		assertEquals(2, rows.size());
		assertEquals(9242, rows.get(0).itemId);
		assertEquals(1L, rows.get(0).quantity);
		assertEquals(1_530L, rows.get(0).grossValue);
		assertEquals(5296, rows.get(1).itemId);
		assertEquals(8L, rows.get(1).quantity);
		assertEquals(8_936L, rows.get(1).grossValue);
	}

	/** An icon with no line near it belongs to nothing, and a line with no icon has no item. */
	@Test
	public void anIconFarFromEveryLineIsNotForcedOntoOne()
	{
		item(200, 4151, 10);
		text(0, "Bought:");
		text(0, "Abyssal whipx 10");
		text(0, "15,000,000 coins= 1,500,000 each");

		assertTrue(GeHistoryReader.read(list(), GeHistoryReaderTest::name).isEmpty());
	}

	/** With no "x N" and no stack, the quantity is the total over the per-item price. */
	@Test
	public void theQuantityFallsBackToTheTotalOverThePerItemPrice()
	{
		item(0, 4151, 0);
		text(0, "Bought:");
		text(0, "Abyssal whip");
		text(0, "4,500,000 coins= 1,500,000 each");

		final List<FlippingRsApi.HistoryRow> rows = GeHistoryReader.read(list(), GeHistoryReaderTest::name);

		assertEquals(3L, rows.get(0).quantity);
	}

	@Test
	public void rowsAreReadTopToBottomWithAllFourFacts()
	{
		item(0, 4151, 10);
		text(0, "Bought");
		text(0, "<col=ff981f>Abyssal whip</col>");
		text(0, "15,000,000 coins");
		item(40, 4151, 3);
		text(40, "Sold");
		text(40, "4,560,000 coins");

		final List<FlippingRsApi.HistoryRow> rows = GeHistoryReader.read(list(), GeHistoryReaderTest::name);

		assertEquals(2, rows.size());
		assertEquals(0, rows.get(0).position);
		assertEquals(4151, rows.get(0).itemId);
		assertEquals("Abyssal whip", rows.get(0).itemName);
		assertEquals("buy", rows.get(0).side);
		assertEquals(10L, rows.get(0).quantity);
		assertEquals(15_000_000L, rows.get(0).grossValue);
		assertEquals(1, rows.get(1).position);
		assertEquals("sell", rows.get(1).side);
		assertEquals(3L, rows.get(1).quantity);
		assertEquals(4_560_000L, rows.get(1).grossValue);
	}

	/** A quantity written in the text rather than on the icon still counts. */
	@Test
	public void aQuantityInTheTextIsUsedWhenTheIconHasNone()
	{
		item(0, 4151, 1);
		text(0, "Bought x 25");
		text(0, "37,500,000 coins");

		final List<FlippingRsApi.HistoryRow> rows = GeHistoryReader.read(list(), GeHistoryReaderTest::name);

		assertEquals(25L, rows.get(0).quantity);
	}

	/** Without a "coins" label the price is the largest number on the line. */
	@Test
	public void thePriceFallsBackToTheLargestNumberOnTheLine()
	{
		item(0, 4151, 10);
		text(0, "Sold 10");
		text(0, "15,000,000");

		final List<FlippingRsApi.HistoryRow> rows = GeHistoryReader.read(list(), GeHistoryReaderTest::name);

		assertEquals(15_000_000L, rows.get(0).grossValue);
	}

	/**
	 * A row whose price is missing must be skipped, not have its item count
	 * read as one. The largest-number fallback is for a layout that writes the
	 * price without the word "coins"; a layout that puts the price somewhere
	 * else entirely leaves the count as the only number on the line, and
	 * "Abyssal whipx 10" must not become ten whips for ten coins -- all four
	 * facts present, every one of them read, and the trade wrong.
	 */
	@Test
	public void aRowWithNoPriceIsNotGivenItsQuantityAsOne()
	{
		item(0, 4151, 10);
		text(0, "Bought");
		text(0, "Abyssal whipx 10");

		assertTrue("a row with no price is not a trade",
			GeHistoryReader.read(list(), GeHistoryReaderTest::name).isEmpty());
	}

	/**
	 * No count on the name, no stack on the icon and no per-item price: one
	 * item is the only reading left, and the row is a real trade that must not
	 * be dropped for want of a number the screen never wrote.
	 */
	@Test
	public void aRowWithNoCountAnywhereIsOneItem()
	{
		item(0, 4151, 1);
		text(0, "Bought");
		text(0, "Abyssal whip");
		text(0, "1,500,000 coins");

		final List<FlippingRsApi.HistoryRow> rows = GeHistoryReader.read(list(), GeHistoryReaderTest::name);

		assertEquals(1, rows.size());
		assertEquals(1L, rows.get(0).quantity);
		assertEquals(1_500_000L, rows.get(0).grossValue);
	}

	/**
	 * A row scrolled out of view is still in the widget list, and reads
	 * perfectly well. Reading it would send the site a trade the screen was
	 * not showing.
	 */
	@Test
	public void aRowScrolledOutOfViewIsNotRead()
	{
		hidden(item(0, 4151, 10));
		hidden(text(0, "Bought"));
		hidden(text(0, "15,000,000 coins"));

		assertTrue("a row nobody can see is not on the screen",
			GeHistoryReader.read(list(), GeHistoryReaderTest::name).isEmpty());
	}

	private static Widget hidden(Widget w)
	{
		when(w.isSelfHidden()).thenReturn(true);
		return w;
	}

	/**
	 * Without a side there is no trade, whatever else the row says. The case
	 * below skips its bad row for want of a price; this one has a price and a
	 * count and still cannot say whether the gp came in or went out.
	 */
	@Test
	public void aRowThatDoesNotSayBoughtOrSoldIsSkipped()
	{
		item(0, 4151, 10);
		text(0, "Abyssal whipx 10");
		text(0, "15,000,000 coins");

		assertTrue("a row that cannot say which way the gp went is not a trade",
			GeHistoryReader.read(list(), GeHistoryReaderTest::name).isEmpty());
	}

	/** A row missing a side or a price is skipped, not sent half-read. */
	@Test
	public void anUnreadableRowIsSkippedNotGuessed()
	{
		item(0, 4151, 10);
		text(0, "Abyssal whip");
		item(40, 4151, 2);
		text(40, "Bought");
		text(40, "3,000,000 coins");

		final List<FlippingRsApi.HistoryRow> rows = GeHistoryReader.read(list(), GeHistoryReaderTest::name);

		assertEquals(1, rows.size());
		assertEquals("the one good row is first, since the bad one was not numbered", 0, rows.get(0).position);
		assertEquals(2L, rows.get(0).quantity);
	}

	@Test
	public void anEmptyOrMissingListIsNoRows()
	{
		assertTrue(GeHistoryReader.read(null, GeHistoryReaderTest::name).isEmpty());
		assertTrue(GeHistoryReader.read(list(), GeHistoryReaderTest::name).isEmpty());
	}

	/**
	 * The plugin warns when a screen with items on it yields no rows, since
	 * that means the layout has changed under the reader. An empty history
	 * must not trip that, or every new player would see the warning.
	 */
	@Test
	public void aScreenWithIconsShowsItemsAndAnEmptyOneDoesNot()
	{
		assertFalse(GeHistoryReader.showsItems(null));
		assertFalse("no children at all", GeHistoryReader.showsItems(list()));

		text(0, "You have no Grand Exchange history.");
		assertFalse("text but no item icon", GeHistoryReader.showsItems(list()));

		item(10, 4151, 1);
		assertTrue(GeHistoryReader.showsItems(list()));
	}
	/**
	 * Any screenful of well-formed rows is read back exactly: the same items,
	 * the same sides, the same counts, the same gp before tax, in the same
	 * order.
	 *
	 * <p>The cases above each pin one decision. This one varies everything the
	 * screen is allowed to vary -- whether the icon comes before or after its
	 * texts and how far below them it sits, whether the count is written on the
	 * name or left to the icon's stack, whether the side has a colon, whether
	 * the price carries a tax breakdown, whether the numbers are written with
	 * separators, and whether the name is wrapped in colour tags -- because a
	 * reader built out of regular expressions goes wrong on combinations rather
	 * than on cases, and a wrong row here is a wrong trade in a journal.
	 */
	@Test
	public void anyWellFormedScreenIsReadBackExactly()
	{
		final java.util.Random random = new java.util.Random(20260903L);
		for (int run = 0; run < 2000; run++)
		{
			children.clear();
			final List<long[]> expected = new ArrayList<>();

			final int rows = 1 + random.nextInt(8);
			for (int row = 0; row < rows; row++)
			{
				// Rows sit forty apart, and an icon belongs to the line it is
				// nearest to, so it stays well inside half that.
				final int y = row * 40;
				final int itemId = 4151 + random.nextInt(50);
				final boolean buy = random.nextBoolean();
				final long each = 1 + random.nextInt(2_000_000);
				// One of the three ways the screen can say how many: written on
				// the name, left to the icon's stack, or only implied by the
				// total over the per-item price. Exactly one per row, so a row
				// cannot be read right by a path this one was not testing.
				final int says = random.nextInt(3);
				final long quantity = says == 1 ? 2 + random.nextInt(999) : 1 + random.nextInt(1000);
				final long gross = quantity * each;
				final long tax = buy ? 0 : Math.min(gross - 1, random.nextInt(1000));

				// "Toadflax" ends in an x, which is exactly what the count is
				// written with, so it belongs in here.
				final String itemName = ITEM_NAMES[random.nextInt(ITEM_NAMES.length)];

				// Rows sit forty apart, and an icon belongs to the line it is
				// nearest to, so it stays well inside half that.
				final Widget icon = item(y + random.nextInt(16), itemId,
					says == 1 ? (int) quantity : 1);
				final boolean iconAfterItsTexts = random.nextBoolean();
				if (iconAfterItsTexts)
				{
					children.remove(icon);
				}

				text(y, (buy ? "Bought" : "Sold") + (random.nextBoolean() ? ":" : ""));
				final String named = itemName + (says == 0 ? "x " + group(quantity, random) : "");
				text(y, random.nextBoolean() ? "<col=ff981f>" + named + "</col>" : named);
				final String perItem = says == 2 ? "= " + group(each, random) + " each" : "";
				text(y, buy
					? group(gross, random) + " coins" + perItem
					: group(gross - tax, random) + " coins(" + group(gross, random) + " - "
						+ group(tax, random) + ")" + perItem);

				if (iconAfterItsTexts)
				{
					children.add(icon);
				}
				expected.add(new long[]{itemId, buy ? 1 : 0, quantity, gross});
			}

			final List<FlippingRsApi.HistoryRow> read =
				GeHistoryReader.read(list(), GeHistoryReaderTest::name);

			assertEquals("run " + run + ": every row must be read", expected.size(), read.size());
			for (int i = 0; i < expected.size(); i++)
			{
				final long[] want = expected.get(i);
				final FlippingRsApi.HistoryRow got = read.get(i);
				final String where = "run " + run + ", row " + i;
				assertEquals(where + ": item", want[0], got.itemId);
				assertEquals(where + ": side", want[1] == 1 ? "buy" : "sell", got.side);
				assertEquals(where + ": quantity", want[2], got.quantity);
				assertEquals(where + ": gp before tax", want[3], got.grossValue);
				assertEquals(where + ": position", i, got.position);
			}
		}
	}

	private static final String[] ITEM_NAMES = {
		"Abyssal whip", "Toadflax seed", "Ruby bolts (e)", "Zulrah's scales", "Xerician fabric",
	};

	/** A number as the screen might write it, with or without separators. */
	private static String group(long n, java.util.Random random)
	{
		return random.nextBoolean() ? String.format(java.util.Locale.ROOT, "%,d", n) : Long.toString(n);
	}
}
