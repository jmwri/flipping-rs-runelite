package com.flippingrs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;

/**
 * The owner's watchlists, and the site's quotes for the one on show.
 *
 * <p>Two caches and nothing of record. The lists are the server's: every
 * edit goes there first and the copy here is replaced with what came back,
 * so the sidebar never shows a change the server did not accept. The quotes
 * are re-read on a timer while something is showing them, and the same copy
 * answers the offer-screen overlay, so an item added or removed in the
 * sidebar shows or vanishes on the offer screen the moment the server has
 * confirmed the edit.
 */
@Slf4j
final class Watchlists
{
	/** What a watchlist is called when the plugin has to create the first one. */
	static final String NEW_WATCHLIST_NAME = "Plan";
	/** The server's cap on one watchlist. */
	static final int MAX_WATCHLIST_ITEMS = 50;

	private final Client client;
	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final FlippingRsConfig config;
	private final ProfileStore store;
	private final Supplier<FlippingRsApi> api;
	private final PanelUpdates panel;
	/** Resolves an item's name. Client thread. */
	private final IntFunction<String> itemName;
	/** Re-reads the Watchlists tab from the server. Net thread, synchronous. */
	private final Runnable reread;
	/** Hands work to the net thread. */
	private final Consumer<Runnable> netThread;

	/** The owner's watchlists, as last read from the server. Null until the first read. */
	@Nullable
	private volatile List<FlippingRsApi.Watchlist> lists;

	/** The site's quotes for the watched items, as last read from the panel endpoint. */
	private volatile Map<Integer, FlippingRsApi.Quote> quotes = Collections.emptyMap();

	/**
	 * The shown watchlist's items, as a set, kept in step with {@link #lists}.
	 *
	 * <p>Only for {@link #isWatched}, which the offer-screen overlay asks on
	 * every frame it draws. Answering it from the lists meant resolving the
	 * chosen watchlist each time -- a RuneLite config read for the remembered
	 * id, then a linear scan of up to fifty boxed ids -- fifty times a second,
	 * for as long as an offer screen is open. This is a hash lookup and no
	 * config read at all.
	 */
	private volatile Set<Integer> watchedIds = Collections.emptySet();

	/**
	 * Whether anything is currently showing the quotes: the sidebar panel is
	 * open, or the Grand Exchange is, where the overlay draws them. While
	 * neither is, the refresh is skipped. It is the one call that would
	 * otherwise run for every user, every half minute, for as long as the
	 * client is open, against a rate limit of thirty requests a minute that
	 * the sends also draw on. Opening the sidebar refreshes at once, so
	 * nothing stale is shown for longer than a tick.
	 */
	private volatile boolean sidebarShown;
	private volatile boolean exchangeOpen;

	Watchlists(Client client, ItemManager itemManager, ClientThread clientThread, FlippingRsConfig config,
		ProfileStore store, Supplier<FlippingRsApi> api, PanelUpdates panel, IntFunction<String> itemName,
		Runnable reread, Consumer<Runnable> netThread)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.config = config;
		this.store = store;
		this.api = api;
		this.panel = panel;
		this.itemName = itemName;
		this.reread = reread;
		this.netThread = netThread;
	}

	/** Forgets everything, for a plugin start or stop. */
	void reset()
	{
		lists = null;
		watchedIds = Collections.emptySet();
		quotes = Collections.emptyMap();
		sidebarShown = false;
		exchangeOpen = false;
	}

	/** Forgets the lists, for a change of server. The next read replaces them. */
	void forget()
	{
		lists = null;
		watchedIds = Collections.emptySet();
	}

	void sidebarShown(boolean shown)
	{
		sidebarShown = shown;
	}

	void exchangeOpen(boolean open)
	{
		exchangeOpen = open;
	}

	/** Whether the quote timer has anything to do: something is showing quotes, and there are items to quote. */
	boolean wantsQuotes()
	{
		if (!sidebarShown && !exchangeOpen)
		{
			return false;
		}
		final FlippingRsApi.Watchlist current = current();
		return current != null && !current.getItemIds().isEmpty();
	}

	/**
	 * Takes what a panel read returned. Either part may be absent, in which
	 * case what was already held is kept; if either is present the sidebar is
	 * redrawn.
	 */
	void accept(@Nullable List<FlippingRsApi.Watchlist> fromServer, @Nullable Map<Integer, FlippingRsApi.Quote> prices)
	{
		if (fromServer != null)
		{
			lists = fromServer;
		}
		if (prices != null)
		{
			quotes = prices;
		}
		if (fromServer != null || prices != null)
		{
			show();
		}
	}

	/** The watchlist the right-click entry adds to: the remembered one, else the first, else null. */
	@Nullable
	FlippingRsApi.Watchlist current()
	{
		final List<FlippingRsApi.Watchlist> known = lists;
		return known == null ? null : currentOf(known);
	}

	@Nullable
	private FlippingRsApi.Watchlist currentOf(List<FlippingRsApi.Watchlist> known)
	{
		if (known.isEmpty())
		{
			return null;
		}
		final String remembered = store.rememberedWatchlistId();
		for (FlippingRsApi.Watchlist watchlist : known)
		{
			if (watchlist.id != null && watchlist.id.equals(remembered))
			{
				return watchlist;
			}
		}
		return known.get(0);
	}

	/** Whether an item is on the watchlist the panel is showing. */
	boolean isWatched(int itemId)
	{
		return watchedIds.contains(itemId);
	}

	/**
	 * The site's quote for an item, if it is on the shown watchlist and the
	 * setting allows the overlay; else null. Client thread, from the overlay,
	 * once per frame.
	 */
	@Nullable
	FlippingRsApi.Quote watchedQuote(int itemId)
	{
		if (!config.setupOverlay() || !isWatched(itemId))
		{
			return null;
		}
		return quotes.get(itemId);
	}

	/**
	 * An offer on an item changed. If the item is watched, its card's
	 * live-offer line is brought up to date. Only that line: rebuilding every
	 * card, sprites and all, on each fill of a watched item was real work for
	 * a flipper with a long list and fast items. Client thread.
	 */
	void offerChanged(int itemId)
	{
		if (!isWatched(itemId))
		{
			return;
		}
		final String live = liveOffer(itemId);
		panel.onPanel(p -> p.updateWatchedOffer(itemId, live));
	}

	/** The player's current offer on an item, in a few words, or null. Client thread. */
	@Nullable
	String liveOffer(int itemId)
	{
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}
		for (GrandExchangeOffer offer : offers)
		{
			if (offer == null || offer.getItemId() != itemId || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			final String verb;
			switch (offer.getState())
			{
				case BUYING:
					verb = "Buying";
					break;
				case SELLING:
					verb = "Selling";
					break;
				case BOUGHT:
					verb = "Bought";
					break;
				case SOLD:
					verb = "Sold";
					break;
				case CANCELLED_BUY:
					verb = "Buy cancelled";
					break;
				case CANCELLED_SELL:
					verb = "Sell cancelled";
					break;
				default:
					continue;
			}
			return verb + " " + offer.getQuantitySold() + "/" + offer.getTotalQuantity()
				+ " at " + FlippingRsPanel.gp(offer.getPrice());
		}
		return null;
	}

	/** The user picked a watchlist in the sidebar. Swing thread. */
	void chosen(String id)
	{
		store.rememberWatchlist(id);
		show();
		netThread.accept(reread);
	}

	/** Net thread. */
	void add(int itemId)
	{
		change(itemId, true);
	}

	/** Net thread. */
	void remove(int itemId)
	{
		change(itemId, false);
	}

	/**
	 * Adds an item to, or removes it from, the chosen watchlist on the server.
	 *
	 * <p>Net thread. The server holds the list, so the edit is sent first and
	 * the panel redrawn from what comes back; nothing is changed locally on
	 * the assumption that it will go through. When the owner has no watchlist
	 * at all, the first add creates one.
	 */
	private void change(int itemId, boolean add)
	{
		if (!config.enabled())
		{
			// "Record trades" off is a promise not to contact the server at
			// all, and a watchlist edit is contact.
			panel.onPanel(p -> p.setWatchlistNotice("Switch \"Record trades\" back on in the plugin settings to change "
				+ "your watchlist.", ColorScheme.BRAND_ORANGE));
			return;
		}
		final String key = config.apiKey().trim();
		if (key.isEmpty())
		{
			panel.onPanel(p -> p.setWatchlistNotice("Add your API key in the plugin settings to use watchlists.",
				ColorScheme.BRAND_ORANGE));
			return;
		}
		try
		{
			List<FlippingRsApi.Watchlist> known = lists;
			if (known == null)
			{
				final List<FlippingRsApi.Watchlist> fromServer = api.get().watchlists(key, null).getWatchlists();
				known = fromServer == null ? Collections.emptyList() : fromServer;
			}
			final FlippingRsApi.Watchlist current = currentOf(known);
			final FlippingRsApi.Watchlist updated;
			if (current == null)
			{
				if (!add)
				{
					return;
				}
				updated = api.get().createWatchlist(key, NEW_WATCHLIST_NAME, Collections.singletonList(itemId));
				store.rememberWatchlist(updated.id);
				known = new ArrayList<>(known);
				known.add(updated);
			}
			else
			{
				final List<Integer> ids = new ArrayList<>(current.getItemIds());
				final String name = current.toString();
				if (add)
				{
					if (ids.contains(itemId))
					{
						panel.onPanel(p -> p.setWatchlistNotice("Already on " + name + ".", ColorScheme.LIGHT_GRAY_COLOR));
						return;
					}
					if (ids.size() >= MAX_WATCHLIST_ITEMS)
					{
						panel.onPanel(p -> p.setWatchlistNotice(name + " is full: a watchlist holds " + MAX_WATCHLIST_ITEMS
							+ " items. Remove something or pick another watchlist.", ColorScheme.BRAND_ORANGE));
						return;
					}
					ids.add(itemId);
				}
				else if (!ids.remove(Integer.valueOf(itemId)))
				{
					return;
				}
				updated = api.get().updateWatchlist(key, current.id, ids);
				known = replacing(known, updated);
			}
			lists = known;
			show();
			reread.run();
			final String name = updated.toString();
			panel.onPanel(p -> p.setWatchlistNotice((add ? "Added to " : "Removed from ") + name + ".",
				ColorScheme.PROGRESS_COMPLETE_COLOR));
		}
		catch (IOException e)
		{
			// A plan limit arrives here too, with the server's own words.
			log.debug("could not change the watchlist", e);
			final String why = FlippingRsApi.describe(e);
			panel.onPanel(p -> p.setWatchlistNotice("Couldn't update your watchlist: " + why, ColorScheme.PROGRESS_ERROR_COLOR));
		}
	}

	private static Set<Integer> setOf(List<Integer> ids)
	{
		final Set<Integer> out = new HashSet<>(Math.max(4, ids.size() * 2));
		for (Integer id : ids)
		{
			if (id != null)
			{
				out.add(id);
			}
		}
		return out;
	}

	private static List<FlippingRsApi.Watchlist> replacing(
		List<FlippingRsApi.Watchlist> known, FlippingRsApi.Watchlist updated)
	{
		final List<FlippingRsApi.Watchlist> out = new ArrayList<>(known.size());
		for (FlippingRsApi.Watchlist watchlist : known)
		{
			out.add(updated.id.equals(watchlist.id) ? updated : watchlist);
		}
		return out;
	}

	/** Puts the cached watchlists on the panel, with the chosen one's items named. */
	private void show()
	{
		final List<FlippingRsApi.Watchlist> known = lists;
		if (known == null)
		{
			return;
		}
		final FlippingRsApi.Watchlist current = currentOf(known);
		final String selected = current == null ? null : current.id;
		final List<Integer> ids = current == null ? Collections.emptyList() : current.getItemIds();
		// Kept in step here, because this is the one place that settles which
		// watchlist is the shown one. Every path that changes the lists or the
		// choice ends up here.
		watchedIds = setOf(ids);
		// Names, prices and sprites come from the item manager, which wants
		// the client thread.
		clientThread.invoke(() ->
		{
			final List<FlippingRsPanel.WatchedItem> items = new ArrayList<>(ids.size());
			for (Integer id : ids)
			{
				if (id != null)
				{
					items.add(describeItem(id));
				}
			}
			panel.onPanel(p ->
			{
				p.setWatchlists(known, selected);
				p.setWatchlistItems(items);
			});
		});
	}

	/**
	 * What the watchlist shows for an item: the site's quote from the last
	 * watchlist read, if it has one, and from the client its name and sprite,
	 * RuneLite's exchange price, the buy limit, the alch value, and whether
	 * the player has an offer on it right now. The client's numbers are the
	 * fallback the card shows when the site has no quote for the item.
	 *
	 * <p>Client thread.
	 */
	private FlippingRsPanel.WatchedItem describeItem(int itemId)
	{
		int price = 0;
		int limit = 0;
		int alch = 0;
		net.runelite.client.util.AsyncBufferedImage image = null;
		try
		{
			price = itemManager.getItemPrice(itemId);
			final net.runelite.client.game.ItemStats stats = itemManager.getItemStats(itemId);
			limit = stats == null ? 0 : stats.getGeLimit();
			alch = itemManager.getItemComposition(itemId).getHaPrice();
			image = itemManager.getImage(itemId);
		}
		catch (RuntimeException e)
		{
			// A row with a name and no numbers beats no row.
			log.debug("could not describe item {}", itemId, e);
		}
		return new FlippingRsPanel.WatchedItem(itemId, itemName.apply(itemId), image, price, limit, alch,
			liveOffer(itemId), quotes.get(itemId));
	}
}
