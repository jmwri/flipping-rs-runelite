package com.flippingrs;

import java.util.Collections;
import java.util.List;

/**
 * One of the owner's watchlists. The side panel shows one of these as the
 * player's plan, and keeps no copy: it is read from here, changed here and
 * read back.
 */
public class Watchlist
{
	String id;
	String name;
	List<Integer> itemIds;

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name == null ? "" : name;
	}

	/** Never null. */
	public List<Integer> getItemIds()
	{
		return itemIds == null ? Collections.emptyList() : itemIds;
	}

	@Override
	public String toString()
	{
		// This is what the combo box renders.
		return Wire.plain(getName().isEmpty() ? id : getName());
	}
}
