package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Entity;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.BoskLogFilter;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Many concurrent flushes should share one read of the change stream rather
 * than each opening its own cursor, and the shared cursor should be read once
 * per round rather than once per flush.
 */
@InjectFields
class FlushBatchingTest extends AbstractMongoDriverTest {
	private final AtomicInteger probeAggregates = new AtomicInteger();
	private final AtomicInteger probeGetMores = new AtomicInteger();
	/** Request IDs of probe aggregates we have seen start, awaiting their cursor ID. */
	private final Set<Integer> probeAggregateRequests = ConcurrentHashMap.newKeySet();
	/** Cursor IDs of the probe's change streams, so we can recognise their getMores. */
	private final Set<Long> probeCursorIds = ConcurrentHashMap.newKeySet();

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.standardDriverSettings();
	}

	@Test
	void concurrentFlushesShareOneProbeRead() throws InvalidTypeException, IOException, InterruptedException {
		Bosk<TestEntity> bosk = new Bosk<>(
			boskName(),
			TestEntity.class,
			AbstractMongoDriverTest::initialState,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());

		// Ignore anything the driver did while initializing.
		bosk.driver().flush();
		resetCounters();

		int threadCount = 32;
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = new ArrayList<>();
		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < threadCount; i++) {
			Thread thread = new Thread(() -> {
				try {
					start.await();
					bosk.driver().flush();
				} catch (Throwable e) {
					synchronized (failures) {
						failures.add(e);
					}
				}
			});
			thread.start();
			threads.add(thread);
		}
		start.countDown();
		for (Thread thread : threads) {
			thread.join(TimeUnit.SECONDS.toMillis(30));
			assertFalse(thread.isAlive(), "Expected every flush to finish");
		}

		assertTrue(failures.isEmpty(), "Expected all flushes to succeed, but: " + failures);
		assertTrue(probeAggregates.get() >= 1, "Expected at least one probe read");
		assertTrue(probeAggregates.get() < threadCount / 2,
			"Expected the burst to share its probe cursor, but saw " + probeAggregates.get()
				+ " change-stream aggregates for " + threadCount + " flushes");
		assertTrue(probeGetMores.get() < threadCount / 2,
			"Expected the shared cursor to be read once per round, but saw " + probeGetMores.get()
				+ " getMores for " + threadCount + " flushes");
	}

	private void resetCounters() {
		probeAggregates.set(0);
		probeGetMores.set(0);
		probeAggregateRequests.clear();
		probeCursorIds.clear();
	}

	@Override
	protected <E extends Entity> DriverFactory<E> createDriverFactory(BoskLogFilter.LogController logController, TestInfo testInfo) {
		CommandListener recorder = new CommandListener() {
			@Override
			public void commandStarted(CommandStartedEvent event) {
				BsonDocument command = event.getCommand();
				if (event.getCommandName().equals("aggregate") && isStateCollectionCommand(command)) {
					if (command.getArray("pipeline", new BsonArray()).size() > 1) {
						// Flush's probes add a $project stage; the receiver's stream does not.
						probeAggregates.incrementAndGet();
						probeAggregateRequests.add(event.getRequestId());
					}
				} else if (event.getCommandName().equals("getMore")) {
					BsonValue id = command.get("getMore");
					if (id != null && id.isNumber() && probeCursorIds.contains(id.asNumber().longValue())) {
						probeGetMores.incrementAndGet();
					}
				}
			}

			@Override
			public void commandSucceeded(CommandSucceededEvent event) {
				if (probeAggregateRequests.remove(event.getRequestId())) {
					BsonDocument response = event.getResponse();
					BsonDocument cursor = response.getDocument("cursor", null);
					if (cursor != null && cursor.get("id") != null) {
						probeCursorIds.add(cursor.get("id").asNumber().longValue());
					}
				}
			}

			private boolean isStateCollectionCommand(BsonDocument command) {
				BsonValue target = command.get("aggregate");
				return target != null
					&& target.isString()
					&& driverSettings.collection().equals(target.asString().getValue());
			}
		};
		MongoClientSettings clientSettings = MongoClientSettings
			.builder(mongoService.clientSettings(testInfo))
			.addCommandListener(recorder)
			.build();
		DriverFactory<E> mongoDriverFactory = (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<E>factory(
				clientSettings,
				driverSettings,
				new BsonSerializer()
			).build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
		return DriverStack.of(
			BoskLogFilter.withController(logController),
			mongoDriverFactory
		);
	}
}
