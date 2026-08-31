package com.flippingrs;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The queue really is used from two threads at once: fills are appended on the
 * plugin's io thread while the sender peeks and confirms on its net thread.
 *
 * <p>Up to now that safety was argued from reading the lock structure. These
 * tests drive it instead, and check the one invariant that matters: every fill
 * ever added is either still queued or was confirmed exactly once. Losing one
 * silently drops a trade from somebody's journal; confirming one twice would
 * mean it was sent twice, and while the server de-duplicates by id, a queue
 * that can hand the same fill out twice is a queue that can also drop the
 * wrong one.
 */
public class TransactionQueueConcurrencyTest
{
	private static final int FILLS = 4000;

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final Gson gson = new Gson();

	private static GeTransaction fill(int n)
	{
		final GeTransaction tx = new GeTransaction();
		tx.id = "fill-" + n;
		tx.itemId = 4151;
		tx.itemName = "Abyssal whip";
		tx.side = "buy";
		tx.quantity = 1;
		tx.grossValue = 1000;
		tx.occurredAt = "2026-08-31T12:00:00Z";
		return tx;
	}

	@Test
	public void nothingIsLostOrDuplicatedUnderConcurrentAddAndDrain() throws Exception
	{
		final File file = new File(folder.newFolder("flippingrs"), "queue-1.json");
		final TransactionQueue queue = new TransactionQueue(gson, file);

		final Set<String> confirmed = new HashSet<>();
		final List<String> duplicates = new java.util.ArrayList<>();
		final CountDownLatch go = new CountDownLatch(1);
		final AtomicReference<Throwable> failure = new AtomicReference<>();

		// Producer: the io thread, appending fills as the game reports them.
		final Thread producer = new Thread(() ->
		{
			try
			{
				go.await();
				for (int i = 0; i < FILLS; i++)
				{
					queue.add(fill(i));
				}
			}
			catch (Throwable t)
			{
				failure.compareAndSet(null, t);
			}
		}, "producer");

		// Consumer: the net thread, draining batches the way drain() does --
		// peek, "send", confirm.
		final Thread consumer = new Thread(() ->
		{
			try
			{
				go.await();
				while (confirmed.size() < FILLS)
				{
					final List<GeTransaction> batch = queue.peek(64);
					if (batch.isEmpty())
					{
						Thread.yield();
						continue;
					}
					for (GeTransaction tx : batch)
					{
						// Recorded before the confirm, so a fill handed out
						// twice is caught even if the second hand-out races.
						if (!confirmed.add(tx.id))
						{
							duplicates.add(tx.id);
						}
					}
					queue.confirm(batch);
				}
			}
			catch (Throwable t)
			{
				failure.compareAndSet(null, t);
			}
		}, "consumer");

		producer.start();
		consumer.start();
		go.countDown();
		producer.join(TimeUnit.MINUTES.toMillis(1));
		consumer.join(TimeUnit.MINUTES.toMillis(1));

		assertNull("a worker threw: " + failure.get(), failure.get());
		assertTrue("no fill may be handed out twice, saw: " + duplicates, duplicates.isEmpty());
		assertEquals("every fill must be accounted for", FILLS, confirmed.size());
		assertTrue("the queue should be drained", queue.isEmpty());

		// And the file must agree with memory: a reopened queue sees nothing,
		// because everything was confirmed.
		assertTrue("confirmed fills must not come back after a restart",
			new TransactionQueue(gson, file).isEmpty());
	}

	/**
	 * The same race stopped part way through. Whatever was not confirmed has to
	 * still be on disk -- that is the entire reason the queue is persisted, and
	 * it is the case a crash actually hits.
	 */
	@Test
	public void whateverIsUnconfirmedSurvivesARestartMidDrain() throws Exception
	{
		final File file = new File(folder.newFolder("flippingrs"), "queue-1.json");
		final TransactionQueue queue = new TransactionQueue(gson, file);

		final int total = 500;
		final CountDownLatch go = new CountDownLatch(1);
		final AtomicReference<Throwable> failure = new AtomicReference<>();

		final Thread producer = new Thread(() ->
		{
			try
			{
				go.await();
				for (int i = 0; i < total; i++)
				{
					queue.add(fill(i));
				}
			}
			catch (Throwable t)
			{
				failure.compareAndSet(null, t);
			}
		}, "producer");

		producer.start();
		go.countDown();

		// Drain only a little, concurrently with the producer, then stop.
		final Set<String> confirmed = new HashSet<>();
		for (int round = 0; round < 3; round++)
		{
			final List<GeTransaction> batch = queue.peek(16);
			for (GeTransaction tx : batch)
			{
				confirmed.add(tx.id);
			}
			queue.confirm(batch);
		}
		producer.join(TimeUnit.MINUTES.toMillis(1));
		assertNull("producer threw: " + failure.get(), failure.get());

		final int expectedRemaining = total - confirmed.size();
		assertEquals(expectedRemaining, queue.size());

		// Reopen, as a restarted client would.
		final TransactionQueue reopened = new TransactionQueue(gson, file);
		assertEquals("the unsent fills must all still be there",
			expectedRemaining, reopened.size());

		final Set<String> restored = new HashSet<>();
		for (GeTransaction tx : reopened.peek(total))
		{
			restored.add(tx.id);
		}
		for (String id : confirmed)
		{
			assertTrue("a confirmed fill came back: " + id, !restored.contains(id));
		}
		assertEquals(expectedRemaining, restored.size());
	}

	/**
	 * Appending is the hot path and it must stay cheap no matter how much is
	 * already waiting. This is the regression guard for the rewrite-per-add
	 * that made filling the queue quadratic.
	 */
	@Test
	public void appendingStaysCheapAsTheBacklogGrows() throws IOException
	{
		final File file = new File(folder.newFolder("flippingrs"), "queue-1.json");
		final TransactionQueue queue = new TransactionQueue(gson, file);

		final long firstThousand = timeAdds(queue, 0, 1000);
		// Now there is a real backlog in front of the next thousand.
		final long laterThousand = timeAdds(queue, 1000, 1000);

		// Quadratic behaviour made the second batch many times the cost of the
		// first. Appending makes them comparable; the bound is loose because
		// this is wall-clock on a shared machine, and it still fails by orders
		// of magnitude if the rewrite ever comes back.
		assertTrue("adds got dramatically slower as the queue grew: "
				+ firstThousand + "ms then " + laterThousand + "ms",
			laterThousand < Math.max(250, firstThousand * 10));
	}

	private long timeAdds(TransactionQueue queue, int from, int count)
	{
		final long start = System.nanoTime();
		for (int i = from; i < from + count; i++)
		{
			queue.add(fill(i));
		}
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}
}
