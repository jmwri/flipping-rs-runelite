package com.flippingrs;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Keeping the sidebar showing what flippingrs.com has, without spending more
 * of the rate limit than that is worth.
 *
 * <p>Five tabs, four reads. Account is the connection test and is only read by
 * {@link #connect}; Trades and Journal are the journal's, and are re-read
 * together after a send and when the sidebar opens; Watchlists carries the
 * quotes, and is re-read on its own timer while there is somewhere they are
 * shown. Every endpoint answers in the same {@link PanelData} shape and fills
 * only its own parts, so one routine draws all of them and a part that is
 * absent leaves what the panel was already showing alone.
 *
 * <p>The throttling is the point of the class as much as the drawing is. The
 * plugin scope is rate limited at sixty requests a minute, which the sends
 * themselves draw on, so a read that nobody can see is not worth making: a
 * flipper keeps the exchange open and the sidebar shut. Reads that are worth
 * making but come too soon are deferred rather than dropped, so the last send
 * of a burst still gets its refresh.
 *
 * <p>The budget is not the only reason for any of this, and having more of it
 * would not be a reason to drop it: a request made to redraw a panel nobody
 * has open is wasted whatever the limit is, and it is wasted on the user's
 * connection as well as the server's.
 *
 * <p>Net thread, except where a method says otherwise. Nothing here throws:
 * two of these run as fixed-delay tasks, where an escaping exception cancels
 * the task for good and the sidebar goes quiet with nothing in the log to say
 * why.
 */
@Slf4j
final class PanelReads
{
	/**
	 * How often the account tabs are re-read after sends, at most.
	 *
	 * <p>The plugin scope is rate limited at sixty requests a minute, and a
	 * "Send every" of five seconds with slots filling continuously would be
	 * twelve ingests plus twenty-four re-reads on its own. Coalescing the
	 * re-reads caps them at eight -- two tabs, four times a minute -- which
	 * leaves the quote ticks and the catch-up snapshots comfortably inside the
	 * budget rather than merely inside it. A re-read that comes too soon is
	 * deferred, not dropped, so the last send of a burst still gets its
	 * refresh.
	 */
	private static final long ACCOUNT_TABS_REFRESH_SECONDS = 15;

	/**
	 * "Not read yet", on the nanoTime clock. That clock's origin is arbitrary
	 * and it may well be negative, so zero is a time it is allowed to return
	 * and cannot stand in for "never". Every comparison against it is a
	 * subtraction rather than a sum, so a wrap comes out right instead of
	 * deferring a refresh for the next three hundred years.
	 */
	private static final long NEVER = Long.MIN_VALUE;

	/** The tabs that are re-read on their own; Account is only read by connect. */
	enum Tab
	{
		TRADES, JOURNAL, WATCHLISTS
	}

	/**
	 * Runs work on the net thread once a delay has passed.
	 *
	 * @return false if the executor is gone, which happens while shutting down
	 */
	@FunctionalInterface
	interface Later
	{
		boolean after(long nanos, Runnable work);
	}

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final FlippingRsConfig config;
	private final ProfileStore store;
	private final Supplier<FlippingRsApi> api;
	private final PanelUpdates panel;
	private final Watchlists watchlists;
	/** Sends whatever is queued. Net thread, synchronous. */
	private final Runnable drain;
	/** Puts refused fills back in the queue and sends them. Net thread, synchronous. */
	private final Runnable retrySetAside;
	/** Reports the open slots to the server. Hands itself to the client thread. */
	private final Runnable snapshotAfterSend;
	/** One account's queue. Opening it reads its file, so it is used on the disk thread. */
	private final java.util.function.LongFunction<TransactionQueue> queueFor;
	/** Hands work to the net thread. */
	private final Consumer<Runnable> netThread;
	/** Hands work to the net thread, after a wait. */
	private final Later later;
	/** Hands work to the disk thread. */
	private final Consumer<Runnable> diskThread;
	/** How many fills have been captured this session, for the Activity tab's count. */
	private final IntSupplier recordedThisSession;

	/**
	 * Whether the sidebar is currently showing this panel. Set from the
	 * panel's own activate and deactivate, and read on the net thread to
	 * decide whether a tab is worth re-reading at all.
	 */
	private volatile boolean sidebarShown;

	private volatile long accountTabsRefreshedAt = NEVER;
	private final AtomicBoolean accountTabsRefreshPending = new AtomicBoolean();

	/**
	 * The journals the key can file under, as last loaded. Kept so that a
	 * login on a different RuneScape account can re-point the picker at that
	 * account's remembered journal without another round trip. Null until the
	 * first successful load.
	 */
	@Nullable
	private volatile List<GameAccount> knownAccounts;

	PanelReads(Client client, ClientThread clientThread, ItemManager itemManager, FlippingRsConfig config,
		ProfileStore store, Supplier<FlippingRsApi> api, PanelUpdates panel, Watchlists watchlists,
		Runnable drain, Runnable retrySetAside, Runnable snapshotAfterSend,
		java.util.function.LongFunction<TransactionQueue> queueFor,
		Consumer<Runnable> netThread, Later later, Consumer<Runnable> diskThread, IntSupplier recordedThisSession)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.config = config;
		this.store = store;
		this.api = api;
		this.panel = panel;
		this.watchlists = watchlists;
		this.drain = drain;
		this.retrySetAside = retrySetAside;
		this.snapshotAfterSend = snapshotAfterSend;
		this.queueFor = queueFor;
		this.netThread = netThread;
		this.later = later;
		this.diskThread = diskThread;
		this.recordedThisSession = recordedThisSession;
	}

	/**
	 * Forgets everything that belonged to the last time the plugin ran: a
	 * sidebar it still thinks is open, a list of journals it has not re-read,
	 * and a deferred re-read that was scheduled on an executor that has since
	 * stopped and will never run to clear its own flag. Left set, that flag
	 * swallows the first coalesced re-read of the new session.
	 */
	void newSession()
	{
		knownAccounts = null;
		accountTabsRefreshedAt = NEVER;
		accountTabsRefreshPending.set(false);
		sidebarShown = false;
	}

	/**
	 * Moves an upgrading install's journal choice out of the RuneScape profile
	 * and onto the account, while there is a logged-in character to attach it
	 * to. Does nothing once it has been moved, and nothing at the login screen,
	 * where the profile config cannot be read anyway.
	 */
	private void migrateIfLoggedIn()
	{
		final long accountHash = client.getAccountHash();
		if (accountHash != FlippingRsPlugin.NO_ACCOUNT)
		{
			store.migrateChosenAccount(accountHash);
		}
	}

	/** The journals the key can file under, as last loaded, or null if none has been. */
	@Nullable
	List<GameAccount> knownAccounts()
	{
		return knownAccounts;
	}

	// ------------------------------------------------------------ panel reads

	/**
	 * Puts a panel reply on the screen. Only the parts present are touched,
	 * so a partial read leaves the rest of the sidebar as it was.
	 *
	 * @param connecting true for the read that doubles as the connection
	 *                   test, which also sets the connection status
	 */
	private void applyPanel(PanelData reply, boolean connecting)
	{
		final Me me = reply.getMe();
		if (me != null)
		{
			final String plan = "Plan: " + me.describePlan();
			panel.onPanel(p -> p.setSubscription(plan));
		}

		final List<GameAccount> accounts = reply.getAccounts();
		if (accounts != null)
		{
			knownAccounts = accounts;
			final String chosen = store.chosenAccountFor(client.getAccountHash());

			// The remembered journal is gone -- deleted on the site, or the key
			// now belongs to a different FlippingRS account. Sending to it
			// would be refused every tick, and the picker would meanwhile show
			// whichever entry sorted first. Forget the choice and say so, and
			// hold the trades until a real one is made.
			final boolean orphaned = chosen != null && !accounts.isEmpty() && !contains(accounts, chosen);
			if (orphaned)
			{
				log.warn("the journal remembered for this account ({}) no longer exists; forgetting it", chosen);
				store.forgetChosenAccountFor(client.getAccountHash());
			}

			panel.onPanel(p -> {
				// The orphaned id is passed through on purpose: the panel shows
				// no selection for a remembered journal it cannot find, where a
				// null would have it select the default as if nothing had ever
				// been chosen.
				p.setAccounts(accounts, chosen);
				if (orphaned)
				{
					p.setStatus("The journal this character was using no longer exists on flippingrs.com. Pick "
						+ "another below. Your trades are being kept safe until you do.", ColorScheme.BRAND_ORANGE);
					return;
				}
				if (connecting)
				{
					p.setStatus(accounts.isEmpty()
							? "Connected, but your flippingrs.com account has no journals yet. Create one on the site first."
							: "Connected and recording.",
						accounts.isEmpty() ? ColorScheme.BRAND_ORANGE : ColorScheme.PROGRESS_COMPLETE_COLOR);
				}
				// A RuneScape account seen for the first time has nothing chosen
				// yet. Adopting whatever the panel selected saves a setup step,
				// and it can still be changed. If nobody is logged in yet, the
				// profile-changed event does this on login instead. (An orphaned
				// choice returned above, so this never adopts over one.)
				if (chosen == null)
				{
					rememberChosenAccountFrom(p, false);
				}
			});
		}
		else if (connecting)
		{
			panel.onPanel(p -> p.setStatus("Connected and recording.", ColorScheme.PROGRESS_COMPLETE_COLOR));
		}

		watchlists.accept(reply.getWatchlists(), reply.getQuotes());

		final List<GeTransaction> all = reply.getRecentTransactions();
		if (all != null)
		{
			// Only the rows that will actually be drawn. The tab shows the
			// newest few and drops the rest, and resolving a sprite for a row
			// nobody will see is item-manager work on the game thread for
			// nothing -- however many the server decides to send back.
			final List<GeTransaction> rows = all.size() > FlippingRsPanel.RECENT_SHOWN
				? all.subList(0, FlippingRsPanel.RECENT_SHOWN)
				: all;
			// Sprites come from the item manager, which wants the client thread.
			clientThread.invoke(() ->
			{
				final Map<Integer, AsyncBufferedImage> images = new HashMap<>();
				for (GeTransaction tx : rows)
				{
					if (!images.containsKey(tx.itemId))
					{
						images.put(tx.itemId, spriteOf(tx.itemId));
					}
				}
				panel.onPanel(p -> p.setRecentTrades(rows, images));
			});
		}

		final Analytics week = reply.getWeek();
		final Positions open = reply.getPositions();
		if (week != null && open != null)
		{
			panel.onPanel(p -> p.setJournal(week, open));
		}
	}

	/**
	 * Checks the key and loads every tab.
	 *
	 * <p>The Account read is the connection test: if it fails, nothing else
	 * is tried and the Account tab says why. The other tabs are then read one
	 * by one, and each reports its own failure on its own tab, since a key
	 * that just worked is not a broken connection.
	 *
	 * <p>Never throws. Not because anything schedules it -- nothing does --
	 * but because this is the read that puts the reason on the Account tab,
	 * and a failure that escapes leaves that tab saying whatever it said
	 * before while the plugin quietly does nothing.
	 */
	void connect()
	{
		try
		{
			if (!config.enabled())
			{
				panel.onPanel(p -> {
					p.setAccounts(Collections.emptyList(), null);
					p.setStatus("Recording is off. Nothing is being recorded or sent to flippingrs.com. Switch "
						+ "\"Record trades\" back on in the plugin settings to carry on.",
						ColorScheme.LIGHT_GRAY_COLOR);
					// Old rows next to a status that says nothing is being read
					// would be a picture of a journal the plugin is not looking at.
					p.setPaused("Recording is off, so nothing is being read from flippingrs.com.");
				});
				// The list the picker was drawn from goes too. It is not read
				// again while this is the state, and a login on another character
				// would otherwise re-point the picker from it -- filling in a
				// journal on a tab that has just said nothing is being read.
				knownAccounts = null;
				// And the offer screen, which draws the same quotes the sidebar
				// does. Left alone it would go on showing the site's prices, frozen
				// at whatever they were when recording was switched off, in front
				// of the box where a price gets typed.
				watchlists.forget();
				return;
			}

			migrateIfLoggedIn();

			final String key = FlippingRsApi.trimmedKey(config.apiKey());
			if (key.isEmpty())
			{
				panel.onPanel(p -> {
					p.setAccounts(Collections.emptyList(), null);
					p.setStatus("Add your API key in the plugin settings. You can create one on flippingrs.com "
						+ "under Account, then API keys.", ColorScheme.LIGHT_GRAY_COLOR);
					p.setPaused("Add an API key to see your journal here.");
				});
				knownAccounts = null;
				watchlists.forget();
				return;
			}

			try
			{
				applyPanel(api.get().account(key), true);
			}
			catch (java.io.IOException e)
			{
				log.debug("could not reach flippingrs.com", e);
				final String why = FlippingRsApi.describe(e);
				panel.onPanel(p -> p.setStatus("Could not connect: " + why, ColorScheme.PROGRESS_ERROR_COLOR));
				return;
			}
			refresh(Tab.TRADES);
			refresh(Tab.JOURNAL);
			accountTabsRefreshedAt = System.nanoTime();
			refresh(Tab.WATCHLISTS);
			refreshPending();
			// The key may have been missing or wrong while trades piled up.
			netThread.accept(drain);
		}
		catch (RuntimeException e)
		{
			// The Account tab is where the plugin explains itself, and this is
			// the read that fills it in. Anything unexpected out of here left
			// it saying whatever it said last -- "Not connected", on a client
			// that had just been given a key -- with the reason in the log and
			// nowhere else, which is the one failure this tab exists to
			// prevent. The sender and the tab reads are wrapped the same way.
			log.warn("unexpected failure while connecting to flippingrs.com", e);
			panel.onPanel(p -> p.setStatus("Something went wrong while connecting. Details are in the client log.",
				ColorScheme.PROGRESS_ERROR_COLOR));
		}
	}

	/**
	 * The sidebar opened on this panel, or closed. Swing thread.
	 *
	 * <p>Opening reads everything the sidebar shows, because while it was shut
	 * none of it was. The two account tabs go through the same throttle the
	 * sends use, so opening and closing it repeatedly cannot become a burst of
	 * requests against a limit of sixty a minute.
	 */
	void sidebarShown(boolean shown)
	{
		sidebarShown = shown;
		watchlists.sidebarShown(shown);
		if (!shown)
		{
			return;
		}
		netThread.accept(() ->
		{
			refresh(Tab.WATCHLISTS);
			refreshAccountTabs();
		});
	}

	/**
	 * What a successful send sets going: the open-slot snapshot always, and a
	 * re-read of the two account tabs if anyone can see them. Net thread.
	 */
	void afterSend()
	{
		// Not a panel read, and so not conditional on anyone looking: this is
		// how the server learns what the open slots hold and recovers a fill
		// the plugin never saw. CatchUp keeps its own, longer, minimum gap.
		clientThread.invoke(snapshotAfterSend);

		if (!sidebarShown)
		{
			// Nobody can see the two tabs. Reading them anyway is two requests
			// per send, against a budget the sends themselves draw on, to
			// redraw a panel that is shut -- and a flipper keeps the exchange
			// open and the sidebar shut. Opening it reads them.
			return;
		}
		refreshAccountTabs();
	}

	/**
	 * Re-reads Trades and Journal, no more often than the limit allows. Net
	 * thread. A read that comes too soon is deferred rather than dropped, so
	 * the last send of a burst still gets its refresh.
	 */
	void refreshAccountTabs()
	{
		final long now = System.nanoTime();
		final long since = now - accountTabsRefreshedAt;
		final long window = TimeUnit.SECONDS.toNanos(ACCOUNT_TABS_REFRESH_SECONDS);
		if (accountTabsRefreshedAt == NEVER || since >= window)
		{
			accountTabsRefreshedAt = now;
			refresh(Tab.TRADES);
			refresh(Tab.JOURNAL);
			return;
		}
		final long wait = window - since;
		if (!accountTabsRefreshPending.compareAndSet(false, true))
		{
			// One is already on its way, and it will see this send's rows too.
			return;
		}
		final boolean scheduled = later.after(wait, () ->
		{
			accountTabsRefreshPending.set(false);
			accountTabsRefreshedAt = System.nanoTime();
			refresh(Tab.TRADES);
			refresh(Tab.JOURNAL);
		});
		if (!scheduled)
		{
			// Shutting down; the next connect re-reads everything anyway.
			accountTabsRefreshPending.set(false);
		}
	}

	/**
	 * Re-reads one tab from its endpoint and redraws it. Net thread. Never
	 * throws, because the watchlist refresh also runs on a fixed-delay
	 * schedule, where an escaping exception would cancel it for good.
	 *
	 * <p>A failure is reported on that tab, not as a failed connection: the
	 * key was good a moment ago and the fills are still going out.
	 */
	void refresh(Tab tab)
	{
		try
		{
			if (!config.enabled())
			{
				return;
			}
			final String key = FlippingRsApi.trimmedKey(config.apiKey());
			if (key.isEmpty())
			{
				return;
			}
			final String accountId = store.chosenAccountFor(client.getAccountHash());
			final PanelData part;
			switch (tab)
			{
				case TRADES:
					if (accountId == null)
					{
						return;
					}
					part = api.get().trades(key, accountId);
					break;
				case JOURNAL:
					if (accountId == null)
					{
						return;
					}
					part = api.get().journal(key, accountId, tzOffsetMinutes());
					break;
				case WATCHLISTS:
					// The journal as well as the list: buy limits are counted
					// per journal, so without one the quotes come back without
					// them. Null here is fine and simply means no limits.
					part = api.get().watchlists(key, store.rememberedWatchlistId(), accountId);
					break;
				default:
					return;
			}
			applyPanel(part, false);
		}
		catch (java.io.IOException e)
		{
			log.warn("could not refresh the {} tab: {}", tab, e.getMessage());
			final String why = FlippingRsApi.describe(e);
			panel.onPanel(p ->
			{
				switch (tab)
				{
					case TRADES:
						p.setRecentTradesProblem(why);
						break;
					case JOURNAL:
						p.setJournalProblem(why);
						break;
					case WATCHLISTS:
						p.setWatchlistProblem(why);
						break;
					default:
						break;
				}
			});
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure refreshing the {} tab", tab, e);
		}
	}

	/**
	 * The tick that keeps quotes current, while there is something to quote
	 * and somewhere it is shown.
	 *
	 * <p>Guarded for the same reason {@link TransactionSender#drain} is: this
	 * runs as a fixed-delay task, and an exception escaping one cancels it for
	 * good -- the quotes would stop refreshing for the rest of the session with
	 * nothing in the log to say why. Deciding whether there is anything to
	 * quote reads nothing that can throw today; the guard is here so that it
	 * stays true of whatever this comes to ask, because the cost of being
	 * wrong about it is silent and lasts all session.
	 */
	void quotesTick()
	{
		try
		{
			if (watchlists.wantsQuotes())
			{
				refresh(Tab.WATCHLISTS);
			}
			// And the items the exchange is showing that the watchlist does
			// not cover, which is one more request and only while the exchange
			// is open with something in it.
			watchlists.fetchOnDemand();
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure deciding whether to refresh the quotes", e);
		}
	}

	/**
	 * Shows what is still buffered for the logged-in account. Disk thread,
	 * because opening the queue reads its file.
	 */
	void refreshPending()
	{
		// Read once, here, rather than again on the disk thread: the two are
		// separated by a handoff, and a hop in between would show one
		// account's buffer under the other's name.
		final long accountHash = client.getAccountHash();
		if (accountHash == FlippingRsPlugin.NO_ACCOUNT)
		{
			panel.onPanel(p ->
			{
				p.setPending(Collections.emptyList());
				p.setCounts(recordedThisSession.getAsInt(), 0);
			});
			return;
		}
		diskThread.accept(() ->
		{
			final TransactionQueue queue = queueFor.apply(accountHash);
			final int waiting = queue.size();
			final List<GeTransaction> buffered = queue.newest(FlippingRsPanel.RECENT_SHOWN);
			final int setAside = queue.setAsideCount();
			panel.onPanel(p ->
			{
				p.setCounts(recordedThisSession.getAsInt(), waiting);
				p.setPending(buffered);
				p.setSetAside(setAside);
			});
		});
	}

	/**
	 * A different RuneScape account is now active. The journal is remembered
	 * per account, so the picker is re-pointed from the list already loaded,
	 * and an account seen for the first time adopts the default the way a
	 * fresh install does. Swing thread work, handed off from the caller.
	 *
	 * @return false if no list has been loaded yet, in which case connect will
	 *         do this when it succeeds
	 */
	boolean accountChanged()
	{
		// Before anything reads the choice. This is the first moment an
		// upgrading install has an account to move its old per-profile choice
		// to, and reading before moving it would find nothing chosen and adopt
		// the server's default over a journal the user had already picked --
		// which is the exact mix-up storing it per character exists to prevent.
		migrateIfLoggedIn();

		final List<GameAccount> accounts = knownAccounts;
		if (accounts == null)
		{
			return false;
		}
		final String chosen = store.chosenAccountFor(client.getAccountHash());
		panel.onPanel(p ->
		{
			p.setAccounts(accounts, chosen);
			if (chosen == null)
			{
				rememberChosenAccountFrom(p, false);
			}
		});
		// The recent trades, the journal and the buffer are the account's, so
		// they change with it.
		netThread.accept(() ->
		{
			refresh(Tab.TRADES);
			refresh(Tab.JOURNAL);
		});
		refreshPending();
		return true;
	}

	private static boolean contains(List<GameAccount> accounts, String id)
	{
		for (GameAccount account : accounts)
		{
			if (account != null && id.equals(account.id))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ the journal

	/**
	 * Stores the panel's selection as this RuneScape account's journal.
	 *
	 * <p>The journal is what Trades and Journal are read for, so a change of
	 * one has to re-read both. Without that, picking a different journal left
	 * the two tabs showing the previous one's rows until something else
	 * happened to refresh them -- a sidebar naming one journal over another
	 * journal's numbers, which is the one thing this panel exists to get
	 * right. The same call covers the first journal a character adopts: the
	 * adoption is handed to the Swing thread and lands after the connect that
	 * asked for it has already read both tabs and found nothing chosen.
	 *
	 * <p>And whatever is queued goes out, because the panel has been telling
	 * the user their trades are being kept safe until they pick one.
	 *
	 * @param interactive true when the user just picked it, in which case a
	 *                    choice that cannot be stored is worth telling them
	 *                    about. The automatic adoptions pass false: on a client
	 *                    started before login there is nothing to attach the
	 *                    choice to yet, and that is not something to nag over.
	 */
	void rememberChosenAccountFrom(FlippingRsPanel from, boolean interactive)
	{
		final String id = from.selectedAccountId();
		if (id == null)
		{
			return;
		}
		if (client.getAccountHash() == FlippingRsPlugin.NO_ACCOUNT)
		{
			// There is no RuneScape profile to attach the choice to yet. Saying
			// so beats writing it somewhere it will never be read back from.
			if (interactive)
			{
				from.setStatus("Log in first, so this choice can be saved for that character.",
					ColorScheme.BRAND_ORANGE);
			}
			return;
		}
		if (id.equals(store.chosenAccountFor(client.getAccountHash())))
		{
			// Repopulating the picker on every reconnect re-selects the same
			// entry, and re-reading two tabs for that would be two requests
			// for no news.
			return;
		}
		store.rememberChosenAccountFor(client.getAccountHash(), id);
		netThread.accept(() ->
		{
			refresh(Tab.TRADES);
			refresh(Tab.JOURNAL);
			// The other thing a wrong journal explains is a batch the server
			// refused over something that was never the rows' fault. Picking a
			// different one is the fix, so what was filed gets another go.
			retrySetAside.run();
			drain.run();
		});
	}

	// ---------------------------------------------------------------- helpers

	/** The machine's UTC offset, so the server's daily buckets fall on the player's calendar. */
	private static int tzOffsetMinutes()
	{
		return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000;
	}

	/** The item's sprite, or null if the client will not give one. Client thread. */
	@Nullable
	private AsyncBufferedImage spriteOf(int itemId)
	{
		try
		{
			return itemManager.getImage(itemId);
		}
		catch (RuntimeException e)
		{
			log.debug("no sprite for item {}", itemId, e);
			return null;
		}
	}
}
