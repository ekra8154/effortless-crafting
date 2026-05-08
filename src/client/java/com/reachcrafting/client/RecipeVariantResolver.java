package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

public final class RecipeVariantResolver {
	private RecipeVariantResolver() {
	}

	public static Selection resolve(
		Minecraft minecraft,
		LocalPlayer player,
		RecipeDisplayId clickedRecipeId,
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
			clickedRecipeId,
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
		RecipeDisplayId clickedRecipeId,
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
		if (minecraft.level == null) {
			return null;
		}

		collection = resolveCanonicalCollection(player, collection, clickedRecipeId);
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) player.getRecipeBook()).getKnown();
		RecipeDisplayEntry clickedEntry = findEntry(collection, knownRecipes, clickedRecipeId);
		if (clickedEntry == null) {
			return null;
		}

		ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
		Selection exactSelection = toSelection(
			clickedEntry,
			clickedDisplayStack,
			context,
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
			.map(entry -> toSelection(
				entry,
				entry.id().equals(clickedRecipeId) ? clickedDisplayStack : ItemStack.EMPTY,
				context,
				availableItems,
				usableCounts,
				preferenceTotals,
				craftAll,
				desiredCopiesPerSlot
			))
			.toList();
		int requestedCopies = Math.max(desiredCopiesPerSlot, 1);
		boolean lockToCurrentVariant = handling == ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK
			&& BulkAutoCraftController.shouldLockToCurrentVariant(clickedRecipeId, collection, explicitVariantSelection);
		ReachCraftingMod.LOGGER.info(
			"[recipe_variant_candidates] clicked_recipe={} collection_size={} craft_all={} requested_copies={} handling={} lock_current={} candidates={}",
			clickedRecipeId,
			collection.getRecipes().size(),
			craftAll,
			requestedCopies,
			handling,
			lockToCurrentVariant,
			summarizeCandidates(candidates)
		);

		if (lockToCurrentVariant) {
			return exactSelection;
		}

		if (handling == ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK
			&& exactSelection.copiesAvailable() > 0
			&& (craftAll || exactSelection.copiesAvailable() >= requestedCopies)) {
			return exactSelection;
		}

		if (!allowReservedGridVariantSwitch && availableItems.hasReservedGrid()) {
			Selection gridMatch = candidates.stream()
				.filter(candidate -> isMatchForGrid(candidate, availableItems.gridStacks(), context))
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
			.max(compareSelections(ReachCraftingConfig.get().countPreference(), craftAll))
			.orElse(exactSelection);
	}

	private static String summarizeCandidates(List<Selection> candidates) {
		return candidates.stream()
			.map(candidate -> candidate.recipeId()
				+ "="
				+ candidate.outputLabel()
				+ " copies="
				+ candidate.copiesAvailable()
				+ " preferred_total="
				+ candidate.preferredTotalCount()
				+ " ingredients="
				+ candidate.ingredientSummary().compactSummary())
			.collect(java.util.stream.Collectors.joining(" || "));
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

		ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
		return collection.getRecipes().stream()
			.map(entry -> toSelection(entry, ItemStack.EMPTY, context, availableItems, usableCounts, preferenceTotals, craftAll, desiredCopiesPerSlot))
			.filter(candidate -> isMatchForGrid(candidate, gridStacks, context))
			.findFirst()
			.orElse(null);
	}

	private static boolean isMatchForGrid(Selection selection, List<ItemStack> gridStacks, ContextMap context) {
		RecipeDisplay display = selection.entry().display();
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			return matchesShapedGrid(shaped, gridStacks, context);
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return matchesShapelessGrid(shapeless, gridStacks, context);
		}

		RecipeIngredientSummary summary = selection.ingredientSummary();
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

	private static boolean matchesShapedGrid(ShapedCraftingRecipeDisplay shaped, List<ItemStack> gridStacks, ContextMap context) {
		GridSize gridSize = GridSize.fromSlotCount(gridStacks.size());
		if (gridSize == null || shaped.width() > gridSize.width() || shaped.height() > gridSize.height()) {
			return false;
		}

		List<List<String>> ingredientIds = shaped.ingredients().stream()
			.map(slot -> resolveItemIds(slot, context))
			.toList();

		for (int offsetY = 0; offsetY <= gridSize.height() - shaped.height(); offsetY++) {
			for (int offsetX = 0; offsetX <= gridSize.width() - shaped.width(); offsetX++) {
				if (matchesShapedAtOffset(shaped, gridStacks, ingredientIds, gridSize, offsetX, offsetY)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean matchesShapedAtOffset(
		ShapedCraftingRecipeDisplay shaped,
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
					&& gridX < offsetX + shaped.width()
					&& gridY >= offsetY
					&& gridY < offsetY + shaped.height();
				if (!withinShape) {
					if (!gridStack.isEmpty()) {
						return false;
					}
					continue;
				}

				int recipeX = gridX - offsetX;
				int recipeY = gridY - offsetY;
				int recipeIndex = recipeY * shaped.width() + recipeX;
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

	private static boolean matchesShapelessGrid(ShapelessCraftingRecipeDisplay shapeless, List<ItemStack> gridStacks, ContextMap context) {
		List<List<String>> remainingSlots = shapeless.ingredients().stream()
			.map(slot -> resolveItemIds(slot, context))
			.filter(ids -> !ids.isEmpty())
			.collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
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

	private static List<String> resolveItemIds(SlotDisplay slot, ContextMap context) {
		return slot.resolveForStacks(context).stream()
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
		RecipeDisplayId recipeId
	) {
		ClientRecipeBook recipeBook = player.getRecipeBook();
		RecipeCollection bestCollection = collection;
		int bestSize = collection != null ? collection.getRecipes().size() : 0;
		for (RecipeCollection candidate : recipeBook.getCollections()) {
			boolean containsRecipe = candidate.getRecipes().stream()
				.anyMatch(entry -> entry.id().equals(recipeId));
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

	private static RecipeDisplayEntry findEntry(
		RecipeCollection collection,
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes,
		RecipeDisplayId recipeId
	) {
		if (collection != null) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				if (entry.id().equals(recipeId)) {
					return entry;
				}
			}
		}
		return knownRecipes.get(recipeId);
	}

	private static Selection toSelection(
		RecipeDisplayEntry entry,
		ItemStack preferredDisplayStack,
		ContextMap context,
		AvailableItemSnapshot availableItems,
		Map<String, Integer> usableCounts,
		Map<String, Integer> preferenceTotals,
		boolean craftAll,
		int desiredCopiesPerSlot
	) {
		RecipeIngredientSummary ingredientSummary = RecipeIngredientSummary.fromDisplay(entry.display(), context);
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
			: resolveDisplayStack(entry.display(), context);
		String outputItemId = BuiltInRegistries.ITEM.getKey(displayStack.getItem()).toString();
		String outputLabel = outputItemId + " x" + displayStack.getCount();
		int preferredTotalCount = ingredientSummary.acceptedItemIds().stream()
			.mapToInt(itemId -> preferenceTotals.getOrDefault(itemId, 0))
			.sum();
		return new Selection(entry.id(), entry, ingredientSummary, displayStack, outputItemId, outputLabel, copiesAvailable, preferredTotalCount);
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

	public static ItemStack resolveDisplayStack(RecipeDisplay display, ContextMap context) {
		return display.result().resolveForStacks(context).stream()
			.filter(stack -> !stack.isEmpty())
			.findFirst()
			.map(ItemStack::copy)
			.orElse(ItemStack.EMPTY);
	}

	public record Selection(
		RecipeDisplayId recipeId,
		RecipeDisplayEntry entry,
		RecipeIngredientSummary ingredientSummary,
		ItemStack displayStack,
		String outputItemId,
		String outputLabel,
		int copiesAvailable,
		int preferredTotalCount
	) {
	}
}
