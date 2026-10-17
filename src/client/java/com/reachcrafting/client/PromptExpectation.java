package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Only Prompt When Different: what the player was shown before a request
 * fired, so a confirmation that would only repeat it can be skipped.
 *
 * <p>The expectation is the queue preview at release (GridPreview.commitForRelease)
 * or, for a single click, an orange chain dot on the recipe. A chain offer
 * or a retrieve-then-ask prompt that matches it proceeds as if confirmed; one
 * that differs (the chests held more or less than the cache said, another
 * variant, fewer copies) still asks.</p>
 */
final class PromptExpectation {
	/** A request that has not reached its prompt by then was shown something else. */
	private static final long TTL_MILLIS = 60_000L;

	private record Expectation(String outputItemId, boolean chain, int crafts, int retrievedItems, String source, long millis) {
	}

	private static Expectation current;

	private PromptExpectation() {
	}

	static boolean enabled() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		return config.enabled() && config.onlyPromptWhenDifferent();
	}

	static void clear() {
		current = null;
	}

	/** The queue preview's plan for the request being released (null: nothing was shown). */
	static void recordPreview(GridPreview.Plan plan) {
		if (!enabled() || plan == null || plan.result().isEmpty()) {
			current = null;
			return;
		}
		current = new Expectation(itemId(plan.result()), plan.chain(), plan.expectedCrafts(), plan.retrievedItems(), "preview", System.currentTimeMillis());
	}

	/** A single click on a recipe whose indicator showed a chain: one chain craft of that output. */
	static void recordChainClick(ItemStack output) {
		if (!enabled() || output == null || output.isEmpty()) {
			current = null;
			return;
		}
		current = new Expectation(itemId(output), true, 1, 0, "indicator", System.currentTimeMillis());
	}

	/** A chain offer the player was already shown: proceed without the confirm. Consumes the expectation. */
	static boolean matchesChain(ChainCraftPlan plan) {
		Expectation expected = live();
		if (expected == null || plan == null) {
			return false;
		}
		boolean match = expected.chain()
			&& expected.outputItemId().equals(itemId(plan.finalOutput()))
			&& expected.crafts() == plan.finalRecipeCopies();
		ReachCraftingMod.diag(
			"[prompt_expectation] chain match={} source={} expected={}x{} offered={}x{}",
			match,
			expected.source(),
			expected.crafts(),
			expected.outputItemId(),
			plan.finalRecipeCopies(),
			itemId(plan.finalOutput())
		);
		if (match) {
			current = null;
		}
		return match;
	}

	/**
	 * A retrieve-then-ask prompt the player was already shown: the retrieval
	 * got what the preview predicted, and (when checkCrafts) the remainder
	 * crafts what it showed. The expectation stays for a chain the remainder
	 * may still offer.
	 */
	static boolean matchesRetrieval(String outputItemId, int retrieved, int remainderCrafts, boolean checkCrafts) {
		Expectation expected = live();
		if (expected == null) {
			return false;
		}
		boolean match = expected.retrievedItems() > 0
			&& expected.outputItemId().equals(outputItemId)
			&& expected.retrievedItems() == retrieved
			&& (!checkCrafts || expected.crafts() == remainderCrafts);
		ReachCraftingMod.diag(
			"[prompt_expectation] retrieval match={} expected_retrieved={} retrieved={} expected_crafts={} crafts={} check_crafts={}",
			match,
			expected.retrievedItems(),
			retrieved,
			expected.crafts(),
			remainderCrafts,
			checkCrafts
		);
		return match;
	}

	private static Expectation live() {
		if (!enabled() || current == null) {
			return null;
		}
		if (System.currentTimeMillis() - current.millis() > TTL_MILLIS) {
			current = null;
			return null;
		}
		return current;
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
