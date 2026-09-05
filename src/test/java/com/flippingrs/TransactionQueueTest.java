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

	/**
	 * A damaged queue file loses fills; it never invents one.
	 *
	 * <p>The file is appended to as each fill arrives, so a client killed
	 * mid-write leaves a line half-finished, and a disk that fills up or a
	 * sync that never happened can leave worse. The queue is meant to drop
	 * what it cannot read and keep the rest, which is the right trade -- but
	 * only if what it keeps is what was written. A fill restored with another
	 * fill's id would be sent under that id, and the server groups and dedupes
	 * by exactly that: the real trade would be taken for a duplicate and
	 * dropped, and the invented one kept in its place.
	 *
	 * <p>So this writes a queue, breaks the file three hundred different ways
	 * and asks only that every fill that comes back is one that went in, whole.
	 *
	 * <p>The damage is the damage a crash does: a line cut short, a line that
	 * is not JSON, a line that is JSON and not a fill, a blank line. Not a byte
	 * flipped inside a field name -- that is a disk going bad rather than a
	 * client being killed, and what it leaves behind is a line that parses
	 * cleanly into a fill with a real id and no quantity, which the queue would
	 * keep and send as a trade of nothing. The server refuses it and the user
	 * is told a trade could not be recorded, so it is confusing rather than
	 * silent, and guarding it means the queue deciding what counts as a fill.
	 */
	@Test
	public void aDamagedQueueFileLosesFillsRatherThanInventingThem() throws Exception
	{
		final java.util.Random random = new java.util.Random(20260905L);
		for (int run = 0; run < 300; run++)
		{
			final File file = new File(folder.newFolder("run" + run), "queue-1.json");
			final java.util.Map<String, String> written = new java.util.HashMap<>();
			final TransactionQueue original = new TransactionQueue(gson, file);
			final int fills = 1 + random.nextInt(8);
			for (int i = 0; i < fills; i++)
			{
				final GeTransaction tx = fill("id" + run + "_" + i);
				tx.quantity = 1 + random.nextInt(1000);
				tx.grossValue = 1 + random.nextInt(1_000_000);
				tx.itemId = 4151 + i;
				original.add(tx);
				written.put(tx.id, tx.itemId + "/" + tx.quantity + "/" + tx.grossValue);
			}

			final byte[] whole = Files.readAllBytes(file.toPath());
			final String text = new String(whole, StandardCharsets.UTF_8);
			// 0 is a client killed mid-append, which takes the tail with it.
			// The rest destroy one line and leave the others alone.
			final int kind = random.nextInt(4);
			Files.write(file.toPath(), kind == 0
				? java.util.Arrays.copyOf(whole, random.nextInt(whole.length))
				: replaceLine(text, random, brokenLine(kind)));

			final TransactionQueue reopened = new TransactionQueue(gson, file);
			final List<GeTransaction> restored = reopened.peek(1000);
			for (GeTransaction tx : restored)
			{
				final String shape = "run " + run + ": " + tx.id + " came back as "
					+ tx.itemId + "/" + tx.quantity + "/" + tx.grossValue;
				assertTrue(shape + ", which was never written", written.containsKey(tx.id));
				assertEquals(shape, written.get(tx.id), tx.itemId + "/" + tx.quantity + "/" + tx.grossValue);
			}
			if (kind != 0)
			{
				// A line per fill is the whole point of the format: one line
				// nobody can read must not cost the fills below it.
				assertTrue("run " + run + ": one broken line cost more than itself, kept "
						+ restored.size() + " of " + fills,
					restored.size() >= fills - 1);
			}
		}
	}

	/** The ways a line comes back unreadable, short of the file being cut. */
	private static String brokenLine(int kind)
	{
		switch (kind)
		{
			case 1:
				return "}{ not json at all";
			case 2:
				// Parses, and is not a fill.
				return "{\"nonsense\":true}";
			default:
				// Not damage, but it happens.
				return "";
		}
	}

	private static byte[] replaceLine(String text, java.util.Random random, String with)
	{
		final String[] lines = text.split("\\n", -1);
		lines[random.nextInt(lines.length)] = with;
		return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * A fill comes back off disk exactly as it went on.
	 *
	 * <p>Everything queued crosses a restart this way, which is the whole
	 * point of the file, and every field of it means something to the site.
	 * The reference is what groups a thousand partial fills into one
	 * purchase. The time is what keeps a trade from being filed as recovered
	 * when it was watched happening. The side decides whether it was a
	 * purchase at all. Whether it was an estimate says if the gp can be
	 * trusted to the coin, and whether it completed says if the offer is still
	 * open.
	 *
	 * <p>Losing any of them is silent: the fill still has an id, still goes
	 * out, and still comes back accepted. Only the journal is wrong.
	 */
	@Test
	public void aFillComesBackOffDiskExactlyAsItWentOn() throws IOException
	{
		final File file = file();
		final GeTransaction tx = fill("t1");
		// Every field something other than its default, so a field that is not
		// written cannot come back looking right anyway.
		tx.offerRef = "offer-1";
		tx.itemId = 4151;
		tx.itemName = "Abyssal whip";
		tx.side = "sell";
		tx.quantity = 25;
		tx.grossValue = 30_864_175L;
		tx.offerPrice = 1_234_567L;
		tx.offerTotal = 100L;
		tx.completed = true;
		tx.cancelled = true;
		tx.estimated = true;
		tx.slot = 5;
		tx.world = 302;
		tx.occurredAt = "2026-08-31T12:00:00Z";
		tx.source = GeTransaction.SOURCE_ADOPTED;
		new TransactionQueue(gson, file).add(tx);

		final List<GeTransaction> back = new TransactionQueue(gson, file).peek(10);

		assertEquals(1, back.size());
		final GeTransaction got = back.get(0);
		assertEquals("id", tx.id, got.id);
		assertEquals("the reference the site groups a purchase by", tx.offerRef, got.offerRef);
		assertEquals("item", tx.itemId, got.itemId);
		assertEquals("item name", tx.itemName, got.itemName);
		assertEquals("side", tx.side, got.side);
		assertEquals("quantity", tx.quantity, got.quantity);
		assertEquals("the gp that moved", tx.grossValue, got.grossValue);
		assertEquals("the offer's price", tx.offerPrice, got.offerPrice);
		assertEquals("the offer's size", tx.offerTotal, got.offerTotal);
		assertEquals("whether the offer finished", tx.completed, got.completed);
		assertEquals("whether it was cancelled", tx.cancelled, got.cancelled);
		assertEquals("whether the gp is an estimate", tx.estimated, got.estimated);
		assertEquals("slot", tx.slot, got.slot);
		assertEquals("world", tx.world, got.world);
		assertEquals("when it happened", tx.occurredAt, got.occurredAt);
		assertEquals("whether it was watched or recovered", tx.source, got.source);
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
		// What a rewrite killed part way through leaves behind: a line that
		// stops in the middle, with no newline after it.
		Files.write(staging.toPath(), "half a rewrite".getBytes(StandardCharsets.UTF_8));

		final TransactionQueue queue = new TransactionQueue(gson, file);
		queue.add(fill("a"));
		queue.add(fill("b"));
		// One fill left over the rewrite, so the next thing written lands
		// where the stale bytes are. Written after them instead of over them,
		// it joins onto the end of that half line and the fill is unreadable.
		queue.confirm(queue.peek(1));

		assertFalse("the staging file must have been moved into place", staging.exists());
		final List<GeTransaction> back = new TransactionQueue(gson, file).peek(10);
		assertEquals("the fill that had not been sent is still there", 1, back.size());
		assertEquals("b", back.get(0).id);
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
	 * A line carrying two whole fills costs that line and no more.
	 *
	 * <p>An append writes the fill and its newline in one call, so a client
	 * killed part way through can get the fill down without the newline. The
	 * next append then lands on the end of it, and one line holds two whole
	 * fills.
	 *
	 * <p>That is not a broken line -- both fills on it are whole -- so it is
	 * worth saying which way it goes. The line is dropped and the rest of the
	 * file is kept, which is the same trade the queue makes everywhere else:
	 * the two fills on it are lost, and nothing is invented from the halves.
	 */
	@Test
	public void twoFillsRunTogetherOnOneLineCostThatLineAndNoMore() throws IOException
	{
		final File file = file();
		final String torn = gson.toJson(fill("a")) + gson.toJson(fill("b"));
		Files.write(file.toPath(),
			(torn + "\n" + gson.toJson(fill("c")) + "\n").getBytes(StandardCharsets.UTF_8));

		final TransactionQueue queue = new TransactionQueue(gson, file);

		assertEquals("the line that could be read is kept", 1, queue.size());
		assertEquals("c", queue.peek(10).get(0).id);
	}

	/**
	 * A compaction that cannot be written must not stop the file recording.
	 *
	 * <p>Once the queue is full every add evicts, and an eviction is expressed
	 * by rewriting the file rather than appending to it. If that rewrite fails,
	 * the new fill was never appended either -- and the eviction counter stays
	 * over the mark, so every add after it goes the same way. One bad rewrite
	 * stopped the file recording anything for the rest of the session, in the
	 * one state where every trade in the queue is already at risk.
	 *
	 * <p>A directory where the staging file goes is a rewrite that cannot be
	 * written and an append that still can, which is a real shape on Windows:
	 * a scanner holding the file open fails the move and not the append.
	 */
	@Test
	public void aFillStillReachesDiskWhenTheCompactionCannotBeWritten() throws IOException
	{
		final File file = file();
		final TransactionQueue queue = new TransactionQueue(gson, file, 300);
		for (int i = 0; i < 300; i++)
		{
			queue.add(fill("a" + i));
		}
		assertEquals(300, lines(file));

		// Nothing can be written where the rewrite stages its copy.
		assertTrue(new File(file.getParentFile(), file.getName() + ".tmp").mkdir());

		// The third eviction is a compaction, and it cannot be written.
		queue.add(fill("over-1"));
		queue.add(fill("over-2"));
		queue.add(fill("over-3"));

		assertEquals("the fill the compaction could not write is appended instead", 303, lines(file));

		// And so is every one after it, rather than the file going quiet.
		queue.add(fill("over-4"));
		queue.add(fill("over-5"));
		assertEquals(305, lines(file));

		final TransactionQueue reopened = new TransactionQueue(gson, file, 300);
		final List<GeTransaction> restored = reopened.peek(1000);
		assertEquals("the newest fill is on disk", "over-5",
			restored.get(restored.size() - 1).id);
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
