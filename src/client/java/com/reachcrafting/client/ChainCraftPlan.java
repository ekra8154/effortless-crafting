package com.reachcrafting.client;

import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

record ChainCraftPlan(List<ChainCraftPlan.Step> steps, ItemStack finalOutput, int finalRecipeCopies) {
	ChainCraftPlan {
		steps = List.copyOf(steps);
		finalOutput = finalOutput != null ? finalOutput.copy() : ItemStack.EMPTY;
	}

	record Step(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		RecipeIngredientSummary ingredientSummary,
		Map<String, Integer> requiredInputs,
		int recipeCopies,
		boolean allowNearby,
		boolean finalStep
	) {
		Step {
			displayStack = displayStack != null ? displayStack.copy() : ItemStack.EMPTY;
			requiredInputs = Map.copyOf(requiredInputs);
			recipeCopies = Math.max(recipeCopies, 1);
		}

		RecipeBookClickCapture.HeldRecipeAction action() {
			return new RecipeBookClickCapture.HeldRecipeAction(
				recipeId,
				collection,
				displayStack.copy(),
				org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
				true
			);
		}
	}
}
