package com.reachcrafting.client;

// import java.util.ArrayList;
// import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

public record RecipeDeficitReport(
	Map<String, Integer> exactMissingCounts,
	Map<String, Integer> flexibleMissingCounts,
	List<String> missingItemIds,
	List<IngredientPlanning.SlotTarget> slotTargets,
	String compactMissingSummary,
	boolean hasMissingIngredients
) {
	public static RecipeDeficitReport from(RecipeIngredientSummary ingredientSummary, AvailableItemSnapshot availableItems) {
		return from(ingredientSummary, availableItems.totalCounts(), availableItems.gridStacks(), false);
	}

	public static RecipeDeficitReport from(RecipeIngredientSummary ingredientSummary, Map<String, Integer> availableCounts) {
		return from(ingredientSummary, availableCounts, List.of(), false);
	}

	public static RecipeDeficitReport from(
		RecipeIngredientSummary ingredientSummary,
		Map<String, Integer> availableCounts,
		List<ItemStack> gridStacks
	) {
		return from(ingredientSummary, availableCounts, gridStacks, false);
	}

	public static RecipeDeficitReport from(RecipeIngredientSummary ingredientSummary, Map<String, Integer> availableCounts, boolean craftAll) {
		return from(ingredientSummary, availableCounts, List.of(), craftAll);
	}

	public static RecipeDeficitReport from(
		RecipeIngredientSummary ingredientSummary,
		Map<String, Integer> availableCounts,
		List<ItemStack> gridStacks,
		boolean craftAll
	) {
		return from(ingredientSummary, availableCounts, gridStacks, craftAll, null);
	}

	public static RecipeDeficitReport from(RecipeIngredientSummary ingredientSummary, Map<String, Integer> availableCounts, int copiesPerSlot) {
		return from(ingredientSummary, availableCounts, List.of(), false, copiesPerSlot);
	}

	public static RecipeDeficitReport from(
		RecipeIngredientSummary ingredientSummary,
		Map<String, Integer> availableCounts,
		List<ItemStack> gridStacks,
		int copiesPerSlot
	) {
		return from(ingredientSummary, availableCounts, gridStacks, false, copiesPerSlot);
	}

	private static RecipeDeficitReport from(
		RecipeIngredientSummary ingredientSummary,
		Map<String, Integer> availableCounts,
		List<ItemStack> gridStacks,
		boolean craftAll,
		Integer explicitCopiesPerSlot
	) {
		int desiredCopies = explicitCopiesPerSlot != null
			? explicitCopiesPerSlot
			: craftAll
				? ingredientSummary.slots().stream()
					.filter(slot -> !slot.isEmpty())
					.mapToInt(RecipeIngredientSummary.IngredientSlot::maxStackSize)
					.min()
					.orElse(0)
				: 1;
		IngredientPlanning.PlanResult plan = IngredientPlanning.plan(
			ingredientSummary,
			availableCounts,
			gridStacks,
			availableCounts,
			availableCounts,
			desiredCopies,
			IngredientPlanning.defaultPolicy()
		);
		return new RecipeDeficitReport(
			Map.copyOf(new LinkedHashMap<>(plan.exactMissingCounts())),
			Map.copyOf(new LinkedHashMap<>(plan.flexibleMissingCounts())),
			List.copyOf(plan.missingItemIds()),
			List.copyOf(plan.slotTargets()),
			plan.compactMissingSummary(),
			plan.hasMissingIngredients()
		);
	}

	public Optional<String> firstExactMissingItemId() {
		return exactMissingCounts.keySet().stream().findFirst();
	}
}
