package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.conversions.Bson;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.drivers.mongo.exceptions.DisconnectedException;
import works.bosk.exceptions.FlushFailureException;

import static com.mongodb.client.model.Aggregates.match;
import static com.mongodb.client.model.Aggregates.project;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Projections.include;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Finds the cluster time a flush should wait for.
 * <p>
 * A flush's only job here is to identify a position such that every state write
 * acknowledged before the flush began is at or before it; {@link FlushBarrier}
 * then does the waiting. That position comes from reading the change stream
 * ahead of {@link ChangeReceiver}, which is the only source of the negative
 * evidence the barrier cannot provide: that nothing is outstanding.
 * <p>
 * The cursor is shared, so creating and tearing down a change-stream cursor per
 * flush is avoided. Reads are batched into rounds: a flush that finds a round
 * in progress waits for the next one, and the next round's leader does one
 * target-discovery pass on behalf of every flush that was waiting when it
 * started. That batching is sound because a round's observation happens after
 * every member arrived, so it covers all of their writes, without needing
 * client clocks or sequence numbers.
 */
final class FlushProbe implements AutoCloseable {
	/**
	 * The collection to open the probe's change stream on. This belongs to the
	 * change-stream client: the query client's operation timeout is a
	 * cursor-lifetime budget, and a cursor that is reused across rounds can
	 * outlive it.
	 */
	private final MongoCollection<BsonDocument> collection;
	private final Supplier<Bson> stateWriteFilter;
	private final FlushBarrier barrier;

	private final Object lock = new Object();
	/** Flushes waiting for the next round. Guarded by {@link #lock}. */
	private final Deque<Waiter> queue = new ArrayDeque<>();
	/** Whether a round is in progress. Guarded by {@link #lock}. */
	private boolean roundRunning;

	/** The shared cursor. Used only by a round's leader, which is the only thread reading events. */
	private @Nullable MongoCursor<ChangeStreamDocument<BsonDocument>> cursor;
	/** The newest state write this probe has seen, or null before any. */
	private @Nullable BsonTimestamp lastSeen;

	private volatile boolean closed;

	FlushProbe(MongoCollection<BsonDocument> collection, Supplier<Bson> stateWriteFilter, FlushBarrier barrier) {
		// Pin the primary explicitly: the flush's guarantee depends on reading
		// the latest value, and a client-level preference must not change that.
		this.collection = collection.withReadPreference(ReadPreference.primary());
		this.stateWriteFilter = stateWriteFilter;
		this.barrier = barrier;
	}

	/**
	 * Returns the cluster time through which a flush must wait.
	 * <p>
	 * The returned position includes every state write acknowledged before this
	 * call, so waiting for {@link FlushBarrier#awaitSubmittedThrough} to reach it
	 * is sufficient for the caller.
	 */
	BsonTimestamp target(long deadlineNanos) throws FlushFailureException, InterruptedException {
		Waiter me = new Waiter();
		List<Waiter> members;
		synchronized (lock) {
			while (true) {
				throwIfClosed();
				if (!roundRunning) {
					if (me.queued) {
						queue.remove(me);
						me.queued = false;
					}
					members = new ArrayList<>(queue);
					queue.clear();
					for (Waiter member : members) {
						member.member = true;
						member.queued = false;
					}
					roundRunning = true;
					break;
				}
				if (!me.member && !me.queued) {
					queue.add(me);
					me.queued = true;
				}
				try {
					awaitUntil(deadlineNanos);
					throwIfClosed();
				} catch (InterruptedException | RuntimeException e) {
					removeFromQueue(me);
					// If we were the last waiter, this ends the era; otherwise the
					// cursor lives on for the next round.
					closeQuietly(takeCursorIfIdleLocked());
					throw e;
				}
				if (me.done) {
					return me.result();
				}
			}
		}
		LOGGER.debug("Leading flush probe round with {} other waiter(s)", members.size());
		return runRound(members, deadlineNanos);
	}

	private void removeFromQueue(Waiter waiter) {
		if (waiter.queued) {
			queue.remove(waiter);
			waiter.queued = false;
		}
	}

	/**
	 * Runs one target-discovery pass on behalf of {@code members} and this thread.
	 * Rounds are serialised, so this thread has sole use of the cursor.
	 */
	private BsonTimestamp runRound(List<Waiter> members, long deadlineNanos) throws FlushFailureException, InterruptedException {
		BsonTimestamp target = null;
		Throwable failure = null;
		try {
			target = observe(deadlineNanos);
		} catch (FlushFailureException | InterruptedException | RuntimeException | Error e) {
			failure = e;
		}

		boolean aboutTheLeaderOnly = failure instanceof FlushTimeoutException
			|| failure instanceof InterruptedException;

		MongoCursor<ChangeStreamDocument<BsonDocument>> toClose = null;
		synchronized (lock) {
			if (aboutTheLeaderOnly && !closed) {
				// The leader ran out of its own budget, or its thread was interrupted.
				// Neither says anything about the members' chances, so let the next
				// round serve them rather than failing their flushes too.
				for (Waiter member : members) {
					member.member = false;
					member.queued = true;
					queue.add(member);
				}
			} else {
				for (Waiter member : members) {
					member.target = target;
					member.failure = failure;
					member.done = true;
				}
			}
			roundRunning = false;
			toClose = takeCursorIfIdleLocked();
			lock.notifyAll();
		}
		if (toClose != null) {
			LOGGER.debug("Closed flush probe cursor: no more waiters");
		}
		closeQuietly(toClose);

		if (failure instanceof FlushFailureException f) {
			throw f;
		} else if (failure instanceof InterruptedException i) {
			throw i;
		} else if (failure instanceof RuntimeException r) {
			throw r;
		} else if (failure instanceof Error e) {
			throw e;
		}
		return requireNonNull(target);
	}

	/**
	 * Reads the change stream until it can identify a position that is safe to
	 * wait for: either the stream goes quiet, or an event arrives that postdates
	 * this round.
	 */
	private BsonTimestamp observe(long deadlineNanos) throws FlushFailureException, InterruptedException {
		checkDeadline(deadlineNanos);
		throwIfClosed();
		try {
			MongoCursor<ChangeStreamDocument<BsonDocument>> probe = cursor;
			BsonTimestamp last = lastSeen;
			if (probe == null) {
				// Start where the receiver has got to, so the probe covers anything
				// it has not submitted yet. Starting later would miss events.
				BsonTimestamp start = barrier.awaitSubmitted(deadlineNanos);
				checkDeadline(deadlineNanos);
				throwIfClosed();
				probe = collection.watch(List.of(
						match(and(gt("clusterTime", start), stateWriteFilter.get())),
						project(include("clusterTime"))))
					.startAtOperationTime(start)
					.batchSize(BATCH_SIZE)
					.maxAwaitTime(MIN_WAIT_MS, MILLISECONDS)
					.cursor();
				checkDeadline(deadlineNanos);
				last = start;
				lastSeen = start;
				cursor = probe;
				LOGGER.debug("Opened flush probe cursor at {}", start);
			}

			var first = probe.tryNext();
			if (first == null) {
				LOGGER.debug("Flush probe found nothing pending");
				return requireNonNull(last);
			}

			checkDeadline(deadlineNanos);
			throwIfClosed();
			try (var arrivalProbe = collection.watch(List.of(match(stateWriteFilter.get()), project(include("clusterTime"))))
				.maxAwaitTime(MIN_WAIT_MS, MILLISECONDS)
				.cursor()) {

				last = remember(last, requireNonNull(first.getClusterTime()));
				while (true) {
					checkDeadline(deadlineNanos);
					throwIfClosed();
					var arrival = arrivalProbe.tryNext();
					if (arrival != null) {
						return remember(last, requireNonNull(arrival.getClusterTime()));
					}
					while (probe.available() > 0) {
						last = remember(last, requireNonNull(probe.next().getClusterTime()));
					}
					var event = probe.tryNext();
					if (event == null) {
						return last;
					}
					last = remember(last, requireNonNull(event.getClusterTime()));
				}
			}
		} catch (MongoInterruptedException e) {
			// The driver sets the interrupt flag before throwing. Clear it and
			// rethrow as an interrupt, so doRetryableDriverOperation retries
			// without disconnecting a healthy driver.
			Thread.interrupted();
			InterruptedException exception = new InterruptedException("Interrupted while probing the change stream");
			exception.initCause(e);
			throw exception;
		} catch (MongoException e) {
			discardCursor();
			throw new FlushFailureException("Flush probe failed", e);
		}
	}

	private BsonTimestamp remember(BsonTimestamp previous, BsonTimestamp event) {
		BsonTimestamp latest = latest(previous, event);
		lastSeen = latest;
		return latest;
	}

	private void awaitUntil(long deadlineNanos) throws InterruptedException, FlushTimeoutException {
		long remainingNanos = deadlineNanos - System.nanoTime();
		if (remainingNanos <= 0) {
			throw new FlushTimeoutException("Timed out waiting for a flush probe round");
		}
		TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
	}

	private void throwIfClosed() {
		if (closed) {
			throw new DisconnectedException("Flush probe was closed while waiting");
		}
	}

	/**
	 * Ends the era if nothing is using the cursor, returning it for the caller to
	 * close outside the lock. Must be called while holding {@link #lock}.
	 */
	private @Nullable MongoCursor<ChangeStreamDocument<BsonDocument>> takeCursorIfIdleLocked() {
		if (!roundRunning && (queue.isEmpty() || closed)) {
			MongoCursor<ChangeStreamDocument<BsonDocument>> old = cursor;
			cursor = null;
			return old;
		} else {
			return null;
		}
	}

	private void discardCursor() {
		MongoCursor<ChangeStreamDocument<BsonDocument>> toClose = cursor;
		cursor = null;
		closeQuietly(toClose);
	}

	private static void closeQuietly(@Nullable MongoCursor<?> cursor) {
		if (cursor != null) {
			try {
				cursor.close();
			} catch (RuntimeException e) {
				LOGGER.debug("Exception closing flush probe cursor", e);
			}
		}
	}

	/**
	 * Wraps a failure from a round leader into something a member can throw.
	 * Members rethrow unchecked failures as they are, and wrap the rest.
	 */
	private static FlushFailureException asFlushFailure(Throwable failure) {
		if (failure instanceof FlushFailureException f) {
			return f;
		} else {
			return new FlushFailureException("Flush probe round failed", failure);
		}
	}

	@Override
	public void close() {
		MongoCursor<ChangeStreamDocument<BsonDocument>> toClose = null;
		synchronized (lock) {
			if (closed) {
				return;
			}
			closed = true;
			if (!roundRunning) {
				toClose = cursor;
				cursor = null;
			}
			lock.notifyAll();
		}
		closeQuietly(toClose);
	}

	private static BsonTimestamp latest(BsonTimestamp first, BsonTimestamp second) {
		return first.compareTo(second) >= 0 ? first : second;
	}

	private static void checkDeadline(long deadlineNanos) throws FlushTimeoutException {
		if (System.nanoTime() - deadlineNanos >= 0) {
			throw new FlushTimeoutException("Timed out probing the change stream");
		}
	}

	/**
	 * The shortest poll we can ask for. A zero {@code maxAwaitTime} leaves the
	 * server's default, which for a tailable cursor is 1000ms, so the probes ask
	 * for 1ms instead.
	 */
	private static final long MIN_WAIT_MS = 1;

	/**
	 * How many events the probe asks for per round trip. The loop drains whatever
	 * is available and stops at the first quiet moment.
	 */
	private static final int BATCH_SIZE = 100;

	private static final Logger LOGGER = LoggerFactory.getLogger(FlushProbe.class);

	private static final class Waiter {
		/** Set when this waiter is a member of the round in progress. */
		boolean member;
		/** Set while this waiter is in {@link #queue}. */
		boolean queued;
		boolean done;
		@Nullable BsonTimestamp target;
		@Nullable Throwable failure;

		BsonTimestamp result() throws FlushFailureException {
			if (failure != null) {
				if (failure instanceof FlushFailureException f) {
					// Give each member its own exception: the stack trace and any
					// suppressed exceptions belong to the flush that is failing.
					throw new FlushFailureException("Flush probe round failed", f);
				} else if (failure instanceof RuntimeException r) {
					// Preserve the type: MainDriver decides whether to disconnect from it.
					throw r;
				} else {
					throw asFlushFailure(failure);
				}
			}
			return requireNonNull(target);
		}
	}
}
