package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

public final class RecipeBookSmartSorter {
	private RecipeBookSmartSorter() {
	}

	public static List<RecipeCollection> sorted(List<RecipeCollection> collections) {
		if (collections == null
			|| collections.size() <= 1
			|| ReachCraftingConfig.get().recipeBookSortingMode() == ReachCraftingConfig.RecipeBookSortingMode.VANILLA) {
			return collections;
		}

		Map<Integer, Integer> originalOrder = new HashMap<>();
		for (int i = 0; i < collections.size(); i++) {
			originalOrder.put(System.identityHashCode(collections.get(i)), i);
		}

		Map<Integer, Integer> recentRanks = recentRanks();
		List<RecipeCollection> sorted = new ArrayList<>(collections);
		sorted.sort(Comparator
			.comparing((RecipeCollection collection) -> score(collection, recentRanks, originalOrder))
			.thenComparingInt(collection -> originalOrder.getOrDefault(System.identityHashCode(collection), Integer.MAX_VALUE)));
		return sorted;
	}

	private static SortScore score(
		RecipeCollection collection,
		Map<Integer, Integer> recentRanks,
		Map<Integer, Integer> originalOrder
	) {
		List<RecipeDisplayEntry> recipes = collection.getSelectedRecipes(RecipeCollection.CraftableStatus.ANY);
		if (recipes.isEmpty()) {
			recipes = collection.getRecipes();
		}

		int recentRank = Integer.MAX_VALUE;
		boolean chainCraftable = false;
		boolean nearbyCraftable = false;
		for (RecipeDisplayEntry entry : recipes) {
			RecipeDisplayId recipeId = entry.id();
			recentRank = Math.min(recentRank, recentRanks.getOrDefault(recipeId.index(), Integer.MAX_VALUE));
			chainCraftable |= ChainCraftabilityCache.isChainCraftable(recipeId);
			nearbyCraftable |= RecipeButtonNearbyIndicator.getCraftability(
				recipeId,
				collection,
				ItemStack.EMPTY,
				recipes.size() > 1
			) == RecipeButtonNearbyIndicator.Craftability.NEARBY_CRAFTABLE;
		}

		int originalIndex = originalOrder.getOrDefault(System.identityHashCode(collection), Integer.MAX_VALUE);
		if (recentRank != Integer.MAX_VALUE) {
			return new SortScore(0, recentRank, originalIndex);
		}
		if (collection.hasCraftable()) {
			return new SortScore(1, 0, originalIndex);
		}
		if (chainCraftable) {
			return new SortScore(2, 0, originalIndex);
		}
		if (nearbyCraftable) {
			return new SortScore(3, 0, originalIndex);
		}
		return new SortScore(4, 0, originalIndex);
	}

	private static Map<Integer, Integer> recentRanks() {
		Map<Integer, Integer> ranks = new HashMap<>();
		List<Integer> recent = ReachCraftingConfig.get().recentRecipeDisplayIds();
		for (int i = 0; i < recent.size(); i++) {
			ranks.putIfAbsent(recent.get(i), i);
		}
		return ranks;
	}

	private record SortScore(int tier, int rank, int originalIndex) implements Comparable<SortScore> {
		@Override
		public int compareTo(SortScore other) {
			int byTier = Integer.compare(tier, other.tier);
			if (byTier != 0) {
				return byTier;
			}
			int byRank = Integer.compare(rank, other.rank);
			if (byRank != 0) {
				return byRank;
			}
			return Integer.compare(originalIndex, other.originalIndex);
		}
	}
}
