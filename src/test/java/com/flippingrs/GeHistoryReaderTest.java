package com.flippingrs;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
}
