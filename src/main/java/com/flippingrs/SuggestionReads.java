package com.flippingrs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Reading the Suggestions tab from the server, and only while somebody can see it.
 *
 * <p>One GET, with the plugin key and the budget if the user set one. It is
 * made when the tab comes on screen, once a minute while it stays there, and
 * when the user presses Refresh. Nothing is read while the sidebar is shut or
 * another tab is up, and nothing is read without a key or with recording off.
 *
 * <p>Net thread, except where a method says otherwise. Nothing here throws:
 * {@link #tick} runs as a fixed-delay task, where an escaping exception would
 * cancel it for the rest of the session.
 */
@Slf4j
final class SuggestionReads
{
	static final long REFRESH_SECONDS = 60;

	/** How many rows are asked for. The tab draws at most {@link SuggestionsTab#MAX_ROWS}. */
	static final int ASK_FOR = 10;

	/** The least gap between two reads however they were asked for. */
	private static final long MIN_GAP_SECONDS = 5;

	/** "Not read yet", on a clock whose zero is arbitrary. See PanelReads. */
	private static final long NEVER = Long.MIN_VALUE;

	private final FlippingRsConfig config;
	private final Supplier<FlippingRsApi> api;
	private final PanelUpdates panel;
	private final Consumer<Runnable> netThread;
	private final Consumer<Runnable> clientThread;
	private final IntFunction<AsyncBufferedImage> sprite;
	private final LongSupplier nanos;

	private volatile boolean sidebarShown;
	private volatile boolean tabShowing;
	private final AtomicBoolean inFlight = new AtomicBoolean();
	private volatile long lastAttempt = NEVER;
	private volatile long lastBudget = -1;
	private volatile long limitedUntil = NEVER;
	/** The notice last put on the tab, so an unchanged one is not sent again. */
	@Nullable
	private volatile String lastNotice;

	SuggestionReads(FlippingRsConfig config, Supplier<FlippingRsApi> api, PanelUpdates panel,
		Consumer<Runnable> netThread, Consumer<Runnable> clientThread, IntFunction<AsyncBufferedImage> sprite,
		LongSupplier nanos)
	{
		this.config = config;
		this.api = api;
		this.panel = panel;
		this.netThread = netThread;
		this.clientThread = clientThread;
		this.sprite = sprite;
		this.nanos = nanos;
	}

	void newSession()
	{
		sidebarShown = false;
		tabShowing = false;
		lastAttempt = NEVER;
		lastBudget = -1;
		limitedUntil = NEVER;
		lastNotice = null;
		inFlight.set(false);
	}

	/** The sidebar opened on the panel, or closed. Swing thread. */
	void sidebarShown(boolean shown)
	{
		sidebarShown = shown;
		if (shown)
		{
			netThread.accept(this::tick);
		}
	}

	/** The Suggestions tab came on screen, or went off it. Swing thread. */
	void tabShowing(boolean showing)
	{
		tabShowing = showing;
		if (showing)
		{
			netThread.accept(this::tick);
		}
	}

	boolean visible()
	{
		return sidebarShown && tabShowing;
	}

	/** Refresh pressed. Swing thread. */
	void refreshNow()
	{
		netThread.accept(() ->
		{
			try
			{
				if (!visible() || !sinceLast(MIN_GAP_SECONDS) || limitedUntil != NEVER && nanos.getAsLong() - limitedUntil < 0)
				{
					// Too soon, or the server has just said to slow down.
					return;
				}
				read();
			}
			catch (RuntimeException e)
			{
				log.warn("unexpected failure reading suggestions", e);
			}
		});
	}

	/**
	 * Reads if the tab is on screen and a read is due: never read, a minute
	 * old, or made for a different budget than the one set now.
	 */
	void tick()
	{
		try
		{
			if (!visible())
			{
				return;
			}
			if (lastAttempt == NEVER || sinceLast(REFRESH_SECONDS) || lastBudget != budget())
			{
				read();
			}
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure reading suggestions", e);
		}
	}

	private boolean sinceLast(long seconds)
	{
		return lastAttempt == NEVER || nanos.getAsLong() - lastAttempt >= TimeUnit.SECONDS.toNanos(seconds);
	}

	private long budget()
	{
		return Math.max(0, config.suggestionsBudget());
	}

	private void notice(@Nullable String text)
	{
		if (java.util.Objects.equals(text, lastNotice))
		{
			return;
		}
		lastNotice = text;
		if (text != null)
		{
			panel.onPanel(p -> p.setSuggestionsNotice(text));
		}
	}

	private void read()
	{
		if (!config.enabled())
		{
			// Recording off means nothing goes to flippingrs.com, this included.
			lastAttempt = NEVER;
			notice("Recording is off, so nothing is being read from flippingrs.com.");
			return;
		}
		final String key = FlippingRsApi.trimmedKey(config.apiKey());
		if (key.isEmpty())
		{
			// Not counted as an attempt, so a key entered while the tab is up
			// is used on the next tick rather than a minute later.
			lastAttempt = NEVER;
			notice("Add your plugin key in the plugin settings.");
			return;
		}
		if (!inFlight.compareAndSet(false, true))
		{
			return;
		}
		lastNotice = null;
		final long budget = budget();
		lastAttempt = nanos.getAsLong();
		lastBudget = budget;
		try
		{
			final Suggestions reply = api.get().suggestions(key, budget, ASK_FOR);
			final List<Suggestion> items = reply.getItems();
			final List<Suggestion> shown = items.size() > SuggestionsTab.MAX_ROWS
				? items.subList(0, SuggestionsTab.MAX_ROWS) : items;
			// Sprites come from the item manager, which wants the client thread.
			clientThread.accept(() ->
			{
				final Map<Integer, AsyncBufferedImage> images = new HashMap<>();
				for (Suggestion s : shown)
				{
					if (s.id > 0 && !images.containsKey(s.id))
					{
						final AsyncBufferedImage image = spriteOf(s.id);
						if (image != null)
						{
							images.put(s.id, image);
						}
					}
				}
				panel.onPanel(p -> p.setSuggestions(shown, images, budget));
			});
		}
		catch (FlippingRsApi.KeyRefusedException e)
		{
			panel.onPanel(p -> p.setSuggestionsProblem(SuggestionsTab.KEY_REFUSED, true));
		}
		catch (FlippingRsApi.RateLimitedException e)
		{
			limitedUntil = nanos.getAsLong() + TimeUnit.SECONDS.toNanos(REFRESH_SECONDS);
			panel.onPanel(p -> p.setSuggestionsProblem(SuggestionsTab.RATE_LIMITED, false));
		}
		catch (java.io.IOException e)
		{
			log.warn("could not read suggestions: {}", e.getMessage());
			final String why = "Couldn't load suggestions: " + FlippingRsApi.describe(e);
			panel.onPanel(p -> p.setSuggestionsProblem(why, false));
		}
		finally
		{
			inFlight.set(false);
		}
	}

	@Nullable
	private AsyncBufferedImage spriteOf(int itemId)
	{
		try
		{
			return sprite.apply(itemId);
		}
		catch (RuntimeException e)
		{
			log.debug("no sprite for item {}", itemId, e);
			return null;
		}
	}
}
