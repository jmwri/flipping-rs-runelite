package com.flippingrs;

import java.lang.reflect.Field;
import net.runelite.api.Client;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Mockito.mock;

/**
 * Working out which row each offer box is on.
 *
 * <p>This is the arithmetic behind the one thing the plugin moves rather than
 * adds to, and getting it wrong is not subtle: a box pushed down by the wrong
 * number of rows lands on the one under it. The exchange is laid out
 * differently in fixed and resizable mode, so the rows are read off where the
 * client actually put the boxes rather than assumed to be two of four.
 */
public class GeSlotLayoutTest
{
	private static GeSlotLayout layoutWith(int... tops) throws Exception
	{
		final GeSlotLayout layout =
			new GeSlotLayout(mock(Client.class), mock(FlippingRsConfig.class));
		final Field field = GeSlotLayout.class.getDeclaredField("baseY");
		field.setAccessible(true);
		field.set(layout, tops);
		return layout;
	}

	/** Two rows of four, which is how the exchange lays out in fixed mode. */
	@Test
	public void twoRowsOfFour() throws Exception
	{
		final GeSlotLayout layout = layoutWith(10, 10, 10, 10, 90, 90, 90, 90);

		assertArrayEquals(new int[]{0, 0, 0, 0, 1, 1, 1, 1}, layout.rowsOf());
	}

	/** Four rows of two, which is how it lays out when the panel is narrow. */
	@Test
	public void fourRowsOfTwo() throws Exception
	{
		final GeSlotLayout layout = layoutWith(10, 10, 90, 90, 170, 170, 250, 250);

		assertArrayEquals(new int[]{0, 0, 1, 1, 2, 2, 3, 3}, layout.rowsOf());
	}

	/**
	 * A box the client has not laid out yet is left where it is rather than
	 * being counted as the top row and dragging the real top row down with it.
	 */
	@Test
	public void boxesNotSeenYetAreNotARow() throws Exception
	{
		final GeSlotLayout layout = layoutWith(-1, -1, 10, 10, 90, 90, -1, -1);

		final int[] rows = layout.rowsOf();
		assertArrayEquals("the two real rows are still the first two",
			new int[]{0, 0, 0, 0, 1, 1, 0, 0}, rows);
	}

	/** One row is one row, however many boxes are on it. */
	@Test
	public void asingleRow() throws Exception
	{
		final GeSlotLayout layout = layoutWith(10, 10, 10, 10, 10, 10, 10, 10);

		assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0, 0, 0}, layout.rowsOf());
	}
}
