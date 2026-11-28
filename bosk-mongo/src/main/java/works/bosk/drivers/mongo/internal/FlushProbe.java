package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.client.MongoCollection;
import java.util.List;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
final class FlushProbe {
	private final MongoCollection<BsonDocument> collection;
	private final Supplier<Bson> stateWriteFilter;
	private final FlushBarrier barrier;

	FlushProbe(SessionCollection collection, Supplier<Bson> stateWriteFilter, FlushBarrier barrier) {
		this.collection = collection.fromPrimary();
		this.stateWriteFilter = stateWriteFilter;
		this.barrier = barrier;
	}

	/**
	 * Waits until {@link FlushBarrier#submittedThrough} is at or past
	 * {@code startPosition}.
	 * <p>
	 * Two probes keep the wait bounded whether the stream is quiet or busy: the
	 * backlog probe bounds it when the stream goes quiet, and the arrival probe
	 * when new events keep arriving.
	 * <p>
	 * The backlog probe starts at {@code startPosition}, so it covers any update
	 * submitted before the flush that the {@code ChangeReceiver} has not yet
	 * submitted downstream. Such an update is already in the oplog, and
	 * committed: writes use {@link com.mongodb.WriteConcern#MAJORITY}, and the
	 * primary's change stream has reached the same majority commit point that
	 * the acknowledgement waited for. So an empty batch means no such update is
	 * outstanding, and the last event it saw is a safe bound.
	 * <p>
	 * The arrival probe opens during the flush, so any event it sees postdates
	 * every update submitted before the flush; waiting for the later of the two
	 * bounds covers them all. If {@code startPosition} has aged out of the
	 * oplog, the backlog probe fails and the flush reloads instead of skipping
	 * events.
	 *
	 * @see <a href="https://www.mongodb.com/docs/manual/changeStreams/#event-notification">MongoDB change streams: Event Notification</a>
	 */
	void awaitCaughtUpTo(BsonTimestamp startPosition, long deadlineNanos) throws FlushFailureException, InterruptedException {
		// The probes use the query client rather than the change-stream client.
		// Every flush waits for ChangeReceiver, so a flood of flushes competing with it
		// for change-stream connections could mean we never catch up.
		// Slowing the pace of updates instead actually tends to help us catch up.
		checkDeadline(deadlineNanos);
		try {
			try (var backlogProbe = collection.watch(List.of(
					match(and(gt("clusterTime", startPosition), stateWriteFilter.get())),
					project(include("clusterTime"))))
				.startAtOperationTime(startPosition)
				.batchSize(BACKLOG_PROBE_BATCH_SIZE)
				.maxAwaitTime(MIN_WAIT_MS, MILLISECONDS)
				.cursor()) {

				var first = backlogProbe.tryNext();
				if (first == null) {
					LOGGER.debug("Flush probe found nothing pending");
					return;
				}

				checkDeadline(deadlineNanos);
				try (var arrivalProbe = collection.watch(List.of(match(stateWriteFilter.get()), project(include("clusterTime"))))
					.maxAwaitTime(MIN_WAIT_MS, MILLISECONDS)
					.cursor()) {

					BsonTimestamp last = requireNonNull(first.getClusterTime());
					while (true) {
						checkDeadline(deadlineNanos);
						var arrival = arrivalProbe.tryNext();
						if (arrival != null) {
							barrier.awaitSubmittedThrough(latest(last, requireNonNull(arrival.getClusterTime())), deadlineNanos);
							return;
						}
						while (backlogProbe.available() > 0) {
							last = latest(last, requireNonNull(backlogProbe.next().getClusterTime()));
						}
						var event = backlogProbe.tryNext();
						if (event == null) {
							barrier.awaitSubmittedThrough(last, deadlineNanos);
							return;
						}
						last = latest(last, requireNonNull(event.getClusterTime()));
					}
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
			throw new FlushFailureException("Flush probe failed", e);
		}
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
	 * How many events the backlog probe asks for per round trip. The loop drains whatever
	 * is available and stops at the first quiet moment, so a bigger batch would
	 * only fetch events past the point where the flush finishes.
	 */
	private static final int BACKLOG_PROBE_BATCH_SIZE = 100;

	private static final Logger LOGGER = LoggerFactory.getLogger(FlushProbe.class);
}
