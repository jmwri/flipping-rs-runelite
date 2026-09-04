package com.flippingrs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * not an API. Each row is three text widgets on one line -- "Sold:",
 * "Irit seedx 6", "438 coins(444 - 6)= 73 each" -- and an item icon that sits
 * at a slightly different height and, in the widget list, sometimes before
 * its texts and sometimes after. So nothing here depends on order: the texts
 * are grouped by their exact line, and each icon is attached to the nearest
 * line by height. The side is whichever of "Bought" or "Sold" appears, the
 * quantity is the "x N" on the name, or one when there is none, and the gp is
 * the figure before tax where the breakdown is shown, else the figure next to
 * "coins". A row that does not yield all four is skipped and logged rather
 * than sent half-read, because a half-read trade is worse than a missing one.
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

	/** Jagex's colour and formatting tags, which are not part of the text. */
	private static final Pattern TAG = Pattern.compile("<[^>]*>");
	private static final Pattern NOT_DIGIT = Pattern.compile("[^0-9]");

	/**
	 * How far, in pixels, an icon may sit from the line it belongs to. Rows
	 * are a good deal taller than this, so the nearest line is the right one
	 * and an icon with no line that close belongs to nothing.
	 */
	private static final int ICON_REACH = 30;

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

		// Texts by their exact line, top to bottom; icons kept aside.
		final TreeMap<Integer, List<Widget>> lines = new TreeMap<>();
		final List<Widget> icons = new ArrayList<>();
		for (Widget child : children)
		{
			if (child == null || child.isSelfHidden())
			{
				continue;
			}
			if (child.getItemId() > 0)
			{
				icons.add(child);
			}
			else if (child.getText() != null && !child.getText().isEmpty())
			{
				lines.computeIfAbsent(child.getRelativeY(), y -> new ArrayList<>()).add(child);
			}
		}

		// Each icon joins the line nearest to it by height. The icon is not on
		// the texts' line, but it is far closer to its own row than to the
		// next.
		//
		// Two icons can still land on one line: a row whose texts are all
		// hidden has no line of its own, so its icon goes looking and settles
		// on the next row's. That row's own icon is nearer, and is the one to
		// keep -- taking whichever was seen first would report the row against
		// an item the player never touched, which is a good deal worse than
		// the loose row being skipped.
		final Map<Integer, Widget> iconByLine = new TreeMap<>();
		for (Widget icon : icons)
		{
			final Integer line = nearestLine(lines, icon.getRelativeY());
			if (line == null)
			{
				continue;
			}
			final Widget held = iconByLine.get(line);
			if (held == null
				|| Math.abs(line - icon.getRelativeY()) < Math.abs(line - held.getRelativeY()))
			{
				iconByLine.put(line, icon);
			}
		}

		int position = 0;
		for (Map.Entry<Integer, List<Widget>> line : lines.entrySet())
		{
			final FlippingRsApi.HistoryRow row = parse(iconByLine.get(line.getKey()), line.getValue(), names);
			if (row == null)
			{
				continue;
			}
			row.position = position++;
			out.add(row);
		}
		return out;
	}

	/**
	 * Whether the list has any item icons on it at all: the difference
	 * between an empty history and one the reader could not make sense of.
	 */
	static boolean showsItems(@Nullable Widget list)
	{
		final Widget[] children = list == null ? null : list.getDynamicChildren();
		if (children == null)
		{
			return false;
		}
		for (Widget child : children)
		{
			if (child != null && child.getItemId() > 0 && !child.isSelfHidden())
			{
				return true;
			}
		}
		return false;
	}

	@Nullable
	private static Integer nearestLine(TreeMap<Integer, List<Widget>> lines, int y)
	{
		final Integer below = lines.ceilingKey(y);
		final Integer above = lines.floorKey(y);
		Integer best = null;
		int distance = Integer.MAX_VALUE;
		for (Integer candidate : new Integer[]{above, below})
		{
			if (candidate != null && Math.abs(candidate - y) < distance)
			{
				best = candidate;
				distance = Math.abs(candidate - y);
			}
		}
		return distance <= ICON_REACH ? best : null;
	}

	@Nullable
	private static FlippingRsApi.HistoryRow parse(@Nullable Widget icon, List<Widget> line, IntFunction<String> names)
	{
		final int itemId = icon == null ? 0 : icon.getItemId();
		final long iconQuantity = icon == null ? 0 : icon.getItemQuantity();
		long textQuantity = 0;
		/** The text the "x N" was read off, if any. See the fallback below. */
		String countedOn = null;
		String side = null;
		long gross = 0;
		long each = 0;
		final List<String> texts = new ArrayList<>();

		for (Widget w : line)
		{
			final String plain = stripTags(w.getText());
			if (plain.isEmpty())
			{
				continue;
			}
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
			//
			// Each matcher is built only if its figure is still wanted. Four
			// per text widget, built and thrown away whether or not they were
			// consulted, is work the client thread does not need.
			if (gross == 0)
			{
				gross = firstNumber(BREAKDOWN, plain, 2);
			}
			if (gross == 0)
			{
				gross = firstNumber(COINS, plain, 1);
			}
			if (each == 0)
			{
				each = firstNumber(EACH, plain, 1);
			}
			if (textQuantity == 0)
			{
				final long counted = firstNumber(QUANTITY, plain, 1);
				if (counted > 0)
				{
					textQuantity = counted;
					countedOn = plain;
				}
			}
		}

		if (gross == 0)
		{
			// No "coins" label. Fall back to the largest number on the line;
			// a price is always the biggest figure.
			//
			// Except on the text the item count came off, whose numbers are
			// that count. A layout that put the name and the side together but
			// the price somewhere else would otherwise leave "Abyssal whipx 10"
			// as the only numbers on the line, and this would report a purchase
			// of ten whips for ten coins -- all four facts present, every one
			// of them read, and the trade wrong. A row that cannot be read is
			// meant to be skipped, and skipping is what happens when the only
			// numbers left are the ones already spoken for.
			for (String text : texts)
			{
				if (text.equals(countedOn))
				{
					continue;
				}
				final Matcher n = NUMBER.matcher(text);
				while (n.find())
				{
					gross = Math.max(gross, digits(n.group()));
				}
			}
		}

		// The "x N" on the name is the offer's quantity. A name without one is
		// a single item -- "Ruby bolts (e)" alone -- which the icon's stack or
		// the total over the per-item price confirms when either is there.
		long quantity = textQuantity;
		if (quantity <= 0 && iconQuantity > 1)
		{
			quantity = iconQuantity;
		}
		if (quantity <= 0 && each > 0 && gross > 0)
		{
			quantity = Math.max(1, Math.round((double) gross / each));
		}
		if (quantity <= 0 && gross > 0)
		{
			quantity = 1;
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

	/** The given group of the first match, as a number, or 0 if it does not match. */
	private static long firstNumber(Pattern pattern, String text, int group)
	{
		final Matcher matcher = pattern.matcher(text);
		return matcher.find() ? digits(matcher.group(group)) : 0;
	}

	private static long digits(String s)
	{
		final String clean = NOT_DIGIT.matcher(s).replaceAll("");
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

	private static String stripTags(@Nullable String text)
	{
		return text == null ? "" : TAG.matcher(text).replaceAll("").trim();
	}
}
