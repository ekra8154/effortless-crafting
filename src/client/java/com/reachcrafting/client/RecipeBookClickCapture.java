package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.core.registries.BuiltInRegistries;

public final class RecipeBookClickCapture {
	private RecipeBookClickCapture() {
	}

	public static void init() {
		// Reserved for future client lifecycle hooks.
	}

	public static void onRecipeButtonClicked(RecipeDisplayId recipeId, RecipeCollection collection, ItemStack displayStack, int mouseButton) {
		Screen screen = Minecraft.getInstance().screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return;
		}

		String screenKind = screen instanceof InventoryScreen ? "inventory_2x2" : "crafting_table_3x3";
		boolean craftable = collection.isCraftable(recipeId);
		String itemId = BuiltInRegistries.ITEM.getKey(displayStack.getItem()).toString();
		int recipeIndex = recipeId.index();

		ReachCraftingMod.LOGGER.info(
			"[recipe_click] screen={} mouse_button={} recipe_index={} craftable={} output_item={} output_count={}",
			screenKind,
			mouseButton,
			recipeIndex,
			craftable,
			itemId,
			displayStack.getCount()
		);

		if (Minecraft.getInstance().player != null) {
			Minecraft.getInstance().player.displayClientMessage(
				Component.literal("Recipe click: " + itemId + " x" + displayStack.getCount() + " craftable=" + craftable)
					.withStyle(ChatFormatting.YELLOW),
				true
			);
		}
	}
}
