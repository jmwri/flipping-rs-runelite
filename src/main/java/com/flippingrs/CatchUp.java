package com.flippingrs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.ColorScheme;

/**
 * Catching the server up on what it could not see for itself.
 *
 * <p>Two things, both reported rather than decided: the state of the open
 * slots, sent as a snapshot for the server to reconcile against the fills it
 * holds for each offer and take on any shortfall as a recovered, untimed
 * fill; and the history screen, sent as read, for the server to match
 * against the completed offers it has and take on the rest. (The third
 * catch-up, an offer that was part-filled when the plugin first saw it, is
 * the tracker's, and goes out as an adopted fill.) In every case the plugin
 * reports what the client shows and the server decides what is new.
 *
 * <p>The tick bookkeeping is the client thread's; the sends are the net
 * thread's. Nothing here is touched from anywhere else.
 */
@Slf4j
final class CatchUp
{
	/** Ticks to wait after login for the client's offer burst to settle before a snapshot. */
	private static final int LOGIN_SETTLE_TICKS = 3;
	/** Snapshots closer together than this are skipped; the state has not changed. */
	private static final long OFFER_SNAPSHOT_SECONDS = 10;
	/**
	 * After a send, rarer still: the send just told the server the state, and
	 * the reconciliation is a safety net rather than the record. This keeps
	 * the worst-case minute well inside the plugin scope's rate limit.
	 */
	private static final long OFFER_SNAPSHOT_AFTER_SEND_SECONDS = 60;
	/** The history screen fills a tick or two after it opens; give up after this many looks. */
	private static final int HISTORY_READ_ATTEMPTS = 5;
	/**
	 * "No snapshot yet". The nanoTime clock's origin is arbitrary and it may
	 * well be negative, so zero is a time it is allowed to return and cannot
	 * stand in for "never".
	 */
	private static final long NEVER = Long.MIN_VALUE;

	private final Client client;
	private final FlippingRsConfig config;
	private final ProfileStore store;
	private final Supplier<FlippingRsApi> api;
	private final PanelUpdates panel;
	/** Resolves an item's name. Client thread. */
	private final IntFunction<String> itemName;
	/** Sends the pending fills. Net thread, synchronous. */
	private final Runnable drain;
	/** Re-reads the account tabs after the server took something on. Net thread. */
	private final Runnable afterRecovery;
	/** Hands work to the net thread. */
	private final Consumer<Runnable> netThread;

	private int offerSnapshotDueTick = -1;
	private int historyReadDueTick = -1;
	private int historyReadAttempts;
	private volatile long lastOfferSnapshotAt = NEVER;

	/**
	 * The history screen as last handed to the server. Net thread only.
	 * See {@link #sendHistory}.
	 */
	@Nullable
	private String lastHistorySent;

	CatchUp(Client client, FlippingRsConfig config, ProfileStore store, Supplier<FlippingRsApi> api,
		PanelUpdates panel, IntFunction<String> itemName, Runnable drain, Runnable afterRecovery,
		Consumer<Runnable> netThread)
	{
		this.client = client;
		this.config = config;
		this.store = store;
		this.api = api;
		this.panel = panel;
		this.itemName = itemName;
		this.drain = drain;
		this.afterRecovery = afterRecovery;
		this.netThread = netThread;
	}

	// ------------------------------------------------------------ the ticks

	/** The client just logged in on this tick. Client thread. */
	void loggedIn(int tick)
	{
		offerSnapshotDueTick = tick + LOGIN_SETTLE_TICKS;
	}

	/** The Grand Exchange just opened on this tick. Client thread. */
	void exchangeOpened(int tick)
	{
		offerSnapshotDueTick = tick + 1;
	}

	/** The history screen just opened on this tick. Client thread. */
	void historyOpened(int tick)
	{
		historyReadDueTick = tick + 2;
		historyReadAttempts = 0;
	}

	/** Client thread, every game tick. */
	void tick(int tick)
	{
		if (offerSnapshotDueTick >= 0 && tick >= offerSnapshotDueTick)
		{
			offerSnapshotDueTick = -1;
			snapshotOffers(OFFER_SNAPSHOT_SECONDS);
		}
		if (historyReadDueTick >= 0 && tick >= historyReadDueTick)
		{
			readHistory(tick);
		}
	}

	/** A send just happened; snapshot the slots if it has been a while. Client thread. */
	void snapshotAfterSend()
	{
		snapshotOffers(OFFER_SNAPSHOT_AFTER_SEND_SECONDS);
	}

	// ---------------------------------------------------------- open offers

	/**
	 * Reads the open slots and hands them to the net thread. Client thread,
	 * because the offers and the baselines are read here.
	 */
	private void snapshotOffers(long minGapSeconds)
	{
		if (!config.enabled())
		{
			return;
		}
		final long now = System.nanoTime();
		if (lastOfferSnapshotAt != NEVER && now - lastOfferSnapshotAt < TimeUnit.SECONDS.toNanos(minGapSeconds))
		{
			return;
		}
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		// Read here, on the thread the slots and the baselines are read on, and
		// checked again before the send. See sendOffers.
		final long accountHash = client.getAccountHash();
		final List<FlippingRsApi.OfferState> open = new ArrayList<>();
		for (int slot = 0; slot < offers.length; slot++)
		{
			final GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			final SavedOffer saved = store.loadOffer(slot);
			final FlippingRsApi.OfferState state = new FlippingRsApi.OfferState();
			state.slot = slot;
			state.offerRef = saved == null ? null : saved.offerRef;
			state.itemId = offer.getItemId();
			state.itemName = itemName.apply(offer.getItemId());
			state.side = SavedOffer.isBuy(offer.getState()) ? "buy" : "sell";
			state.price = offer.getPrice();
			state.totalQuantity = offer.getTotalQuantity();
			state.quantitySold = offer.getQuantitySold();
			state.spent = offer.getSpent();
			state.spentEstimated = offer.getSpent() < 0;
			state.state = offer.getState().name();
			open.add(state);
		}
		lastOfferSnapshotAt = now;
		netThread.accept(() -> sendOffers(open, accountHash));
	}

	/**
	 * Net thread. A failure here costs nothing but the reconciliation; the
	 * next snapshot retries.
	 *
	 * <p>The buffer is sent first. An adopted fill still waiting in the queue
	 * is exactly the shortfall the server would otherwise recover from this
	 * snapshot, and the server's dedupe deliberately trusts a fill under an
	 * offer's own reference, so sending both would count it twice.
	 *
	 * @param accountHash whose slots these are, as read when they were read
	 */
	private void sendOffers(List<FlippingRsApi.OfferState> open, long accountHash)
	{
		try
		{
			if (!config.enabled())
			{
				return;
			}
			drain.run();
			final String key = config.apiKey().trim();
			final String accountId = store.chosenAccount();
			if (key.isEmpty() || accountId == null)
			{
				return;
			}
			if (!stillTheSameAccount(accountHash))
			{
				return;
			}
			final FlippingRsApi.Reconciliation result = api.get().submitOffers(key, accountId, open);
			if (!result.getProblems().isEmpty())
			{
				log.warn("flippingrs.com could not read {} of the open offers: {}",
					result.getProblems().size(), result.getProblems());
			}
			if (result.getRecovered() > 0)
			{
				final int recovered = result.getRecovered();
				panel.onPanel(p -> p.setActivityNotice("Recovered " + recovered + " trade(s) from your open offers that had "
					+ "been missed. They are saved without a time.", ColorScheme.BRAND_ORANGE));
				afterRecovery.run();
			}
		}
		catch (IOException e)
		{
			// A plan cap arrives here too, in the server's words, and a
			// snapshot that cannot be reconciled is worth a line on Activity
			// rather than a log entry nobody reads.
			log.warn("could not send the open offers: {}", e.getMessage());
			final String why = FlippingRsApi.describe(e);
			panel.onPanel(p -> p.setActivityNotice("Couldn't check your open offers against your journal: " + why,
				ColorScheme.BRAND_ORANGE));
		}
		catch (RuntimeException e)
		{
			// Not every failure is an IOException: a key the HTTP client will
			// not put in a header is refused before the request exists. This
			// is a one-shot task, so an escape costs only this snapshot -- but
			// it would cost it silently, and the next one would go the same way.
			log.warn("unexpected failure while sending the open offers", e);
			panel.onPanel(p -> p.setActivityNotice(
				"Something went wrong checking your open offers. Details are in the client log.",
				ColorScheme.BRAND_ORANGE));
		}
	}

	/**
	 * Whether the character whose slots or history were read is still the one
	 * logged in.
	 *
	 * <p>What was read came off the client thread; the journal it would be
	 * filed under is read here, from whichever RuneScape profile is active
	 * now, and the two are separated by a drain that can block for a whole
	 * call timeout. Pairing a mismatched two would hand one character's open
	 * offers to another character's journal, and the server's answer to a
	 * shortfall is to take it on as a recovered trade -- so the mistake would
	 * not merely be ignored, it would write the main's holdings into the alt's
	 * journal, untimed and unattributable. The sender guards its batches the
	 * same way and for the same reason. Cheaper to notice and let the next
	 * snapshot do it.
	 */
	private boolean stillTheSameAccount(long accountHash)
	{
		if (client.getAccountHash() == accountHash)
		{
			return true;
		}
		log.debug("the account changed while a catch-up was in flight; dropping it");
		return false;
	}

	// -------------------------------------------------------------- history

	/** Client thread. The history list fills a tick or two after the screen opens. */
	private void readHistory(int tick)
	{
		if (!config.enabled())
		{
			historyReadDueTick = -1;
			return;
		}
		final Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		final List<FlippingRsApi.HistoryRow> rows = GeHistoryReader.read(list, itemName);
		if (rows.isEmpty() && ++historyReadAttempts < HISTORY_READ_ATTEMPTS)
		{
			historyReadDueTick = tick + 1;
			return;
		}
		final long accountHash = client.getAccountHash();
		historyReadDueTick = -1;
		if (rows.isEmpty())
		{
			// A screen with items on it that yielded no rows means the reader
			// no longer understands the layout. Each row is logged at debug
			// as it is skipped, which nobody sees; this is the line that says
			// the catch-up has quietly stopped working.
			if (GeHistoryReader.showsItems(list))
			{
				log.warn("the Grand Exchange history screen shows items but none of its rows could be read; "
					+ "the screen's layout may have changed and the history catch-up is not working");
			}
			return;
		}
		netThread.accept(() -> sendHistory(rows, accountHash));
	}

	/**
	 * Net thread. The buffer is sent first, for the same reason as
	 * {@link #sendOffers}: a completed offer whose fills are still queued
	 * would be unmatched on the screen and added a second time.
	 *
	 * @param accountHash whose history screen this was, as read when it was read
	 */
	private void sendHistory(List<FlippingRsApi.HistoryRow> rows, long accountHash)
	{
		try
		{
			if (!config.enabled())
			{
				return;
			}
			drain.run();
			final String key = config.apiKey().trim();
			final String accountId = store.chosenAccount();
			if (key.isEmpty() || accountId == null)
			{
				return;
			}
			if (!stillTheSameAccount(accountHash))
			{
				return;
			}
			final String screen = signatureOf(accountHash, accountId, rows);
			if (screen.equals(lastHistorySent))
			{
				// The same screen the server has already been shown. Opening the
				// history is a click, and there is no gap between one open and the
				// next: without this, a user flicking between their offers and
				// their history spent a request on each one, against a limit of
				// thirty a minute that the sends themselves draw on. The screen
				// changes when an offer completes and is collected, and that is
				// exactly when this stops matching.
				log.debug("the history screen has not changed since it was last sent");
				return;
			}
			final FlippingRsApi.Reconciliation result = api.get().submitHistory(key, accountId, rows);
			lastHistorySent = screen;
			if (!result.getProblems().isEmpty())
			{
				log.warn("flippingrs.com could not read {} history row(s): {}",
					result.getProblems().size(), result.getProblems());
			}
			if (result.getAdded() > 0)
			{
				final int added = result.getAdded();
				panel.onPanel(p -> p.setActivityNotice("Recovered " + added + " trade(s) from your Grand Exchange history. "
					+ "They are saved without a time.", ColorScheme.BRAND_ORANGE));
				afterRecovery.run();
			}
		}
		catch (IOException e)
		{
			log.warn("could not send the exchange history: {}", e.getMessage());
			final String why = FlippingRsApi.describe(e);
			panel.onPanel(p -> p.setActivityNotice("Couldn't send your Grand Exchange history: " + why,
				ColorScheme.PROGRESS_ERROR_COLOR));
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure while sending the exchange history", e);
			panel.onPanel(p -> p.setActivityNotice(
				"Something went wrong sending your Grand Exchange history. Details are in the client log.",
				ColorScheme.PROGRESS_ERROR_COLOR));
		}
	}

	/**
	 * The screen as one string, for telling one screenful from another.
	 *
	 * <p>The character and the journal are in it as well as the rows, so that
	 * an alt whose history happens to read the same is still sent. Only set
	 * after a send that went through, so a failed one is tried again.
	 */
	private static String signatureOf(long accountHash, String accountId, List<FlippingRsApi.HistoryRow> rows)
	{
		final StringBuilder out = new StringBuilder(rows.size() * 24);
		out.append(accountHash).append('/').append(accountId);
		for (FlippingRsApi.HistoryRow row : rows)
		{
			out.append('|').append(row.itemId).append(',').append(row.side).append(',')
				.append(row.quantity).append(',').append(row.grossValue);
		}
		return out.toString();
	}
}
