package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import com.reachcrafting.ReachCraftingMod;
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
		if (ContainerUtils.isAnySessionActive()) {
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
					+ " collection_memo=" + sortContext.collectionCraftability.size()
			);
			ReachCraftingMod.LOGGER.info(
				"[recipe_sort] sorted mode=eager collections={} chain_memo={} collection_memo={}",
				collections.size(),
				sortContext.chainCraftableByRecipe.size(),
				sortContext.collectionCraftability.size()
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

	static Map<String, Integer> recentRanks() {
		Map<String, Integer> ranks = new HashMap<>();
		List<String> recent = ReachCraftingConfig.get().recentRecipeDisplayIds();
		for (int i = 0; i < recent.size(); i++) {
			ranks.putIfAbsent(recent.get(i), i);
		}
		return ranks;
	}

	public static SortScore fastScore(RecipeCollection collection, SortPassContext context, int originalIndex) {
		List<Recipe<?>> recipes = selectedRecipes(collection);
		int recentRank = recentRank(recipes, context.recentRanks);

		// Craftability decides the tier; recency only ranks WITHIN a tier, so
		// a recently-used recipe never outranks something craftable right now.
		if (collection.hasCraftable()) {
			return new SortScore(0, recentRank, originalIndex);
		}

		boolean locallyDirect = false;
		boolean locallyChain = false;
		boolean nearbyDirect = false;
		boolean anyChain = false;
		// getRecipes(), NOT the grid-filtered selection - see collectionCraftability.
		for (Recipe<?> recipe : collection.getRecipes()) {
			ResourceLocation recipeId = recipe.getId();
			locallyDirect |= ChainCraftabilityCache.isReachableLocally(recipeId);
			locallyChain |= ChainCraftabilityCache.isChainCraftableLocally(recipeId);
			nearbyDirect |= ChainCraftabilityCache.isReachable(recipeId);
			anyChain |= ChainCraftabilityCache.isChainCraftable(recipeId);
			if (locallyDirect) {
				break;
			}
		}

		if (locallyDirect) {
			return new SortScore(0, recentRank, originalIndex);
		}
		if (locallyChain) {
			return new SortScore(1, recentRank, originalIndex);
		}
		if (nearbyDirect) {
			return new SortScore(2, recentRank, originalIndex);
		}
		if (anyChain) {
			return new SortScore(3, recentRank, originalIndex);
		}
		return new SortScore(4, recentRank, originalIndex);
	}

	static SortScore fullScore(RecipeCollection collection, SortPassContext context, int originalIndex) {
		List<Recipe<?>> recipes = selectedRecipes(collection);
		int recentRank = recentRank(recipes, context.recentRanks);

		// Tier order (user-facing contract): in-inventory direct, in-inventory
		// chain, needs-nearby direct, needs-nearby chain, everything else.
		// Recency only ranks WITHIN a tier - a recently-used recipe with no
		// materials must not outrank something craftable right now.
		if (collection.hasCraftable()) {
			return new SortScore(0, recentRank, originalIndex);
		}

		// Exactly the predicate the craftable ICON is drawn from, so a recipe
		// can never show a craftable icon and sort into an uncraftable tier.
		RecipeButtonNearbyIndicator.Craftability craftability =
			context.collectionCraftability.computeIfAbsent(
				collection, RecipeButtonNearbyIndicator::collectionCraftability);
		if (craftability == RecipeButtonNearbyIndicator.Craftability.LOCALLY_CRAFTABLE) {
			return new SortScore(0, recentRank, originalIndex);
		}

		boolean locallyChain = false;
		boolean anyChain = false;
		for (Recipe<?> recipe : collection.getRecipes()) {
			ResourceLocation recipeId = recipe.getId();
			if (ChainCraftabilityCache.isChainCraftableLocally(recipeId)) {
				locallyChain = true;
				break;
			}
			anyChain |= context.chainCraftableByRecipe.computeIfAbsent(recipeId, ChainCraftabilityCache::isChainCraftable);
		}

		if (locallyChain) {
			return new SortScore(1, recentRank, originalIndex);
		}
		if (craftability == RecipeButtonNearbyIndicator.Craftability.NEARBY_CRAFTABLE) {
			return new SortScore(2, recentRank, originalIndex);
		}
		if (anyChain) {
			return new SortScore(3, recentRank, originalIndex);
		}
		return new SortScore(4, recentRank, originalIndex);
	}

	private static List<Recipe<?>> selectedRecipes(RecipeCollection collection) {
		return collection.getRecipes();
	}

	private static int recentRank(List<Recipe<?>> recipes, Map<String, Integer> recentRanks) {
		int recentRank = Integer.MAX_VALUE;
		for (Recipe<?> recipe : recipes) {
			recentRank = Math.min(recentRank, recentRanks.getOrDefault(recipe.getId().toString(), Integer.MAX_VALUE));
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
		if (!(minecraft.screen instanceof RecipeUpdateListener recipeBookScreen)) {
			return true;
		}
		RecipeBookComponent recipeBookComponent = recipeBookScreen.getRecipeBookComponent();
		if (recipeBookComponent == null) {
			return true;
		}
		var searchBox = ((RecipeBookComponentAccessor) recipeBookComponent).getSearchBox();
		return searchBox == null || searchBox.getValue().isBlank();
	}


	static final class SortPassContext {
		final Map<String, Integer> recentRanks;
		final Map<ResourceLocation, Boolean> chainCraftableByRecipe = new HashMap<>();
		final Map<RecipeCollection, RecipeButtonNearbyIndicator.Craftability> collectionCraftability = new IdentityHashMap<>();

		SortPassContext(Map<String, Integer> recentRanks) {
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
