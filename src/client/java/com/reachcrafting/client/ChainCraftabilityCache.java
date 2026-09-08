package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * Background cache that precomputes which recipes are chain-craftable.
 *
 * <p>A recipe is "chain-craftable" when its ingredients are not all directly
 * available but could be produced through a sequence of intermediate crafts
 * using currently available base materials.</p>
 *
 * <p>The cache runs a forward-reachability flood-fill over the recipe graph
 * every ~10 client ticks and stores the result as an immutable
 * {@code Set<RecipeDisplayId>}. Render-time queries are O(1) set lookups.</p>
 */
public final class ChainCraftabilityCache {
	private static final int RECOMPUTE_INTERVAL_TICKS = 10;

	private static Set<RecipeDisplayId> chainCraftableRecipeIds = Set.of();
	private static Set<RecipeDisplayId> reachableRecipeIds = Set.of();
	private static Set<RecipeDisplayId> locallyChainCraftableRecipeIds = Set.of();
	private static Set<RecipeDisplayId> locallyReachableRecipeIds = Set.of();
	private static int tickCooldown = 0;
	private static long lastInventoryHash = 0;
	private static long lastNearbyRevision = -1;
	private static int lastReachableSignature = 0;
	private static int lastKnownRecipeCount = -1;
	private static int lastGridSlotCount = -1;
	private static List<LightRecipe> recipeIndex = List.of();
	private static Map<String, List<LightRecipe>> recipesByOutput = Map.of();
	private static java.util.concurrent.CompletableFuture<Void> backgroundTask = null;
	/**
	 * Bumped by every recompute that starts. An async recompute applies its
	 * result only if nothing newer started meanwhile, so a synchronous refresh
	 * taken on a click can never be overwritten by a slower, staler one.
	 */
	private static int generation = 0;
	/** Generation of the async recompute whose publish has not landed yet (0 = none). */
	private static int pendingAsyncGeneration = 0;

	private ChainCraftabilityCache() {
	}

	public static void clearCache() {
		chainCraftableRecipeIds = Set.of();
		reachableRecipeIds = Set.of();
		locallyChainCraftableRecipeIds = Set.of();
		locallyReachableRecipeIds = Set.of();
		tickCooldown = 0;
		lastInventoryHash = 0;
		lastNearbyRevision = -1;
		lastReachableSignature = 0;
		lastKnownRecipeCount = -1;
		lastGridSlotCount = -1;
		recipeIndex = List.of();
		recipesByOutput = Map.of();
		backgroundTask = null;
		pendingAsyncGeneration = 0;
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(ChainCraftabilityCache::tick);
	}

	/**
	 * O(1) render-time check. Returns {@code true} if the given recipe is not
	 * directly craftable but could be completed through chain crafting.
	 */
	public static boolean isChainCraftable(RecipeDisplayId recipeId) {
		refreshIfNeeded(Minecraft.getInstance(), false);
		return recipeId != null && chainCraftableRecipeIds.contains(recipeId);
	}

	public static boolean isReachable(RecipeDisplayId recipeId) {
		refreshIfNeeded(Minecraft.getInstance(), false);
		return recipeId != null && reachableRecipeIds.contains(recipeId);
	}

	/**
	 * Like {@link #isChainCraftable} but counting ONLY the player's inventory,
	 * not nearby containers. Used by the smart sort to rank in-inventory chains
	 * above crafts that would need chest withdrawals.
	 */
	public static boolean isChainCraftableLocally(RecipeDisplayId recipeId) {
		refreshIfNeeded(Minecraft.getInstance(), false);
		return recipeId != null && locallyChainCraftableRecipeIds.contains(recipeId);
	}

	/** Like {@link #isReachable} but counting ONLY the player's inventory. */
	public static boolean isReachableLocally(RecipeDisplayId recipeId) {
		refreshIfNeeded(Minecraft.getInstance(), false);
		return recipeId != null && locallyReachableRecipeIds.contains(recipeId);
	}

	/**
	 * Item ids consumed by the known recipes that produce {@code outputItemId}
	 * -- the material one hop below an ingredient (spruce logs for spruce
	 * planks).
	 *
	 * <p>Reads the prebuilt index directly and deliberately does NOT call
	 * {@link #refreshIfNeeded}: that re-walks every recipe collection and
	 * re-hashes the inventory before its own staleness check, which is far too
	 * expensive to pay per ingredient. An index that has not been built yet
	 * simply yields nothing, which degrades the caller to its own tiebreak.</p>
	 */
	public static Set<String> producerInputItemIds(String outputItemId) {
		List<LightRecipe> producers = recipesByOutput.get(outputItemId);
		if (producers == null || producers.isEmpty()) {
			return Set.of();
		}
		Set<String> inputItemIds = new HashSet<>();
		for (LightRecipe producer : producers) {
			for (List<String> slot : producer.ingredientSlots()) {
				inputItemIds.addAll(slot);
			}
		}
		return Set.copyOf(inputItemIds);
	}

	private static void tick(Minecraft client) {
		refreshIfNeeded(client, true);
	}

	/**
	 * Bring the sets up to date NOW, on the calling thread. The tick refresh
	 * is asynchronous, which is right for painting indicators but wrong for
	 * a decision: the first click after opening a crafting table (or after a
	 * chest scan changed the nearby counts) read the previous state's sets
	 * while the recompute for the new one was still in flight, so a variant
	 * fallback saw every sibling as chain-unreachable and the harness probe
	 * reported the stale answer. Cheap enough for a click: the recipe index
	 * is rebuilt only when the recipe count changed, and the classification
	 * is one flood-fill over the light index.
	 */
	public static void refreshNow(Minecraft client) {
		refresh(client, false, true);
	}

	private static void refreshIfNeeded(Minecraft client, boolean fromTick) {
		refresh(client, fromTick, false);
	}

	private static void refresh(Minecraft client, boolean fromTick, boolean synchronous) {
		if (!ReachCraftingConfig.get().enabled()
			|| client.player == null
			|| client.level == null) {
			if (!chainCraftableRecipeIds.isEmpty() || !reachableRecipeIds.isEmpty()) {
				chainCraftableRecipeIds = Set.of();
				reachableRecipeIds = Set.of();
				locallyChainCraftableRecipeIds = Set.of();
				locallyReachableRecipeIds = Set.of();
				lastKnownRecipeCount = -1;
			}
			return;
		}
		if (!(client.gui.screen() instanceof CraftingScreen) && !(client.gui.screen() instanceof InventoryScreen)) {
			return;
		}
		if (ReachCraftingConfig.get().chainCraftingMode() == ReachCraftingConfig.ChainCraftingMode.DISABLED) {
			if (!chainCraftableRecipeIds.isEmpty() || !reachableRecipeIds.isEmpty()) {
				chainCraftableRecipeIds = Set.of();
				reachableRecipeIds = Set.of();
				locallyChainCraftableRecipeIds = Set.of();
				locallyReachableRecipeIds = Set.of();
			}
			return;
		}

		// "In flight" runs from the async start until its publish lands on
		// the render thread, not until the worker returns: the future is
		// done a moment before client.execute runs the publish, and a
		// synchronous refresh in that gap must still recompute.
		boolean inFlight = pendingAsyncGeneration != 0
			|| (backgroundTask != null && !backgroundTask.isDone());
		if (inFlight && !synchronous) {
			return; // Don't start a new recompute if one is currently in progress
		}

		if (fromTick && --tickCooldown > 0) {
			return;
		}
		tickCooldown = RECOMPUTE_INTERVAL_TICKS;

		LocalPlayer player = client.player;
		int gridSlotCount = client.gui.screen() instanceof InventoryScreen ? 4 : 9;
		List<RecipeDisplayEntry> allRecipes = new java.util.ArrayList<>();
		for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
			allRecipes.addAll(collection.getRecipes());
		}
		int knownCount = allRecipes.size();
		long inventoryHash = computeInventoryHash(player);

		long nearbyRevision = 0;
		int reachableSignature = 0;
		if (ReachCraftingConfig.get().enableNearbyContainerUsage()
			&& ReachCraftingConfig.get().cacheContainersForFasterSearch()
			&& client.getCameraEntity() != null) {
			NearbyContainerCache.ReachableView view = NearbyContainerCache.getReachableView(
				client.level, client.getCameraEntity(), player.blockInteractionRange()
			);
			nearbyRevision = view.revision();
			reachableSignature = reachableSignature(view);
		}

		boolean indexStale = knownCount != lastKnownRecipeCount || gridSlotCount != lastGridSlotCount;
		boolean countsStale = inventoryHash != lastInventoryHash
			|| nearbyRevision != lastNearbyRevision
			|| reachableSignature != lastReachableSignature;

		// An in-flight async recompute still leaves the sets stale until it
		// lands; a synchronous caller needs the answer now, so it recomputes
		// even when nothing changed since that task started.
		if (!indexStale && !countsStale && !(synchronous && inFlight)) {
			return;
		}

		// Only the COUNT markers are stamped up front (so ticks do not restart
		// an in-flight recompute). The index markers are stamped by apply():
		// a synchronous refresh arriving while an async index build is still
		// in flight must build the index itself, not classify over the old
		// (possibly empty) one and then out-generation the real result.
		lastInventoryHash = inventoryHash;
		lastNearbyRevision = nearbyRevision;
		lastReachableSignature = reachableSignature;

		final long finalNearbyRevision = nearbyRevision;
		final int finalReachableSignature = reachableSignature;

		ContextMap context = SlotDisplayContext.fromLevel(client.level);

		long recomputeStartNanos = PerformanceProfiler.start();

		CountsCapture counts = captureCounts(player, client);
		final int thisGeneration = ++generation;
		final List<LightRecipe> baseIndex = recipeIndex;
		final Map<String, List<LightRecipe>> baseByOutput = recipesByOutput;

		if (synchronous) {
			Computed computed = compute(allRecipes, gridSlotCount, context, indexStale, baseIndex, baseByOutput, counts);
			apply(thisGeneration, computed, knownCount, gridSlotCount, inventoryHash, nearbyRevision, reachableSignature);
			PerformanceProfiler.record(
				"chain.cache_sync_recompute",
				recomputeStartNanos,
				"recipes=" + computed.index().size() + " screen_slots=" + gridSlotCount + " index_stale=" + indexStale + " async=false"
			);
			return;
		}

		pendingAsyncGeneration = thisGeneration;
		backgroundTask = java.util.concurrent.CompletableFuture.runAsync(() -> {
			Computed computed = compute(allRecipes, gridSlotCount, context, indexStale, baseIndex, baseByOutput, counts);
			client.execute(() -> {
				if (pendingAsyncGeneration == thisGeneration) {
					pendingAsyncGeneration = 0;
				}
				if (thisGeneration != generation) {
					return; // a newer recompute (a synchronous one on a click) already applied
				}
				apply(thisGeneration, computed, knownCount, gridSlotCount, inventoryHash, finalNearbyRevision, finalReachableSignature);
				PerformanceProfiler.record(
					"chain.cache_tick_recompute",
					recomputeStartNanos,
					"recipes=" + computed.index().size() + " screen_slots=" + gridSlotCount + " index_stale=" + indexStale + " async=true"
				);
			});
		});
	}

	private record Computed(
		List<LightRecipe> index,
		Map<String, List<LightRecipe>> byOutput,
		Classification local,
		Classification merged,
		int directlyAvailable
	) {
	}

	/** The whole recompute, thread-agnostic: index (if stale) plus the two classification passes. */
	private static Computed compute(
		List<RecipeDisplayEntry> allRecipes,
		int gridSlotCount,
		ContextMap context,
		boolean indexStale,
		List<LightRecipe> baseIndex,
		Map<String, List<LightRecipe>> baseByOutput,
		CountsCapture counts
	) {
		List<LightRecipe> localRecipeIndex = indexStale ? buildRecipeIndex(allRecipes, gridSlotCount, context) : baseIndex;
		Map<String, List<LightRecipe>> localRecipesByOutput;
		if (indexStale) {
			Map<String, List<LightRecipe>> byOutput = new java.util.HashMap<>();
			for (LightRecipe recipe : localRecipeIndex) {
				byOutput.computeIfAbsent(recipe.outputItemId, k -> new ArrayList<>()).add(recipe);
			}
			localRecipesByOutput = Map.copyOf(byOutput);
			ReachCraftingMod.LOGGER.debug(
				"[chain_cache] rebuilt recipe index recipes={} grid_slots={}",
				localRecipeIndex.size(),
				gridSlotCount
			);
		} else {
			localRecipesByOutput = baseByOutput;
		}
		// Two classification passes: inventory-only, then inventory + nearby
		// containers. The smart sort ranks in-inventory results above ones
		// that would need chest withdrawals. When nearby adds nothing the
		// merged map is the SAME instance and the second pass is skipped.
		Classification local = classify(localRecipeIndex, localRecipesByOutput, counts.local());
		Classification merged = counts.merged() == counts.local()
			? local
			: classify(localRecipeIndex, localRecipesByOutput, counts.merged());
		return new Computed(localRecipeIndex, localRecipesByOutput, local, merged, counts.merged().size());
	}

	/** Publish a recompute's result; render thread only. */
	private static void apply(
		int thisGeneration,
		Computed computed,
		int knownCount,
		int gridSlotCount,
		long inventoryHash,
		long nearbyRevision,
		int reachableSignature
	) {
		recipeIndex = computed.index();
		recipesByOutput = computed.byOutput();
		chainCraftableRecipeIds = computed.merged().chain();
		reachableRecipeIds = computed.merged().direct();
		locallyChainCraftableRecipeIds = computed.local().chain();
		locallyReachableRecipeIds = computed.local().direct();
		lastKnownRecipeCount = knownCount;
		lastGridSlotCount = gridSlotCount;
		lastInventoryHash = inventoryHash;
		lastNearbyRevision = nearbyRevision;
		lastReachableSignature = reachableSignature;
		ReachCraftingMod.LOGGER.debug(
			"[chain_cache] recomputed generation={} chain_craftable={} reachable={} local_chain={} local_reachable={} directly_available={}",
			thisGeneration,
			computed.merged().chain().size(),
			computed.merged().direct().size(),
			computed.local().chain().size(),
			computed.local().direct().size(),
			computed.directlyAvailable()
		);
	}

	record CountsCapture(Map<String, Integer> local, Map<String, Integer> merged) {
	}

	private static CountsCapture captureCounts(LocalPlayer player, Minecraft client) {
		Map<String, Integer> localCounts = new java.util.HashMap<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				localCounts.merge(id, stack.getCount(), Integer::sum);
			}
		}

		Map<String, Integer> mergedCounts = localCounts;
		if (ReachCraftingConfig.get().enableNearbyContainerUsage()
			&& ReachCraftingConfig.get().cacheContainersForFasterSearch()
			&& client.getCameraEntity() != null
			&& client.level != null) {
			NearbyContainerCache.ReachableView view = NearbyContainerCache.getReachableView(
				client.level, client.getCameraEntity(), player.blockInteractionRange()
			);
			if (!view.aggregateCounts().isEmpty()) {
				mergedCounts = new java.util.HashMap<>(localCounts);
				for (Map.Entry<String, Integer> entry : view.aggregateCounts().entrySet()) {
					mergedCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
				}
			}
		}
		return new CountsCapture(localCounts, mergedCounts);
	}

	private record Classification(Set<RecipeDisplayId> chain, Set<RecipeDisplayId> direct) {
	}

	private static Classification classify(
		List<LightRecipe> localRecipeIndex,
		Map<String, List<LightRecipe>> localRecipesByOutput,
		Map<String, Integer> availableCounts
	) {
		Set<String> directlyAvailable = new HashSet<>(availableCounts.keySet());

		// Forward-reachability flood-fill
		Set<String> reachable = new HashSet<>(directlyAvailable);
		boolean changed = true;
		while (changed) {
			changed = false;
			for (LightRecipe recipe : localRecipeIndex) {
				if (reachable.contains(recipe.outputItemId)) {
					continue;
				}
				if (allSlotsSatisfied(recipe.ingredientSlots, reachable)) {
					reachable.add(recipe.outputItemId);
					changed = true;
				}
			}
		}

		// Classify each recipe in the index using exact verification
		Set<RecipeDisplayId> chainResult = new HashSet<>();
		Set<RecipeDisplayId> reachableResult = new HashSet<>();
		for (LightRecipe recipe : localRecipeIndex) {
			if (!allSlotsSatisfied(recipe.ingredientSlots, reachable)) {
				continue;
			}
			VerifyResult verifyResult = verifyExact(recipe, availableCounts, localRecipesByOutput);
			if (verifyResult == VerifyResult.CHAIN_CRAFTABLE) {
				chainResult.add(recipe.recipeId);
			} else if (verifyResult == VerifyResult.DIRECTLY_CRAFTABLE) {
				reachableResult.add(recipe.recipeId);
			}
		}
		return new Classification(Set.copyOf(chainResult), Set.copyOf(reachableResult));
	}

	private static boolean allSlotsSatisfied(List<List<String>> ingredientSlots, Set<String> available) {
		for (List<String> slotOptions : ingredientSlots) {
			boolean satisfied = false;
			for (String itemId : slotOptions) {
				if (available.contains(itemId)) {
					satisfied = true;
					break;
				}
			}
			if (!satisfied) {
				return false;
			}
		}
		return true;
	}

	private enum VerifyResult {
		NOT_CRAFTABLE,
		DIRECTLY_CRAFTABLE,
		CHAIN_CRAFTABLE
	}

	private static int dfsOperations = 0;

	private static VerifyResult verifyExact(LightRecipe recipe, Map<String, Integer> directlyAvailableCounts, Map<String, List<LightRecipe>> localRecipesByOutput) {
		dfsOperations = 0;
		Map<String, Integer> state = new java.util.HashMap<>(directlyAvailableCounts);
		return verifyRecipe(recipe, 1, state, new HashSet<>(), 0, localRecipesByOutput);
	}

	private static VerifyResult verifyRecipe(LightRecipe recipe, int craftsNeeded, Map<String, Integer> state, Set<RecipeDisplayId> resolving, int depth, Map<String, List<LightRecipe>> localRecipesByOutput) {
		if (depth > 6) return VerifyResult.NOT_CRAFTABLE;
		if (dfsOperations++ > 2000) return VerifyResult.NOT_CRAFTABLE;

		if (!resolving.add(recipe.recipeId)) {
			return VerifyResult.NOT_CRAFTABLE; // Cycle detected
		}
		
		Map<List<String>, Integer> aggregatedSlots = new java.util.HashMap<>();
		for (List<String> slot : recipe.ingredientSlots) {
			aggregatedSlots.merge(slot, craftsNeeded, Integer::sum);
		}
		
		boolean anyBacktracked = false;
		for (Map.Entry<List<String>, Integer> entry : aggregatedSlots.entrySet()) {
			VerifyResult reqResult = fulfillRequirement(entry.getKey(), entry.getValue(), state, resolving, depth + 1, localRecipesByOutput);
			if (reqResult == VerifyResult.NOT_CRAFTABLE) {
				resolving.remove(recipe.recipeId);
				return VerifyResult.NOT_CRAFTABLE;
			}
			if (reqResult == VerifyResult.CHAIN_CRAFTABLE) {
				anyBacktracked = true;
			}
		}
		resolving.remove(recipe.recipeId);
		return anyBacktracked ? VerifyResult.CHAIN_CRAFTABLE : VerifyResult.DIRECTLY_CRAFTABLE;
	}

	private static VerifyResult fulfillRequirement(List<String> options, int needed, Map<String, Integer> state, Set<RecipeDisplayId> resolving, int depth, Map<String, List<LightRecipe>> localRecipesByOutput) {
		if (dfsOperations++ > 2000) return VerifyResult.NOT_CRAFTABLE;

		// 1. Greedily consume what we already have
		for (String option : options) {
			int available = state.getOrDefault(option, 0);
			if (available > 0) {
				int consume = Math.min(available, needed);
				state.put(option, available - consume);
				needed -= consume;
				if (needed <= 0) return VerifyResult.DIRECTLY_CRAFTABLE;
			}
		}
		
		// 2. Backtrack to craft the remainder
		for (String option : options) {
			List<LightRecipe> producers = localRecipesByOutput.get(option);
			if (producers == null) continue;
			
			for (LightRecipe producer : producers) {
				int craftsNeeded = (needed + producer.outputCount - 1) / producer.outputCount;
				Map<String, Integer> stateBackup = new java.util.HashMap<>(state);
				
				VerifyResult prodResult = verifyRecipe(producer, craftsNeeded, state, resolving, depth, localRecipesByOutput);
				if (prodResult != VerifyResult.NOT_CRAFTABLE) {
					int produced = craftsNeeded * producer.outputCount;
					int remainder = produced - needed;
					state.put(option, state.getOrDefault(option, 0) + remainder);
					return VerifyResult.CHAIN_CRAFTABLE;
				}
				
				state.clear();
				state.putAll(stateBackup);
			}
		}
		return VerifyResult.NOT_CRAFTABLE;
	}

	private static List<LightRecipe> buildRecipeIndex(
		List<RecipeDisplayEntry> allRecipes,
		int gridSlotCount,
		ContextMap context
	) {
		List<LightRecipe> index = new ArrayList<>();
		for (RecipeDisplayEntry entry : allRecipes) {
			if (!fitsGrid(entry, gridSlotCount)) {
				continue;
			}
			ItemStack output = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
			if (output.isEmpty()) {
				continue;
			}
			RecipeIngredientSummary summary = RecipeIngredientSummary.fromDisplay(entry.display(), context);
			List<List<String>> slots = new ArrayList<>();
			for (RecipeIngredientSummary.IngredientSlot slot : summary.slots()) {
				if (!slot.isEmpty()) {
					slots.add(slot.itemIds());
				}
			}
			if (slots.isEmpty()) {
				continue;
			}
			String outputId = BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
			int outputCount = Math.max(output.getCount(), 1);
			index.add(new LightRecipe(outputId, outputCount, List.copyOf(slots), entry.id()));
		}
		return List.copyOf(index);
	}

	private static boolean fitsGrid(RecipeDisplayEntry entry, int gridSlotCount) {
		if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
			if (gridSlotCount == 4) {
				return shaped.width() <= 2 && shaped.height() <= 2;
			}
			return gridSlotCount == 9 && shaped.width() <= 3 && shaped.height() <= 3;
		}
		if (entry.display() instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return shapeless.ingredients().size() <= gridSlotCount;
		}
		return false;
	}

	private static long computeInventoryHash(LocalPlayer player) {
		long hash = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				hash = hash * 31 + BuiltInRegistries.ITEM.getKey(stack.getItem()).hashCode();
				hash = hash * 31 + stack.getCount();
			} else {
				hash = hash * 31;
			}
		}
		return hash;
	}

	private static int reachableSignature(NearbyContainerCache.ReachableView view) {
		int signature = 1;
		signature = (31 * signature) + view.aggregateCounts().hashCode();
		signature = (31 * signature) + view.snapshotsByKey().keySet().hashCode();
		return signature;
	}

	private record LightRecipe(String outputItemId, int outputCount, List<List<String>> ingredientSlots, RecipeDisplayId recipeId) {
	}
}
