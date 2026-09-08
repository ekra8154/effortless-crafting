package com.reachcrafting.client;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

record ExistingOutputRetrievalRequest(
	RecipeHolder<?> requestedRecipe,
	RecipeHolder<?> resolvedRecipe,
	RecipeCollection recipeCollection,
	boolean explicitVariantSelection,
	String outputItemId,
	String outputLabel,
	ItemStack displayStack,
	int requestedCount,
	boolean fillOnly,
	RetrieveThenCraftController.FollowUp followUp
) {
	/** A plain retrieval: eject per the setting, nobody waiting on the result. */
	ExistingOutputRetrievalRequest(
		RecipeHolder<?> requestedRecipe,
		RecipeHolder<?> resolvedRecipe,
		RecipeCollection recipeCollection,
		boolean explicitVariantSelection,
		String outputItemId,
		String outputLabel,
		ItemStack displayStack,
		int requestedCount
	) {
		this(requestedRecipe, resolvedRecipe, recipeCollection, explicitVariantSelection, outputItemId, outputLabel, displayStack, requestedCount, false, null);
	}

	ResourceLocation requestedRecipeId() {
		return requestedRecipe != null ? requestedRecipe.id() : null;
	}

	ResourceLocation resolvedRecipeId() {
		return resolvedRecipe != null ? resolvedRecipe.id() : null;
	}
}
