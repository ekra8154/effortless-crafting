package com.reachcrafting.client;

import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
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

		if (handling == ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK
			&& exactSelection.copiesAvailable() > 0
			&& (craftAll || exactSelection.copiesAvailable() >= requestedCopies)) {
			return exactSelection;
		}

		if (!allowReservedGridVariantSwitch && availableItems.hasReservedGrid()) {
			Selection gridMatch = candidates.stream()
				.filter(candidate -> isMatchForGrid(candidate, availableItems.gridStacks()))
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

		if (!craftAll) {
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
			.filter(candidate -> isMatchForGrid(candidate, gridStacks))
			.findFirst()
			.orElse(null);
	}

	private static boolean isMatchForGrid(Selection selection, List<ItemStack> gridStacks) {
		RecipeIngredientSummary summary = selection.ingredientSummary();
		for (int i = 0; i < gridStacks.size(); i++) {
			ItemStack gridStack = gridStacks.get(i);
			if (gridStack.isEmpty()) continue;
			if (i >= summary.slots().size()) return false;
			
			String itemId = BuiltInRegistries.ITEM.getKey(gridStack.getItem()).toString();
			if (!summary.slots().get(i).itemIds().contains(itemId)) {
				return false;
			}
		}
		return true;
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
