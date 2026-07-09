package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.player.LocalPlayer;
import java.util.Map;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;

public final class RecipeButtonNearbyIndicator {
	private RecipeButtonNearbyIndicator() {
	}

	public enum Craftability {
		NOT_CRAFTABLE,
		LOCALLY_CRAFTABLE,
		NEARBY_CRAFTABLE
	}

	public static boolean shouldShow(RecipeButton button) {
		return shouldShow(button.getRecipe(), button.getCollection(), RecipeVariantResolver.resolveDisplayStack(button.getRecipe(), Minecraft.getInstance()), false);
	}

	/**
	 * Classifies whether the recipe (identified by id within {@code collection}) is craftable using
	 * nearby container contents. 1.20.1 computes this live (no persistent cache), so this simply
	 * resolves the recipe and reuses {@link #shouldShow}.
	 */
	public static Craftability getCraftability(net.minecraft.resources.ResourceLocation recipeId, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		if (recipeId == null || collection == null) {
			return Craftability.NOT_CRAFTABLE;
		}
		Recipe<?> recipe = null;
		for (Recipe<?> candidate : collection.getRecipes()) {
			if (candidate.getId().equals(recipeId)) {
				recipe = candidate;
				break;
			}
		}
		if (recipe == null) {
			return Craftability.NOT_CRAFTABLE;
		}
		ItemStack stack = (displayStack == null || displayStack.isEmpty())
			? RecipeVariantResolver.resolveDisplayStack(recipe, Minecraft.getInstance())
			: displayStack;
		return shouldShow(recipe, collection, stack, explicitVariantSelection)
			? Craftability.NEARBY_CRAFTABLE
			: Craftability.NOT_CRAFTABLE;
	}

	/** 1.20.1 computes nearby-craftability live; there is no persistent cache to clear. */
	public static void clearCaches() {
	}

	/**
	 * Renders the recipe-book button indicator: a yellow dot when the recipe is craftable using
	 * nearby containers, otherwise an orange dot when it is chain-craftable. Directly-craftable
	 * recipes get no dot (vanilla already highlights them).
	 */
	public static void renderButton(GuiGraphics guiGraphics, RecipeButton button) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (!ReachCraftingConfig.get().showNearbyCraftableIndicator()) return;
		Recipe<?> recipe = button.getRecipe();
		RecipeCollection collection = button.getCollection();
		if (recipe == null || collection == null) return;

		AbstractWidget widget = (AbstractWidget) (Object) button;
		int x = widget.getX() + 3;
		int y = widget.getY() + 3;
		ItemStack displayStack = RecipeVariantResolver.resolveDisplayStack(recipe, Minecraft.getInstance());
		if (shouldShow(recipe, collection, displayStack, false)) {
			renderDot(guiGraphics, x, y);
		} else if (ChainCraftabilityCache.isChainCraftable(recipe.getId())) {
			renderChainDot(guiGraphics, x, y);
		}
	}

	public static boolean shouldShow(Recipe<?> recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
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
			minecraft.gameMode != null ? minecraft.gameMode.getPickRange() : 4.5D
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

	public static void renderOverlayButton(GuiGraphics guiGraphics, int x, int y, int width, Recipe<?> recipe, RecipeCollection collection) {
		if (shouldShow(recipe, collection, RecipeVariantResolver.resolveDisplayStack(recipe, Minecraft.getInstance()), true)) {
			renderDot(guiGraphics, x, y);
		} else if (recipe != null && ChainCraftabilityCache.isChainCraftable(recipe.getId())) {
			renderChainDot(guiGraphics, x, y);
		}
	}

	public static void renderDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC8B7B00;
		int inner = 0xFFFFDD00;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderChainDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC8B4400;
		int inner = 0xFFFF8800;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderBlackDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC000000;
		int inner = 0xFF000000;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderWhiteDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCCFFFFFF;
		int inner = 0xFFFFFFFF;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC888888;
		int inner = 0xFF888888;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayArrow(GuiGraphics guiGraphics, int x, int y) {
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
	public static void renderOrangeArrowOutline(GuiGraphics guiGraphics, int x, int y) {
		int color = 0xFFFF9A1F;
		guiGraphics.fill(x - 1, y - 1, x + 4, y + 2, color); // Stem (5x3)
		guiGraphics.fill(x - 2, y + 2, x + 5, y + 5, color); // Wide base (7x3)
		guiGraphics.fill(x - 1, y + 5, x + 4, y + 6, color); // Taper 1 (5x1)
		guiGraphics.fill(x + 0, y + 6, x + 3, y + 7, color); // Taper 2 (3x1)
	}
}


