package com.flippingrs;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The queue is what stands between a network blip and a lost evening of flips,
 * so its two promises are worth pinning: nothing leaves before the server has
 * it, and nothing is lost across a restart.
 */
public class TransactionQueueTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final Gson gson = new Gson();

	private static GeTransaction fill(String id)
	{
		final GeTransaction tx = new GeTransaction();
		tx.id = id;
		tx.itemId = 4151;
		tx.itemName = "Abyssal whip";
		tx.side = "buy";
		tx.quantity = 1;
		tx.grossValue = 1000;
		tx.occurredAt = "2026-08-31T12:00:00Z";
		return tx;
	}

	private File file() throws IOException
	{
		return new File(folder.newFolder("flippingrs"), "queue-1.json");
	}

	@Test
	public void peekingDoesNotRemove() throws IOException
	{
		final TransactionQueue queue = new TransactionQueue(gson, file());
		queue.add(fill("a"));
		queue.add(fill("b"));

		assertEquals(2, queue.peek(10).size());
		assertEquals("peek must leave the fills in place until the server has them",
			2, queue.size());
	}

	@Test
	public void confirmingRemovesOnlyWhatWasSent() throws IOException
	{
		final TransactionQueue queue = new TransactionQueue(gson, file());
		queue.add(fill("a"));
		queue.add(fill("b"));
		queue.add(fill("c"));

		final List<GeTransaction> batch = queue.peek(2);
		queue.confirm(batch);

		assertEquals(1, queue.size());
		assertEquals("c", queue.peek(10).get(0).id);
	}

	/**
	 * Confirmation matches on id, not on object identity, so a batch that has
	 * been round-tripped through JSON still clears the right rows.
	 */
	@Test
	public void confirmingMatchesOnId() throws IOException
	{
		final TransactionQueue queue = new TransactionQueue(gson, file());
		queue.add(fill("a"));

		queue.confirm(Collections.singletonList(fill("a")));

		assertTrue(queue.isEmpty());
	}

	@Test
	public void refusedFillsAreSetAsideNotDeleted() throws IOException
	{
		final File file = file();
		final TransactionQueue queue = new TransactionQueue(gson, file);
		queue.add(fill("a"));
		queue.add(fill("b"));

		queue.reject(queue.peek(1));

		assertEquals("the refused fill must not wedge the queue", 1, queue.size());
		assertEquals("b", queue.peek(10).get(0).id);

		final File dropped = new File(file.getParentFile(), "dropped-1.json");
		final String kept = new String(Files.readAllBytes(dropped.toPath()), StandardCharsets.UTF_8);
		assertTrue("the refused fill must still be readable on disk", kept.contains("\"id\":\"a\""));
		assertEquals("and it must not come back on restart", 1, new TransactionQueue(gson, file).size());
	}

	/**
	 * A client killed between staging a rewrite and moving it into place. The
	 * staging file has a fixed name so the next rewrite overwrites it rather
	 * than leaving it behind forever.
	 */
	@Test
	public void aStaleStagingFileFromAKilledRewriteIsOverwritten() throws IOException
	{
		final File file = file();
		final File staging = new File(file.getParentFile(), file.getName() + ".tmp");
		Files.write(staging.toPath(), "half a rewrite".getBytes(StandardCharsets.UTF_8));

		final TransactionQueue queue = new TransactionQueue(gson, file);
		queue.add(fill("a"));
		queue.confirm(queue.peek(1));

		assertFalse("the staging file must have been moved into place", staging.exists());
		assertTrue(new TransactionQueue(gson, file).isEmpty());
	}

	@Test
	public void pendingFillsSurviveARestart() throws IOException
	{
		final File file = file();
		final TransactionQueue first = new TransactionQueue(gson, file);
		first.add(fill("a"));
		first.add(fill("b"));

		final TransactionQueue reopened = new TransactionQueue(gson, file);

		assertEquals(2, reopened.size());
		assertEquals("order matters: fills are matched oldest first",
			"a", reopened.peek(10).get(0).id);
		assertEquals(1000, reopened.peek(10).get(0).grossValue);
	}

	@Test
	public void confirmedFillsDoNotComeBack() throws IOException
	{
		final File file = file();
		final TransactionQueue first = new TransactionQueue(gson, file);
		first.add(fill("a"));
		first.confirm(first.peek(10));

		assertTrue("a queue that resurrects sent fills would double every trade",
			new TransactionQueue(gson, file).isEmpty());
	}

	@Test
	public void aCorruptFileStartsEmptyRatherThanThrowing() throws IOException
	{
		final File file = file();
		Files.write(file.toPath(), "{ this is not the queue".getBytes(StandardCharsets.UTF_8));

		final TransactionQueue queue = new TransactionQueue(gson, file);

		assertTrue(queue.isEmpty());
		// And it still works from here on, which is the point: refusing to start
		// would mean nothing is recorded either.
		queue.add(fill("a"));
		assertEquals(1, queue.size());
	}

	@Test
	public void rowsWithNoIdAreDiscardedOnLoad() throws IOException
	{
		final File file = file();
		// An id-less row could never be de-duplicated by the server, so sending
		// it risks double counting.
		Files.write(file.toPath(),
			"[{\"itemId\":4151,\"quantity\":1},{\"id\":\"b\",\"itemId\":4151,\"quantity\":1}]"
				.getBytes(StandardCharsets.UTF_8));

		final TransactionQueue queue = new TransactionQueue(gson, file);

		assertEquals(1, queue.size());
		assertEquals("b", queue.peek(10).get(0).id);
	}

	// A queue written by the previous version -- one JSON array -- must still be
	// readable, or upgrading the plugin silently discards whatever was pending.
	@Test
	public void aLegacyWholeArrayFileIsStillRead() throws IOException
	{
	final File file = file();
	Files.write(file.toPath(),
	    "[{\"id\":\"a\",\"itemId\":4151,\"quantity\":1,\"grossValue\":1000}]"
	        .getBytes(StandardCharsets.UTF_8));

	final TransactionQueue queue = new TransactionQueue(gson, file);

	assertEquals(1, queue.size());
	assertEquals("a", queue.peek(10).get(0).id);

	// And it is rewritten in the new format, so later adds can append.
	final String rewritten = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	assertFalse("the file should no longer be a JSON array", rewritten.trim().startsWith("["));
	}

	// A process killed mid-append leaves a torn final line. That must cost one
	// fill, not the whole backlog -- which is what the old whole-array format did,
	// because a truncated array fails to parse in its entirety.
	@Test
	public void aTornFinalLineCostsOnlyThatFill() throws IOException
	{
	final File file = file();
	final TransactionQueue first = new TransactionQueue(gson, file);
	first.add(fill("a"));
	first.add(fill("b"));

	// Simulate the tear: append half a record.
	Files.write(file.toPath(), "{\"id\":\"c\",\"itemI".getBytes(StandardCharsets.UTF_8),
	    StandardOpenOption.APPEND);

	final TransactionQueue reopened = new TransactionQueue(gson, file);

	assertEquals("the two complete fills must survive", 2, reopened.size());
	assertEquals("a", reopened.peek(10).get(0).id);
	assertEquals("b", reopened.peek(10).get(1).id);
	}

	/**
	 * A fresh install has no folder yet, and the first write is what says so.
	 * Confirming it exists before every fill cost as much as the fill's own
	 * write, to establish something true since startup.
	 */
	@Test
	public void theFolderIsMadeWhenTheFirstWriteFindsItMissing() throws IOException
	{
		final File dir = new File(folder.getRoot(), "not-there-yet");
		assertFalse(dir.exists());
		final File file = new File(dir, "queue-1.json");

		final TransactionQueue queue = new TransactionQueue(gson, file);
		queue.add(fill("a"));

		assertTrue("the folder must be made when a fill needs it", dir.isDirectory());
		assertEquals("and the fill must be on disk", 1, new TransactionQueue(gson, file).size());
	}

	/** And one tidied away under a running client is made again. */
	@Test
	public void aFolderRemovedUnderARunningClientIsMadeAgain() throws IOException
	{
		final File dir = folder.newFolder("flippingrs");
		final File file = new File(dir, "queue-1.json");
		final TransactionQueue queue = new TransactionQueue(gson, file);
		queue.add(fill("a"));

		assertTrue(file.delete());
		assertTrue(dir.delete());

		queue.add(fill("b"));

		assertTrue("the folder must be made again", dir.isDirectory());
		assertTrue("and the fill written since must be on disk", file.isFile());
		assertEquals("nothing leaves the queue itself over a missing folder", 2, queue.size());
	}

	/**
	 * A refusal that cannot be filed must still leave the queue. Holding it
	 * would wedge every later trade behind it forever, which is the whole
	 * reason refused rows are set aside rather than retried -- and the panel
	 * is meanwhile telling the user they were put somewhere they can be found,
	 * so the rows go to the log when they cannot go to the file.
	 */
	@Test
	public void refusedFillsThatCannotBeSetAsideStillLeaveTheQueue() throws IOException
	{
		final File dir = folder.newFolder("flippingrs");
		final File file = new File(dir, "queue-1.json");
		// A directory in the way of the set-aside file, so the write cannot work.
		assertTrue(new File(dir, "dropped-1.json").mkdir());

		final TransactionQueue queue = new TransactionQueue(gson, file);
		queue.add(fill("a"));
		queue.add(fill("b"));

		queue.reject(queue.peek(1));

		assertEquals("the queue must not wedge behind a row it could not file", 1, queue.size());
		assertEquals("b", queue.peek(10).get(0).id);
	}

	/**
	 * A full queue must not rewrite itself on every fill.
	 *
	 * <p>Evicting the oldest needs the file rewritten, because an append cannot
	 * express a removal. Doing that on the add that evicts costs one rewrite in
	 * every CAPACITY fills the first time and every fill thereafter, because
	 * the queue does not go back under the cap -- and a full queue is exactly
	 * the state a client is in when sending has been failing for hours, so each
	 * new trade turned into a rewrite of ten thousand rows.
	 */
	@Test
	public void aFullQueueDoesNotRewriteItselfOnEveryFill() throws IOException
	{
		final File file = file();
		// A hundredth of the cap is the compaction interval, so this one
		// compacts every third eviction.
		final TransactionQueue queue = new TransactionQueue(gson, file, 300);
		for (int i = 0; i < 300; i++)
		{
			queue.add(fill("a" + i));
		}
		assertEquals(300, lines(file));

		queue.add(fill("over-1"));
		assertEquals("the evicted row is left in the file, not rewritten out of it", 301, lines(file));
		queue.add(fill("over-2"));
		assertEquals(302, lines(file));

		queue.add(fill("over-3"));
		assertEquals("the third eviction compacts it", 300, lines(file));

		// And the queue itself never went over the cap, nor kept the oldest.
		assertEquals(300, queue.size());
		final List<GeTransaction> held = queue.peek(1000);
		assertEquals("a3", held.get(0).id);
		assertEquals("over-3", held.get(299).id);

		// And the counting starts again from there. If it did not, every
		// eviction after the first compaction would be past the interval and
		// compact too, which is the rewrite-per-fill this exists to avoid.
		queue.add(fill("over-4"));
		assertEquals(301, lines(file));
		queue.add(fill("over-5"));
		assertEquals("counting up to the next compaction, not compacting", 302, lines(file));
	}

	/**
	 * A client killed while the file was running ahead of the queue restores
	 * more rows than the cap. The next fill has to bring it back down rather
	 * than sit over the cap for good.
	 */
	@Test
	public void aFileRestoredFromOverTheCapIsBroughtBackDown() throws IOException
	{
		// A thousand, so the compaction interval is ten: dropping three at once
		// is well under it, and only the rule about dropping more than one can
		// bring the file back down. At three hundred the interval would be
		// three, and the same add would have compacted either way -- which is
		// what this test used to be quietly asserting.
		final File file = file();
		final TransactionQueue first = new TransactionQueue(gson, file, 1000);
		for (int i = 0; i < 1002; i++)
		{
			first.add(fill("a" + i));
		}
		assertEquals("two evictions, not yet compacted", 1002, lines(file));

		final TransactionQueue reopened = new TransactionQueue(gson, file, 1000);
		assertEquals("the file's extra rows are all restored", 1002, reopened.size());

		reopened.add(fill("next"));

		assertEquals("back to the cap", 1000, reopened.size());
		assertEquals("and compacted, because it had to drop more than one", 1000, lines(file));
	}

	private static int lines(File file) throws IOException
	{
		int count = 0;
		for (String line : new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\n"))
		{
			if (!line.trim().isEmpty())
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * A byte that is not valid UTF-8 must cost the line it is on and no more.
	 * The strict readers throw on one, which would abandon the read and lose
	 * every fill after it -- and this file is appended to a line at a time by
	 * a process that can be killed between the two halves of a character.
	 */
	@Test
	public void oneBadByteDoesNotCostTheRestOfTheFile() throws IOException
	{
		final File file = file();
		try (java.io.OutputStream out = Files.newOutputStream(file.toPath()))
		{
			out.write("{\"id\":\"a\",\"quantity\":1}\n{\"id\":\"b\",\"itemName\":\"x"
				.getBytes(StandardCharsets.UTF_8));
			// 0xFF never begins a valid UTF-8 sequence.
			out.write(new byte[]{(byte) 0xFF});
			out.write("\"}\n{\"id\":\"c\",\"quantity\":1}\n".getBytes(StandardCharsets.UTF_8));
		}

		final TransactionQueue queue = new TransactionQueue(gson, file);

		assertEquals("the fills either side of the bad byte must survive", 3, queue.size());
		final List<GeTransaction> restored = queue.peek(10);
		assertEquals("a", restored.get(0).id);
		assertEquals("b", restored.get(1).id);
		assertEquals("c", restored.get(2).id);
	}
}
