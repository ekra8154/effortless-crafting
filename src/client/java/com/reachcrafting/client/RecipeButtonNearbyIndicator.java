package com.reachcrafting.client;

import net.minecraft.client.gui.screens.recipebook.RecipeButton;

public final class RecipeButtonNearbyIndicator {
	private RecipeButtonNearbyIndicator() {
	}

	public static boolean shouldShow(RecipeButton button) {
		return button.getCurrentRecipe() != null;
	}
}
