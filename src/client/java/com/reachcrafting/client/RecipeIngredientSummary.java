package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;

public record RecipeIngredientSummary(List<IngredientSlot> slots, List<String> rawSlots, String compactSummary) {
	public static RecipeIngredientSummary fromRecipe(Recipe<?> recipe, int craftingGridSlotCount) {
		List<Ingredient> ingredients = extractIngredients(recipe, craftingGridSlotCount);
		List<IngredientSlot> slots = new ArrayList<>();
		List<String> rawSlots = new ArrayList<>();
		Map<String, Integer> aggregate = new LinkedHashMap<>();

		for (Ingredient ingredient : ingredients) {
			IngredientSlot slot = describeSlot(ingredient.getItems());
			slots.add(slot);
			rawSlots.add(slot.display());
			if (!slot.isEmpty()) {
				aggregate.merge(slot.display(), 1, Integer::sum);
			}
		}

		StringJoiner compact = new StringJoiner(", ");
		for (Map.Entry<String, Integer> entry : aggregate.entrySet()) {
			compact.add(entry.getValue() + "x " + entry.getKey());
		}

		String compactSummary = compact.length() == 0 ? "<no ingredients>" : compact.toString();
		return new RecipeIngredientSummary(List.copyOf(slots), List.copyOf(rawSlots), compactSummary);
	}

	public Set<String> acceptedItemIds() {
		Set<String> acceptedItemIds = new LinkedHashSet<>();
		for (IngredientSlot slot : slots) {
			acceptedItemIds.addAll(slot.itemIds());
		}
		return Set.copyOf(acceptedItemIds);
	}

	public int estimateRequiredInventorySlots(int craftCopies) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<String, Integer> stackLimits = new LinkedHashMap<>();
		for (IngredientSlot slot : slots) {
			if (slot.isEmpty()) continue;
			counts.put(slot.display(), counts.getOrDefault(slot.display(), 0) + 1);
			stackLimits.put(slot.display(), slot.maxStackSize());
		}

		int requiredSlots = 0;
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			int countPerCraft = entry.getValue();
			int maxStackSize = stackLimits.get(entry.getKey());
			int totalItems = countPerCraft * craftCopies;
			requiredSlots += (totalItems + maxStackSize - 1) / maxStackSize;
		}
		return requiredSlots;
	}

	private static List<Ingredient> extractIngredients(Recipe<?> recipe, int craftingGridSlotCount) {
		if (!(recipe instanceof CraftingRecipe craftingRecipe)) {
			return List.of();
		}

		if (recipe instanceof ShapedRecipe shapedRecipe) {
			return expandShapedIngredients(shapedRecipe, craftingGridSlotCount);
		}

		return List.copyOf(craftingRecipe.getIngredients());
	}

	private static List<Ingredient> expandShapedIngredients(ShapedRecipe shapedRecipe, int craftingGridSlotCount) {
		GridSize gridSize = GridSize.fromSlotCount(craftingGridSlotCount);
		if (gridSize == null) {
			return List.copyOf(shapedRecipe.getIngredients());
		}

		List<Ingredient> expanded = new ArrayList<>(java.util.Collections.nCopies(craftingGridSlotCount, Ingredient.EMPTY));
		int width = Math.min(shapedRecipe.getWidth(), gridSize.width());
		int height = Math.min(shapedRecipe.getHeight(), gridSize.height());
		List<Ingredient> recipeItems = shapedRecipe.getIngredients();
		for (int recipeY = 0; recipeY < height; recipeY++) {
			for (int recipeX = 0; recipeX < width; recipeX++) {
				int recipeIndex = recipeY * shapedRecipe.getWidth() + recipeX;
				int gridIndex = recipeY * gridSize.width() + recipeX;
				if (recipeIndex < recipeItems.size() && gridIndex < expanded.size()) {
					expanded.set(gridIndex, recipeItems.get(recipeIndex));
				}
			}
		}
		return expanded;
	}

	private static IngredientSlot describeSlot(ItemStack[] resolvedStacks) {
		List<String> itemIds = java.util.Arrays.stream(resolvedStacks)
			.filter(stack -> !stack.isEmpty())
			.map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
			.distinct()
			.sorted(Comparator.naturalOrder())
			.toList();
		int stackLimit = java.util.Arrays.stream(resolvedStacks)
			.filter(stack -> !stack.isEmpty())
			.mapToInt(ItemStack::getMaxStackSize)
			.min()
			.orElse(64);

		if (itemIds.isEmpty()) {
			return new IngredientSlot(List.of(), "<empty>", 0);
		}
		if (itemIds.size() == 1) {
			return new IngredientSlot(itemIds, itemIds.get(0), stackLimit);
		}

		StringJoiner joiner = new StringJoiner(" | ", "[", "]");
		itemIds.stream()
			.filter(Objects::nonNull)
			.forEach(joiner::add);
		return new IngredientSlot(itemIds, joiner.toString(), stackLimit);
	}

	private record GridSize(int width, int height) {
		static GridSize fromSlotCount(int slotCount) {
			return switch (slotCount) {
				case 4 -> new GridSize(2, 2);
				case 9 -> new GridSize(3, 3);
				default -> null;
			};
		}
	}

	public record IngredientSlot(List<String> itemIds, String display, int maxStackSize) {
		public IngredientSlot {
			itemIds = List.copyOf(itemIds);
		}

		public boolean isEmpty() {
			return itemIds.isEmpty();
		}

		public boolean isExact() {
			return itemIds.size() == 1;
		}

		public int copiesForPlacement(boolean craftAll) {
			if (!craftAll) {
				return 1;
			}
			return Math.max(maxStackSize, 1);
		}
	}
}
