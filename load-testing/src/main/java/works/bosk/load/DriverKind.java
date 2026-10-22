package works.bosk.load;

import java.util.Locale;

/**
 * The driver under test.
 *
 * <p>This first slice only supports the in-memory driver. The Mongo variants will be added
 * once the harness has a baseline to compare against.
 */
public enum DriverKind {
	SIMPLE;

	public static DriverKind fromCli(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "simple" -> SIMPLE;
			default -> throw new IllegalArgumentException("Unsupported driver \"" + value + "\"; only \"simple\" is implemented");
		};
	}
}
