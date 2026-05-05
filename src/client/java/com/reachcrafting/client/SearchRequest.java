package com.reachcrafting.client;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

record SearchRequest(
	RecipeDisplayId recipeId,
	RecipeCollection recipeCollection,
	boolean explicitVariantSelection,
	int recipeIndex,
	String outputLabel,
	RecipeIngredientSummary ingredientSummary,
	AvailableItemSnapshot localItems,
	boolean craftAll,
	int requestedSingleClicks,
	boolean allowNearby
) {
}
