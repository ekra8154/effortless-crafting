package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.ReachCraftingMod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.lwjgl.glfw.GLFW;

public final class RecipeBookClickCapture {
	private RecipeBookClickCapture() {
	}

	public static void init() {
		// Reserved for future client lifecycle hooks.
	}

	public static void onRecipeButtonClicked(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean shiftModifierDown,
		boolean ctrlModifierDown,
		boolean explicitVariantSelection
	) {
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return;
		}
		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		boolean craftAll = shiftModifierDown || isShiftKeyDown(minecraft);
		boolean allowNearbyFallback = ctrlModifierDown || isControlKeyDown(minecraft);
		boolean allowReservedGridVariantSwitch = false;
		int desiredVariantCopies = availableItems.hasReservedGrid() && !craftAll
			? currentReservedCraftCopies(availableItems) + 1
			: 1;

		String screenKind = screen instanceof InventoryScreen ? "inventory_2x2" : "crafting_table_3x3";
		RecipeVariantResolver.Selection selectedRecipe = RecipeVariantResolver.resolve(
			minecraft,
			player,
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			explicitVariantSelection,
			allowNearbyFallback,
			availableItems,
			availableItems.inventoryCounts(),
			availableItems.inventoryCounts(),
			craftAll,
			allowReservedGridVariantSwitch,
			desiredVariantCopies
		);
		if (selectedRecipe == null) {
			ReachCraftingMod.LOGGER.warn("[recipe_click] missing RecipeDisplayEntry for recipe_index={}", recipeId.index());
			return;
		}
		boolean craftable = collection.isCraftable(recipeId);
		int recipeIndex = selectedRecipe.recipeId().index();
		if (!selectedRecipe.recipeId().equals(recipeId)) {
			ReachCraftingMod.LOGGER.info(
				"[recipe_variant] clicked_idx={} selected_idx={} mode={} output={}",
				recipeId.index(),
				selectedRecipe.recipeId().index(),
				ReachCraftingConfig.get().revolvingCraftHandling().name().toLowerCase(),
				selectedRecipe.outputLabel()
			);
		}

		ItemStack resolvedDisplayStack = selectedRecipe.displayStack().copy();
		RecipeIngredientSummary ingredientSummary = selectedRecipe.ingredientSummary();
		RecipeDeficitReport deficitReport = RecipeDeficitReport.from(
			ingredientSummary,
			availableItems.inventoryCounts(),
			availableItems.gridStacks(),
			craftAll
		);
		String resolvedItemId = BuiltInRegistries.ITEM.getKey(resolvedDisplayStack.getItem()).toString();
		String outputLabel = resolvedItemId + " x" + resolvedDisplayStack.getCount();
		String chatMessage = deficitReport.hasMissingIngredients()
			? "Missing: " + deficitReport.compactMissingSummary()
			: "Ready: " + outputLabel;

		ReachCraftingMod.LOGGER.info(
			"[recipe_click] screen={} button={} idx={} craftable={} shift={} ctrl={} output={}",
			screenKind,
			mouseButton,
			recipeIndex,
			craftable,
			craftAll,
			allowNearbyFallback,
			outputLabel
		);
		ReachCraftingMod.LOGGER.info(
			"[recipe_needs] idx={} summary={} slots={}",
			recipeIndex,
			ingredientSummary.compactSummary(),
			ingredientSummary.rawSlots()
		);
		ReachCraftingMod.LOGGER.info(
			"[recipe_missing] idx={} inventory={} grid={} missing={}",
			recipeIndex,
			availableItems.inventorySummary(),
			availableItems.gridSummary(),
			deficitReport.compactMissingSummary()
		);

		player.displayClientMessage(
			Component.literal("[Reach Crafting] " + chatMessage)
				.withStyle(ChatFormatting.YELLOW),
			false
		);

		if (allowNearbyFallback && availableItems.hasReservedGrid() && !deficitReport.hasMissingIngredients()) {
			if (NearbyContainerDryRun.tryExpandReservedGrid(
				selectedRecipe.recipeId(),
				collection,
				explicitVariantSelection,
				recipeIndex,
				outputLabel,
				ingredientSummary,
				availableItems,
				craftAll
			)) {
				return;
			}
			NearbyContainerDryRun.start(
				selectedRecipe.recipeId(),
				collection,
				explicitVariantSelection,
				recipeIndex,
				outputLabel,
				ingredientSummary,
				availableItems,
				craftAll
			);
			return;
		}

		if (allowNearbyFallback && !deficitReport.hasMissingIngredients() && !availableItems.hasReservedGrid()) {
			MultiPlayerGameMode gameMode = minecraft.gameMode;
			if (gameMode != null) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipeId(), craftAll);
				player.displayClientMessage(
					Component.literal("[Reach Crafting] Placed recipe: " + outputLabel)
						.withStyle(ChatFormatting.YELLOW),
					false
				);
				return;
			}
		}

		if (deficitReport.hasMissingIngredients() && allowNearbyFallback) {
			NearbyContainerDryRun.start(
				selectedRecipe.recipeId(),
				collection,
				explicitVariantSelection,
				recipeIndex,
				outputLabel,
				ingredientSummary,
				availableItems,
				craftAll
			);
		} else {
			NearbyContainerDryRun.cancelCurrent();
		}
	}

	private static boolean isShiftKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private static boolean isControlKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	private static int currentReservedCraftCopies(AvailableItemSnapshot availableItems) {
		int minCount = Integer.MAX_VALUE;
		for (ItemStack stack : availableItems.gridStacks()) {
			if (stack.isEmpty()) {
				continue;
			}
			minCount = Math.min(minCount, stack.getCount());
		}
		return minCount == Integer.MAX_VALUE ? 0 : minCount;
	}
}
