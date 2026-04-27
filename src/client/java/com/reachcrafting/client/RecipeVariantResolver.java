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
		boolean allowNearbyFallback,
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
			allowNearbyFallback,
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
		boolean allowNearbyFallback,
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
		if (!shouldResolveCollectionVariant(collection, explicitVariantSelection, allowNearbyFallback, availableItems, allowReservedGridVariantSwitch)) {
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

		if (handling == ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK
			&& exactSelection.copiesAvailable() > 0) {
			return exactSelection;
		}

		return candidates.stream()
			.filter(candidate -> candidate.copiesAvailable() > 0)
			.max(compareSelections(ReachCraftingConfig.get().countPreference()))
			.orElse(exactSelection);
	}

	private static boolean shouldResolveCollectionVariant(
		RecipeCollection collection,
		boolean explicitVariantSelection,
		boolean allowNearbyFallback,
		AvailableItemSnapshot availableItems,
		boolean allowReservedGridVariantSwitch
	) {
		return collection != null
			&& collection.getRecipes().size() > 1
			&& allowNearbyFallback
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
		int copiesAvailable = craftAll
			? IngredientPlanning.computeMaxCraftCopies(
				ingredientSummary,
				availableItems.inventoryCounts(),
				availableItems.gridStacks(),
				usableCounts,
				preferenceTotals,
				policy
			)
			: IngredientPlanning.plan(
				ingredientSummary,
				availableItems.inventoryCounts(),
				availableItems.gridStacks(),
				usableCounts,
				preferenceTotals,
				Math.max(desiredCopiesPerSlot, 1),
				policy
			).hasMissingIngredients() ? 0 : Math.max(desiredCopiesPerSlot, 1);

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

	private static Comparator<Selection> compareSelections(IngredientPlanning.CountPreference countPreference) {
		Comparator<Selection> byPreferredCount = Comparator.comparingInt(Selection::preferredTotalCount);
		if (countPreference == IngredientPlanning.CountPreference.LOWEST_TOTAL) {
			byPreferredCount = byPreferredCount.reversed();
		}
		return byPreferredCount
			.thenComparingInt(Selection::copiesAvailable)
			.thenComparing(Selection::outputItemId);
	}

	private static ItemStack resolveDisplayStack(RecipeDisplay display, ContextMap context) {
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
