package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/**
 * Scores the variants of one recipe collection by how much of the material
 * that actually DECIDES the variant the player can reach.
 *
 * <p>A sign is six planks and a stick. The stick is the same item in every
 * variant of the collection and can be made from any wood, so counting it
 * would credit every variant with every log the player owns and drown out the
 * plank slot that is actually choosing oak over spruce. Only slots whose
 * accepted items DIFFER across the collection contribute to the score;
 * everything else is still planned and sourced exactly as before, it just does
 * not get a vote on which variant to build.</p>
 *
 * <p>Scoring looks one hop below those slots: what the player holds of the
 * ingredient itself, plus what they hold of anything that produces it (spruce
 * logs for spruce planks). That covers the shapes variant collections
 * overwhelmingly take -- doors, signs, fences, slabs -- without a planner run
 * per variant. It does not model consumption ratios, chains deeper than one
 * hop, or variants competing for one shared base material. Where it is wrong
 * it picks a workable variant rather than the best one, and the planner still
 * refuses anything that cannot actually be built.</p>
 */
final class ChainVariantRanking {
	private ChainVariantRanking() {
	}

	/**
	 * Reachable-material totals keyed by recipe. Variants that nothing in the
	 * collection distinguishes are absent rather than zero, so callers can tell
	 * "no material" apart from "no signal" -- they rank differently.
	 */
	static Map<RecipeDisplayId, Integer> scoreVariants(
		List<RecipeVariantResolver.Selection> variants,
		Map<String, Integer> availableCounts
	) {
		if (variants == null || variants.size() <= 1) {
			return Map.of();
		}

		// itemIds() is distinct+sorted at construction, so the list itself is a
		// stable identity for "what this slot accepts".
		List<Set<List<String>>> slotSignatures = new ArrayList<>(variants.size());
		for (RecipeVariantResolver.Selection variant : variants) {
			Set<List<String>> signatures = new HashSet<>();
			for (RecipeIngredientSummary.IngredientSlot slot : variant.ingredientSummary().slots()) {
				if (!slot.isEmpty()) {
					signatures.add(slot.itemIds());
				}
			}
			slotSignatures.add(signatures);
		}

		Set<List<String>> sharedSignatures = new HashSet<>(slotSignatures.get(0));
		for (int i = 1; i < slotSignatures.size(); i++) {
			sharedSignatures.retainAll(slotSignatures.get(i));
		}

		Map<RecipeDisplayId, Integer> scores = new HashMap<>();
		for (int i = 0; i < variants.size(); i++) {
			Set<List<String>> discriminating = new HashSet<>(slotSignatures.get(i));
			discriminating.removeAll(sharedSignatures);
			if (discriminating.isEmpty()) {
				continue;
			}

			// A set, so two producers sharing an input cannot count it twice.
			Set<String> sourceItemIds = new HashSet<>();
			for (List<String> signature : discriminating) {
				for (String itemId : signature) {
					sourceItemIds.add(itemId);
					sourceItemIds.addAll(ChainCraftabilityCache.producerInputItemIds(itemId));
				}
			}

			int score = 0;
			for (String itemId : sourceItemIds) {
				score += availableCounts.getOrDefault(itemId, 0);
			}
			scores.put(variants.get(i).recipeId(), score);
		}
		return Map.copyOf(scores);
	}
}
