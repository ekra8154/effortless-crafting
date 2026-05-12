package com.reachcrafting.client;

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

	public String getBlockingDeficitSummary(RecipeIngredientSummary ingredientSummary, Map<String, Integer> availableCounts, List<ItemStack> gridStacks) {
		// Identify what is missing for just ONE craft (the immediate blockers)
		// We must include gridStacks in the available counts check to ensure we don't report items already staged
		RecipeDeficitReport oneCraft = RecipeDeficitReport.from(ingredientSummary, availableCounts, gridStacks, 1);
		
		java.util.Set<String> blockingExact = oneCraft.exactMissingCounts().keySet();
		java.util.Set<String> blockingFlexible = oneCraft.flexibleMissingCounts().keySet();

		if (blockingExact.isEmpty() && blockingFlexible.isEmpty()) {
			return "";
		}

		// Filter the current deficit (for the full request) down to only those items that blocked the first craft
		Map<String, Integer> filteredExact = new java.util.LinkedHashMap<>();
		exactMissingCounts.forEach((itemId, count) -> {
			if (blockingExact.contains(itemId)) {
				filteredExact.put(itemId, count);
			}
		});

		Map<String, Integer> filteredFlexible = new java.util.LinkedHashMap<>();
		flexibleMissingCounts.forEach((display, count) -> {
			if (blockingFlexible.contains(display)) {
				filteredFlexible.put(display, count);
			}
		});

		return IngredientPlanning.summarizeMissing(filteredExact, filteredFlexible);
	}
}
