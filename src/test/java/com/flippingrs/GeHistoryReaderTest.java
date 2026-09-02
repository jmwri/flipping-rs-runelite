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
