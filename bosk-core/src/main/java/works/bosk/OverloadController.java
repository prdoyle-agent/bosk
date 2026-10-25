package works.bosk;

/**
 * Decides when the local driver should leave overload mode.
 *
 * <p>
 * Overload mode is left through a bounded standard-mode trial. The trial starts
 * when the pending set has drained and there is queued work to test. It succeeds
 * only if at least half of the backlog has drained by the end of its work budget.
 * A successful trial runs one final overload catch-up generation before the
 * driver returns to standard mode.
 *
 * <p>
 * A standard-mode overflow soon after a successful trial means the trial was too
 * short. The next trial is longer. After sustained standard-mode work, the trial
 * length decays linearly back to its base value.
 */
final class OverloadController {
	enum Mode {
		STANDARD,
		OVERLOAD,
		PROBATION,
		CATCH_UP
	}

	enum ProbationResult {
		CONTINUE,
		SUCCEEDED,
		FAILED
	}

	private final int queueMaxSize;
	private final int baseProbeBudget;
	private final int maxProbeBudget;

	/**
	 * Volatile because the draining thread reads the mode outside the driver's monitor.
	 */
	private volatile Mode mode = Mode.STANDARD;
	private int probeBudget;
	private int probeRemaining;
	private int probeExecutions;
	private int probeStartBacklog;
	private boolean postProbationOverflowWindow;
	private int standardExecutions;
	private int decayExecutions;

	OverloadController(int queueMaxSize) {
		this.queueMaxSize = queueMaxSize;
		this.baseProbeBudget = Math.max(1, Math.min(queueMaxSize, MAX_BASE_PROBE_BUDGET));
		this.maxProbeBudget = (int) Math.min((long) baseProbeBudget * MAX_PROBE_MULTIPLIER, Integer.MAX_VALUE);
		this.probeBudget = baseProbeBudget;
	}

	Mode mode() {
		return mode;
	}

	synchronized int probeBudget() {
		return probeBudget;
	}

	synchronized int probeRemaining() {
		return probeRemaining;
	}

	/**
	 * Enters overload mode after a standard-mode queue overflow.
	 */
	synchronized void enterOverload() {
		assert mode == Mode.STANDARD : "Overload mode is entered from standard mode";
		if (postProbationOverflowWindow) {
			probeBudget = (int) Math.min((long) maxProbeBudget, (long) probeBudget * 2);
		}
		mode = Mode.OVERLOAD;
		postProbationOverflowWindow = false;
		standardExecutions = 0;
		decayExecutions = 0;
		probeRemaining = 0;
	}

	/**
	 * Starts a standard-mode trial from overload mode.
	 *
	 * @param startBacklog the backlog when the trial begins
	 */
	synchronized void startProbation(int startBacklog) {
		assert mode == Mode.OVERLOAD : "Probation starts from overload mode";
		mode = Mode.PROBATION;
		probeStartBacklog = startBacklog;
		probeRemaining = probeBudget;
		probeExecutions = 0;
	}

	/**
	 * Records one completed hook execution during a trial.
	 *
	 * @param backlog the backlog after the execution
	 */
	synchronized ProbationResult recordProbationExecution(int backlog) {
		assert mode == Mode.PROBATION : "Probation executions only occur in probation mode";
		probeExecutions++;
		probeRemaining--;
		if (probeExecutions >= minimumProbeExecutions() && backlog >= probeStartBacklog) {
			mode = Mode.OVERLOAD;
			return ProbationResult.FAILED;
		}
		if (probeRemaining > 0) {
			return ProbationResult.CONTINUE;
		}
		if ((long) backlog * 2 <= probeStartBacklog) {
			mode = Mode.CATCH_UP;
			return ProbationResult.SUCCEEDED;
		} else {
			mode = Mode.OVERLOAD;
			return ProbationResult.FAILED;
		}
	}

	/**
	 * Returns to overload mode after an interrupted batch. Any probe budget
	 * adjustment is preserved.
	 */
	synchronized void returnToOverload() {
		mode = Mode.OVERLOAD;
		postProbationOverflowWindow = false;
		probeRemaining = 0;
	}

	/**
	 * Abandons a trial because the queue reached its limit.
	 */
	synchronized void probationFailed() {
		assert mode == Mode.PROBATION : "A trial can only fail while in probation mode";
		mode = Mode.OVERLOAD;
		probeRemaining = 0;
	}

	private int minimumProbeExecutions() {
		return Math.min(MIN_PROBE_EXECUTIONS, baseProbeBudget);
	}

	/**
	 * Completes the final catch-up generation.
	 *
	 * @param canUseStandard true if standard mode can take over
	 */
	synchronized void catchUpFinished(boolean canUseStandard) {
		assert mode == Mode.CATCH_UP : "Catch-up completion requires catch-up mode";
		if (canUseStandard) {
			mode = Mode.STANDARD;
			postProbationOverflowWindow = true;
			standardExecutions = 0;
			decayExecutions = 0;
		} else {
			mode = Mode.OVERLOAD;
			postProbationOverflowWindow = false;
			probeRemaining = 0;
		}
	}

	/**
	 * Abandons the final catch-up generation because the queue overflowed.
	 */
	synchronized void catchUpFailed() {
		assert mode == Mode.CATCH_UP : "Catch-up can only fail while in catch-up mode";
		mode = Mode.OVERLOAD;
		postProbationOverflowWindow = false;
		probeRemaining = 0;
	}

	/**
	 * Records one completed standard-mode hook execution. The first queue's
	 * worth of executions validates a preceding trial; after that, each queue's
	 * worth slowly reduces an inflated probe budget.
	 */
	synchronized void recordStandardExecution() {
		if (mode != Mode.STANDARD) {
			return;
		}
		if (postProbationOverflowWindow) {
			standardExecutions++;
			if (standardExecutions >= queueMaxSize) {
				postProbationOverflowWindow = false;
				standardExecutions = 0;
				decayExecutions = 0;
			}
		} else {
			decayExecutions++;
			if (decayExecutions >= queueMaxSize) {
				decayExecutions = 0;
				if (probeBudget > baseProbeBudget) {
					probeBudget--;
				}
			}
		}
	}

	private static final int MAX_BASE_PROBE_BUDGET = 64;
	private static final int MIN_PROBE_EXECUTIONS = 8;
	private static final int MAX_PROBE_MULTIPLIER = 16;
}
