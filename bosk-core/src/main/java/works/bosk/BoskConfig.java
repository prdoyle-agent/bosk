package works.bosk;

import static java.util.Objects.requireNonNull;

/**
 * @param driverFactory      the factory used to build the driver stack
 * @param registrarFactory   the factory used to build the hook registrar
 * @param hookQueueMaxSize   the maximum number of queued hook executions before the local driver
 *                           switches to overload mode; defaults to 1000. Raising the limit allows
 *                           more state snapshots to accumulate before coalescing, so it also raises
 *                           the queue's memory bound.
 * @param hookExitThreshold  the maximum combined queued and pending hook backlog at which the
 *                           local driver may try standard mode again; defaults to 8. Lower
 *                           values make the driver stay in overload mode longer before making
 *                           another attempt.
 */
public record BoskConfig<R extends StateTreeNode> (
	DriverFactory<R> driverFactory,
	RegistrarFactory registrarFactory,
	int hookQueueMaxSize,
	int hookExitThreshold
) {

	public BoskConfig {
		driverFactory = requireNonNull(driverFactory);
		registrarFactory = requireNonNull(registrarFactory);
		if (hookQueueMaxSize < 1) {
			throw new IllegalArgumentException("hookQueueMaxSize must be positive");
		}
		if (hookExitThreshold < 1) {
			throw new IllegalArgumentException("hookExitThreshold must be positive");
		}
	}

	/**
	 * Uses the default hook exit threshold.
	 */
	public BoskConfig(DriverFactory<R> driverFactory, RegistrarFactory registrarFactory, int hookQueueMaxSize) {
		this(driverFactory, registrarFactory, hookQueueMaxSize, DEFAULT_HOOK_EXIT_THRESHOLD);
	}

	/**
	 * Uses the default hook queue limit and exit threshold.
	 */
	public BoskConfig(DriverFactory<R> driverFactory, RegistrarFactory registrarFactory) {
		this(driverFactory, registrarFactory, DEFAULT_HOOK_QUEUE_MAX_SIZE, DEFAULT_HOOK_EXIT_THRESHOLD);
	}

	/**
	 * Calls {@code BoskConfig.builder().build()} and returns the result.
	 * To customize the config, it's convenient to inline this method
	 * and then call some additional methods on the builder.
	 */
	public static <RR extends StateTreeNode> BoskConfig<RR> simple() {
		return BoskConfig.<RR>builder().build();
	}

	public static <R extends StateTreeNode> Builder<R> builder() {
		return new Builder<>();
	}

	/**
	 * @return a {@link DriverFactory} with only the basic functionality.
	 */
	@SuppressWarnings("unchecked")
	public static <RR extends StateTreeNode> DriverFactory<RR> simpleDriver() {
		return (DriverFactory<RR>) SIMPLE_DRIVER_FACTORY;
	}

	/**
	 * @return a {@link RegistrarFactory} with only the basic functionality.
	 */
	public static RegistrarFactory simpleRegistrar() {
		return SIMPLE_REGISTRAR_FACTORY;
	}

	public static class Builder<R extends StateTreeNode> {
		private DriverFactory<R> driverFactory;
		private RegistrarFactory registrarFactory;
		private int hookQueueMaxSize = DEFAULT_HOOK_QUEUE_MAX_SIZE;
		private int hookExitThreshold = DEFAULT_HOOK_EXIT_THRESHOLD;

		Builder() {
			driverFactory = simpleDriver();
			registrarFactory = simpleRegistrar();
		}

		public Builder<R> driverFactory(DriverFactory<R> driverFactory) {
			this.driverFactory = requireNonNull(driverFactory);
			return this;
		}

		public Builder<R> registrarFactory(RegistrarFactory registrarFactory) {
			this.registrarFactory = requireNonNull(registrarFactory);
			return this;
		}

		/**
		 * Sets the number of queued hook executions at which the local driver
		 * switches to overload mode.
		 */
		public Builder<R> hookQueueMaxSize(int hookQueueMaxSize) {
			this.hookQueueMaxSize = hookQueueMaxSize;
			return this;
		}

		/**
		 * Sets the maximum combined queued and pending hook backlog at which the
		 * local driver may try standard mode again.
		 */
		public Builder<R> hookExitThreshold(int hookExitThreshold) {
			this.hookExitThreshold = hookExitThreshold;
			return this;
		}

		public BoskConfig<R> build() {
			return new BoskConfig<>(
				this.driverFactory,
				this.registrarFactory,
				this.hookQueueMaxSize,
				this.hookExitThreshold
			);
		}

		@Override
		public String toString() {
			return "BoskConfig.Builder(driverFactory=" + this.driverFactory + ", registrarFactory=" + this.registrarFactory + ", hookQueueMaxSize=" + this.hookQueueMaxSize + ", hookExitThreshold=" + this.hookExitThreshold + ")";
		}
	}

	private static final int DEFAULT_HOOK_QUEUE_MAX_SIZE = 1000;
	private static final int DEFAULT_HOOK_EXIT_THRESHOLD = 8;
	private static final DriverFactory<?> SIMPLE_DRIVER_FACTORY = (_, d) -> d;
	private static final RegistrarFactory SIMPLE_REGISTRAR_FACTORY = (_, d) -> d;
}
