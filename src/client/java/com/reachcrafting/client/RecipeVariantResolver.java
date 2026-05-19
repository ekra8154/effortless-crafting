package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

public final class RecipeVariantResolver {
	private RecipeVariantResolver() {
	}

	public static Selection resolve(
		Minecraft minecraft,
		LocalPlayer player,
		Recipe<?> clickedRecipe,
		RecipeCollection collection,
		ItemStack clickedDisplayStack,
		boolean explicitVariantSelection,
		boolean allowVariantSwitching,
		AvailableItemSnapshot availableItems,
		Map<String, Integer> usableCounts,
		Map<String, Integer> preferenceTotals,
		boolean craftAll
	) {
		return resolve(
			minecraft,
			player,
			clickedRecipe,
			collection,
			clickedDisplayStack,
			explicitVariantSelection,
			allowVariantSwitching,
			availableItems,
			usableCounts,
			preferenceTotals,
			craftAll,
			false,
			1
		);
	}

	public static Selection resolve(
		Minecraft minecraft,
		LocalPlayer player,
		Recipe<?> clickedRecipe,
		RecipeCollection collection,
		ItemStack clickedDisplayStack,
		boolean explicitVariantSelection,
		boolean allowVariantSwitching,
		AvailableItemSnapshot availableItems,
		Map<String, Integer> usableCounts,
		Map<String, Integer> preferenceTotals,
		boolean craftAll,
		boolean allowReservedGridVariantSwitch,
		int desiredCopiesPerSlot
	) {
		if (minecraft.level == null || clickedRecipe == null) {
			return null;
		}

		collection = resolveCanonicalCollection(player, collection, clickedRecipe);
		Selection exactSelection = toSelection(
			minecraft,
			clickedRecipe,
			clickedDisplayStack,
			availableItems,
			usableCounts,
			preferenceTotals,
			craftAll,
			desiredCopiesPerSlot
		);
		if (!shouldResolveCollectionVariant(collection, explicitVariantSelection, allowVariantSwitching, availableItems, allowReservedGridVariantSwitch)) {
			return exactSelection;
		}

		ReachCraftingConfig.RevolvingCraftHandling handling = ReachCraftingConfig.get().revolvingCraftHandling();
		if (handling == ReachCraftingConfig.RevolvingCraftHandling.SPECIFIC_VARIANT_ONLY) {
			return exactSelection;
		}

		List<Selection> candidates = collection.getRecipes().stream()
			.map(candidate -> toSelection(
				minecraft,
				candidate,
				candidate.getId().equals(clickedRecipe.getId()) ? clickedDisplayStack : ItemStack.EMPTY,
				availableItems,
				usableCounts,
				preferenceTotals,
				craftAll,
				desiredCopiesPerSlot
			))
			.toList();
		int requestedCopies = Math.max(desiredCopiesPerSlot, 1);
		boolean lockToCurrentVariant = BulkAutoCraftController.shouldLockToCurrentVariant(clickedRecipe.getId(), collection, explicitVariantSelection);

		if (lockToCurrentVariant) {
			ResourceLocation lockedId = BulkAutoCraftController.getActiveLockedRecipeId();
			if (lockedId != null) {
				for (Selection selection : candidates) {
					if (selection.recipeId().equals(lockedId)) {
						return selection;
					}
				}
			}
			return exactSelection;
		}

		boolean bulkStrictOrUndecided = !craftAll
			&& BulkAutoCraftController.isActive()
			&& BulkAutoCraftController.currentVariantContinuationMode() != BulkAutoCraftController.VariantContinuationMode.FAMILY_FALLBACK;

		if (handling == ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK
			&& exactSelection.copiesAvailable() > 0
			&& (craftAll || bulkStrictOrUndecided || exactSelection.copiesAvailable() >= requestedCopies)) {
			return exactSelection;
		}

		if (!allowReservedGridVariantSwitch && availableItems.hasReservedGrid()) {
			Selection gridMatch = candidates.stream()
				.filter(candidate -> isMatchForGrid(candidate.recipe(), availableItems.gridStacks()))
				.filter(candidate -> candidate.copiesAvailable() > 0)
				.findFirst()
				.orElse(null);
			if (gridMatch != null) {
				return gridMatch;
			}
		}

		List<Selection> viableCandidates = candidates.stream()
			.filter(candidate -> candidate.copiesAvailable() > 0)
			.toList();
		if (viableCandidates.isEmpty()) {
			return exactSelection;
		}

		boolean bulkFamilyFallback = BulkAutoCraftController.currentVariantContinuationMode() == BulkAutoCraftController.VariantContinuationMode.FAMILY_FALLBACK;

		if (!craftAll) {
			if (bulkFamilyFallback) {
				return viableCandidates.stream()
					.max(compareSelections(ReachCraftingConfig.get().countPreference(), false))
					.orElse(exactSelection);
			}
			List<Selection> fullRequestCandidates = viableCandidates.stream()
				.filter(candidate -> candidate.copiesAvailable() >= requestedCopies)
				.toList();
			if (!fullRequestCandidates.isEmpty()) {
				return fullRequestCandidates.stream()
					.max(compareSelections(ReachCraftingConfig.get().countPreference(), false))
					.orElse(exactSelection);
			}

			return viableCandidates.stream()
				.max(comparePartialRequestSelections(ReachCraftingConfig.get().countPreference()))
				.orElse(exactSelection);
		}

		return viableCandidates.stream()
			.max(compareSelections(ReachCraftingConfig.get().countPreference(), true))
			.orElse(exactSelection);
	}

	public static Selection resolveMatchForGrid(
		Minecraft minecraft,
		LocalPlayer player,
		RecipeCollection collection,
		List<ItemStack> gridStacks,
		AvailableItemSnapshot availableItems,
		Map<String, Integer> usableCounts,
		Map<String, Integer> preferenceTotals,
		boolean craftAll,
		int desiredCopiesPerSlot
	) {
		if (collection == null || minecraft.level == null) {
			return null;
		}

		return collection.getRecipes().stream()
			.map(candidate -> toSelection(minecraft, candidate, ItemStack.EMPTY, availableItems, usableCounts, preferenceTotals, craftAll, desiredCopiesPerSlot))
			.filter(candidate -> isMatchForGrid(candidate.recipe(), gridStacks))
			.findFirst()
			.orElse(null);
	}

	private static boolean isMatchForGrid(Recipe<?> recipe, List<ItemStack> gridStacks) {
		if (recipe instanceof ShapedRecipe shaped) {
			return matchesShapedGrid(shaped, gridStacks);
		}
		if (recipe instanceof ShapelessRecipe shapeless) {
			return matchesShapelessGrid(shapeless, gridStacks);
		}

		RecipeIngredientSummary summary = RecipeIngredientSummary.fromRecipe(recipe, gridStacks.size());
		if (summary.slots().size() != gridStacks.size()) {
			return false;
		}
		for (int i = 0; i < gridStacks.size(); i++) {
			ItemStack gridStack = gridStacks.get(i);
			RecipeIngredientSummary.IngredientSlot ingredientSlot = summary.slots().get(i);
			if (gridStack.isEmpty()) {
				if (!ingredientSlot.isEmpty()) {
					return false;
				}
				continue;
			}
			if (ingredientSlot.isEmpty()) {
				return false;
			}

			String itemId = BuiltInRegistries.ITEM.getKey(gridStack.getItem()).toString();
			if (!ingredientSlot.itemIds().contains(itemId)) {
				return false;
			}
		}
		return true;
	}

	private static boolean matchesShapedGrid(ShapedRecipe shaped, List<ItemStack> gridStacks) {
		GridSize gridSize = GridSize.fromSlotCount(gridStacks.size());
		if (gridSize == null || shaped.getWidth() > gridSize.width() || shaped.getHeight() > gridSize.height()) {
			return false;
		}

		List<List<String>> ingredientIds = shaped.getIngredients().stream()
			.map(RecipeVariantResolver::resolveItemIds)
			.toList();

		for (int offsetY = 0; offsetY <= gridSize.height() - shaped.getHeight(); offsetY++) {
			for (int offsetX = 0; offsetX <= gridSize.width() - shaped.getWidth(); offsetX++) {
				if (matchesShapedAtOffset(shaped, gridStacks, ingredientIds, gridSize, offsetX, offsetY)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean matchesShapedAtOffset(
		ShapedRecipe shaped,
		List<ItemStack> gridStacks,
		List<List<String>> ingredientIds,
		GridSize gridSize,
		int offsetX,
		int offsetY
	) {
		for (int gridY = 0; gridY < gridSize.height(); gridY++) {
			for (int gridX = 0; gridX < gridSize.width(); gridX++) {
				int gridIndex = gridY * gridSize.width() + gridX;
				ItemStack gridStack = gridStacks.get(gridIndex);
				boolean withinShape = gridX >= offsetX
					&& gridX < offsetX + shaped.getWidth()
					&& gridY >= offsetY
					&& gridY < offsetY + shaped.getHeight();
				if (!withinShape) {
					if (!gridStack.isEmpty()) {
						return false;
					}
					continue;
				}

				int recipeX = gridX - offsetX;
				int recipeY = gridY - offsetY;
				int recipeIndex = recipeY * shaped.getWidth() + recipeX;
				List<String> acceptedIds = ingredientIds.get(recipeIndex);
				if (gridStack.isEmpty()) {
					if (!acceptedIds.isEmpty()) {
						return false;
					}
					continue;
				}
				if (acceptedIds.isEmpty()) {
					return false;
				}

				String itemId = BuiltInRegistries.ITEM.getKey(gridStack.getItem()).toString();
				if (!acceptedIds.contains(itemId)) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean matchesShapelessGrid(ShapelessRecipe shapeless, List<ItemStack> gridStacks) {
		List<List<String>> remainingSlots = shapeless.getIngredients().stream()
			.map(RecipeVariantResolver::resolveItemIds)
			.filter(ids -> !ids.isEmpty())
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		int occupiedCount = 0;
		for (ItemStack gridStack : gridStacks) {
			if (gridStack.isEmpty()) {
				continue;
			}
			occupiedCount++;
			String itemId = BuiltInRegistries.ITEM.getKey(gridStack.getItem()).toString();
			boolean matched = false;
			for (int i = 0; i < remainingSlots.size(); i++) {
				if (remainingSlots.get(i).contains(itemId)) {
					remainingSlots.remove(i);
					matched = true;
					break;
				}
			}
			if (!matched) {
				return false;
			}
		}
		return occupiedCount > 0 && remainingSlots.isEmpty();
	}

	private static List<String> resolveItemIds(Ingredient ingredient) {
		return java.util.Arrays.stream(ingredient.getItems())
			.filter(stack -> !stack.isEmpty())
			.map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
			.distinct()
			.sorted()
			.toList();
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

	private static boolean shouldResolveCollectionVariant(
		RecipeCollection collection,
		boolean explicitVariantSelection,
		boolean allowVariantSwitching,
		AvailableItemSnapshot availableItems,
		boolean allowReservedGridVariantSwitch
	) {
		return collection != null
			&& collection.getRecipes().size() > 1
			&& allowVariantSwitching
			&& !explicitVariantSelection
			&& (!availableItems.hasReservedGrid() || allowReservedGridVariantSwitch);
	}

	private static RecipeCollection resolveCanonicalCollection(
		LocalPlayer player,
		RecipeCollection collection,
		Recipe<?> recipe
	) {
		ClientRecipeBook recipeBook = player.getRecipeBook();
		RecipeCollection bestCollection = collection;
		int bestSize = collection != null ? collection.getRecipes().size() : 0;
		for (RecipeCollection candidate : recipeBook.getCollections()) {
			boolean containsRecipe = candidate.getRecipes().stream()
				.anyMatch(entry -> entry.getId().equals(recipe.getId()));
			if (!containsRecipe) {
				continue;
			}

			int candidateSize = candidate.getRecipes().size();
			if (bestCollection == null || candidateSize > bestSize) {
				bestCollection = candidate;
				bestSize = candidateSize;
			}
		}
		return bestCollection;
	}

	private static Selection toSelection(
		Minecraft minecraft,
		Recipe<?> recipe,
		ItemStack preferredDisplayStack,
		AvailableItemSnapshot availableItems,
		Map<String, Integer> usableCounts,
		Map<String, Integer> preferenceTotals,
		boolean craftAll,
		int desiredCopiesPerSlot
	) {
		RecipeIngredientSummary ingredientSummary = RecipeIngredientSummary.fromRecipe(recipe, availableItems.gridStacks().size());
		IngredientPlanning.Policy policy = ReachCraftingConfig.get().toPlanningPolicy();
		int maxPossible = IngredientPlanning.computeMaxCraftCopies(
			ingredientSummary,
			availableItems.inventoryCounts(),
			availableItems.gridStacks(),
			usableCounts,
			preferenceTotals,
			policy
		);
		int copiesAvailable = craftAll ? maxPossible : Math.min(maxPossible, Math.max(desiredCopiesPerSlot, 1));

		ItemStack displayStack = !preferredDisplayStack.isEmpty()
			? preferredDisplayStack.copy()
			: resolveDisplayStack(recipe, minecraft);
		String outputItemId = displayStack.isEmpty()
			? "<empty>"
			: BuiltInRegistries.ITEM.getKey(displayStack.getItem()).toString();
		String outputLabel = outputItemId + " x" + Math.max(displayStack.getCount(), 1);
		int preferredTotalCount = ingredientSummary.acceptedItemIds().stream()
			.mapToInt(itemId -> preferenceTotals.getOrDefault(itemId, 0))
			.sum();
		return new Selection(recipe, recipe.getId(), ingredientSummary, displayStack, outputItemId, outputLabel, copiesAvailable, preferredTotalCount);
	}

	private static Comparator<Selection> compareSelections(IngredientPlanning.CountPreference countPreference, boolean craftAll) {
		Comparator<Selection> byPreferredCount = Comparator.comparingInt(Selection::preferredTotalCount);
		if (countPreference == IngredientPlanning.CountPreference.LOWEST_TOTAL) {
			byPreferredCount = byPreferredCount.reversed();
		}
		Comparator<Selection> byCopies = Comparator.comparingInt(Selection::copiesAvailable);
		return craftAll
			? byCopies.thenComparing(byPreferredCount).thenComparing(Selection::outputItemId)
			: byPreferredCount.thenComparing(byCopies).thenComparing(Selection::outputItemId);
	}

	private static Comparator<Selection> comparePartialRequestSelections(IngredientPlanning.CountPreference countPreference) {
		Comparator<Selection> byCopies = Comparator.comparingInt(Selection::copiesAvailable);
		Comparator<Selection> byPreferredCount = Comparator.comparingInt(Selection::preferredTotalCount);
		if (countPreference == IngredientPlanning.CountPreference.LOWEST_TOTAL) {
			byPreferredCount = byPreferredCount.reversed();
		}
		return byCopies.thenComparing(byPreferredCount).thenComparing(Selection::outputItemId);
	}

	public static ItemStack resolveDisplayStack(Recipe<?> recipe, Minecraft minecraft) {
		if (recipe == null || minecraft.level == null) {
			return ItemStack.EMPTY;
		}
		return recipe.getResultItem(minecraft.level.registryAccess()).copy();
	}

	public record Selection(
		Recipe<?> recipe,
		ResourceLocation recipeId,
		RecipeIngredientSummary ingredientSummary,
		ItemStack displayStack,
		String outputItemId,
		String outputLabel,
		int copiesAvailable,
		int preferredTotalCount
	) {
	}
}
