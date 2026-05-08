package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.OverlayRecipeButtonAccessor;
import com.reachcrafting.client.mixin.OverlayRecipeComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.lwjgl.glfw.GLFW;

final class RecipeBookFocusManager {
	private RecipeBookFocusManager() {
	}

	static boolean isShiftKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	static boolean isControlKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	static boolean isAltKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
	}

	static boolean isSpaceKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_SPACE);
	}

	static void defocusRecipeBookSearch(Minecraft minecraft, HeldRecipeQueueState state) {
		if (!(minecraft.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (componentAccessor != null) {
			EditBox searchBox = componentAccessor.getSearchBox();
			if (searchBox != null && searchBox.isFocused()) {
				searchBox.setFocused(false);
				state.setWasSearchBoxFocusedByMod(true);
			}
		}
	}

	static void defocusRecipeBookSearch(Minecraft minecraft) {
		if (!(minecraft.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (componentAccessor != null) {
			EditBox searchBox = componentAccessor.getSearchBox();
			if (searchBox != null && searchBox.isFocused()) {
				searchBox.setFocused(false);
			}
		}
	}

	static void refocusRecipeBookSearch(Minecraft minecraft, HeldRecipeQueueState state) {
		if (!(minecraft.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (componentAccessor != null) {
			EditBox searchBox = componentAccessor.getSearchBox();
			if (searchBox != null) {
				searchBox.setFocused(true);
				searchBox.setCursorPosition(searchBox.getValue().length());
				searchBox.setHighlightPos(0);
			}
		}
		state.setWasSearchBoxFocusedByMod(false);
	}

	static RecipeBookClickCapture.HeldRecipeAction findHoveredHeldRecipeAction(Screen screen, double mouseX, double mouseY) {
		if (!(screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return null;
		}

		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) componentAccessor.getRecipeBookPage();
		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		if (overlay != null && overlay.isVisible() && overlay.getRecipeCollection() != null) {
			for (Object entry : ((OverlayRecipeComponentAccessor) overlay).getRecipeButtons()) {
				if (!(entry instanceof AbstractWidget widget) || !widget.isMouseOver(mouseX, mouseY)) {
					continue;
				}
				RecipeDisplayId recipeId = ((OverlayRecipeButtonAccessor) entry).getRecipe();
				if (recipeId == null) {
					continue;
				}
				return new RecipeBookClickCapture.HeldRecipeAction(
					recipeId,
					overlay.getRecipeCollection(),
					ItemStack.EMPTY,
					GLFW.GLFW_MOUSE_BUTTON_LEFT,
					true
				);
			}
			return null;
		}

		for (RecipeButton button : pageAccessor.getButtons()) {
			if (!button.isMouseOver(mouseX, mouseY) || button.getCurrentRecipe() == null || button.getCollection() == null) {
				continue;
			}
			return new RecipeBookClickCapture.HeldRecipeAction(
				button.getCurrentRecipe(),
				button.getCollection(),
				button.getDisplayStack().copy(),
				GLFW.GLFW_MOUSE_BUTTON_LEFT,
				false
			);
		}
		return null;
	}
}
