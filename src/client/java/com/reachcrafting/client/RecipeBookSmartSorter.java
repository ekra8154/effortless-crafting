package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;

public final class RecipeBookSmartSorter {
	private static final Map<RecipeCollection, Integer> lastPresentedOrder = new IdentityHashMap<>();

	private RecipeBookSmartSorter() {
	}

	public static List<RecipeCollection> sorted(List<RecipeCollection> collections) {
		return sorted(collections, RecipeBookChunkedScheduler.consumeForceEagerNextSort());
	}

	public static List<RecipeCollection> sorted(List<RecipeCollection> collections, boolean eager) {
		if (collections == null
			|| collections.size() <= 1
			|| ReachCraftingConfig.get().recipeBookSortingMode() == ReachCraftingConfig.RecipeBookSortingMode.VANILLA) {
			RecipeBookChunkedScheduler.clear();
			return collections;
		}
		if (RecipeBookChunkedScheduler.shouldFreezeResort()) {
			ReachCraftingMod.LOGGER.info(
				"[recipe_sort] preserve reason=frozen_page collections={} freeze=true",
				collections.size()
			);
			return preservePresentedOrder(collections);
		}
		if (ContainerUtils.isAnySessionActiveExcludingRetrievalMode()) {
			ReachCraftingMod.LOGGER.info(
				"[recipe_sort] preserve reason=active_session collections={} freeze={}",
				collections.size(),
				RecipeBookChunkedScheduler.shouldFreezeResort()
			);
			return preservePresentedOrder(collections);
		}
		long startNanos = PerformanceProfiler.start();

		Map<Integer, Integer> originalOrder = originalOrder(collections);
		List<ScoredCollection> scoredCollections = new ArrayList<>(collections.size());
		if (eager) {
			RecipeBookChunkedScheduler.clear();
			SortPassContext sortContext = new SortPassContext(recentRanks());
			for (RecipeCollection collection : collections) {
				int originalIndex = originalOrder.getOrDefault(System.identityHashCode(collection), Integer.MAX_VALUE);
				SortScore score = fullScore(collection, sortContext, originalIndex);
				scoredCollections.add(new ScoredCollection(collection, score));
			}
			scoredCollections.sort(Comparator.comparing(ScoredCollection::score));

			List<RecipeCollection> sorted = new ArrayList<>(collections.size());
			for (ScoredCollection scoredCollection : scoredCollections) {
				sorted.add(scoredCollection.collection());
			}
			rememberPresentedOrder(sorted);
			PerformanceProfiler.record(
				"recipe_book.smart_sort",
				startNanos,
				"collections=" + collections.size()
					+ " eager=true"
					+ " chain_memo=" + sortContext.chainCraftableByRecipe.size()
					+ " nearby_memo=" + sortContext.nearbyCraftabilityByRecipe.size()
			);
			ReachCraftingMod.LOGGER.info(
				"[recipe_sort] sorted mode=eager collections={} chain_memo={} nearby_memo={}",
				collections.size(),
				sortContext.chainCraftableByRecipe.size(),
				sortContext.nearbyCraftabilityByRecipe.size()
			);
			return sorted;
		}

		RecipeBookChunkedScheduler.PassSnapshot passSnapshot = RecipeBookChunkedScheduler.beginOrReusePass(collections, originalOrder);
		for (RecipeCollection collection : collections) {
			int originalIndex = originalOrder.getOrDefault(System.identityHashCode(collection), Integer.MAX_VALUE);
			SortScore score = passSnapshot.scoreFor(collection, originalIndex);
			scoredCollections.add(new ScoredCollection(collection, score));
		}
		scoredCollections.sort(Comparator.comparing(ScoredCollection::score));

		List<RecipeCollection> sorted = new ArrayList<>(collections.size());
		for (ScoredCollection scoredCollection : scoredCollections) {
			sorted.add(scoredCollection.collection());
		}
		rememberPresentedOrder(sorted);
		PerformanceProfiler.record(
			"recipe_book.smart_sort",
			startNanos,
			"collections=" + collections.size()
				+ " settled=" + passSnapshot.settledCount()
				+ " pending=" + passSnapshot.pendingCount()
		);
		ReachCraftingMod.LOGGER.info(
			"[recipe_sort] sorted mode=chunked collections={} settled={} pending={}",
			collections.size(),
			passSnapshot.settledCount(),
			passSnapshot.pendingCount()
		);
		return sorted;
	}

	static Map<Integer, Integer> originalOrder(List<RecipeCollection> collections) {
		Map<Integer, Integer> originalOrder = new HashMap<>();
		for (int i = 0; i < collections.size(); i++) {
			RecipeCollection collection = collections.get(i);
			int lastIndex = lastPresentedOrder.getOrDefault(collection, -1);
			int index = (lastIndex != -1) ? lastIndex : (i + collections.size());
			originalOrder.put(System.identityHashCode(collection), index);
		}
		return originalOrder;
	}

	static Map<Integer, Integer> recentRanks() {
		Map<Integer, Integer> ranks = new HashMap<>();
		List<Integer> recent = ReachCraftingConfig.get().recentRecipeDisplayIds();
		for (int i = 0; i < recent.size(); i++) {
			ranks.putIfAbsent(recent.get(i), i);
		}
		return ranks;
	}

	static SortScore fallbackScore(RecipeCollection collection, Map<Integer, Integer> recentRanks, int originalIndex) {
		List<RecipeDisplayEntry> recipes = selectedRecipes(collection);
		int recentRank = recentRank(recipes, recentRanks);
		if (ContainerUtils.isExistingOutputRetrievalEnabled()) {
			if (recentRank != Integer.MAX_VALUE) {
				return new SortScore(0, recentRank, originalIndex);
			}
			return new SortScore(3, 0, originalIndex);
		}
		if (recentRank != Integer.MAX_VALUE) {
			return new SortScore(0, recentRank, originalIndex);
		}
		return new SortScore(3, 0, originalIndex);
	}

	public static SortScore fastScore(RecipeCollection collection, SortPassContext context, int originalIndex) {
		List<RecipeDisplayEntry> recipes = selectedRecipes(collection);
		int recentRank = recentRank(recipes, context.recentRanks);
		
		if (context.retrievalModeEnabled) {
			boolean explicitVariantSelection = recipes.size() > 1;
			boolean retrievable = false;
			for (RecipeDisplayEntry entry : recipes) {
				NearbyMemoKey nearbyMemoKey = new NearbyMemoKey(entry.id(), explicitVariantSelection);
				retrievable |= context.retrievabilityByRecipe.computeIfAbsent(
					nearbyMemoKey,
					ignored -> RecipeButtonNearbyIndicator.hasRetrievableOutput(
						entry.id(),
						collection,
						ItemStack.EMPTY,
						explicitVariantSelection
					)
				);
				if (retrievable) {
					break;
				}
			}

			if (retrievable && recentRank != Integer.MAX_VALUE) {
				return new SortScore(0, recentRank, originalIndex);
			}
			if (retrievable) {
				return new SortScore(1, 0, originalIndex);
			}
			if (recentRank != Integer.MAX_VALUE) {
				return new SortScore(2, recentRank, originalIndex);
			}
			return new SortScore(3, 0, originalIndex);
		}

		if (recentRank != Integer.MAX_VALUE) {
			return new SortScore(0, recentRank, originalIndex);
		}
		
		boolean isReachable = false;
		boolean isChainCraftable = false;
		for (RecipeDisplayEntry entry : recipes) {
			if (ChainCraftabilityCache.isReachable(entry.id())) {
				isReachable = true;
				break;
			}
			if (ChainCraftabilityCache.isChainCraftable(entry.id())) {
				isChainCraftable = true;
			}
		}

		if (isReachable) {
			return new SortScore(1, 0, originalIndex);
		}
		if (isChainCraftable) {
			return new SortScore(2, 0, originalIndex);
		}

		// Fallback for uncraftable (or vanilla craftable which hasn't been checked yet)
		return new SortScore(3, 0, originalIndex);
	}

	static SortScore fullScore(RecipeCollection collection, SortPassContext context, int originalIndex) {
		List<RecipeDisplayEntry> recipes = selectedRecipes(collection);
		int recentRank = recentRank(recipes, context.recentRanks);
		
		if (context.retrievalModeEnabled) {
			boolean explicitVariantSelection = recipes.size() > 1;
			boolean retrievable = false;
			for (RecipeDisplayEntry entry : recipes) {
				NearbyMemoKey nearbyMemoKey = new NearbyMemoKey(entry.id(), explicitVariantSelection);
				retrievable |= context.retrievabilityByRecipe.computeIfAbsent(
					nearbyMemoKey,
					ignored -> RecipeButtonNearbyIndicator.hasRetrievableOutput(
						entry.id(),
						collection,
						ItemStack.EMPTY,
						explicitVariantSelection
					)
				);
				if (retrievable) {
					break;
				}
			}

			if (retrievable && recentRank != Integer.MAX_VALUE) {
				return new SortScore(0, recentRank, originalIndex);
			}
			if (retrievable) {
				return new SortScore(1, 0, originalIndex);
			}
			if (recentRank != Integer.MAX_VALUE) {
				return new SortScore(2, recentRank, originalIndex);
			}
			return new SortScore(3, 0, originalIndex);
		}

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
			return new SortScore(1, 0, originalIndex);
		}
		if (chainCraftable) {
			return new SortScore(2, 0, originalIndex);
		}
		return new SortScore(3, 0, originalIndex);
	}

	private static List<RecipeDisplayEntry> selectedRecipes(RecipeCollection collection) {
		List<RecipeDisplayEntry> recipes = collection.getSelectedRecipes(RecipeCollection.CraftableStatus.ANY);
		return recipes.isEmpty() ? collection.getRecipes() : recipes;
	}

	private static int recentRank(List<RecipeDisplayEntry> recipes, Map<Integer, Integer> recentRanks) {
		int recentRank = Integer.MAX_VALUE;
		for (RecipeDisplayEntry entry : recipes) {
			RecipeDisplayId recipeId = entry.id();
			recentRank = Math.min(recentRank, recentRanks.getOrDefault(recipeId.index(), Integer.MAX_VALUE));
		}
		return recentRank;
	}

	private record ScoredCollection(RecipeCollection collection, SortScore score) {
	}

	public static List<RecipeCollection> preservePresentedOrder(List<RecipeCollection> collections) {
		Map<Integer, Integer> originalOrder = originalOrder(collections);
		List<RecipeCollection> preserved = new ArrayList<>(collections);
		preserved.sort(Comparator.comparingInt(collection -> originalOrder.getOrDefault(System.identityHashCode(collection), Integer.MAX_VALUE)));
		return preserved;
	}

	private static void rememberPresentedOrder(List<RecipeCollection> sortedCollections) {
		if (!shouldRememberPresentedOrder()) {
			return;
		}
		lastPresentedOrder.clear();
		for (int i = 0; i < sortedCollections.size(); i++) {
			lastPresentedOrder.put(sortedCollections.get(i), i);
		}
	}

	private static boolean shouldRememberPresentedOrder() {
		var minecraft = net.minecraft.client.Minecraft.getInstance();
		if (!(minecraft.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return true;
		}
		var component = ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (!(component instanceof net.minecraft.client.gui.screens.recipebook.RecipeBookComponent<?> recipeBookComponent)) {
			return true;
		}
		var searchBox = ((RecipeBookComponentAccessor) recipeBookComponent).getSearchBox();
		return searchBox == null || searchBox.getValue().isBlank();
	}

	static record NearbyMemoKey(RecipeDisplayId recipeId, boolean explicitVariantSelection) {
	}

	static final class SortPassContext {
		final Map<Integer, Integer> recentRanks;
		final boolean retrievalModeEnabled = ExistingOutputRetrievalController.isEnabled();
		final Map<RecipeDisplayId, Boolean> chainCraftableByRecipe = new HashMap<>();
		final Map<NearbyMemoKey, RecipeButtonNearbyIndicator.Craftability> nearbyCraftabilityByRecipe = new HashMap<>();
		final Map<NearbyMemoKey, Boolean> retrievabilityByRecipe = new HashMap<>();

		SortPassContext(Map<Integer, Integer> recentRanks) {
			this.recentRanks = recentRanks;
		}
	}

	static record SortScore(int tier, int rank, int originalIndex) implements Comparable<SortScore> {
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
