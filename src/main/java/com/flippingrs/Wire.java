package com.flippingrs;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The handful of things every wire shape needs.
 *
 * <p>The shapes themselves are one class each, so that {@link FlippingRsApi}
 * is the requests and nothing else. What they share is here rather than left
 * behind on the API class, so a shape does not have to reach back into the
 * transport to render its own name.
 */
final class Wire
{
	private Wire()
	{
	}

	/**
	 * A name for a Swing label, shown as typed. A JLabel treats any string
	 * that begins with {@code <html>} as markup, so a watchlist or journal
	 * named that way would render as a formatted fragment rather than its
	 * name. A leading space defeats the check and is invisible in a combo
	 * box. Only the owner can name their own lists, so this is a display
	 * oddity rather than an attack, but it is a cheap one to close.
	 */
	static String plain(String text)
	{
		return text != null && text.regionMatches(true, 0, "<html", 0, 5) ? " " + text : text;
	}

	static <T> List<T> withoutNulls(@Nullable List<T> in)
	{
		if (in == null)
		{
			return Collections.emptyList();
		}
		final List<T> out = new ArrayList<>(in.size());
		for (T item : in)
		{
			if (item != null)
			{
				out.add(item);
			}
		}
		return out;
	}

	/**
	 * The refusals a reply carries, as lines for a log or a panel.
	 *
	 * <p>Held as raw JSON on the way in -- see {@link IngestResult#problems} --
	 * so a string comes out as itself and anything structured as the JSON it
	 * was, rather than failing the parse of a reply that was otherwise fine.
	 *
	 * @return never null, and never containing null
	 */
	static List<String> problemsOf(@Nullable List<JsonElement> problems)
	{
		if (problems == null)
		{
			return Collections.emptyList();
		}
		final List<String> out = new ArrayList<>(problems.size());
		for (JsonElement problem : problems)
		{
			if (problem == null || problem.isJsonNull())
			{
				continue;
			}
			out.add(problem.isJsonPrimitive() ? problem.getAsString() : problem.toString());
		}
		return out;
	}
}
