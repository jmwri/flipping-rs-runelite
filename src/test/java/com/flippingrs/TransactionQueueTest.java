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
}
