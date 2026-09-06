package com.flippingrs;

import java.util.List;

/** The open positions and their totals. */
public class Positions
{
	List<Position> positions;
	Summary summary;

	public static class Summary
	{
		int openPositions;
		long costBasis;
		long marketValue;
		long unrealisedPnl;
		boolean marketDataAvailable = true;
	}

	/** Never null. */
	public List<Position> getPositions()
	{
		return Wire.withoutNulls(positions);
	}

	/** Never null. */
	public Summary getSummary()
	{
		return summary == null ? new Summary() : summary;
	}
}
