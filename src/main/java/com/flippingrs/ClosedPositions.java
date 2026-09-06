package com.flippingrs;

import java.util.List;

/**
 * The lots that are finished, and what they came to.
 *
 * <p>However many the server chose to send. This is a sidebar, so it is the
 * most recent handful rather than the whole journal -- the site's own Journal
 * page is where somebody goes to read all of it, and paging a full history
 * through a panel this size would spend the rate limit on scrolling.
 */
public class ClosedPositions
{
	List<ClosedPosition> positions;
	Summary summary;

	/**
	 * The totals for what was sent, not for all time.
	 *
	 * <p>Separate from the week's figures on the Analytics tab, which are a
	 * seven-day window whatever was traded in it. These describe exactly the
	 * cards underneath them, which is the only reading that cannot mislead
	 * somebody who counts them.
	 */
	public static class Summary
	{
		int closedPositions;
		long realisedProfit;
		long taxPaid;

		public int getClosedPositions()
		{
			return closedPositions;
		}

		public long getRealisedProfit()
		{
			return realisedProfit;
		}

		public long getTaxPaid()
		{
			return taxPaid;
		}
	}

	/** Never null. */
	public List<ClosedPosition> getPositions()
	{
		return Wire.withoutNulls(positions);
	}

	/** Never null. */
	public Summary getSummary()
	{
		return summary == null ? new Summary() : summary;
	}
}
