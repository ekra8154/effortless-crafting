package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
		long startNanos = PerformanceProfiler.start();

		Map<Integer, Integer> originalOrder = new HashMap<>();
		for (int i = 0; i < collections.size(); i++) {
			originalOrder.put(System.identityHashCode(collections.get(i)), i);
		}

		SortPassContext sortContext = new SortPassContext(recentRanks(), originalOrder);
		Map<RecipeCollection, SortScore> scoresByCollection = new IdentityHashMap<>();
		for (RecipeCollection collection : collections) {
			scoresByCollection.put(collection, score(collection, sortContext));
		}

		List<ScoredCollection> scoredCollections = new ArrayList<>(collections.size());
		for (RecipeCollection collection : collections) {
			scoredCollections.add(new ScoredCollection(collection, scoresByCollection.get(collection)));
		}
		scoredCollections.sort(Comparator.comparing(ScoredCollection::score));

		List<RecipeCollection> sorted = new ArrayList<>(collections.size());
		for (ScoredCollection scoredCollection : scoredCollections) {
			sorted.add(scoredCollection.collection());
		}
		PerformanceProfiler.record(
			"recipe_book.smart_sort",
			startNanos,
			"collections=" + collections.size()
				+ " chain_memo=" + sortContext.chainCraftableByRecipe.size()
				+ " nearby_memo=" + sortContext.nearbyCraftabilityByRecipe.size()
		);
		return sorted;
	}

	private static SortScore score(RecipeCollection collection, SortPassContext context) {
		List<RecipeDisplayEntry> recipes = collection.getSelectedRecipes(RecipeCollection.CraftableStatus.ANY);
		if (recipes.isEmpty()) {
			recipes = collection.getRecipes();
		}

		int recentRank = Integer.MAX_VALUE;
		for (RecipeDisplayEntry entry : recipes) {
			RecipeDisplayId recipeId = entry.id();
			recentRank = Math.min(recentRank, context.recentRanks.getOrDefault(recipeId.index(), Integer.MAX_VALUE));
		}

		int originalIndex = context.originalOrder.getOrDefault(System.identityHashCode(collection), Integer.MAX_VALUE);
		if (recentRank != Integer.MAX_VALUE) {
			return new SortScore(0, recentRank, originalIndex);
		}
		if (collection.hasCraftable()) {
			return new SortScore(1, 0, originalIndex);
		}

		boolean explicitVariantSelection = recipes.size() > 1;
		boolean chainCraftable = false;
		boolean nearbyCraftable = false;
		for (RecipeDisplayEntry entry : recipes) {
			RecipeDisplayId recipeId = entry.id();
			chainCraftable |= context.chainCraftableByRecipe.computeIfAbsent(recipeId, ChainCraftabilityCache::isChainCraftable);
			if (!nearbyCraftable) {
				NearbyMemoKey nearbyMemoKey = new NearbyMemoKey(recipeId, explicitVariantSelection);
				RecipeButtonNearbyIndicator.Craftability craftability = context.nearbyCraftabilityByRecipe.computeIfAbsent(
					nearbyMemoKey,
					ignored -> RecipeButtonNearbyIndicator.getCraftability(
						recipeId,
						collection,
						ItemStack.EMPTY,
						explicitVariantSelection
					)
				);
				nearbyCraftable = craftability == RecipeButtonNearbyIndicator.Craftability.NEARBY_CRAFTABLE;
			}
			if (chainCraftable && nearbyCraftable) {
				break;
			}
		}

		if (nearbyCraftable) {
			return new SortScore(2, 0, originalIndex);
		}
		if (chainCraftable) {
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

	private record ScoredCollection(RecipeCollection collection, SortScore score) {
	}

	private record NearbyMemoKey(RecipeDisplayId recipeId, boolean explicitVariantSelection) {
	}

	private static final class SortPassContext {
		private final Map<Integer, Integer> recentRanks;
		private final Map<Integer, Integer> originalOrder;
		private final Map<RecipeDisplayId, Boolean> chainCraftableByRecipe = new HashMap<>();
		private final Map<NearbyMemoKey, RecipeButtonNearbyIndicator.Craftability> nearbyCraftabilityByRecipe = new HashMap<>();

		private SortPassContext(Map<Integer, Integer> recentRanks, Map<Integer, Integer> originalOrder) {
			this.recentRanks = recentRanks;
			this.originalOrder = originalOrder;
		}
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
