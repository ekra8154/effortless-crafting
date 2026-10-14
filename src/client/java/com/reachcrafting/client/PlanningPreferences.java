package com.reachcrafting.client;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;

/**
 * The variant preferences a resolved recipe adds to ingredient planning:
 * under Prefer Clicked Type, an oak stairs craft prefers oak planks over the
 * other planks it would also accept. Shared by SearchSession (which plans
 * the real craft) and GridPreview (which shows it in advance), so the two
 * cannot drift apart.
 */
final class PlanningPreferences {
	private static final int PREFERRED_VARIANT_BOOST = 1_000_000;

	private PlanningPreferences() {
	}

	static IngredientPlanning.Policy policyFor(
		IngredientPlanning.Policy basePolicy,
		RecipeVariantResolver.Selection selection,
		RecipeCollection collection
	) {
		if (selection == null) {
			return basePolicy;
		}
		Set<String> preferredVariants = preferredVariantsFor(selection, collection);
		if (preferredVariants.isEmpty()) {
			return basePolicy;
		}
		return basePolicy.withPreferredVariants(preferredVariants);
	}

	static Map<String, Integer> preferenceTotalsFor(
		Map<String, Integer> baseTotals,
		RecipeVariantResolver.Selection selection,
		RecipeCollection collection
	) {
		Set<String> preferredVariants = preferredVariantsFor(selection, collection);
		if (preferredVariants.isEmpty()) {
			return baseTotals;
		}
		Map<String, Integer> boostedTotals = new LinkedHashMap<>(baseTotals);
		for (String itemId : preferredVariants) {
			boostedTotals.merge(itemId, PREFERRED_VARIANT_BOOST, Integer::sum);
		}
		return Map.copyOf(boostedTotals);
	}

	static Set<String> preferredVariantsFor(RecipeVariantResolver.Selection selection, RecipeCollection collection) {
		if (selection == null || collection == null || collection.getRecipes().size() <= 1) {
			return Set.of();
		}
		if (ReachCraftingConfig.get().revolvingCraftHandling() != ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK) {
			return Set.of();
		}

		int bestScore = 0;
		LinkedHashSet<String> preferred = new LinkedHashSet<>();
		for (String itemId : selection.ingredientSummary().acceptedItemIds()) {
			int score = variantMatchScore(selection.outputItemId(), itemId);
			if (score <= 0) {
				continue;
			}
			if (score > bestScore) {
				bestScore = score;
				preferred.clear();
			}
			if (score == bestScore) {
				preferred.add(itemId);
			}
		}
		return preferred.isEmpty() ? Set.of() : Set.copyOf(preferred);
	}

	private static int variantMatchScore(String outputItemId, String ingredientItemId) {
		String outputPath = outputItemId.substring(outputItemId.indexOf(':') + 1);
		String ingredientPath = ingredientItemId.substring(ingredientItemId.indexOf(':') + 1);
		String[] outputTokens = outputPath.split("_");
		String[] ingredientTokens = ingredientPath.split("_");
		int max = Math.min(outputTokens.length, ingredientTokens.length);
		int matched = 0;
		while (matched < max && outputTokens[matched].equals(ingredientTokens[matched])) {
			matched++;
		}
		return matched;
	}
}
