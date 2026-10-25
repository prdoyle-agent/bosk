package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.OverloadController.Mode.OVERLOAD;
import static works.bosk.OverloadController.Mode.PROBATION;
import static works.bosk.OverloadController.Mode.STANDARD;
import static works.bosk.OverloadController.OverloadDecision.CONTINUE;
import static works.bosk.OverloadController.OverloadDecision.START_PROBATION;

class OverloadControllerTest {
	final OverloadController controller = new OverloadController(10, 0.5);

	@Test
	void startsInStandardMode() {
		assertEquals(STANDARD, controller.mode(), "A new controller should start in standard mode");
	}

	@Test
	void lowWaterMarkIsAFractionOfTheQueueLimit() {
		assertEquals(5, controller.lowWaterMark(), "Half of a ten-entry queue should be the default low-water mark");
	}

	@Test
	void lowBacklogStartsAProbation() {
		controller.enterOverload();
		assertEquals(START_PROBATION, controller.observeBacklog(5), "A low backlog should start a standard-mode trial");
		assertEquals(PROBATION, controller.mode(), "The driver should be in probation after a low backlog observation");
		assertEquals(10, controller.probationRemaining(), "The trial should require one queue's worth of work");
	}

	@Test
	void highBacklogResetsTheQuietStreak() {
		controller.enterOverload();
		startAndFailTrial();
		assertEquals(2, controller.requiredQuietGenerations(), "One failed trial should require two quiet generations");

		assertEquals(CONTINUE, controller.observeBacklog(1), "First quiet generation should not be enough");
		assertEquals(CONTINUE, controller.observeBacklog(6), "A high backlog should reset the streak");
		assertEquals(CONTINUE, controller.observeBacklog(1), "A new quiet streak should start over");
		assertEquals(START_PROBATION, controller.observeBacklog(1), "Two consecutive quiet generations should start a trial");
	}

	@Test
	void busyGenerationResetsTheQuietStreak() {
		controller.enterOverload();
		controller.recordBusyGeneration();
		controller.recordBusyGeneration();
		assertEquals(START_PROBATION, controller.observeBacklog(1), "A fresh quiet generation should start the first trial");
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

		assertEquals(CONTINUE, controller.observeBacklog(1), "One quiet generation should no longer be enough");
		assertEquals(START_PROBATION, controller.observeBacklog(1), "Two quiet generations should start the next trial");
	}

	@Test
	void repeatedFailuresGrowTheRequiredQuietGenerations() {
		controller.enterOverload();
		for (int i = 0; i < 20; i++) {
			startAndFailTrial();
		}
		assertEquals(1024, controller.requiredQuietGenerations(), "The required evidence should be capped");
	}

	private void startAndFailTrial() {
		while (controller.mode() == OVERLOAD) {
			controller.observeBacklog(1);
		}
		assertEquals(PROBATION, controller.mode(), "A trial should start after the required quiet generations");
		controller.failProbation();
	}
}
