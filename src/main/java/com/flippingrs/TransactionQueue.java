package com.flippingrs;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
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
	 * it, and the file is compacted every hundredth eviction instead. The file
	 * runs at most this far past the cap, and the rows it holds over are real
	 * trades that were dropped -- so a client killed in between restores a few
	 * more than it strictly had, which is the harmless direction.
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
			rewrite();
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
		pending.removeIf(tx -> ids.contains(tx.id));
		rewrite();
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
		final Path parent = parent();
		if (parent != null)
		{
			try
			{
				Files.createDirectories(parent);
				try (Writer out = Files.newBufferedWriter(dropped.toPath(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND))
				{
					for (GeTransaction tx : refused)
					{
						out.write(gson.toJson(tx));
						out.write('\n');
					}
				}
			}
			catch (IOException e)
			{
				log.warn("could not record the refused fills in {}: {}", dropped, e.toString());
			}
		}
		confirm(refused);
	}

	/** Where refused fills are kept. Exposed for the log line that points at it. */
	public File droppedFile()
	{
		return dropped;
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
		catch (IOException e)
		{
			// Losing the queue is bad; refusing to start because of it is worse,
			// because then nothing is recorded from here on either.
			log.warn("could not read the pending queue at {}, starting empty: {}", file, e.toString());
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
		catch (JsonSyntaxException e)
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
		catch (JsonSyntaxException e)
		{
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
		try
		{
			Files.createDirectories(parent);
			Files.write(file.toPath(), (gson.toJson(tx) + "\n").getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException e)
		{
			// The fill is still in memory and the next rewrite will persist it,
			// so this is a durability gap rather than a lost trade.
			log.warn("could not append to the pending queue at {}: {}", file, e.toString());
		}
	}

	/**
	 * Writes the whole queue out. Needed whenever fills are removed, which an
	 * append cannot express: after a confirmed send, and on the one add in every
	 * CAPACITY that has to evict.
	 */
	private void rewrite()
	{
		final Path parent = parent();
		if (parent == null)
		{
			return;
		}
		try
		{
			Files.createDirectories(parent);
			evictionsSinceRewrite = 0;
			// Write beside the target and move it into place, so a client killed
			// mid-rewrite leaves the previous good queue rather than half a file.
			//
			// Straight out to the file, a line at a time. Building the whole
			// thing as a String first meant a full backlog existed three times
			// over at once -- the builder, the String it copies to, and the
			// bytes that copies to -- on a client whose heap is 768M and which
			// is drawing a game at the same time.
			final Path temp = staging.toPath();
			try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8))
			{
				for (GeTransaction tx : pending)
				{
					out.write(gson.toJson(tx));
					out.write('\n');
				}
			}
			try
			{
				Files.move(temp, file.toPath(),
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			// Keep the in-memory queue and keep going: the fills are still there
			// for this session, and the next write may work.
			log.warn("could not persist the pending queue to {}: {}", file, e.toString());
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
