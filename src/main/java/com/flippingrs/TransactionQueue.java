package com.flippingrs;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Fills waiting to be sent, held on disk as well as in memory.
 *
 * <p>A trade that happened is a fact, and the plugin should not lose it because
 * the network blinked or the user closed the client. So every change is written
 * through to a file, and the file is re-read on startup. The cost is a small
 * write per fill, which is nothing next to losing an evening of flips.
 *
 * <p>The file is per RuneScape account. Two accounts logged in on two clients
 * would otherwise take turns overwriting each other's pending work.
 *
 * <p>Every method is synchronised. Fills arrive on the client thread and drain
 * on a scheduler thread, and those are genuinely concurrent.
 */
@Slf4j
public class TransactionQueue
{
	/**
	 * How many fills to hold before dropping the oldest.
	 *
	 * <p>Eight slots cannot produce this in any normal session, so reaching it
	 * means something has been failing to send for a very long time. Dropping
	 * the oldest keeps the file bounded, and the alternative -- an unbounded
	 * file -- eventually fails to write at all and loses everything rather than
	 * the stalest thing.
	 */
	private static final int CAPACITY = 10_000;

	private final Deque<GeTransaction> pending = new ArrayDeque<>();
	private final Gson gson;
	private final File file;

	/** {@link #CAPACITY}, unless a test asked for a smaller one. */
	private final int capacity;

	/**
	 * How many evictions the file may run ahead by before it is compacted.
	 *
	 * <p>A removal cannot be expressed by appending, so an eviction needs the
	 * whole file rewritten. Doing that on the add that evicts sounds like it
	 * costs one rewrite in every {@link #CAPACITY} fills, and it does the first
	 * time -- but the queue does not go back under the cap afterwards, so from
	 * then on <em>every</em> fill rewrote ten thousand rows. That is the state
	 * a client is in precisely when sending has been failing for hours, and it
	 * turned each new trade into several megabytes of writing.
	 *
	 * <p>So the evicted row is left in the file and the new one appended after
	 * it, and the file is compacted every hundredth eviction instead. While the
	 * compactions are landing the file runs at most this far past the cap, and
	 * the rows it holds over are real trades that were dropped -- so a client
	 * killed in between restores a few more than it strictly had, which is the
	 * harmless direction.
	 *
	 * <p>It can run further than that when a compaction cannot be written at
	 * all: see {@link #add}, which appends rather than let the fill go
	 * unrecorded. The file comes back down on the next compaction that works.
	 */
	private final int evictionsPerRewrite;

	private int evictionsSinceRewrite;

	/**
	 * Where refused fills go. Beside the queue, named {@code dropped-} in place
	 * of {@code queue-}, so a user who is told a trade could not be recorded
	 * can still find it rather than take it on faith that it ever existed.
	 */
	private final File dropped;

	/**
	 * The rewrite's staging file. A fixed name rather than a fresh temp file per
	 * rewrite: a client killed between creating one and moving it into place
	 * used to leave a {@code queueNNNN.tmp} behind forever, and a fixed name
	 * means the next rewrite simply overwrites the orphan.
	 */
	private final File staging;

	/**
	 * How many fills are in {@link #dropped}, or -1 for "not counted yet".
	 * Counting means reading the file, and it is nearly always absent.
	 */
	private int setAside = -1;

	public TransactionQueue(Gson gson, File file)
	{
		this(gson, file, CAPACITY);
	}

	/** Test seam: lets a test reach the cap without ten thousand writes. */
	TransactionQueue(Gson gson, File file, int capacity)
	{
		this.gson = gson;
		this.file = file;
		this.capacity = capacity;
		this.evictionsPerRewrite = Math.max(1, capacity / 100);
		final String name = file.getName();
		this.dropped = new File(file.getParentFile(),
			name.startsWith("queue-") ? "dropped-" + name.substring("queue-".length()) : "dropped-" + name);
		this.staging = new File(file.getParentFile(), name + ".tmp");
		load();
	}

	public synchronized void add(GeTransaction tx)
	{
		if (pending.size() < capacity)
		{
			pending.addLast(tx);
			append(tx);
			return;
		}

		// More than one only when a file was restored from over the cap, which
		// is the compaction below running behind at the moment the client was
		// last killed.
		int evicted = 0;
		while (pending.size() >= capacity)
		{
			final GeTransaction oldest = pending.pollFirst();
			if (evicted == 0)
			{
				log.warn("queue is full at {}; dropping the oldest pending fill: {}", capacity, oldest);
			}
			evicted++;
		}
		pending.addLast(tx);

		evictionsSinceRewrite += evicted;
		if (evicted > 1 || evictionsSinceRewrite >= evictionsPerRewrite)
		{
			// A compaction that could not be written leaves the new fill
			// nowhere on disk, and the counter over the mark -- so every add
			// after it takes this branch too, and never appends either. One
			// failed rewrite would have stopped the file recording anything
			// for the rest of the session, in the state where the queue is
			// full and every trade in it is already at risk. Appending is the
			// same durability the fill would have had; the cost is that the
			// file runs further past the cap until a rewrite works, and it is
			// brought back down when one does.
			if (!rewrite())
			{
				append(tx);
			}
		}
		else
		{
			append(tx);
		}
	}

	/**
	 * Takes up to {@code max} fills for sending, leaving them in the queue.
	 *
	 * <p>They stay until {@link #confirm} says the server has them. Removing
	 * them here and putting them back on failure would lose the batch outright
	 * if the client were killed mid-send, which is exactly when a flipper is
	 * most likely to kill it.
	 */
	public synchronized List<GeTransaction> peek(int max)
	{
		final List<GeTransaction> batch = new ArrayList<>(Math.min(max, pending.size()));
		for (GeTransaction tx : pending)
		{
			if (batch.size() >= max)
			{
				break;
			}
			batch.add(tx);
		}
		return batch;
	}

	/**
	 * Drops fills the server has accepted.
	 *
	 * <p>Matched on id rather than on object identity. The two are the same
	 * within one session, but the id is what actually identifies a fill, and
	 * relying on identity would break the moment anything copied a row.
	 *
	 * <p>Only rewritten if something actually left, so a confirmation of rows
	 * that have already gone does not cost a write of the whole backlog.
	 */
	public synchronized void confirm(Collection<GeTransaction> sent)
	{
		if (sent.isEmpty())
		{
			return;
		}
		final Set<String> ids = new HashSet<>();
		for (GeTransaction tx : sent)
		{
			ids.add(tx.id);
		}
		if (pending.removeIf(tx -> ids.contains(tx.id)))
		{
			rewrite();
		}
	}

	/**
	 * Sets aside fills the server has refused for good.
	 *
	 * <p>They leave the queue, because retrying cannot help and holding them
	 * would wedge every later trade behind them. But they are appended to a
	 * sibling file rather than deleted: the plugin's promise is that a trade is
	 * never silently lost, and a row on disk that a user can read, fix and
	 * enter by hand keeps that promise where a log line does not.
	 */
	public synchronized void reject(Collection<GeTransaction> refused)
	{
		if (refused.isEmpty())
		{
			return;
		}
		if (writeDropped(refused))
		{
			if (setAside >= 0)
			{
				setAside += refused.size();
			}
		}
		else
		{
			// These are about to leave the queue whether or not they could be
			// filed -- holding them would wedge every later trade -- and the
			// panel is at this moment telling the user they were set aside
			// where they can be found. That has to be true somewhere, so if it
			// is not the file it is the log.
			for (GeTransaction tx : refused)
			{
				log.warn("refused fill that could not be written to {}: {}", dropped, gson.toJson(tx));
			}
		}
		confirm(refused);
	}

	/** @return whether the refused fills reached the sibling file */
	private boolean writeDropped(Collection<GeTransaction> refused)
	{
		final Path parent = parent();
		if (parent == null)
		{
			return false;
		}
		try
		{
			intoFolder(parent, () ->
			{
				try (Writer out = Files.newBufferedWriter(dropped.toPath(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND))
				{
					for (GeTransaction tx : refused)
					{
						out.write(gson.toJson(tx));
						out.write('\n');
					}
				}
			});
			return true;
		}
		catch (IOException e)
		{
			log.warn("could not record the refused fills in {}: {}", dropped, e.toString());
			return false;
		}
	}

	/** Where refused fills are kept. Exposed for the log line that points at it. */
	public File droppedFile()
	{
		return dropped;
	}

	/**
	 * How many fills are set aside, so the sidebar can offer to try them
	 * again.
	 *
	 * <p>Counted off the file the first time it is asked for and kept from
	 * then on, because this is read on every panel refresh and the answer is
	 * nearly always zero. A file that cannot be read counts as empty: the
	 * button it decides is an offer to retry, and offering to retry something
	 * that cannot be read would fail in a way that explains nothing.
	 */
	public synchronized int setAsideCount()
	{
		if (setAside < 0)
		{
			setAside = countDropped();
		}
		return setAside;
	}

	/**
	 * Puts the set-aside fills back in the queue for another go, and forgets
	 * the file.
	 *
	 * <p>The only reason to ask for this is that whatever the server was
	 * refusing them over has been changed: a key that was wrong, a journal
	 * that was not this owner's, a plan that had lapsed. Those are properties
	 * of the account rather than of the rows, and the rows were only ever set
	 * aside because the plugin could not tell the two apart. Retrying is safe
	 * however wrong that guess was, because every fill carries the id the
	 * server de-duplicates on.
	 *
	 * <p>The rows go into the queue before the file is forgotten, never the
	 * other way round. A client killed in between restores them twice, which
	 * the server drops as repeats; the other order loses them for good.
	 *
	 * @return how many went back, which is zero if there were none or the
	 *         file could not be read
	 */
	public synchronized int restoreSetAside()
	{
		if (!dropped.isFile())
		{
			setAside = 0;
			return 0;
		}
		final List<GeTransaction> restored = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			Files.newInputStream(dropped.toPath()), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = in.readLine()) != null)
			{
				if (line.trim().isEmpty())
				{
					continue;
				}
				final GeTransaction tx = parse(line);
				// The same bar a restored fill has to clear: without an id the
				// server could not de-duplicate it, so it can only be sent once
				// and never safely again.
				if (tx != null && tx.id != null && !tx.id.isEmpty())
				{
					restored.add(tx);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("could not read the set-aside fills in {}: {}", dropped, e.toString());
			return 0;
		}
		for (GeTransaction tx : restored)
		{
			// Through add, so each is written to the queue file as it goes and
			// the cap is honoured the same way a fresh fill is.
			add(tx);
		}
		if (!dropped.delete() && dropped.isFile())
		{
			// They are in the queue and will be sent; leaving the file would
			// only mean restoring them a second time, which the server drops.
			// Worth a line, because the count the sidebar shows comes from it.
			log.warn("restored {} set-aside fill(s) but could not remove {}", restored.size(), dropped);
			setAside = -1;
			return restored.size();
		}
		setAside = 0;
		log.info("put {} set-aside fill(s) back in the queue", restored.size());
		return restored.size();
	}

	/** Lines in the set-aside file, or zero if there is not one to read. */
	private int countDropped()
	{
		if (!dropped.isFile())
		{
			return 0;
		}
		int rows = 0;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			Files.newInputStream(dropped.toPath()), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = in.readLine()) != null)
			{
				if (!line.trim().isEmpty())
				{
					rows++;
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("could not count the set-aside fills in {}", dropped, e);
			return 0;
		}
		return rows;
	}

	/**
	 * The most recently added fills, newest first, for showing what is still
	 * buffered. A copy; the queue is not touched.
	 */
	public synchronized List<GeTransaction> newest(int max)
	{
		final List<GeTransaction> out = new ArrayList<>(Math.min(max, pending.size()));
		final java.util.Iterator<GeTransaction> it = pending.descendingIterator();
		while (it.hasNext() && out.size() < max)
		{
			out.add(it.next());
		}
		return out;
	}

	public synchronized int size()
	{
		return pending.size();
	}

	public synchronized boolean isEmpty()
	{
		return pending.isEmpty();
	}

	/**
	 * Reads the queue back, tolerating damage.
	 *
	 * <p>Two formats are accepted. The current one is JSON Lines, one fill per
	 * line; the leading '[' of the original whole-array format is recognised so
	 * that upgrading does not silently throw away whatever was still pending.
	 *
	 * <p>Lines are parsed individually and a bad one is skipped rather than
	 * failing the file. That is the point of the line format as much as the
	 * append is: a process killed mid-append leaves a torn final line, and this
	 * costs that one fill instead of the entire backlog.
	 *
	 * <p>Read a line at a time rather than whole. Slurping the file gave a
	 * String of the entire backlog, a trimmed second copy of it to look for
	 * the legacy '[', and then an array of every line on top of that -- all
	 * before the first fill was parsed, and all on the path a client takes
	 * while it is starting up.
	 */
	private void load()
	{
		if (!file.isFile())
		{
			return;
		}
		try
		{
			if (startsAnArray())
			{
				loadLegacyArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
				// Written back in the new format so the next add can append.
				rewrite();
				return;
			}
			int damaged = 0;
			try (BufferedReader in = reader())
			{
				String line;
				while ((line = in.readLine()) != null)
				{
					if (line.trim().isEmpty())
					{
						continue;
					}
					if (!accept(parse(line)))
					{
						damaged++;
					}
				}
			}
			if (damaged > 0)
			{
				log.warn("skipped {} unreadable line(s) in {}", damaged, file);
			}
			log.debug("restored {} pending fills from {}", pending.size(), file);
		}
		catch (IOException | RuntimeException e)
		{
			// Losing part of the queue is bad; refusing to build one at all is
			// worse. This runs in the constructor, which is on the path every
			// fill takes, so an exception out of here is not a queue that comes
			// back short -- it is an account with no queue at all, and every
			// fill from then on lost, for as long as the file stays as it is.
			log.warn("could not finish reading the pending queue at {}; keeping the {} fill(s) read so far: {}",
				file, pending.size(), e.toString());
		}
	}

	/**
	 * Whether the file is the original whole-array format, from its first
	 * character that is not whitespace.
	 */
	private boolean startsAnArray() throws IOException
	{
		try (BufferedReader in = reader())
		{
			int c;
			while ((c = in.read()) != -1)
			{
				if (!Character.isWhitespace(c))
				{
					return c == '[';
				}
			}
		}
		return false;
	}

	/**
	 * The file as characters.
	 *
	 * <p>Built by hand rather than through {@code Files.newBufferedReader},
	 * which reports a malformed byte as an IOException. That would lose the
	 * whole backlog to one corrupt byte, where this substitutes it and costs
	 * only the line it is on -- the same tolerance the rest of this method has.
	 */
	private BufferedReader reader() throws IOException
	{
		return new BufferedReader(
			new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8));
	}

	private void loadLegacyArray(String content)
	{
		try
		{
			final GeTransaction[] saved = gson.fromJson(content, GeTransaction[].class);
			if (saved != null)
			{
				for (GeTransaction tx : saved)
				{
					accept(tx);
				}
			}
		}
		catch (JsonParseException e)
		{
			log.warn("could not read the pending queue at {}, starting empty: {}", file, e.toString());
		}
	}

	@Nullable
	private GeTransaction parse(String line)
	{
		try
		{
			return gson.fromJson(line, GeTransaction.class);
		}
		catch (JsonParseException e)
		{
			// The family, not the one member of it Gson happens to raise for
			// the damage seen so far. Anything Gson refuses has to come out
			// here as a line skipped: the guard around the whole read is a
			// backstop, and reaching it costs every line after this one as
			// well as this one.
			return null;
		}
	}

	/**
	 * Keeps a restored fill, unless it has no id.
	 *
	 * <p>An id-less row could never be de-duplicated by the server, so sending
	 * it risks counting a trade twice -- which is the one outcome worse than
	 * losing it.
	 */
	private boolean accept(@Nullable GeTransaction tx)
	{
		if (tx == null || tx.id == null || tx.id.isEmpty())
		{
			return false;
		}
		pending.addLast(tx);
		return true;
	}

	/**
	 * Appends one fill. This is the hot path and it is O(1) in the size of the
	 * queue.
	 *
	 * <p>It used to serialise and rewrite the entire deque on every add, which
	 * is quadratic in the number of fills waiting: filling the queue to its
	 * 10,000 cap wrote about twelve gigabytes to get there. Appending writes one
	 * line regardless of how much is already pending.
	 */
	private void append(GeTransaction tx)
	{
		final Path parent = parent();
		if (parent == null)
		{
			return;
		}
		final byte[] line = (gson.toJson(tx) + "\n").getBytes(StandardCharsets.UTF_8);
		try
		{
			intoFolder(parent, () -> Files.write(file.toPath(), line,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND));
		}
		catch (IOException e)
		{
			// The fill is still in memory and the next rewrite will persist it,
			// so this is a durability gap rather than a lost trade.
			log.warn("could not append to the pending queue at {}: {}", file, e.toString());
		}
	}

	/** A write that may find the folder it writes into is not there. */
	@FunctionalInterface
	private interface Write
	{
		void run() throws IOException;
	}

	/**
	 * Runs a write, making the folder if that turns out to be what was missing.
	 *
	 * <p>The folder is only absent on the very first write of a fresh install,
	 * or if someone tidies it away under a running client, so it is asked for
	 * when a write says it is needed rather than before every write. Confirming
	 * it each time cost as much as the write itself -- 264 microseconds against
	 * an append's 255, measured -- to establish something that had been true
	 * all session, and every fill pays the append.
	 */
	private static void intoFolder(Path parent, Write write) throws IOException
	{
		try
		{
			write.run();
		}
		catch (NoSuchFileException missing)
		{
			Files.createDirectories(parent);
			write.run();
		}
	}

	/**
	 * Writes the whole queue out. Needed whenever fills are removed, which an
	 * append cannot express: after a confirmed send, and on the one add in every
	 * CAPACITY that has to evict.
	 *
	 * @return whether the file now matches the queue
	 */
	private boolean rewrite()
	{
		final Path parent = parent();
		if (parent == null)
		{
			return false;
		}
		try
		{
			// Write beside the target and move it into place, so a client killed
			// mid-rewrite leaves the previous good queue rather than half a file.
			//
			// Straight out to the file, a line at a time. Building the whole
			// thing as a String first meant a full backlog existed three times
			// over at once -- the builder, the String it copies to, and the
			// bytes that copies to -- on a client whose heap is 768M and which
			// is drawing a game at the same time.
			final Path temp = staging.toPath();
			intoFolder(parent, () ->
			{
				try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8))
				{
					for (GeTransaction tx : pending)
					{
						out.write(gson.toJson(tx));
						out.write('\n');
					}
				}
			});
			try
			{
				Files.move(temp, file.toPath(),
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			// Only now: the file matches the deque, so the rows evictions have
			// been leaving behind are gone. Resetting before the write would
			// have a failed one look like a compaction and leave the file
			// growing for another hundred evictions.
			evictionsSinceRewrite = 0;
			return true;
		}
		catch (IOException e)
		{
			// Keep the in-memory queue and keep going: the fills are still there
			// for this session, and the next write may work.
			log.warn("could not persist the pending queue to {}: {}", file, e.toString());
			return false;
		}
	}

	@Nullable
	private Path parent()
	{
		final Path parent = file.toPath().getParent();
		if (parent == null)
		{
			// A bare filename has no directory to write the temporary file
			// beside, and the plugin never builds one, so this is a bug rather
			// than a condition to work around.
			log.warn("queue path {} has no parent directory; not persisting", file);
		}
		return parent;
	}
}
