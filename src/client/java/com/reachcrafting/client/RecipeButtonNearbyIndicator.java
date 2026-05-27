package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.player.LocalPlayer;
import java.util.Map;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;

public final class RecipeButtonNearbyIndicator {
	private static long lastInventoryHash = 0;
	private static long lastNearbyRevision = -1;
	public enum Craftability {
		NOT_CRAFTABLE,
		LOCALLY_CRAFTABLE,
		NEARBY_CRAFTABLE
	}

	private static final Map<RecipeDisplayId, Craftability> mainCache = new java.util.HashMap<>();
	private static final Map<RecipeDisplayId, Craftability> overlayCache = new java.util.HashMap<>();

	private RecipeButtonNearbyIndicator() {
	}

	public static boolean shouldShow(RecipeButton button) {
		if (!ReachCraftingConfig.get().showNearbyCraftableIndicator()) {
			return false;
		}
		if (button.getCollection() != null && button.getCollection().getRecipes().size() > 1) {
			for (net.minecraft.world.item.crafting.display.RecipeDisplayEntry entry : button.getCollection().getRecipes()) {
				if (getCraftability(entry.id(), button.getCollection(), ItemStack.EMPTY, true) == Craftability.NEARBY_CRAFTABLE) {
					return true;
				}
			}
			return false;
		}
		return getCraftability(button.getCurrentRecipe(), button.getCollection(), button.getDisplayStack().copy(), false) == Craftability.NEARBY_CRAFTABLE;
	}

	public static boolean isChainCraftable(RecipeButton button) {
		if (!ReachCraftingConfig.get().showNearbyCraftableIndicator()) {
			return false;
		}
		if (button.getCollection() != null && button.getCollection().getRecipes().size() > 1) {
			for (net.minecraft.world.item.crafting.display.RecipeDisplayEntry entry : button.getCollection().getRecipes()) {
				if (ChainCraftabilityCache.isChainCraftable(entry.id())) {
					return true;
				}
			}
			return false;
		}
		return ChainCraftabilityCache.isChainCraftable(button.getCurrentRecipe());
	}

	public static Craftability getCraftability(RecipeDisplayId recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		if (!ReachCraftingConfig.get().enabled()
			|| !ReachCraftingConfig.get().enableNearbyContainerUsage()
			|| !ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			return Craftability.NOT_CRAFTABLE;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return Craftability.NOT_CRAFTABLE;
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.getCameraEntity() == null) {
			return Craftability.NOT_CRAFTABLE;
		}
		if (recipe == null || collection == null) {
			return Craftability.NOT_CRAFTABLE;
		}

		NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(
			minecraft.level,
			minecraft.getCameraEntity(),
			player.blockInteractionRange()
		);
		long nearbyRevision = reachableView.revision();
		long inventoryHash = computeInventoryHash(player, screen);

		if (nearbyRevision != lastNearbyRevision || inventoryHash != lastInventoryHash) {
			lastNearbyRevision = nearbyRevision;
			lastInventoryHash = inventoryHash;
			mainCache.clear();
			overlayCache.clear();
		}

		Map<RecipeDisplayId, Craftability> cacheMap = explicitVariantSelection ? overlayCache : mainCache;
		if (cacheMap.containsKey(recipe)) {
			return cacheMap.get(recipe);
		}

		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		Map<String, Integer> cachedNearbyCounts = reachableView.aggregateCounts();
		Map<String, Integer> totalAvailable = AvailableItemSnapshot.mergeCounts(availableItems.inventoryCounts(), cachedNearbyCounts);
		
		// If the variant matches the grid, we check for a 'top-up' (current + 1).
		// If it's a DIFFERENT variant, we just check if we can craft 1.
		boolean variantMatchesGrid = false;
		if (availableItems.hasReservedGrid()) {
			RecipeVariantResolver.Selection currentGridSelection = RecipeVariantResolver.resolveMatchForGrid(
				minecraft, player, collection, availableItems.gridStacks(), availableItems.gridStacks().stream().anyMatch(s -> !s.isEmpty()) ? availableItems : AvailableItemSnapshot.capture(player, screen), totalAvailable, totalAvailable, false, 1
			);
			if (currentGridSelection != null && currentGridSelection.recipeId().equals(recipe)) {
				variantMatchesGrid = true;
			}
		}

		int desiredVariantCopies = variantMatchesGrid
			? currentReservedCraftCopies(availableItems) + 1
			: 1;

		RecipeVariantResolver.Selection selection = RecipeVariantResolver.resolve(
			minecraft,
			player,
			recipe,
			collection,
			displayStack,
			explicitVariantSelection,
			true,
			availableItems,
			totalAvailable,
			totalAvailable,
			false,
			ReachCraftingConfig.get().redistributeToCraftWhenNeeded(),
			desiredVariantCopies
		);
		if (selection == null) {
			cacheMap.put(recipe, Craftability.NOT_CRAFTABLE);
			return Craftability.NOT_CRAFTABLE;
		}

		IngredientPlanning.Policy policy = ReachCraftingConfig.get().toPlanningPolicy();
		IngredientPlanning.PlanResult localPlan = IngredientPlanning.plan(
			selection.ingredientSummary(),
			availableItems.inventoryCounts(),
			availableItems.gridStacks(),
			availableItems.inventoryCounts(),
			availableItems.inventoryCounts(),
			desiredVariantCopies,
			policy
		);
		if (!localPlan.hasMissingIngredients()) {
			cacheMap.put(recipe, Craftability.LOCALLY_CRAFTABLE);
			return Craftability.LOCALLY_CRAFTABLE;
		}

		if (reachableView.isEmpty()) {
			cacheMap.put(recipe, Craftability.NOT_CRAFTABLE);
			return Craftability.NOT_CRAFTABLE;
		}

		IngredientPlanning.PlanResult cachedPlan = IngredientPlanning.plan(
			selection.ingredientSummary(),
			availableItems.inventoryCounts(),
			availableItems.gridStacks(),
			totalAvailable,
			totalAvailable,
			desiredVariantCopies,
			policy
		);
		Craftability result = !cachedPlan.hasMissingIngredients() ? Craftability.NEARBY_CRAFTABLE : Craftability.NOT_CRAFTABLE;
		cacheMap.put(recipe, result);
		return result;
	}

	private static long computeInventoryHash(LocalPlayer player, Screen screen) {
		long hash = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				hash = hash * 31 + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).hashCode();
				hash = hash * 31 + stack.getCount();
			} else {
				hash = hash * 31;
			}
		}
		if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen) {
			int gridSlotCount = screen instanceof InventoryScreen ? 4 : screen instanceof CraftingScreen ? 9 : 0;
			for (int slotIndex = 1; slotIndex <= gridSlotCount; slotIndex++) {
				ItemStack stack = containerScreen.getMenu().getSlot(slotIndex).getItem();
				if (!stack.isEmpty()) {
					hash = hash * 31 + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).hashCode();
					hash = hash * 31 + stack.getCount();
				} else {
					hash = hash * 31;
				}
			}
		}
		return hash;
	}

	private static int currentReservedCraftCopies(AvailableItemSnapshot availableItems) {
		return ContainerUtils.currentReservedCraftCopies(availableItems.gridStacks());
	}

	public static void renderOverlayButton(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int x, int y, int width, RecipeDisplayId recipe, RecipeCollection collection) {
		if (!ReachCraftingConfig.get().showNearbyCraftableIndicator()) {
			return;
		}
		Craftability craftability = getCraftability(recipe, collection, ItemStack.EMPTY, true);
		if (craftability == Craftability.NEARBY_CRAFTABLE || craftability == Craftability.LOCALLY_CRAFTABLE) {
			renderDot(guiGraphics, x, y);
		} else if (ChainCraftabilityCache.isChainCraftable(recipe)) {
			renderChainDot(guiGraphics, x, y);
		}
	}

	public static void renderDot(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int x, int y) {
		int outer = 0xCC8B7B00;
		int inner = 0xFFFFDD00;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderChainDot(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int x, int y) {
		int outer = 0xCC8B4400;
		int inner = 0xFFFF8800;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderBlackDot(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int x, int y) {
		int outer = 0xCC000000;
		int inner = 0xFF000000;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderWhiteDot(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int x, int y) {
		int outer = 0xCCFFFFFF;
		int inner = 0xFFFFFFFF;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayDot(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int x, int y) {
		int outer = 0xCC888888;
		int inner = 0xFF888888;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayArrow(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int x, int y) {
		int color = 0x80000000;
		// Stylized arrow with tail:
		//   ###
		//   ###
		//   ###
		//  #####
		//   ###
		//    #
		guiGraphics.fill(x + 0, y,     x + 3, y + 3, color); // Tail (3x3)
		guiGraphics.fill(x - 1, y + 3, x + 4, y + 4, color); // (5x1)
		guiGraphics.fill(x + 0, y + 4, x + 3, y + 5, color); // (3x1)
		guiGraphics.fill(x + 1, y + 5, x + 2, y + 6, color); // (1x1)
	}

	// Stylized arrow with tail:
	//   #####
	//   #####
	//   #####
	//   #####
	//  #######
	//   #####
	//    ###
	//     #
	public static void renderOrangeArrowOutline(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int x, int y) {
		int color = 0xFFFF9A1F;
		guiGraphics.fill(x - 1, y - 1, x + 4, y + 2, color); // Stem (5x3)
		guiGraphics.fill(x - 2, y + 2, x + 5, y + 5, color); // Wide base (7x3)
		guiGraphics.fill(x - 1, y + 5, x + 4, y + 6, color); // Taper 1 (5x1)
		guiGraphics.fill(x + 0, y + 6, x + 3, y + 7, color); // Taper 2 (3x1)
	}
}
