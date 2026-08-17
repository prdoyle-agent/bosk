import java.nio.file.Path;
import works.bosk.boson.TestUtils;

/**
 * Generates the large JSON test file that ParseBenchmark reads by relative path.
 * Compiled and run by the parse-benchmark workflow against the test runtime classpath.
 */
public class BigfileGen {
	public static void main(String[] args) throws Exception {
		Path file = Path.of("boson/build/bigfiles/100k.json");
		file.toFile().getParentFile().mkdirs();
		TestUtils.writeRandomToFile(file, Integer.parseInt(args[0]));
	}
}
