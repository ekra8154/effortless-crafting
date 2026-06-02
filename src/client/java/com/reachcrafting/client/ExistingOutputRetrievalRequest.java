package com.reachcrafting.client;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

record ExistingOutputRetrievalRequest(
	RecipeDisplayId requestedRecipeId,
	RecipeDisplayId resolvedRecipeId,
	RecipeCollection recipeCollection,
	boolean explicitVariantSelection,
	String outputItemId,
	String outputLabel,
	ItemStack displayStack,
	int requestedCount
) {
}

