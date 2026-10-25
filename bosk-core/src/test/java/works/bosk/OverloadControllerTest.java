package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.OverloadController.Mode.OVERLOAD;
import static works.bosk.OverloadController.Mode.PROBATION;
import static works.bosk.OverloadController.Mode.STANDARD;

class OverloadControllerTest {
	final OverloadController controller = new OverloadController(10, 5);

	@Test
	void startsInStandardMode() {
		assertEquals(STANDARD, controller.mode(), "A new controller should start in standard mode");
	}

	@Test
	void backlogThresholdIsTheConfiguredValue() {
		assertEquals(5, controller.backlogThreshold(), "The backlog threshold should be the configured value");
	}

	@Test
	void backlogThresholdIsCappedByTheQueueLimit() {
		assertEquals(3, new OverloadController(3, 8).backlogThreshold(), "The backlog threshold should never exceed the queue limit");
	}

	@Test
	void lowBacklogStartsAProbation() {
		controller.enterOverload();
		assertTrue(controller.observeBacklog(5), "A low backlog should start a standard-mode trial");
		assertEquals(PROBATION, controller.mode(), "The driver should be in probation after a low backlog observation");
		assertEquals(10, controller.probationRemaining(), "The trial should require one queue's worth of work");
	}

	@Test
	void highBacklogResetsTheQuietStreak() {
		controller.enterOverload();
		startAndFailTrial();
		assertEquals(2, controller.requiredQuietGenerations(), "One failed trial should require two quiet generations");

		assertFalse(controller.observeBacklog(1), "First quiet generation should not be enough");
		assertFalse(controller.observeBacklog(6), "A high backlog should reset the streak");
		assertFalse(controller.observeBacklog(1), "A new quiet streak should start over");
		assertTrue(controller.observeBacklog(1), "Two consecutive quiet generations should start a trial");
	}

	@Test
	void busyGenerationResetsTheQuietStreak() {
		controller.enterOverload();
		controller.recordBusyGeneration();
		controller.recordBusyGeneration();
		assertTrue(controller.observeBacklog(1), "A fresh quiet generation should start the first trial");
	}

	@Test
	void probationSucceedsAfterAQueueWorthOfExecutions() {
		controller.enterOverload();
		controller.observeBacklog(1);
		for (int i = 0; i < 9; i++) {
			assertFalse(controller.recordProbationExecution(), "The trial should not finish early");
		}
		assertTrue(controller.recordProbationExecution(), "The tenth execution should complete the trial");
		assertEquals(STANDARD, controller.mode(), "A completed trial should commit to standard mode");
	}

	@Test
	void failedProbationRequiresMoreQuietGenerations() {
		controller.enterOverload();
		controller.observeBacklog(1);
		controller.failProbation();
		assertEquals(OVERLOAD, controller.mode(), "A failed trial should return to overload mode");
		assertEquals(2, controller.requiredQuietGenerations(), "A failed trial should double the quiet evidence required");

		assertFalse(controller.observeBacklog(1), "One quiet generation should no longer be enough");
		assertTrue(controller.observeBacklog(1), "Two quiet generations should start the next trial");
	}

	@Test
	void repeatedFailuresGrowTheRequiredQuietGenerations() {
		controller.enterOverload();
		for (int i = 0; i < 20; i++) {
			startAndFailTrial();
		}
		assertEquals(1000, controller.requiredQuietGenerations(), "The required evidence should be capped");
	}

	private void startAndFailTrial() {
		while (controller.mode() == OVERLOAD) {
			controller.observeBacklog(1);
		}
		assertEquals(PROBATION, controller.mode(), "A trial should start after the required quiet generations");
		controller.failProbation();
	}
}
