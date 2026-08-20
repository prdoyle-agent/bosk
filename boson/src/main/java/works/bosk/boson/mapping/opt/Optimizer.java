package works.bosk.boson.mapping.opt;

import works.bosk.boson.mapping.TypeMap;

public class Optimizer {

	/**
	 * Given a {@link TypeMap}, returns a copy of it.
	 * <p>
	 * Requires that the input {@link TypeMap} is {@link TypeMap#isFrozen() frozen}.
	 * Optimization produces the best results when all types are fully specified
	 * before optimization begins;
	 * requiring a frozen map helps avoid mistakenly optimizing
	 * a type map that is still under construction.
	 * <p>
	 * No optimization is currently applied: the one pass we had, inlining
	 * scalar refs, turned out to be counterproductive (see git log for the
	 * measurements), and the copy is all that the consumers need. Revisit the
	 * policy once the generated methods are small enough that inlining a scalar
	 * into them would pay.
	 */
	public TypeMap optimize(TypeMap original) {
		assert original.isFrozen():
			"TypeMap must be frozen before optimization; " +
				"ensure all types are specified and then call freeze()";
		return TypeMap.copyOf(original);
	}

}
