package com.flippingrs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.Widget;

/**
 * Reads the Grand Exchange history screen off its widgets.
 *
 * <p>The history is the one place the game shows offers that completed while
 * the plugin was not watching, so it is worth reading, but it is a screen and
 * not an API: rows are laid out as an item icon and a few text widgets on the
 * same line, with no ids and no times. This reads them defensively: rows are
 * grouped by their vertical position, the side is whichever of "Bought" or
 * "Sold" appears, the quantity is the icon's stack size, and the gp is the
 * number next to "coins". A row that does not yield all four is skipped and
 * logged rather than sent half-read, because a half-read trade is worse than
 * a missing one.
 *
 * <p>What the server does with the rows is its business: it knows which
 * completed offers it already has, and the plugin does not.
 */
@Slf4j
class GeHistoryReader
{
	private static final Pattern COINS = Pattern.compile("([\\d,]+)\\s*(?:coins?|gp)", Pattern.CASE_INSENSITIVE);
	private static final Pattern QUANTITY = Pattern.compile("(?:x\\s*([\\d,]+))|(?:([\\d,]+)\\s*x)", Pattern.CASE_INSENSITIVE);
	private static final Pattern NUMBER = Pattern.compile("[\\d,]{2,}");

	/**
	 * Reads every parseable row of the list widget, top to bottom.
	 *
	 * @param list  the history list, or null if the screen is not open
	 * @param names resolves an item id to its name; client thread
	 */
	static List<FlippingRsApi.HistoryRow> read(@Nullable Widget list, IntFunction<String> names)
	{
		final List<FlippingRsApi.HistoryRow> out = new ArrayList<>();
		if (list == null)
		{
			return out;
		}
		final Widget[] children = list.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return out;
		}

		// Group by line. Widgets on one row share a y within a few pixels;
		// grouping on the exact value is enough because the game lays each
		// row's widgets out from the same origin.
		final Map<Integer, List<Widget>> lines = new LinkedHashMap<>();
		for (Widget child : children)
		{
			if (child == null || child.isSelfHidden())
			{
				continue;
			}
			lines.computeIfAbsent(child.getRelativeY(), y -> new ArrayList<>()).add(child);
		}

		int position = 0;
		for (List<Widget> line : lines.values())
		{
			final FlippingRsApi.HistoryRow row = parse(line, names);
			if (row == null)
			{
				continue;
			}
			row.position = position++;
			out.add(row);
		}
		return out;
	}

	@Nullable
	private static FlippingRsApi.HistoryRow parse(List<Widget> line, IntFunction<String> names)
	{
		int itemId = 0;
		long quantity = 0;
		String side = null;
		long gross = 0;
		final List<String> texts = new ArrayList<>();

		for (Widget w : line)
		{
			if (w.getItemId() > 0 && itemId == 0)
			{
				itemId = w.getItemId();
				quantity = Math.max(1, w.getItemQuantity());
			}
			final String text = w.getText();
			if (text == null || text.isEmpty())
			{
				continue;
			}
			final String plain = stripTags(text);
			texts.add(plain);
			final String lower = plain.toLowerCase();
			if (side == null && lower.contains("bought"))
			{
				side = "buy";
			}
			else if (side == null && lower.contains("sold"))
			{
				side = "sell";
			}
			final Matcher coins = COINS.matcher(plain);
			if (gross == 0 && coins.find())
			{
				gross = digits(coins.group(1));
			}
			final Matcher qty = QUANTITY.matcher(plain);
			if (quantity <= 1 && qty.find())
			{
				quantity = digits(qty.group(1) != null ? qty.group(1) : qty.group(2));
			}
		}

		if (gross == 0)
		{
			// No "coins" label. Fall back to the largest number on the line
			// that is not the quantity; a price is always the biggest figure.
			for (String text : texts)
			{
				final Matcher n = NUMBER.matcher(text);
				while (n.find())
				{
					final long value = digits(n.group());
					if (value != quantity && value > gross)
					{
						gross = value;
					}
				}
			}
		}

		if (itemId <= 0 || side == null || quantity <= 0 || gross <= 0)
		{
			if (!texts.isEmpty() || itemId > 0)
			{
				log.debug("could not read a history row: item {} texts {}", itemId, texts);
			}
			return null;
		}

		final FlippingRsApi.HistoryRow row = new FlippingRsApi.HistoryRow();
		row.itemId = itemId;
		row.itemName = names.apply(itemId);
		row.side = side;
		row.quantity = quantity;
		row.grossValue = gross;
		return row;
	}

	private static long digits(String s)
	{
		final String clean = s.replaceAll("[^0-9]", "");
		if (clean.isEmpty())
		{
			return 0;
		}
		try
		{
			return Long.parseLong(clean);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private static String stripTags(String text)
	{
		return text.replaceAll("<[^>]*>", "").trim();
	}
}
