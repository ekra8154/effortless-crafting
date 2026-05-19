package com.reachcrafting.client;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Immutable output of the search planning phase.
 * SearchSession applies this decision, but does not re-derive planning branches afterwards.
 */
record SearchPlanDecision(
	Recipe<?> resolvedRecipe,
	ResourceLocation resolvedRecipeId,
	String resolvedOutputLabel,
	RecipeIngredientSummary resolvedIngredientSummary,
	boolean redistributeReservedGrid,
	int targetCopiesPerSlot,
	List<IngredientPlanning.SlotTarget> plannedTargets,
	List<String> fetchItemIds,
	List<BlockPos> withdrawCandidates,
	boolean hasMissingIngredients,
	boolean resumeOriginalContext,
	boolean startFallbackDiscovery,
	String blockedCommittedLayoutMissingSummary,
	String compactMissingSummary,
	String totalAvailableSummary,
	java.util.Map<String, Integer> totalAvailableCounts
) {
	SearchPlanDecision {
		plannedTargets = List.copyOf(plannedTargets);
		fetchItemIds = List.copyOf(fetchItemIds);
		withdrawCandidates = List.copyOf(withdrawCandidates);
		totalAvailableCounts = java.util.Map.copyOf(totalAvailableCounts);
	}

	public RecipeDeficitReport asDeficitReport() {
		return RecipeDeficitReport.from(resolvedIngredientSummary, totalAvailableCounts);
	}
}
