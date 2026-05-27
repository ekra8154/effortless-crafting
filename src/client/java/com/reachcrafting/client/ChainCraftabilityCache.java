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
	private static int tickCooldown = 0;
	private static long lastInventoryHash = 0;
	private static long lastNearbyRevision = -1;
	private static int lastKnownRecipeCount = -1;
	private static int lastGridSlotCount = -1;
	private static List<LightRecipe> recipeIndex = List.of();

	private ChainCraftabilityCache() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(ChainCraftabilityCache::tick);
	}

	/**
	 * O(1) render-time check. Returns {@code true} if the given recipe is not
	 * directly craftable but could be completed through chain crafting.
	 */
	public static boolean isChainCraftable(RecipeDisplayId recipeId) {
		return recipeId != null && chainCraftableRecipeIds.contains(recipeId);
	}

	private static void tick(Minecraft client) {
		if (!ReachCraftingConfig.get().enabled()
			|| client.player == null
			|| client.level == null) {
			if (!chainCraftableRecipeIds.isEmpty()) {
				chainCraftableRecipeIds = Set.of();
				lastKnownRecipeCount = -1;
			}
			return;
		}
		if (!(client.screen instanceof CraftingScreen) && !(client.screen instanceof InventoryScreen)) {
			return;
		}
		if (ReachCraftingConfig.get().chainCraftingMode() == ReachCraftingConfig.ChainCraftingMode.DISABLED) {
			if (!chainCraftableRecipeIds.isEmpty()) {
				chainCraftableRecipeIds = Set.of();
			}
			return;
		}

		if (--tickCooldown > 0) {
			return;
		}
		tickCooldown = RECOMPUTE_INTERVAL_TICKS;

		LocalPlayer player = client.player;
		int gridSlotCount = client.screen instanceof InventoryScreen ? 4 : 9;
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) player.getRecipeBook()).getKnown();
		int knownCount = knownRecipes.size();
		long inventoryHash = computeInventoryHash(player);

		long nearbyRevision = 0;
		if (ReachCraftingConfig.get().enableNearbyContainerUsage()
			&& ReachCraftingConfig.get().cacheContainersForFasterSearch()
			&& client.getCameraEntity() != null) {
			NearbyContainerCache.ReachableView view = NearbyContainerCache.getReachableView(
				client.level, client.getCameraEntity(), player.blockInteractionRange()
			);
			nearbyRevision = view.revision();
		}

		boolean indexStale = knownCount != lastKnownRecipeCount || gridSlotCount != lastGridSlotCount;
		boolean countsStale = inventoryHash != lastInventoryHash || nearbyRevision != lastNearbyRevision;

		if (!indexStale && !countsStale) {
			return;
		}

		lastKnownRecipeCount = knownCount;
		lastGridSlotCount = gridSlotCount;
		lastInventoryHash = inventoryHash;
		lastNearbyRevision = nearbyRevision;

		ContextMap context = SlotDisplayContext.fromLevel(client.level);

		if (indexStale) {
			recipeIndex = buildRecipeIndex(knownRecipes, gridSlotCount, context);
			ReachCraftingMod.LOGGER.debug(
				"[chain_cache] rebuilt recipe index recipes={} grid_slots={}",
				recipeIndex.size(),
				gridSlotCount
			);
		}

		recompute(client, player);
	}

	private static void recompute(Minecraft client, LocalPlayer player) {
		// Collect directly available item IDs (boolean — at least 1 exists)
		Set<String> directlyAvailable = new HashSet<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				directlyAvailable.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			}
		}

		// Include nearby cache items when enabled
		if (ReachCraftingConfig.get().enableNearbyContainerUsage()
			&& ReachCraftingConfig.get().cacheContainersForFasterSearch()
			&& client.getCameraEntity() != null
			&& client.level != null) {
			NearbyContainerCache.ReachableView view = NearbyContainerCache.getReachableView(
				client.level, client.getCameraEntity(), player.blockInteractionRange()
			);
			directlyAvailable.addAll(view.aggregateCounts().keySet());
		}

		// Forward-reachability flood-fill
		Set<String> reachable = new HashSet<>(directlyAvailable);
		boolean changed = true;
		int iterations = 0;
		while (changed) {
			changed = false;
			iterations++;
			for (LightRecipe recipe : recipeIndex) {
				if (reachable.contains(recipe.outputItemId)) {
					continue;
				}
				if (allSlotsSatisfied(recipe.ingredientSlots, reachable)) {
					reachable.add(recipe.outputItemId);
					changed = true;
				}
			}
		}

		// Classify each recipe in the index
		Set<RecipeDisplayId> result = new HashSet<>();
		for (LightRecipe recipe : recipeIndex) {
			if (!allSlotsSatisfied(recipe.ingredientSlots, reachable)) {
				continue;
			}
			if (allSlotsSatisfied(recipe.ingredientSlots, directlyAvailable)) {
				continue;
			}
			result.add(recipe.recipeId);
		}

		chainCraftableRecipeIds = Set.copyOf(result);
		ReachCraftingMod.LOGGER.debug(
			"[chain_cache] recomputed chain_craftable={} reachable={} directly_available={} flood_iterations={}",
			result.size(),
			reachable.size(),
			directlyAvailable.size(),
			iterations
		);
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

	private static List<LightRecipe> buildRecipeIndex(
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes,
		int gridSlotCount,
		ContextMap context
	) {
		List<LightRecipe> index = new ArrayList<>();
		for (RecipeDisplayEntry entry : knownRecipes.values()) {
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
			index.add(new LightRecipe(outputId, List.copyOf(slots), entry.id()));
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

	private record LightRecipe(String outputItemId, List<List<String>> ingredientSlots, RecipeDisplayId recipeId) {
	}
}
