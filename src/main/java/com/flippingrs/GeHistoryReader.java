package com.flippingrs;

import java.util.ArrayList;
import java.util.List;
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
 * not an API: each row is an item icon followed by three text widgets --
 * "Sold:", "Irit seedx 6", "438 coins(444 - 6)= 73 each" -- with no ids and
 * no times, and the icon does not even share the texts' vertical position.
 * So rows are taken in sequence, an icon starting each one, and read
 * defensively: the side is whichever of "Bought" or "Sold" appears, the
 * quantity is the "x N" at the end of the name, and the gp is the figure
 * before tax where the breakdown is shown, else the figure next to "coins".
 * A row that does not yield all four is skipped and logged rather than sent
 * half-read, because a half-read trade is worse than a missing one.
 *
 * <p>What the server does with the rows is its business: it knows which
 * completed offers it already has, and the plugin does not.
 */
@Slf4j
class GeHistoryReader
{
	/** "438 coins(444 - 6)": the net, then the gross and the tax it was cut by. */
	private static final Pattern BREAKDOWN = Pattern.compile(
		"([\\d,]+)\\s*coins?\\s*\\(\\s*([\\d,]+)\\s*-\\s*([\\d,]+)\\s*\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern COINS = Pattern.compile("([\\d,]+)\\s*(?:coins?|gp)", Pattern.CASE_INSENSITIVE);
	/** "= 73 each" */
	private static final Pattern EACH = Pattern.compile("=\\s*([\\d,]+)\\s*each", Pattern.CASE_INSENSITIVE);
	/** "Irit seedx 6", "Bought x 25" */
	private static final Pattern QUANTITY = Pattern.compile("x\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);
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

		// Group in sequence: the icon comes first and the row's texts follow
		// it. Grouping by vertical position does not work here, because the
		// icon sits at a different y from the texts beside it.
		final List<List<Widget>> lines = new ArrayList<>();
		List<Widget> current = null;
		for (Widget child : children)
		{
			if (child == null || child.isSelfHidden())
			{
				continue;
			}
			final boolean icon = child.getItemId() > 0;
			final boolean text = child.getText() != null && !child.getText().isEmpty();
			if (!icon && !text)
			{
				continue;
			}
			if (icon || current == null)
			{
				current = new ArrayList<>();
				lines.add(current);
			}
			current.add(child);
		}

		int position = 0;
		for (List<Widget> line : lines)
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
		long iconQuantity = 0;
		long textQuantity = 0;
		String side = null;
		long gross = 0;
		long each = 0;
		final List<String> texts = new ArrayList<>();

		for (Widget w : line)
		{
			if (w.getItemId() > 0 && itemId == 0)
			{
				itemId = w.getItemId();
				iconQuantity = w.getItemQuantity();
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
			// The gp that moved is the figure before tax. A sale shows
			// "438 coins(444 - 6)": 444 changed hands and 6 of it was tax,
			// which the server works out for itself from the sale price. A
			// buy has no tax and shows the one figure.
			final Matcher breakdown = BREAKDOWN.matcher(plain);
			if (gross == 0 && breakdown.find())
			{
				gross = digits(breakdown.group(2));
			}
			final Matcher coins = COINS.matcher(plain);
			if (gross == 0 && coins.find())
			{
				gross = digits(coins.group(1));
			}
			final Matcher per = EACH.matcher(plain);
			if (each == 0 && per.find())
			{
				each = digits(per.group(1));
			}
			final Matcher qty = QUANTITY.matcher(plain);
			if (textQuantity == 0 && qty.find())
			{
				textQuantity = digits(qty.group(1));
			}
		}

		if (gross == 0)
		{
			// No "coins" label. Fall back to the largest number on the line;
			// a price is always the biggest figure.
			for (String text : texts)
			{
				final Matcher n = NUMBER.matcher(text);
				while (n.find())
				{
					gross = Math.max(gross, digits(n.group()));
				}
			}
		}

		// The "x N" on the name is the offer's quantity; the icon's stack is a
		// fallback, and failing both, the total over the per-item price.
		long quantity = textQuantity;
		if (quantity <= 0)
		{
			quantity = iconQuantity;
		}
		if (quantity <= 0 && each > 0 && gross > 0)
		{
			quantity = Math.round((double) gross / each);
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
