package works.bosk.drivers.mongo.internal;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.junit.InjectFields;
import works.bosk.junit.InjectorMethod;
import works.bosk.logback.ReplayLogsOnFailure;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.drivers.mongo.internal.TestParameters.ParameterSet;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * The root object cannot be deleted. {@link works.bosk.BoskDriver#submitDeletion}
 * rejects a root target, and so does a conditional deletion whose precondition
 * matches, because the two are equivalent in that case.
 * <p>
 * A conditional deletion whose precondition does not match is a no-op, which is
 * why the rejection belongs after the precondition, not before it. The local
 * driver behaves this way; these tests require every format to agree.
 */
@ReplayLogsOnFailure
@InjectFields
class ConditionalRootDeletionTest extends AbstractMongoDriverTest {
	@InjectorMethod
	static Stream<ParameterSet> parameterSets() {
		return TestParameters.standardDriverSettings();
	}

	@Test
	void conditionalDeletionOfRootWithMatchingPrecondition_isRejected(TestInfo testInfo) throws Exception {
		Bosk<TestEntity> bosk = newBosk(testInfo);
		Reference<Identifier> rootIDRef = bosk.rootReference().then(Identifier.class, "id");

		assertThrows(IllegalArgumentException.class,
			() -> bosk.driver().submitConditionalDeletion(bosk.rootReference(), rootIDRef, rootID),
			"A conditional deletion of the root with a matching precondition must be rejected like an unconditional one");

		try (var _ = bosk.readSession()) {
			assertEquals(initialRoot(bosk), bosk.rootReference().value(),
				"The rejected deletion must not change the state");
		}
	}

	private Bosk<TestEntity> newBosk(TestInfo testInfo) {
		return new Bosk<>(
			boskName("conditionalRootDeletion"),
			TestEntity.class,
			AbstractMongoDriverTest::initialRoot,
			BoskConfig.<TestEntity>builder().driverFactory(driverFactory).build());
	}
}
