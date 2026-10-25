package works.bosk;

/**
 * Decides when the local driver should leave overload mode.
 *
 * <p>
 * Overload mode is left in two steps. First, the backlog must remain low for
 * enough consecutive generations. Then the driver runs a bounded trial of
 * standard mode. The driver commits to standard mode only if that trial
 * completes without overflowing the queue. A failed trial increases the
 * number of quiet generations required before the next trial.
 */
final class OverloadController {
	enum Mode {
		STANDARD,
		OVERLOAD,
		PROBATION
	}

	private final int queueMaxSize;
	private final int exitThreshold;

	/**
	 * Volatile because the draining thread reads the mode outside the driver's monitor.
	 */
	private volatile Mode mode = Mode.STANDARD;
	private int lowBacklogStreak;
	private int requiredQuietGenerations = 1;
	private int probationRemaining;

	OverloadController(int queueMaxSize, int exitThreshold) {
		this.queueMaxSize = queueMaxSize;
		this.exitThreshold = exitThreshold;
	}

	Mode mode() {
		return mode;
	}

	int backlogThreshold() {
		return Math.min(queueMaxSize, Math.max(1, exitThreshold));
	}

	synchronized int requiredQuietGenerations() {
		return requiredQuietGenerations;
	}

	synchronized int probationRemaining() {
		return probationRemaining;
	}

	synchronized void enterOverload() {
		assert mode == Mode.STANDARD : "Can only enter overload mode from standard mode";
		mode = Mode.OVERLOAD;
		lowBacklogStreak = 0;
		requiredQuietGenerations = 1;
		probationRemaining = 0;
	}

	/**
	 * Records an overload generation that still had pending work, so it does
	 * not count as evidence that the backlog has drained.
	 */
	synchronized void recordBusyGeneration() {
		assert mode == Mode.OVERLOAD : "Busy generations only occur in overload mode";
		lowBacklogStreak = 0;
	}

	/**
	 * Records the backlog at a generation boundary.
	 *
	 * @param backlog queued hook executions plus pending hook notifications
	 * @return true if the driver should start a standard-mode trial
	 */
	synchronized boolean observeBacklog(int backlog) {
		assert mode == Mode.OVERLOAD : "Backlog observations only occur in overload mode";
		if (backlog <= backlogThreshold()) {
			lowBacklogStreak++;
			if (lowBacklogStreak >= requiredQuietGenerations) {
				mode = Mode.PROBATION;
				probationRemaining = Math.max(1, queueMaxSize);
				lowBacklogStreak = 0;
				return true;
			}
		} else {
			lowBacklogStreak = 0;
		}
		return false;
	}

	/**
	 * Records one completed hook execution during a standard-mode trial.
	 *
	 * @return true if the trial completed successfully
	 */
	synchronized boolean recordProbationExecution() {
		assert mode == Mode.PROBATION : "Probation executions only occur in probation mode";
		probationRemaining--;
		if (probationRemaining <= 0) {
			mode = Mode.STANDARD;
			lowBacklogStreak = 0;
			requiredQuietGenerations = 1;
			probationRemaining = 0;
			return true;
		}
		return false;
	}

	/**
	 * Abandons a standard-mode trial because the queue overflowed.
	 */
	synchronized void failProbation() {
		assert mode == Mode.PROBATION : "Can only fail a trial while in probation mode";
		mode = Mode.OVERLOAD;
		lowBacklogStreak = 0;
		requiredQuietGenerations = Math.min(MAX_REQUIRED_QUIET_GENERATIONS, requiredQuietGenerations * 2);
	}

	/**
	 * Returns to overload mode after an interrupted batch. Any backoff already
	 * accumulated is preserved.
	 */
	synchronized void returnToOverload() {
		mode = Mode.OVERLOAD;
		lowBacklogStreak = 0;
		probationRemaining = 0;
	}

	private static final int MAX_REQUIRED_QUIET_GENERATIONS = 1000;
}
