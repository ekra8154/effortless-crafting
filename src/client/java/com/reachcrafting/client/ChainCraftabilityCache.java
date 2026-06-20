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
	private static int tickCooldown = 0;
	private static long lastInventoryHash = 0;
	private static long lastNearbyRevision = -1;
	private static int lastReachableSignature = 0;
	private static int lastKnownRecipeCount = -1;
	private static int lastGridSlotCount = -1;
	private static List<LightRecipe> recipeIndex = List.of();
	private static Map<String, List<LightRecipe>> recipesByOutput = Map.of();
	private static java.util.concurrent.CompletableFuture<Void> backgroundTask = null;

	private ChainCraftabilityCache() {
	}

	public static void clearCache() {
		chainCraftableRecipeIds = Set.of();
		reachableRecipeIds = Set.of();
		tickCooldown = 0;
		lastInventoryHash = 0;
		lastNearbyRevision = -1;
		lastReachableSignature = 0;
		lastKnownRecipeCount = -1;
		lastGridSlotCount = -1;
		recipeIndex = List.of();
		recipesByOutput = Map.of();
		backgroundTask = null;
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

	private static void tick(Minecraft client) {
		refreshIfNeeded(client, true);
	}

	private static void refreshIfNeeded(Minecraft client, boolean fromTick) {
		if (!ReachCraftingConfig.get().enabled()
			|| client.player == null
			|| client.level == null) {
			if (!chainCraftableRecipeIds.isEmpty() || !reachableRecipeIds.isEmpty()) {
				chainCraftableRecipeIds = Set.of();
				reachableRecipeIds = Set.of();
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
			}
			return;
		}

		if (backgroundTask != null && !backgroundTask.isDone()) {
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

		if (!indexStale && !countsStale) {
			return;
		}

		lastKnownRecipeCount = knownCount;
		lastGridSlotCount = gridSlotCount;
		lastInventoryHash = inventoryHash;
		lastNearbyRevision = nearbyRevision;
		lastReachableSignature = reachableSignature;

		final long finalNearbyRevision = nearbyRevision;
		final int finalReachableSignature = reachableSignature;

		ContextMap context = SlotDisplayContext.fromLevel(client.level);

		long recomputeStartNanos = PerformanceProfiler.start();
		
		Map<String, Integer> availableCounts = captureAvailableCounts(player, client);

		backgroundTask = java.util.concurrent.CompletableFuture.runAsync(() -> {
			List<LightRecipe> localRecipeIndex = indexStale ? buildRecipeIndex(allRecipes, gridSlotCount, context) : recipeIndex;
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
				localRecipesByOutput = recipesByOutput;
			}

			recompute(localRecipeIndex, localRecipesByOutput, availableCounts, () -> {
				client.execute(() -> {
					recipeIndex = localRecipeIndex;
					recipesByOutput = localRecipesByOutput;
					lastKnownRecipeCount = knownCount;
					lastGridSlotCount = gridSlotCount;
					lastInventoryHash = inventoryHash;
					lastNearbyRevision = finalNearbyRevision;
					lastReachableSignature = finalReachableSignature;
					PerformanceProfiler.record(
						"chain.cache_tick_recompute",
						recomputeStartNanos,
						"recipes=" + localRecipeIndex.size() + " screen_slots=" + gridSlotCount + " index_stale=" + indexStale + " async=true"
					);
				});
			});
		});
	}

	private static Map<String, Integer> captureAvailableCounts(LocalPlayer player, Minecraft client) {
		Map<String, Integer> availableCounts = new java.util.HashMap<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				availableCounts.merge(id, stack.getCount(), Integer::sum);
			}
		}

		if (ReachCraftingConfig.get().enableNearbyContainerUsage()
			&& ReachCraftingConfig.get().cacheContainersForFasterSearch()
			&& client.getCameraEntity() != null
			&& client.level != null) {
			NearbyContainerCache.ReachableView view = NearbyContainerCache.getReachableView(
				client.level, client.getCameraEntity(), player.blockInteractionRange()
			);
			for (Map.Entry<String, Integer> entry : view.aggregateCounts().entrySet()) {
				availableCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}
		return availableCounts;
	}

	private static void recompute(
		List<LightRecipe> localRecipeIndex,
		Map<String, List<LightRecipe>> localRecipesByOutput,
		Map<String, Integer> availableCounts,
		Runnable onComplete
	) {
		long startNanos = PerformanceProfiler.start();
		Set<String> directlyAvailable = new HashSet<>(availableCounts.keySet());

		// Forward-reachability flood-fill
		Set<String> reachable = new HashSet<>(directlyAvailable);
		boolean changed = true;
		int iterations = 0;
		while (changed) {
			changed = false;
			iterations++;
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

		final int finalIterations = iterations;
		Minecraft.getInstance().execute(() -> {
			chainCraftableRecipeIds = Set.copyOf(chainResult);
			reachableRecipeIds = Set.copyOf(reachableResult);
			ReachCraftingMod.LOGGER.debug(
				"[chain_cache] recomputed chain_craftable={} reachable={} directly_available={} flood_iterations={}",
				chainResult.size(),
				reachableResult.size(),
				directlyAvailable.size(),
				finalIterations
			);
			PerformanceProfiler.record(
				"chain.cache_recompute_body",
				startNanos,
				"chain=" + chainResult.size() + " reachable=" + reachableResult.size() + " direct=" + directlyAvailable.size() + " iterations=" + finalIterations
			);
			onComplete.run();
		});
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
