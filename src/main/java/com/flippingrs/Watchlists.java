package com.flippingrs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
	private volatile List<Watchlist> lists;

	/** The site's quotes for the watched items, as last read from the panel endpoint. */
	private volatile Map<Integer, Quote> quotes = Collections.emptyMap();

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
	 * client is open, against a rate limit of sixty requests a minute that
	 * the sends also draw on. Opening the sidebar refreshes at once, so
	 * nothing stale is shown for longer than a tick.
	 */
	private volatile boolean sidebarShown;
	private volatile boolean exchangeOpen;

	/**
	 * Quotes for items the exchange is showing that are not on the watchlist.
	 *
	 * <p>The watchlist is a curated list, and the moment a price is actually
	 * wanted is the moment one is being typed -- which is for whatever item is
	 * in front of you, not only the ones somebody thought to add to a list
	 * beforehand. So the exchange says which items it is showing and these are
	 * fetched for those, on the same tick the watchlist's own quotes are
	 * refreshed on.
	 *
	 * <p>Separate from {@link #quotes} rather than merged into it, because
	 * that map is what the sidebar's cards are drawn from and it is replaced
	 * wholesale by every watchlist read.
	 */
	private volatile Map<Integer, Quote> onDemand = Collections.emptyMap();

	/**
	 * The items the exchange is showing right now: the open offers, and the
	 * one being set up. Written from the client thread, read from the net one.
	 *
	 * <p>Two fields rather than one set, because two overlays report them and
	 * each draws on its own frame. Sharing one collection had them overwrite
	 * each other -- the setup screen's item replacing the slots' and then being
	 * replaced back -- so which items got a price depended on which overlay
	 * happened to render last.
	 */
	private final Set<Integer> offerItems = ConcurrentHashMap.newKeySet();

	/** The item on the offer setup screen, or 0 when it is not open. */
	private volatile int setupItem;

	/** The last item examined that had no price, or 0. */
	private volatile int examinedItem;

	/**
	 * Set once a server has said it has no per-item quote route, so the plugin
	 * stops asking.
	 *
	 * <p>An older flippingrs.com will answer every one of these the same way
	 * forever, and asking twice a minute for the life of the client spends the
	 * budget on a question already answered. Everything the plugin needs to do
	 * its job goes through routes that have always been there, so this costs
	 * the extra prices and nothing else.
	 */
	private volatile boolean quoteRouteMissing;

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
		onDemand = Collections.emptyMap();
		offerItems.clear();
		setupItem = 0;
		sidebarShown = false;
		exchangeOpen = false;
	}

	/**
	 * Forgets everything read from the server, keeping what is on screen.
	 *
	 * <p>For a change of server, and for recording being switched off or the
	 * key being taken away. In those last two the sidebar says plainly that
	 * nothing is being read from flippingrs.com, and the offer screen has to
	 * agree with it: quotes that stop refreshing but go on being drawn are
	 * prices from a service the user has turned off, silently frozen at
	 * whatever they were, in front of the box where a price gets typed.
	 */
	void forget()
	{
		lists = null;
		watchedIds = Collections.emptySet();
		quotes = Collections.emptyMap();
		onDemand = Collections.emptyMap();
		// A different server may well have the route this one did not. Holding
		// the refusal across a change of server would leave a developer's
		// local instance unable to price anything, for a decision made about
		// somebody else's.
		quoteRouteMissing = false;
	}

	void sidebarShown(boolean shown)
	{
		final boolean opened = shown && !sidebarShown;
		sidebarShown = shown;
		if (opened)
		{
			// Draw what is already cached at once. The plugin starts a read
			// alongside this, but that is a network round trip, and the tab
			// should not be blank or stale for the length of one. It is also
			// what makes it safe for show() to skip a closed sidebar: whatever
			// it skipped is drawn here.
			show();
		}
	}

	void exchangeOpen(boolean open)
	{
		exchangeOpen = open;
	}

	/** Whether the quote timer has anything to do: something is showing quotes, and there are items to quote. */
	boolean wantsQuotes()
	{
		// The shown list's items are already resolved, so this asks them rather
		// than working the chosen watchlist out again -- which meant a RuneLite
		// config read on the net thread every half minute.
		return (sidebarShown || exchangeOpen) && !watchedIds.isEmpty();
	}

	/**
	 * Takes what a panel read returned. Either part may be absent, in which
	 * case what was already held is kept; if either is present the sidebar is
	 * redrawn.
	 */
	void accept(@Nullable List<Watchlist> fromServer, @Nullable Map<Integer, Quote> prices)
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

	/**
	 * The watchlist the tab is showing: the remembered one, or the first the
	 * plugin can actually use.
	 *
	 * <p>One without an id is skipped rather than counted. The picker drops
	 * those, so settling on one had the tab name the next list along while
	 * listing this one's items -- and an add would have gone out as a PATCH
	 * to a watchlist with no id, which is not a request that can be built.
	 */
	@Nullable
	private Watchlist currentOf(List<Watchlist> known)
	{
		if (known.isEmpty())
		{
			return null;
		}
		final String remembered = store.rememberedWatchlistId();
		Watchlist first = null;
		for (Watchlist watchlist : known)
		{
			if (watchlist == null || watchlist.id == null || watchlist.id.isEmpty())
			{
				continue;
			}
			if (watchlist.id.equals(remembered))
			{
				return watchlist;
			}
			if (first == null)
			{
				first = watchlist;
			}
		}
		return first;
	}

	/** Whether an item is on the watchlist the panel is showing. */
	boolean isWatched(int itemId)
	{
		return watchedIds.contains(itemId);
	}

	/**
	 * The site's quote for an item, whether or not it is on the watchlist.
	 *
	 * <p>The watchlist's own price first, because that is the one the sidebar
	 * is showing and the two must not disagree; then whatever was fetched
	 * because the exchange put the item on screen. Null when there is neither,
	 * or when the setting that draws these is off.
	 *
	 * <p>Client thread, from the overlays, once per frame. Both maps are
	 * replaced rather than mutated, so this never blocks the frame.
	 */
	@Nullable
	Quote quoteFor(int itemId)
	{
		if (!config.setupOverlay())
		{
			return null;
		}
		// The shown watchlist first, and only if the item is actually on it.
		// The quote map is whatever the last watchlist read returned, which
		// can still hold the previous list's items; answering from it for an
		// item that has since been taken off would have the offer screen
		// pricing something the sidebar no longer lists.
		if (isWatched(itemId))
		{
			final Quote watched = quotes.get(itemId);
			if (watched != null)
			{
				return watched;
			}
		}
		return onDemand.get(itemId);
	}

	/**
	 * The items your open offers are on. Client thread, once a frame.
	 *
	 * <p>Recorded rather than fetched here. A price is a request, the frame
	 * path is the game thread, and the tick that refreshes the watchlist's
	 * quotes is already going to the server on a sensible cadence anyway.
	 */
	void showingOffers(Set<Integer> itemIds)
	{
		if (offerItems.equals(itemIds))
		{
			return;
		}
		offerItems.clear();
		offerItems.addAll(itemIds);
	}

	/** The item on the offer setup screen, or 0 when it is not open. Client thread. */
	void showingSetup(int itemId)
	{
		setupItem = itemId;
	}

	/**
	 * An item somebody examined that nobody has a price for. Client thread.
	 *
	 * <p>Kept until the next fetch takes it, so that examining an item once is
	 * what makes the second examine of it able to answer. Only one: examine is
	 * a deliberate act on one item, and remembering a list of them would turn
	 * an idle rummage through a bank into a request for forty prices.
	 */
	void showingExamined(int itemId)
	{
		examinedItem = itemId;
	}

	/**
	 * Fetches quotes for what the exchange is showing and the watchlist does
	 * not cover. Net thread, on the quote tick.
	 *
	 * <p>Everything on screen is asked for, not only what is missing, because
	 * a price that is already held is a price that is getting older: this is
	 * the refresh as much as it is the first fetch. It is one request however
	 * many items are on screen, and there are at most nine.
	 */
	void fetchOnDemand()
	{
		if (quoteRouteMissing)
		{
			return;
		}
		// The exchange being open is what makes the offer screens worth
		// pricing, but an examined item is examined anywhere -- a bank, the
		// ground -- so it is asked for wherever it was.
		final boolean wantsScreens = config.setupOverlay() && exchangeOpen;
		final boolean wantsExamined = config.examinePrices() && examinedItem > 0;
		if (!wantsScreens && !wantsExamined)
		{
			return;
		}
		final Set<Integer> wanted = new java.util.LinkedHashSet<>(wantsScreens ? offerItems
			: java.util.Collections.<Integer>emptySet());
		final int setup = setupItem;
		if (setup > 0)
		{
			wanted.add(setup);
		}
		final int examined = examinedItem;
		if (examined > 0)
		{
			wanted.add(examined);
		}
		wanted.removeAll(watchedIds);
		if (wanted.isEmpty())
		{
			if (!onDemand.isEmpty())
			{
				onDemand = Collections.emptyMap();
			}
			return;
		}
		final String key = FlippingRsApi.trimmedKey(config.apiKey());
		if (key.isEmpty())
		{
			return;
		}
		try
		{
			// The journal, so the server can say how much of each buy limit
			// this character has left. Limits are counted per journal, which
			// is the whole reason a main and an alt must not share one.
			final String accountId = store.chosenAccountFor(client.getAccountHash());
			final Map<Integer, Quote> got = api.get().quotes(key, accountId, wanted).getQuotes();
			// A reply with the part absent is "nothing changed", the same as
			// everywhere else, so what is held stays held.
			if (got != null)
			{
				onDemand = got;
			}
		}
		catch (FlippingRsApi.NotHereException e)
		{
			log.info("this flippingrs.com has no per-item quotes; the offer screen will only price watched items");
			quoteRouteMissing = true;
		}
		catch (IOException e)
		{
			// A price nobody asked for out loud. The next tick tries again.
			log.debug("could not fetch quotes for what the exchange is showing", e);
		}
	}

	/**
	 * An offer on an item changed. If the item is watched, its card's
	 * live-offer line is brought up to date. Only that line: rebuilding every
	 * card, sprites and all, on each fill of a watched item was real work for
	 * a flipper with a long list and fast items. Client thread.
	 */
	void offerChanged(int itemId)
	{
		// Nothing to bring up to date while the sidebar is shut, and a fill on
		// a watched item is otherwise a scan of the eight exchange slots here
		// and, when the line appears or disappears, a rebuild of every card on
		// the Swing thread. Opening the sidebar reads the live offers afresh.
		if (!sidebarShown || !isWatched(itemId))
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
		if (id.equals(store.rememberedWatchlistId()))
		{
			// A combo box fires its action on any pick, including re-picking
			// what was already selected, and that would cost a settings write,
			// a rebuild of every card and a request against a sixty-a-minute
			// limit to arrive back where it started.
			return;
		}
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
		try
		{
			if (!config.enabled())
			{
				// "Record trades" off is a promise not to contact the server at
				// all, and a watchlist edit is contact.
				panel.onPanel(p -> p.setWatchlistNotice("Switch \"Record trades\" back on in the plugin settings to change "
					+ "your watchlist.", ColorScheme.BRAND_ORANGE));
				return;
			}
			final String key = FlippingRsApi.trimmedKey(config.apiKey());
			if (key.isEmpty())
			{
				panel.onPanel(p -> p.setWatchlistNotice("Add your API key in the plugin settings to use watchlists.",
					ColorScheme.BRAND_ORANGE));
				return;
			}
			List<Watchlist> known = lists;
			if (known == null)
			{
				// Only the lists are wanted here, so neither a watchlist to
				// price nor a journal to count limits against is worth naming.
				final List<Watchlist> fromServer = api.get().watchlists(key, null, null).getWatchlists();
				known = fromServer == null ? Collections.emptyList() : fromServer;
			}
			final Watchlist current = currentOf(known);
			final Watchlist updated;
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
		catch (RuntimeException e)
		{
			// Not every way this can fail is an IOException. A key with a stray
			// control character in it is refused by the HTTP client before the
			// request is built, and a config read can throw. This runs as a
			// one-shot task on the net thread, so an escape kills the task and
			// nothing else -- but the user right-clicked Add to watchlist and
			// is owed an answer either way.
			log.warn("unexpected failure while changing the watchlist", e);
			panel.onPanel(p -> p.setWatchlistNotice(
				"Something went wrong updating your watchlist. Details are in the client log.",
				ColorScheme.PROGRESS_ERROR_COLOR));
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

	private static List<Watchlist> replacing(
		List<Watchlist> known, Watchlist updated)
	{
		final List<Watchlist> out = new ArrayList<>(known.size());
		for (Watchlist watchlist : known)
		{
			out.add(updated.id.equals(watchlist.id) ? updated : watchlist);
		}
		return out;
	}

	/** Puts the cached watchlists on the panel, with the chosen one's items named. */
	private void show()
	{
		final List<Watchlist> known = lists;
		if (known == null)
		{
			return;
		}
		final Watchlist current = currentOf(known);
		final String selected = current == null ? null : current.id;
		final List<Integer> ids = current == null ? Collections.emptyList() : current.getItemIds();
		// Kept in step here, because this is the one place that settles which
		// watchlist is the shown one. Every path that changes the lists or the
		// choice ends up here.
		watchedIds = setOf(ids);

		if (!sidebarShown)
		{
			// The offer-screen overlay reads the ids above and the quotes, and
			// both are now current, which is the whole of what it needs. The
			// cards are not: describing an item asks the item manager for a
			// price, its stats, its composition and its sprite, and scans the
			// eight exchange slots for a live offer, all on the game thread --
			// and then rebuilds fifty cards on the Swing thread. Doing that
			// every half minute for a sidebar nobody has open is the common
			// case for a flipper, who keeps the exchange open and the sidebar
			// shut. Opening it redraws from this same cache.
			return;
		}
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
		String name = "";
		int price = 0;
		int limit = 0;
		int alch = 0;
		net.runelite.client.util.AsyncBufferedImage image = null;
		try
		{
			price = itemManager.getItemPrice(itemId);
			final net.runelite.client.game.ItemStats stats = itemManager.getItemStats(itemId);
			limit = stats == null ? 0 : stats.getGeLimit();
			// One composition read, not two. The alch value and the name both
			// come off it, and asking twice meant two of these for every item
			// on the watchlist -- fifty of them, on the game thread, every
			// time the quotes are refreshed.
			final net.runelite.api.ItemComposition composition = itemManager.getItemComposition(itemId);
			alch = composition.getHaPrice();
			name = composition.getName() == null ? "" : composition.getName();
			image = itemManager.getImage(itemId);
		}
		catch (RuntimeException e)
		{
			// A row with a name and no numbers beats no row.
			log.debug("could not describe item {}", itemId, e);
		}
		// Only when the read above did not get one: the supplier is the same
		// lookup with the same guard around it, so asking it after a read that
		// worked is the second read this method exists to avoid.
		return new FlippingRsPanel.WatchedItem(itemId, name.isEmpty() ? itemName.apply(itemId) : name,
			image, price, limit, alch, liveOffer(itemId), quotes.get(itemId));
	}
}
