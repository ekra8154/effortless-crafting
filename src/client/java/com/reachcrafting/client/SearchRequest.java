package com.reachcrafting.client;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

record SearchRequest(
	RecipeHolder<?> recipe,
	ResourceLocation recipeId,
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
