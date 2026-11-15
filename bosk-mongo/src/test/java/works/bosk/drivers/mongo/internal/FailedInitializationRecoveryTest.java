package works.bosk.drivers.mongo.internal;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoCollection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.slf4j.event.Level.WARN;
import static works.bosk.drivers.mongo.internal.MainDriver.MANIFEST_ID;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.drivers.mongo.internal.TestParameters.SHORT_TIMESCALE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A failure while initializing the database falls back to the downstream initial
 * state so that the application can start, but the collection is still
 * uninitialized. Bosk aims to be self-repairing, so once the cause clears, the
 * driver must try again rather than remain disconnected until some unrelated
 * change event happens to arrive.
 */
@ReplayLogsOnFailure
@InjectFields
class FailedInitializationRecoveryTest extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(
				DatabaseFormat.SEQUOIA,
				PandoFormat.oneBigDocument()
			),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s.timescaleMS(SHORT_TIMESCALE)));
	}

	@Test
	void failedInitialization_recoversOnceTheFailureClears(TestInfo testInfo) throws Exception {
		// This test deliberately provokes a disconnect, so log errors only.
		suppressLogging(WARN, MainDriver.class, ChangeReceiver.class);

		AtomicBoolean failInitialization = new AtomicBoolean(true);
		MainDriver.setProbes(TestProbes.noop().withWriteInterceptor(filter -> {
			if (failInitialization.get() && isManifestWrite(filter)) {
				throw new IllegalStateException("Simulated transient initialization failure");
			}
		}));
		try {
			new Bosk<>(
				boskName("failedInitialization"),
				TestEntity.class,
				AbstractMongoDriverTest::initialRoot,
				BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
		} finally {
			MainDriver.resetProbes();
		}

		// The injected failure is gone now, so the driver should retry by itself.
		failInitialization.set(false);

		MongoCollection<BsonDocument> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection(), BsonDocument.class);
		long deadline = System.nanoTime() + SECONDS.toNanos(20);
		while (System.nanoTime() < deadline && collection.countDocuments() == 0) {
			Thread.sleep(50);
		}
		assertTrue(collection.countDocuments() > 0,
			"The driver must retry initialization after a transient failure, without waiting for an unrelated change event");
	}

	private static boolean isManifestWrite(Bson filter) {
		BsonDocument asDoc = filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
		BsonValue idValue = asDoc.get("_id");
		return MANIFEST_ID.equals(idValue);
	}
}
