package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoskConfigTest {
	record TestState() implements StateTreeNode {}

	@Test
	void defaultExitThresholdIsSmall() {
		BoskConfig<TestState> config = BoskConfig.<TestState>builder().build();
		assertEquals(8, config.hookExitThreshold(), "The default exit threshold should allow only a small backlog");
	}

	@Test
	void exitThresholdMustBePositive() {
		assertThrows(IllegalArgumentException.class,
			() -> BoskConfig.<TestState>builder().hookExitThreshold(0).build(),
			"A zero exit threshold would never leave overload mode");
	}
}
