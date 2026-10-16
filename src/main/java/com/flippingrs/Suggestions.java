package com.flippingrs;

import java.util.ArrayList;
import java.util.List;

/** The reply to {@code GET /api/plugin/suggestions}. */
public class Suggestions
{
	/** Unix seconds the server worked these out at, or 0 if it did not say. */
	long updatedAt;
	long budget;
	boolean members;
	List<Suggestion> items;

	public long getUpdatedAt()
	{
		return updatedAt;
	}

	/** The rows, never null and without the null entries a malformed reply could hold. */
	public List<Suggestion> getItems()
	{
		final List<Suggestion> out = new ArrayList<>();
		if (items != null)
		{
			for (Suggestion item : items)
			{
				if (item != null)
				{
					out.add(item);
				}
			}
		}
		return out;
	}
}
