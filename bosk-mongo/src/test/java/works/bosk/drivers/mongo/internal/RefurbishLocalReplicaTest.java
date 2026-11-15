package works.bosk.drivers.mongo.internal;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.DriverFactory;
import works.bosk.Reference;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.drivers.mongo.MongoDriver;
import works.bosk.drivers.mongo.MongoDriverSettings;
import works.bosk.drivers.mongo.PandoFormat;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.libtesting.BlockingGate;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static works.bosk.drivers.mongo.internal.TestParameters.LONG_TIMESCALE;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * A {@code refurbish} reads the database state and writes it back, so the local
 * in-memory replica must end up at least as up to date as the state that was
 * read. Otherwise an update committed by another replica, but not yet applied
 * here, is overwritten in the database by the refurbish and then ignored when
 * its change event finally arrives, because the refurbish declared that
 * revision applied before the state behind it had been applied downstream.
 * <p>
 * The Pando format checks the revision on the refurbish's own change events,
 * so the missing state would never be delivered. Sequoia happens not to check
 * the revision on INSERT and REPLACE events, so it re-applies the state and
 * masks the underlying mistake; that is why this test targets Pando.
 */
@ReplayLogsOnFailure
@InjectFields
class RefurbishLocalReplicaTest extends AbstractMongoDriverTest {
	private static final long EVENT_DELAY_MS = 1_000;

	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.driverSettings(
			Stream.of(PandoFormat.withGraftPoints("/catalog")),
			Stream.of(TestParameters.EventTiming.NORMAL)
		).map(b -> b.applyDriverSettings(s -> s.timescaleMS(LONG_TIMESCALE)));
	}

	@Test
	void refurbishIncludesUpdatesTheLocalReplicaHasNotAppliedYet(TestInfo testInfo) throws Exception {
		Bosk<TestEntity> writer = newBosk(testInfo, driverSettings, "writer");
		writer.driver().flush();
		Refs writerRefs = writer.buildReferences(Refs.class);
		Reference<String> writerRootString = writer.rootReference().then(String.class, "string");

		// The victim's change-event processing is delayed, so an update committed by
		// the writer stays invisible to the victim long enough for a refurbish to
		// read it from the database first.
		MongoDriverSettings victimSettings = driverSettings.toBuilder()
			.testing(MongoDriverSettings.Testing.builder().eventDelayMS(EVENT_DELAY_MS).build())
			.build();

		BlockingGate refurbishGate = new BlockingGate("the refurbish load");
		Bosk<TestEntity> victim;
		MainDriver.setProbes(TestProbes.noop().withBeforeRefurbishLoad(() -> {
			refurbishGate.signal();
			refurbishGate.awaitRelease(Duration.ofSeconds(60));
		}));
		try {
			victim = newBosk(testInfo, victimSettings, "victim");
		} finally {
			MainDriver.resetProbes();
		}
		victim.driver().flush();
		Refs victimRefs = victim.buildReferences(Refs.class);

		// Start the refurbish and hold it before it reads the database, so the
		// update below is committed before the refurbish's snapshot is taken.
		AtomicReference<Throwable> refurbishFailure = new AtomicReference<>();
		Thread refurbisher = new Thread(() -> {
			try {
				victim.getDriver(MongoDriver.class).refurbish();
			} catch (Throwable e) {
				refurbishFailure.set(e);
			}
		});
		refurbisher.start();
		refurbishGate.awaitSignal(Duration.ofSeconds(30));

		Catalog<TestEntity> newCatalog = Catalog.of(
			TestEntity.empty(entity123, writerRefs.childCatalog(entity123)).withString("changed by the writer"));
		writer.driver().submitReplacement(writerRefs.catalog(), newCatalog);
		writer.driver().flush();

		refurbishGate.release();
		refurbisher.join(60_000);
		assertFalse(refurbisher.isAlive(), "Refurbish should finish");
		assertNull(refurbishFailure.get(), "Refurbish should not fail");

		// Change events are ordered, so a marker update whose application the victim
		// waits for proves that the victim has processed every earlier event.
		writer.driver().submitReplacement(writerRootString, "marker");
		writer.driver().flush();
		victim.driver().flush();

		try (var _ = victim.readSession()) {
			assertEquals(newCatalog, victimRefs.catalog().value(),
				"The refurbisher's own replica must include changes the refurbish loaded from the database");
		}
	}

	private Bosk<TestEntity> newBosk(TestInfo testInfo, MongoDriverSettings settings, String name) {
		DriverFactory<TestEntity> factory = (boskInfo, downstream) -> {
			MongoDriver driver = MongoDriver.<TestEntity>factory(
				mongoService.clientSettings(testInfo),
				settings,
				new BsonSerializer())
				.build(boskInfo, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
		return new Bosk<>(
			boskName(name),
			TestEntity.class,
			this::singleEntryInitialState,
			BoskConfig.<TestEntity>builder().driverFactory(factory).build());
	}

	private TestEntity singleEntryInitialState(Bosk<TestEntity> bosk) throws InvalidTypeException {
		Refs refs = bosk.buildReferences(Refs.class);
		return initialRootWithEmptyCatalog(bosk)
			.withCatalog(Catalog.of(TestEntity.empty(entity123, refs.childCatalog(entity123))));
	}
}
