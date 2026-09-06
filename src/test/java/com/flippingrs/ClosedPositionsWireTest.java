package com.flippingrs;

import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The closed-positions part, parsed from what flippingrs.com actually sends.
 *
 * <p>Every other test here builds the objects directly, which proves the panel
 * draws what it is given and proves nothing at all about whether the server
 * gives it that. A field renamed on either side would leave those tests green
 * and the tab empty -- the same shape of failure as a plugin whose tests
 * encoded its own wrong assumption about how examine works, and which passed
 * for exactly as long as nobody ran it.
 *
 * <p>So the JSON below is copied from a live reply rather than composed here.
 */
public class ClosedPositionsWireTest
{
	/** A reply from the server, verbatim. */
	private static final String REPLY = "{"
		+ "\"closedPositions\": {"
		+ "  \"positions\": [{"
		+ "    \"id\": \"lZxYhBMnKbenJaFddWcjMQ\", \"itemId\": 4151, \"itemName\": \"Abyssal whip\","
		+ "    \"buyPrice\": 1480000, \"buyQty\": 10, \"sellPrice\": 1520000, \"sellQty\": 10,"
		+ "    \"taxPaid\": 304000, \"netProfit\": 96000, \"roi\": 0.006486486486486486,"
		+ "    \"hoursHeld\": 167, \"timesKnown\": true"
		+ "  }],"
		+ "  \"summary\": { \"closedPositions\": 3, \"realisedProfit\": 73800, \"taxPaid\": 308200 }"
		+ "}}";

	private static PanelData parse(String json)
	{
		return new Gson().fromJson(json, PanelData.class);
	}

	/** Every field the card reads, off the wire. */
	@Test
	public void aClosedLotArrivesWithEverythingTheCardShows()
	{
		final ClosedPositions closed = parse(REPLY).getClosedPositions();
		assertEquals(1, closed.getPositions().size());
		final ClosedPosition whip = closed.getPositions().get(0);

		assertEquals("lZxYhBMnKbenJaFddWcjMQ", whip.getId());
		assertEquals(4151, whip.getItemId());
		assertEquals("Abyssal whip", whip.getItemName());
		assertEquals(1_480_000, whip.getBuyPrice());
		assertEquals(10, whip.getBuyQty());
		assertEquals(1_520_000, whip.getSellPrice());
		assertEquals(10, whip.getSellQty());
		assertEquals(304_000, whip.getTaxPaid());
		assertEquals("after tax, which is most of the gross here",
			96_000, whip.getNetProfit());
		assertEquals(0.006486486486486486, whip.getRoi(), 1e-12);
		assertEquals("hours, as a number that may or may not have a fraction",
			167d, whip.getHoursHeld(), 1e-9);
		assertTrue(whip.isTimesKnown());

		assertEquals("Bought 1,480,000 · Sold 1,520,000", FlippingRsPanel.closedPrices(whip));
		assertEquals("10 sold · held 6d 23h", FlippingRsPanel.closedHeld(whip));
		assertEquals("P&L +96,000 (0.6%) · tax 304,000", FlippingRsPanel.closedResult(whip));
	}

	/** The totals are of the rows sent, and they reconcile against them. */
	@Test
	public void theSummaryDescribesTheRowsUnderIt()
	{
		final ClosedPositions.Summary totals = parse(REPLY).getClosedPositions().getSummary();

		assertEquals(3, totals.getClosedPositions());
		assertEquals(73_800, totals.getRealisedProfit());
		assertEquals(308_200, totals.getTaxPaid());
	}

	/**
	 * A loss keeps its sign, through the row and through the total.
	 *
	 * <p>The card is red or green on this one number, and an absolute value
	 * would paint a losing flip as a winning one.
	 */
	@Test
	public void aLossStaysNegative()
	{
		final String json = "{\"closedPositions\": {\"positions\": [{"
			+ "\"itemId\": 5296, \"itemName\": \"Ranarr seed\","
			+ "\"buyPrice\": 40000, \"buyQty\": 5, \"sellPrice\": 36000, \"sellQty\": 5,"
			+ "\"taxPaid\": 3600, \"netProfit\": -24000, \"roi\": -0.109,"
			+ "\"hoursHeld\": 3, \"timesKnown\": true}],"
			+ "\"summary\": {\"closedPositions\": 1, \"realisedProfit\": -24000, \"taxPaid\": 3600}}}";

		final ClosedPositions closed = parse(json).getClosedPositions();

		assertEquals(-24_000, closed.getPositions().get(0).getNetProfit());
		assertEquals(-24_000, closed.getSummary().getRealisedProfit());
		assertEquals("P&L -24,000 (-10.9%) · tax 3,600",
			FlippingRsPanel.closedResult(closed.getPositions().get(0)));
	}

	/**
	 * A flip the plugin found already done says so, rather than showing a hold
	 * nobody measured.
	 *
	 * <p>The server floors the duration at zero for these, because both legs
	 * adopted in one batch can land a hair out of order and subtract to a
	 * negative. That makes the field safe, not meaningful -- the card is what
	 * has to be honest about it, and this list is ordered by when a lot sold,
	 * so a recovered lot adopted a moment ago is likely the first card there
	 * is.
	 */
	@Test
	public void aRecoveredFlipSaysWhyItHasNoHold()
	{
		final String json = "{\"closedPositions\": {\"positions\": [{"
			+ "\"itemId\": 561, \"itemName\": \"Nature rune\","
			+ "\"buyPrice\": 100, \"buyQty\": 100, \"sellPrice\": 120, \"sellQty\": 100,"
			+ "\"taxPaid\": 0, \"netProfit\": 1800, \"roi\": 0.18,"
			+ "\"hoursHeld\": 0, \"timesKnown\": false}],"
			+ "\"summary\": {\"closedPositions\": 1, \"realisedProfit\": 1800, \"taxPaid\": 0}}}";

		final ClosedPosition runes = parse(json).getClosedPositions().getPositions().get(0);

		assertFalse(runes.isTimesKnown());
		assertEquals("100 sold · recovered, time unknown", FlippingRsPanel.closedHeld(runes));
		assertEquals("and the profit is still the real one",
			"P&L +1,800 (18.0%)", FlippingRsPanel.closedResult(runes));
	}

	/**
	 * A server with nothing to say leaves the part out, and that is not the
	 * same as a server saying there are none.
	 *
	 * <p>The whole closed section hangs off this: absent hides it, empty says
	 * you have closed nothing. Getting them the same way round would tell
	 * somebody with a year of finished flips that they have never finished
	 * one.
	 */
	@Test
	public void anOlderServerSaysNothingRatherThanNone()
	{
		assertNull("an older flippingrs.com, which has no such part",
			parse("{\"week\": null, \"positions\": null}").getClosedPositions());
		assertNull(parse("{\"closedPositions\": null}").getClosedPositions());

		final ClosedPositions none = parse(
			"{\"closedPositions\": {\"positions\": [], \"summary\": {\"closedPositions\": 0}}}")
			.getClosedPositions();
		assertTrue("but an empty list is an answer", none.getPositions().isEmpty());
		assertEquals("No closed positions yet.", FlippingRsPanel.closedSummaryLine(none));
	}

	/**
	 * A part that arrives without its pieces still answers rather than
	 * throwing, on the same terms as every other wire shape here.
	 */
	@Test
	public void aPartWithoutItsPiecesIsStillAnswerable()
	{
		final ClosedPositions bare = parse("{\"closedPositions\": {}}").getClosedPositions();

		final List<ClosedPosition> positions = bare.getPositions();
		assertTrue(positions.isEmpty());
		assertEquals(0, bare.getSummary().getClosedPositions());
		assertEquals("No closed positions yet.", FlippingRsPanel.closedSummaryLine(bare));
	}
}
