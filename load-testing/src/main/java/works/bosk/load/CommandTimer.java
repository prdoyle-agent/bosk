package works.bosk.load;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Diagnostic command listener: attributes wall-clock time to MongoDB commands.
 * Temporary instrumentation for the Pando one-big-document investigation.
 */
final class CommandTimer implements CommandListener {
	static final CommandTimer INSTANCE = new CommandTimer();

	private final Map<String, Long> starts = new ConcurrentHashMap<>();
	private final Map<String, String> classifications = new ConcurrentHashMap<>();
	private final Map<String, Stats> stats = new ConcurrentHashMap<>();

	private CommandTimer() { }

	void reset() {
		starts.clear();
		classifications.clear();
		stats.clear();
	}

	@Override
	public void commandStarted(CommandStartedEvent event) {
		String key = key(event);
		starts.put(key, System.nanoTime());
		classifications.put(key, classify(event.getCommandName(), event.getCommand()));
	}

	@Override
	public void commandSucceeded(CommandSucceededEvent event) {
		String key = key(event);
		Long start = starts.remove(key);
		String classification = classifications.remove(key);
		if (start == null) {
			return;
		}
		long elapsed = System.nanoTime() - start;
		String name = classification == null ? event.getCommandName() : classification;
		stats.computeIfAbsent(name, _ -> new Stats()).record(elapsed);
	}

	@Override
	public void commandFailed(CommandFailedEvent event) {
		String key = key(event);
		starts.remove(key);
		String classification = classifications.remove(key);
		String name = classification == null ? event.getCommandName() : classification;
		stats.computeIfAbsent(name, _ -> new Stats()).failures.increment();
	}

	String summary() {
		List<Map.Entry<String, Stats>> entries = new ArrayList<>(stats.entrySet());
		entries.sort(Comparator.comparingLong((Map.Entry<String, Stats> e) -> e.getValue().totalNanos()).reversed());
		StringBuilder result = new StringBuilder();
		result.append("\n=== MongoDB command timing (client-observed, measurement phase) ===\n");
		result.append(String.format("%-22s %8s %10s %10s %10s %10s %10s%n",
			"command", "count", "p50 ms", "p95 ms", "p99 ms", "max ms", "total ms"));
		for (Map.Entry<String, Stats> entry : entries) {
			result.append(entry.getValue().format(entry.getKey()));
		}
		result.append("(Durations include client-side dispatch and network round trip, not just server time.)\n");
		return result.toString();
	}

	private static String key(CommandStartedEvent event) {
		return connectionId(event) + ":" + event.getRequestId();
	}

	private static String key(CommandSucceededEvent event) {
		return connectionId(event) + ":" + event.getRequestId();
	}

	private static String key(CommandFailedEvent event) {
		return connectionId(event) + ":" + event.getRequestId();
	}

	private static String connectionId(CommandStartedEvent event) {
		return String.valueOf(event.getConnectionDescription().getConnectionId().getLocalValue());
	}

	private static String connectionId(CommandSucceededEvent event) {
		return String.valueOf(event.getConnectionDescription().getConnectionId().getLocalValue());
	}

	private static String connectionId(CommandFailedEvent event) {
		return String.valueOf(event.getConnectionDescription().getConnectionId().getLocalValue());
	}

	private static String classify(String commandName, BsonDocument command) {
		if ("update".equals(commandName) && command != null) {
			BsonValue updates = command.get("updates");
			if (updates != null && updates.isArray()) {
				BsonArray array = updates.asArray();
				if (!array.isEmpty()) {
					BsonValue update = array.get(0).asDocument().get("u");
					if (update != null && update.isDocument()) {
						BsonDocument updateDoc = update.asDocument();
						if (updateDoc.containsKey("$unset")) {
							return "update-predelete";
						}
						if (updateDoc.containsKey("$inc")) {
							return "update-main";
						}
						if (updateDoc.containsKey("$set")) {
							return "update-sentinel";
						}
					}
				}
			}
			return "update-other";
		}
		return commandName;
	}

	private static final class Stats {
		final ConcurrentLinkedQueue<Long> samples = new ConcurrentLinkedQueue<>();
		final LongAdder failures = new LongAdder();

		void record(long nanos) {
			samples.add(nanos);
		}

		long totalNanos() {
			long total = 0;
			for (long sample : samples) {
				total += sample;
			}
			return total;
		}

		String format(String name) {
			long[] values = samples.stream().mapToLong(Long::longValue).sorted().toArray();
			if (values.length == 0) {
				return String.format("%-22s %8d %10s %10s %10s %10s %10.1f%n",
					name, 0, "-", "-", "-", "-", 0.0);
			}
			return String.format("%-22s %8d %10.3f %10.3f %10.3f %10.3f %10.1f%s%n",
				name,
				values.length,
				percentile(values, 0.50) / 1_000_000.0,
				percentile(values, 0.95) / 1_000_000.0,
				percentile(values, 0.99) / 1_000_000.0,
				values[values.length - 1] / 1_000_000.0,
				totalNanos() / 1_000_000.0,
				failures.sum() == 0 ? "" : "  failures=" + failures.sum());
		}

		private static double percentile(long[] sorted, double p) {
			int index = (int) Math.ceil(p * sorted.length) - 1;
			return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
		}
	}
}
