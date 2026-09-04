package com.reachcrafting.client;

import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * @param limitingItemId the raw material that stopped the plan going further,
 *     or null when the full request was satisfied (or nothing identifiable ran
 *     out). Only ever a base material - see ChainCraftPlanner.baseShortfalls.
 */
record ChainCraftPlan(
	List<ChainCraftPlan.Step> steps,
	ItemStack finalOutput,
	int finalRecipeCopies,
	String limitingItemId
) {
	ChainCraftPlan {
		steps = List.copyOf(steps);
		finalOutput = finalOutput != null ? finalOutput.copy() : ItemStack.EMPTY;
	}

	ChainCraftPlan(List<ChainCraftPlan.Step> steps, ItemStack finalOutput, int finalRecipeCopies) {
		this(steps, finalOutput, finalRecipeCopies, null);
	}

	ChainCraftPlan withLimitingItem(String itemId) {
		return new ChainCraftPlan(steps, finalOutput, finalRecipeCopies, itemId);
	}

	record Step(
		RecipeHolder<?> recipe,
		ResourceLocation recipeId,
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
				recipe,
				recipeId,
				collection,
				displayStack.copy(),
				org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
				true
			);
		}
	}
}
