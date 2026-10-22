package works.bosk.load;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of one load run.
 *
 * @param runId               identifies this run in the CSV output
 * @param startedAt           when the run began
 * @param config              the configuration that produced the run
 * @param environment         the machine and JVM that ran it
 * @param measuredNanos       the actual measurement duration
 * @param ops                 operations completed during measurement
 * @param errors              exceptions seen by the workers or the verifier
 * @param submitLatency       update submission latency
 * @param flushLatency        flush latency; empty when the run never flushed during measurement
 * @param verified            whether the final state matched the expected state
 * @param verificationMessage why verification failed, or {@code null}
 * @param stalled             whether a worker failed to stop before the stall timeout
 * @param firstError          the first exception seen, or {@code null}
 * @param heapUsedBefore      heap in use before the workers started
 * @param heapUsedAfter       heap in use after the workers stopped
 * @param gcCountBefore       total GC count before the run
 * @param gcCountAfter        total GC count after the run
 * @param gcTimeMillisBefore  total GC time before the run
 * @param gcTimeMillisAfter   total GC time after the run
 * @param samples             periodic JVM observations from the measurement phase
 */
public record LoadResult(
	String runId,
	Instant startedAt,
	LoadConfig config,
	EnvironmentInfo environment,
	long measuredNanos,
	long ops,
	long errors,
	LatencySummary submitLatency,
	LatencySummary flushLatency,
	boolean verified,
	String verificationMessage,
	boolean stalled,
	Throwable firstError,
	long heapUsedBefore,
	long heapUsedAfter,
	long gcCountBefore,
	long gcCountAfter,
	long gcTimeMillisBefore,
	long gcTimeMillisAfter,
	List<MetricsSample> samples
) {

	public double throughputOpsPerSecond() {
		if (measuredNanos <= 0) {
			return 0;
		} else {
			return ops / (measuredNanos / 1_000_000_000.0);
		}
	}

	public long heapUsedDelta() {
		return heapUsedAfter - heapUsedBefore;
	}

	public long gcCountDelta() {
		return gcCountAfter - gcCountBefore;
	}

	public long gcTimeMillisDelta() {
		return gcTimeMillisAfter - gcTimeMillisBefore;
	}
}
