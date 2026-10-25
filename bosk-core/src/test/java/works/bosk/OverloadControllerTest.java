package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.OverloadController.Mode.CATCH_UP;
import static works.bosk.OverloadController.Mode.OVERLOAD;
import static works.bosk.OverloadController.Mode.PROBATION;
import static works.bosk.OverloadController.Mode.STANDARD;
import static works.bosk.OverloadController.ProbationResult.CONTINUE;
import static works.bosk.OverloadController.ProbationResult.FAILED;
import static works.bosk.OverloadController.ProbationResult.SUCCEEDED;

class OverloadControllerTest {
	final OverloadController controller = new OverloadController(10);

	@Test
	void startsInStandardModeWithABaseProbeBudget() {
		assertEquals(STANDARD, controller.mode(), "A new controller should start in standard mode");
		assertEquals(50, controller.probeBudget(), "The initial probe budget should be the fixed base value");
	}

	@Test
	void baseProbeBudgetIsFixed() {
		assertEquals(50, new OverloadController(1000).probeBudget(), "Large queues should use the fixed base probe budget");
		assertEquals(50, new OverloadController(10).probeBudget(), "Small queues should also use the fixed base probe budget");
	}

	@Test
	void probationRunsForTheProbeBudget() {
		controller.enterOverload();
		controller.startProbation(5);
		assertEquals(PROBATION, controller.mode(), "Starting a trial should enter probation mode");
		assertEquals(50, controller.probeRemaining(), "The trial should use the base probe budget");
	}

	@Test
	void probationSucceedsWhenHalfTheBacklogDrains() {
		controller.enterOverload();
		controller.startProbation(10);
		for (int i = 0; i < 49; i++) {
			assertEquals(CONTINUE, controller.recordProbationExecution(5), "The trial should not finish early");
		}
		assertEquals(SUCCEEDED, controller.recordProbationExecution(5), "The trial should succeed after the budget");
		assertEquals(CATCH_UP, controller.mode(), "A successful trial should start the final catch-up");
	}

	@Test
	void probationFailsWhenTheBacklogDoesNotDrain() {
		controller.enterOverload();
		controller.startProbation(10);
		for (int i = 0; i < 49; i++) {
			controller.recordProbationExecution(6);
		}
		assertEquals(FAILED, controller.recordProbationExecution(6), "The trial should fail without a clear drain");
		assertEquals(OVERLOAD, controller.mode(), "A failed trial should return to overload mode");
	}

	@Test
	void probationAbortsEarlyWhenTheBacklogIsNotDraining() {
		controller.enterOverload();
		controller.startProbation(10);
		for (int i = 0; i < 4; i++) {
			assertEquals(CONTINUE, controller.recordProbationExecution(10), "The trial should keep sampling");
		}
		assertEquals(FAILED, controller.recordProbationExecution(10), "A flat backlog should abort the trial");
		assertEquals(OVERLOAD, controller.mode(), "An early abort should return to overload mode");
	}

	@Test
	void probationFailureReturnsToOverload() {
		controller.enterOverload();
		controller.startProbation(10);
		controller.probationFailed();
		assertEquals(OVERLOAD, controller.mode(), "A probation overflow should return to overload mode");
	}

	@Test
	void catchUpFinishesInStandardMode() {
		controller.enterOverload();
		controller.startProbation(10);
		for (int i = 0; i < 49; i++) {
			controller.recordProbationExecution(5);
		}
		controller.recordProbationExecution(5);
		controller.catchUpFinished(true);
		assertEquals(STANDARD, controller.mode(), "A completed catch-up should enter standard mode");
	}

	@Test
	void catchUpFailureReturnsToOverload() {
		controller.enterOverload();
		controller.startProbation(10);
		for (int i = 0; i < 49; i++) {
			controller.recordProbationExecution(5);
		}
		controller.recordProbationExecution(5);
		controller.catchUpFailed();
		assertEquals(OVERLOAD, controller.mode(), "A failed catch-up should return to overload mode");
	}

	@Test
	void earlyStandardOverflowDoublesTheProbeBudget() {
		completeSuccessfulProbation();
		assertEquals(50, controller.probeBudget(), "The base probe budget should be unchanged");
		controller.enterOverload();
		assertEquals(100, controller.probeBudget(), "An early standard overflow should double the probe budget");
	}

	@Test
	void lateStandardOverflowDoesNotGrowTheProbeBudget() {
		completeSuccessfulProbation();
		for (int i = 0; i < 10; i++) {
			controller.recordStandardExecution();
		}
		controller.enterOverload();
		assertEquals(50, controller.probeBudget(), "A validated probe should not be extended by a later overflow");
	}

	@Test
	void probeBudgetDecaysAfterSustainedStandardWork() {
		completeSuccessfulProbation();
		controller.enterOverload();
		assertEquals(100, controller.probeBudget(), "An early overflow should double the probe budget");

		completeSuccessfulProbation();
		for (int i = 0; i < 20; i++) {
			controller.recordStandardExecution();
		}
		assertEquals(99, controller.probeBudget(), "Sustained standard work should decay the probe budget linearly");
	}

	@Test
	void probeBudgetGrowthIsCapped() {
		for (int i = 0; i < 20; i++) {
			completeSuccessfulProbation();
			controller.enterOverload();
		}
		assertEquals(500, controller.probeBudget(), "Probe budget growth should be capped at 10x the base");
	}

	@Test
	void returnToOverloadPreservesTheProbeBudget() {
		controller.enterOverload();
		controller.startProbation(10);
		controller.returnToOverload();
		assertEquals(OVERLOAD, controller.mode(), "An interrupted trial should return to overload mode");
		assertEquals(50, controller.probeBudget(), "An interrupted trial should not change the probe budget");
	}

	private void completeSuccessfulProbation() {
		if (controller.mode() == STANDARD) {
			controller.enterOverload();
		}
		controller.startProbation(10);
		for (int i = 1; i < controller.probeBudget(); i++) {
			assertEquals(CONTINUE, controller.recordProbationExecution(5), "The trial should continue");
		}
		assertEquals(SUCCEEDED, controller.recordProbationExecution(5), "The trial should succeed");
		controller.catchUpFinished(true);
		assertEquals(STANDARD, controller.mode(), "The catch-up should enter standard mode");
	}
}
