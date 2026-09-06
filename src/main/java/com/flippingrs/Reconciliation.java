package com.flippingrs;

import com.google.gson.JsonElement;
import java.util.List;

/** What the server made of a snapshot: how much it already had and how much it took on. */
public class Reconciliation
{
	int reconciled;
	int recovered;
	int matched;
	int added;
	int ignored;
	/** Only present when a row was malformed; raw JSON for the same reason as IngestResult's. */
	List<JsonElement> problems;

	public int getRecovered()
	{
		return recovered;
	}

	public int getAdded()
	{
		return added;
	}

	/** Why rows were refused, one line each. Never null. */
	public List<String> getProblems()
	{
		return Wire.problemsOf(problems);
	}
}
