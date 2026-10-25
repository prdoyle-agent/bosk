package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoskConfigTest {
	record TestState() implements StateTreeNode {}

	@Test
	void defaultExitThresholdIsHalf() {
		BoskConfig<TestState> config = BoskConfig.<TestState>builder().build();
		assertEquals(0.5, config.hookExitThreshold(), "The default exit threshold should require 2x headroom");
	}

	@Test
	void exitThresholdMustBePositive() {
		assertThrows(IllegalArgumentException.class,
			() -> BoskConfig.<TestState>builder().hookExitThreshold(0).build(),
			"A zero exit threshold would never leave overload mode");
	}

	@Test
	void exitThresholdMustNotExceedOne() {
		assertThrows(IllegalArgumentException.class,
			() -> BoskConfig.<TestState>builder().hookExitThreshold(1.01).build(),
			"An exit threshold above the queue limit is not meaningful");
	}
}
