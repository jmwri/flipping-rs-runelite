package com.flippingrs;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;

/**
 * Getting the queued fills to flippingrs.com, and saying what became of them.
 *
 * <p>This is where the plugin's three promises are actually kept. Fills are
 * held on disk by {@link TransactionQueue} until the server confirms them, so
 * a dropped connection costs nothing; every fill carries an id the server
 * de-duplicates on, so a retry is free; and a batch the server refuses for
 * good is set aside in a file rather than dropped, so nothing is ever lost
 * without somebody being told where it went.
 *
 * <p>One queue per RuneScape account, because which FlippingRS journal a trade
 * belongs to is remembered per account: a main's fills and an alt's cannot go
 * in the same batch or under the same id.
 *
 * <p>Net thread. {@link #drain} never throws, because the plugin runs it as a
 * fixed-delay task and an exception escaping one of those cancels it for
 * good -- the plugin would go quiet with nothing in the log to say why.
 */
@Slf4j
final class TransactionSender
{
	/** Matches the server's cap on one ingest call. */
	private static final int MAX_BATCH = 500;

	private final Client client;
	private final FlippingRsConfig config;
	private final ProfileStore store;
	private final Supplier<FlippingRsApi> api;
	private final PanelUpdates panel;
	private final Gson gson;
	/** Where the pending-queue files live: RuneLite's folder, or a test's. */
	private final File queueDir;
	/** What a successful send sets going, when anyone can see the result. Net thread. */
	private final Runnable afterSend;
	/** Whether the client or the plugin is stopping, so a send is the last thing worth doing. */
	private final BooleanSupplier shuttingDown;
	/** How many fills have been captured this session, for the Activity tab's count. */
	private final IntSupplier recordedThisSession;
	/** Gets the user's attention when a trade could not be recorded. */
	private final Consumer<String> notifier;

	/**
	 * Pending fills, one queue per RuneScape account.
	 *
	 * <p>Separate queues because the FlippingRS journal a trade belongs to is
	 * remembered per RuneScape account, so a main's fills and an alt's cannot be
	 * sent in the same batch or under the same id. It also stops two clients
	 * logged into two accounts overwriting each other's file.
	 */
	private final Map<Long, TransactionQueue> queues = new ConcurrentHashMap<>();

	/**
	 * Guards the sender against re-entry. Every drain -- the scheduled tick,
	 * the panel's button, the offer and history catch-ups, shutdown -- runs on
	 * the plugin's net executor, which has one thread, so today this is never
	 * contended. It stays because the invariant it protects matters: two
	 * threads draining the same queue would send the same batch twice, and
	 * while the server would drop the repeat, the panel's counts would be
	 * nonsense. A future caller on another thread hits this rather than that.
	 */
	private final AtomicBoolean sending = new AtomicBoolean();

	@Nullable
	// Written on the net thread, read on the Swing thread. Without volatile the
	// panel can keep showing a stale "last sent" indefinitely.
	private volatile Instant lastSyncAt;

	/**
	 * The account hashes with a queue file, as the folder had them when this
	 * session first looked. Null until then. Net thread only.
	 */
	@Nullable
	private List<Long> scanned;

	TransactionSender(Client client, FlippingRsConfig config, ProfileStore store, Supplier<FlippingRsApi> api,
		PanelUpdates panel, Gson gson, File queueDir, Runnable afterSend, BooleanSupplier shuttingDown,
		IntSupplier recordedThisSession, Consumer<String> notifier)
	{
		this.client = client;
		this.config = config;
		this.store = store;
		this.api = api;
		this.panel = panel;
		this.gson = gson;
		this.queueDir = queueDir;
		this.afterSend = afterSend;
		this.shuttingDown = shuttingDown;
		this.recordedThisSession = recordedThisSession;
		this.notifier = notifier;
	}

	/**
	 * Forgets what belonged to the last time the plugin ran. The queues are
	 * deliberately kept: they are what is on disk for each account, and
	 * re-reading every file on a disable-and-enable would be work for nothing.
	 */
	void newSession()
	{
		lastSyncAt = null;
		// Looked for again, because a queue file may have appeared since: a
		// second client, or this one while the plugin was switched off.
		scanned = null;
	}

	@Nullable
	Instant lastSyncAt()
	{
		return lastSyncAt;
	}

	TransactionQueue queueFor(long accountHash)
	{
		return queues.computeIfAbsent(accountHash,
			hash -> new TransactionQueue(gson, new File(queueDir, "queue-" + hash + ".json")));
	}

	/**
	 * Sends whatever is waiting, for every account that has anything waiting.
	 *
	 * <p>The account that is logged in first, because that is the one whose
	 * trades are happening now and whose panel is showing the result. Then any
	 * other account with a queue on disk: an alt played while the network was
	 * down, or before a key was entered, leaves fills behind, and they used to
	 * sit there until that character next logged in -- which for a retired alt
	 * is never. The journal each queue files under is remembered per account
	 * rather than per RuneScape profile precisely so that this is answerable
	 * while somebody else is logged in.
	 *
	 * <p>Never throws. The plugin runs it as a {@code scheduleWithFixedDelay}
	 * task, and an exception escaping such a task cancels it for good -- the
	 * plugin would go quiet with nothing in the log to say why.
	 */
	void drain()
	{
		// The claim above that this never throws has to hold for every line of
		// it, so the only thing outside the try is the one statement that
		// cannot throw. Reading a setting goes through a config proxy, and an
		// exception from that used to escape and cancel the schedule.
		if (!sending.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			// Checked before anything else, and before connect's equivalent
			// check, because "Record trades" being off is a promise that the
			// plugin is not talking to flippingrs.com at all -- not merely that
			// it has stopped capturing. Anything already queued stays on disk
			// and goes out when recording is turned back on; it was captured
			// while the user wanted it recorded, so discarding it would be its
			// own kind of surprise.
			if (!config.enabled())
			{
				return;
			}
			final String key = FlippingRsApi.trimmedKey(config.apiKey());
			if (key.isEmpty())
			{
				panel.onPanel(p -> p.setStatus(
					"No API key yet. Add one in the plugin settings. Your trades are being kept safe until you do.",
					ColorScheme.BRAND_ORANGE));
				return;
			}

			final long loggedIn = client.getAccountHash();
			if (loggedIn != FlippingRsPlugin.NO_ACCOUNT)
			{
				// The one whose panel is on screen, so it reports.
				drainOne(loggedIn, key, true);
			}
			for (long accountHash : otherAccounts(loggedIn))
			{
				// Nobody is looking at these, and no panel line would be true
				// of them, so they go out quietly.
				drainOne(accountHash, key, false);
			}
		}
		catch (IOException e)
		{
			// Worth retrying: the batch stays queued for the next tick.
			log.debug("could not send to flippingrs.com; will retry", e);
			final String why = FlippingRsApi.describe(e);
			panel.onPanel(p -> p.setLastSync(null, why));
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure while sending", e);
			panel.onPanel(p -> p.setLastSync(null, "Something went wrong while sending. Details are in the client log."));
		}
		finally
		{
			sending.set(false);
		}
	}

	/**
	 * Sends one account's queue.
	 *
	 * @param reporting whether what happened is worth putting on the panel.
	 *                  True only for the account that is logged in: the counts,
	 *                  the buffer and "last sent" are all that account's, and
	 *                  filling them in from an alt's backlog would describe a
	 *                  character the user is not playing.
	 */
	private void drainOne(long accountHash, String key, boolean reporting) throws IOException
	{
		final TransactionQueue queue = queueFor(accountHash);
		if (queue.isEmpty())
		{
			return;
		}
		final String accountId = store.chosenAccountFor(accountHash);
		if (accountId == null)
		{
			if (reporting)
			{
				panel.onPanel(p -> p.setStatus(
					"No journal chosen for this character yet. Pick one on the Account tab. Your trades are being "
						+ "kept safe until you do.",
					ColorScheme.BRAND_ORANGE));
			}
			// For an account nobody is logged into this is not worth saying
			// anything about: it was never given a journal, and it cannot be
			// given one until it is played again.
			return;
		}

		final List<GeTransaction> batch = queue.peek(MAX_BATCH);

		final Sent sent = new Sent();
		attempt(queue, key, accountId, batch, sent);

		if (sent.accepted > 0)
		{
			lastSyncAt = Instant.now();
		}
		final int waiting = queue.size();
		final List<GeTransaction> buffered = queue.newest(FlippingRsPanel.RECENT_SHOWN);

		log.debug("sent {} fills for account {}: {} flips opened, {} closed, {} unmatched",
			sent.accepted, accountHash, sent.flipsOpened, sent.flipsClosed, sent.unmatchedSellQty);

		// A 200 can still refuse individual rows, and the batch is dropped
		// from the queue regardless -- so if this is not surfaced here, the
		// trade is gone and nobody is ever told. Silently losing one is far
		// worse than a blunt warning, because the journal then disagrees
		// with what the player remembers doing and nothing explains why.
		if (sent.rejected > 0)
		{
			log.warn("flippingrs.com refused {} of {} fills: {}", sent.rejected, batch.size(), sent.problems);
		}
		if (sent.setAside > 0)
		{
			log.warn("set aside {} fills that flippingrs.com will not accept; they are in {}",
				sent.setAside, queue.droppedFile(), sent.cause);
		}

		// Only for the account being played. What this sets going -- a snapshot
		// of the open slots, and a re-read of the two journal tabs -- is all
		// about the character that is logged in, and none of it describes the
		// alt whose backlog just went out. Firing it for one would spend two
		// requests re-reading a journal that has not changed, and take a slot
		// snapshot of somebody else's exchange.
		if (reporting && sent.accepted > 0 && !shuttingDown.getAsBoolean())
		{
			afterSend.run();
		}

		// Said out loud, whichever account it was and whether or not anyone is
		// looking at the sidebar. A trade that did not reach the journal is
		// the one thing here that silence actively harms: the numbers are
		// wrong from then on and nothing on screen explains why.
		if (sent.setAside > 0)
		{
			notifier.accept("FlippingRS couldn't record " + sent.setAside
				+ " trade(s). They have been set aside so nothing is lost -- see the Activity tab.");
		}
		else if (sent.rejected > 0)
		{
			notifier.accept("FlippingRS couldn't record " + sent.rejected
				+ " trade(s), and they won't be sent again. See the Activity tab.");
		}

		if (!reporting)
		{
			return;
		}

		final Instant syncedAt = lastSyncAt;
		final String droppedFile = whereItIs(queue.droppedFile());
		final int stillAside = queue.setAsideCount();
		panel.onPanel(p -> {
			p.setCounts(recordedThisSession.getAsInt(), waiting);
			p.setPending(buffered);
			p.setSetAside(stillAside);
			if (sent.accepted > 0)
			{
				p.setLastSync(syncedAt, null);
				p.setStatus("Connected and recording.", ColorScheme.PROGRESS_COMPLETE_COLOR);
			}
			else
			{
				p.setLastSync(null, FlippingRsApi.describe(sent.cause));
			}
			if (sent.setAside > 0)
			{
				p.setActivityNotice("flippingrs.com couldn't accept " + sent.setAside + " trade(s). They have been "
					+ "set aside in " + droppedFile + " in your RuneLite folder so nothing is lost. The client log "
					+ "says why.", ColorScheme.PROGRESS_ERROR_COLOR);
			}
			else if (sent.rejected > 0)
			{
				// Not "set aside": the reply says how many rows it refused, not
				// which, so there is nothing to file. This notice is the only
				// time anybody is told, so it has to say that they are gone.
				p.setActivityNotice("flippingrs.com couldn't record " + sent.rejected
					+ " trade(s), and they won't be sent again. The client log says why.",
					ColorScheme.PROGRESS_ERROR_COLOR);
			}
			else if (sent.unmatchedSellQty > 0)
			{
				p.setActivityNotice(sent.unmatchedSellQty
						+ " item(s) were sold without a recorded purchase, so they can't be counted as a flip yet.",
					ColorScheme.BRAND_ORANGE);
			}
			// A send with nothing to report leaves whatever is up alone.
			// Clearing here wiped every notice, and the one it wiped most
			// reliably was the explanation of an adopted offer: adopting
			// queues a recovered fill, so the very next send is the one
			// carrying it, and it removed the sentence saying why that
			// trade has no purchase behind it. Every notice expires on its
			// own after twenty seconds, which is what that interval is for.
		});
	}

	/**
	 * Puts every account's set-aside fills back in its queue and sends them.
	 *
	 * <p>Nothing retries a permanent refusal on its own, and it should not:
	 * the server said this batch was bad, and asking again every thirty
	 * seconds would spend the rate limit to be told so. But the likeliest
	 * cause is not a bad row at all -- it is a key that was wrong, a journal
	 * that was not this owner's, or a plan that had lapsed -- and those are
	 * exactly the things a user goes and fixes. So this runs when one of them
	 * changes, and from a button on the Activity tab.
	 *
	 * <p>Safe however wrong the original guess was: every fill carries the id
	 * the server de-duplicates on, so a row that did land is dropped as a
	 * repeat rather than doubled.
	 */
	void retrySetAside()
	{
		int restored = 0;
		for (long accountHash : allAccounts())
		{
			restored += queueFor(accountHash).restoreSetAside();
		}
		if (restored == 0)
		{
			return;
		}
		final int count = restored;
		panel.onPanel(p -> p.setActivityNotice(
			"Put " + count + " set-aside trade(s) back in the queue to try again.", ColorScheme.BRAND_ORANGE));
		drain();
	}

	/** How many fills are set aside for the logged-in account, for the sidebar's offer. */
	int setAsideCount()
	{
		final long accountHash = client.getAccountHash();
		if (accountHash == FlippingRsPlugin.NO_ACCOUNT)
		{
			return 0;
		}
		return queueFor(accountHash).setAsideCount();
	}

	/** Every account with a queue, the logged-in one included. */
	private List<Long> allAccounts()
	{
		final long loggedIn = client.getAccountHash();
		final List<Long> all = new ArrayList<>(otherAccounts(loggedIn));
		if (loggedIn != FlippingRsPlugin.NO_ACCOUNT && !all.contains(loggedIn))
		{
			all.add(loggedIn);
		}
		return all;
	}

	/**
	 * The other accounts with a queue file, so a backlog left by a character
	 * nobody is playing still goes out.
	 *
	 * <p>The folder is read once a session. A queue file is only written for
	 * an account that has actually traded, so there are as many of these as
	 * the user has characters, and re-reading the folder on every tick to
	 * learn the same thing would be a directory listing every thirty seconds
	 * for the life of the client.
	 */
	private List<Long> otherAccounts(long loggedIn)
	{
		if (scanned == null)
		{
			scanned = scan();
		}
		final List<Long> out = new ArrayList<>();
		for (long hash : scanned)
		{
			if (hash != loggedIn)
			{
				out.add(hash);
			}
		}
		// A queue opened since the scan -- an account logged into this session
		// and then logged out of -- is known without it.
		for (Long hash : queues.keySet())
		{
			if (hash != loggedIn && !out.contains(hash))
			{
				out.add(hash);
			}
		}
		return out;
	}

	/** The account hashes with a queue file in the folder. Never null, possibly empty. */
	private List<Long> scan()
	{
		final List<Long> found = new ArrayList<>();
		final File[] files = queueDir.listFiles();
		if (files == null)
		{
			// No folder yet, which is every install that has not traded.
			return found;
		}
		for (File file : files)
		{
			final String name = file.getName();
			if (!name.startsWith("queue-") || !name.endsWith(".json"))
			{
				continue;
			}
			try
			{
				found.add(Long.parseLong(name.substring("queue-".length(), name.length() - ".json".length())));
			}
			catch (NumberFormatException e)
			{
				// Not one of ours, or a name that has been meddled with.
				log.debug("ignoring {} in the queue folder", name);
			}
		}
		return found;
	}

	/**
	 * Where to tell someone a set-aside file is, relative to the RuneLite
	 * folder the panel names.
	 *
	 * <p>The file sits in a subfolder, and naming it alone sent a user who had
	 * just been told nothing was lost to look in the wrong place -- from where
	 * the only reasonable conclusion is that it was.
	 */
	private static String whereItIs(File file)
	{
		final File folder = file.getParentFile();
		return folder == null ? file.getName() : folder.getName() + "/" + file.getName();
	}

	/** What one drain achieved, added up over however many sends it took. */
	private static final class Sent
	{
		/** Rows the server took, whatever it then made of them. */
		int accepted;
		/** Rows the server took and then refused individually, in a 200. They are gone. */
		int rejected;
		/** Rows set aside on disk after a refusal that retrying cannot fix. */
		int setAside;
		int flipsOpened;
		int flipsClosed;
		long unmatchedSellQty;
		final List<String> problems = new ArrayList<>();
		/** The last permanent refusal, for the panel and the log. */
		@Nullable
		FlippingRsApi.PermanentException cause;

		void took(int rows, IngestResult result)
		{
			accepted += rows;
			rejected += result.getRejected();
			flipsOpened += result.getFlipsOpened();
			flipsClosed += result.getFlipsClosed();
			unmatchedSellQty += result.getUnmatchedSellQty();
			problems.addAll(result.getProblems());
		}
	}

	/**
	 * Sends a batch, and goes looking for the rows at fault if the server
	 * refuses it for good. Retrying a refusal cannot help, and leaving it at
	 * the head of the queue would wedge every later trade behind it forever.
	 */
	private void attempt(TransactionQueue queue, String key, String accountId, List<GeTransaction> batch, Sent sent)
		throws IOException
	{
		if (!send(queue, key, accountId, batch, sent))
		{
			narrow(queue, key, accountId, batch, sent);
		}
	}

	/**
	 * One send of one batch. On a 2xx the rows are confirmed out of the queue
	 * and the result is added up.
	 *
	 * @return false if the server refused the batch for good, with the cause
	 *         recorded on {@code sent}; anything retryable propagates
	 */
	private boolean send(TransactionQueue queue, String key, String accountId, List<GeTransaction> batch, Sent sent)
		throws IOException
	{
		try
		{
			final IngestResult result = api.get().submit(key, accountId, batch);
			queue.confirm(batch);
			sent.took(batch.size(), result);
			return true;
		}
		catch (FlippingRsApi.PermanentException e)
		{
			sent.cause = e;
			return false;
		}
	}

	/**
	 * Finds the rows behind a refused batch and sets exactly those aside.
	 *
	 * <p>A 400 or 422 says the server will not take this batch. It does not
	 * say which row is at fault, and setting aside five hundred fills for one
	 * bad row is a lot of journal to lose. So a refused batch of more than one
	 * is split in half and each half sent on its own; one bad row is found in
	 * about nine rounds of that, and every good row goes through.
	 *
	 * <p>Both halves being refused is the case that has to be decided rather
	 * than assumed, because it has two readings. Either there is a bad row in
	 * each half, where halving further would save the good ones, or this is a
	 * batch that nothing will take -- a journal id that is not this owner's, a
	 * lapsed plan -- where halving further asks about every row to be told the
	 * same thing each time, and would spend the whole rate limit every sync
	 * for as long as the setting stays wrong.
	 *
	 * <p>So one row is sent on its own to tell them apart. If the server takes
	 * it, this is provably not a refusal of everything, and the halving carries
	 * on to find the bad rows and save the good ones beside them. If it does
	 * not, the blanket refusal is assumed and the batch is set aside whole,
	 * which is where this stopped before the probe existed. The probe costs one
	 * extra request in the case that ends in a set-aside anyway, and buys back
	 * every good row in the case that does not.
	 *
	 * <p>The batch is the one that was sent, not a fresh read of the queue.
	 * Fills arrive on the disk thread while a request is in flight, and peek
	 * returns from the head, so re-reading would set aside trades that had
	 * never been sent.
	 *
	 * @param batch a batch the server has just refused as a whole
	 */
	private void narrow(TransactionQueue queue, String key, String accountId, List<GeTransaction> batch, Sent sent)
		throws IOException
	{
		if (batch.size() <= 1)
		{
			setAside(queue, batch, sent);
			return;
		}
		final int mid = batch.size() / 2;
		final List<GeTransaction> first = batch.subList(0, mid);
		final List<GeTransaction> second = batch.subList(mid, batch.size());
		final boolean firstTaken = send(queue, key, accountId, first, sent);
		final boolean secondTaken = send(queue, key, accountId, second, sent);
		if (firstTaken && secondTaken)
		{
			// Both halves went through on their own, so what was refused was
			// the size of the whole rather than anything in it. Nothing is at
			// fault and nothing is set aside.
			return;
		}
		if (!firstTaken && !secondTaken)
		{
			if (first.size() == 1)
			{
				// The first half was already a single row sent on its own, and
				// it was refused: that row is bad on its own account, not
				// because of the company it was in. There is nothing left to
				// probe with, and the other half is still worth searching.
				setAside(queue, first, sent);
				narrow(queue, key, accountId, second, sent);
				return;
			}
			if (send(queue, key, accountId, first.subList(0, 1), sent))
			{
				// The server took a row out of this batch on its own, so it is
				// not refusing everything. There is a bad row in each half, and
				// the good rows around them are worth the requests.
				attempt(queue, key, accountId, first.subList(1, first.size()), sent);
				attempt(queue, key, accountId, second, sent);
				return;
			}
			setAside(queue, batch, sent);
			return;
		}
		if (!firstTaken)
		{
			narrow(queue, key, accountId, first, sent);
		}
		if (!secondTaken)
		{
			narrow(queue, key, accountId, second, sent);
		}
	}

	/**
	 * Takes refused fills out of the queue and onto the sibling file, where a
	 * user who is told a trade could not be recorded can still find it.
	 */
	private static void setAside(TransactionQueue queue, List<GeTransaction> batch, Sent sent)
	{
		queue.reject(batch);
		sent.setAside += batch.size();
	}
}
