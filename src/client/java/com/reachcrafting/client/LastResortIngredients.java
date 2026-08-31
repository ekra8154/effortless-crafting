package com.reachcrafting.client;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Item categories to spend LAST when a recipe slot accepts several
 * alternatives.
 *
 * <p>Some ingredients cost real work to produce and are annoying to have
 * silently drained by a craft that would have been just as happy with the
 * cheap alternative -- stripped logs being the motivating case, since a slot
 * that accepts "any spruce log" will happily eat the stripped ones you made on
 * purpose. Membership here only ever reorders the alternatives a slot already
 * accepts: nothing is excluded, so nothing becomes uncraftable, and a recipe
 * that genuinely requires the category (its slot accepts stripped logs only)
 * is unaffected because every candidate is deprioritised equally and the tier
 * falls through.</p>
 *
 * <p>Adding a category later is one entry in {@link #RULES} plus the config
 * flag that turns it on.</p>
 */
final class LastResortIngredients {
	/** Stable id, also what {@link IngredientPlanning.Policy} carries. */
	static final String STRIPPED_LOGS = "stripped_logs";

	private record Rule(String category, Predicate<String> matches) {
	}

	private static final List<Rule> RULES = List.of(
		// Convention-based rather than tag-based: item ids are all the planner
		// has here, and both vanilla and modded woods name these
		// stripped_<wood>_log / stripped_<wood>_wood. A modded wood that breaks
		// the convention just misses out on the preference.
		new Rule(STRIPPED_LOGS, itemId -> itemPath(itemId).startsWith("stripped_"))
	);

	private LastResortIngredients() {
	}

	/** Categories the current config wants spent last. */
	static Set<String> activeCategories(ReachCraftingConfig config) {
		Set<String> active = new LinkedHashSet<>();
		if (config.preferNonStrippedLogs()) {
			active.add(STRIPPED_LOGS);
		}
		return Set.copyOf(active);
	}

	/** Whether {@code itemId} falls in any of the given active categories. */
	static boolean isLastResort(String itemId, Set<String> activeCategories) {
		if (activeCategories.isEmpty() || itemId == null) {
			return false;
		}
		for (Rule rule : RULES) {
			if (activeCategories.contains(rule.category()) && rule.matches().test(itemId)) {
				return true;
			}
		}
		return false;
	}

	private static String itemPath(String itemId) {
		int separator = itemId.indexOf(':');
		return separator < 0 ? itemId : itemId.substring(separator + 1);
	}
}
