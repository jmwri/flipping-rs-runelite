package com.flippingrs;

/** The journal's performance over the last week, as the server works it out. */
public class Analytics
{
	int totalFlips;
	int completedFlips;
	int openFlips;
	long realisedProfit;
	long totalTaxPaid;
	/** A fraction: 0.75 is 75%. */
	double winRate;
	/** A fraction. */
	double averageRoi;
	long gpPerHour;

	public int getCompletedFlips()
	{
		return completedFlips;
	}

	public int getOpenFlips()
	{
		return openFlips;
	}

	public long getRealisedProfit()
	{
		return realisedProfit;
	}

	public double getWinRate()
	{
		return winRate;
	}

	public double getAverageRoi()
	{
		return averageRoi;
	}

	public long getGpPerHour()
	{
		return gpPerHour;
	}
}
