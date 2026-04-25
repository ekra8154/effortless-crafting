package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.network.chat.Component;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.core.registries.BuiltInRegistries;

public final class RecipeBookClickCapture {
	private RecipeBookClickCapture() {
	}

	public static void init() {
		// Reserved for future client lifecycle hooks.
	}

	public static void onRecipeButtonClicked(RecipeDisplayId recipeId, RecipeCollection collection, ItemStack displayStack, int mouseButton) {
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return;
		}

		String screenKind = screen instanceof InventoryScreen ? "inventory_2x2" : "crafting_table_3x3";
		boolean craftable = collection.isCraftable(recipeId);
		String itemId = BuiltInRegistries.ITEM.getKey(displayStack.getItem()).toString();
		int recipeIndex = recipeId.index();
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) player.getRecipeBook()).getKnown();
		RecipeDisplayEntry entry = knownRecipes.get(recipeId);
		if (entry == null) {
			ReachCraftingMod.LOGGER.warn("[recipe_click] missing RecipeDisplayEntry for recipe_index={}", recipeIndex);
			return;
		}
		ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
		RecipeIngredientSummary ingredientSummary = RecipeIngredientSummary.fromDisplay(entry.display(), context);
		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		RecipeDeficitReport deficitReport = RecipeDeficitReport.from(ingredientSummary, availableItems);
		String outputLabel = itemId + " x" + displayStack.getCount();
		String chatMessage = deficitReport.hasMissingIngredients()
			? "Missing: " + deficitReport.compactMissingSummary()
			: "Ready: " + outputLabel;

		ReachCraftingMod.LOGGER.info(
			"[recipe_click] screen={} button={} idx={} craftable={} output={}",
			screenKind,
			mouseButton,
			recipeIndex,
			craftable,
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
	}
}
