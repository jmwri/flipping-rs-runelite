package com.flippingrs;

import com.google.gson.JsonElement;
import java.util.List;

/** What the server did with a batch, so the panel can say something true. */
public class IngestResult
{
	int accepted;
	int duplicate;
	int rejected;
	int flipsOpened;
	int flipsClosed;
	long unmatchedSellQty;
	/**
	 * Held as raw JSON rather than as strings. If the server ever sends
	 * structured problems, a List&lt;String&gt; here would fail to parse a
	 * response for a batch that was in fact accepted, and the batch would
	 * be re-sent every tick with the queue wedged behind it.
	 */
	List<JsonElement> problems;

	public int getFlipsOpened()
	{
		return flipsOpened;
	}

	public int getFlipsClosed()
	{
		return flipsClosed;
	}

	public long getUnmatchedSellQty()
	{
		return unmatchedSellQty;
	}

	/**
	 * Fills the server recorded nothing for. They are gone: the batch is
	 * confirmed and dropped from the queue either way, so this is the only
	 * chance anyone has to learn a trade did not make it.
	 */
	public int getRejected()
	{
		return rejected;
	}

	/** Why, one line per refused row. Never null. */
	public List<String> getProblems()
	{
		return Wire.problemsOf(problems);
	}

	/** Rows the server accounted for, one way or another. */
	int acknowledged()
	{
		return accepted + duplicate + rejected;
	}
}
