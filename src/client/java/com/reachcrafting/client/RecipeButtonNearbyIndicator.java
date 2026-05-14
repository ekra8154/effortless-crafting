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
	private RecipeButtonNearbyIndicator() {
	}

	public static boolean shouldShow(RecipeButton button) {
		return shouldShow(button.getCurrentRecipe(), button.getCollection(), button.getDisplayStack().copy(), false);
	}

	public static boolean shouldShow(RecipeDisplayId recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		if (!ReachCraftingConfig.get().enabled()
			|| !ReachCraftingConfig.get().enableNearbyContainerUsage()
			|| !ReachCraftingConfig.get().showNearbyCraftableIndicator()
			|| !ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return false;
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.getCameraEntity() == null) {
			return false;
		}
		if (recipe == null || collection == null) {
			return false;
		}

		NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(
			minecraft.level,
			minecraft.getCameraEntity(),
			player.blockInteractionRange()
		);
		if (reachableView.isEmpty()) {
			return false;
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
			return false;
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
			return false;
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
		return !cachedPlan.hasMissingIngredients();
	}

	private static int currentReservedCraftCopies(AvailableItemSnapshot availableItems) {
		return ContainerUtils.currentReservedCraftCopies(availableItems.gridStacks());
	}

	public static void renderOverlayButton(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y, int width, RecipeDisplayId recipe, RecipeCollection collection) {
		if (shouldShow(recipe, collection, ItemStack.EMPTY, true)) {
			renderDot(guiGraphics, x, y);
		}
	}

	public static void renderDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC8B3A10;
		int inner = 0xFFFFB24A;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderBlackDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC000000;
		int inner = 0xFF000000;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderWhiteDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCCFFFFFF;
		int inner = 0xFFFFFFFF;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC888888;
		int inner = 0xFF888888;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayArrow(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
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
	public static void renderOrangeArrowOutline(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int color = 0xFFFF9A1F;
		guiGraphics.fill(x - 1, y - 1, x + 4, y + 2, color); // Stem (5x3)
		guiGraphics.fill(x - 2, y + 2, x + 5, y + 5, color); // Wide base (7x3)
		guiGraphics.fill(x - 1, y + 5, x + 4, y + 6, color); // Taper 1 (5x1)
		guiGraphics.fill(x + 0, y + 6, x + 3, y + 7, color); // Taper 2 (3x1)
	}
}
