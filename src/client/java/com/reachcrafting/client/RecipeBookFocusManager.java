package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.client.mixin.OverlayRecipeButtonAccessor;
import com.reachcrafting.client.mixin.OverlayRecipeComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import org.lwjgl.glfw.GLFW;

public final class RecipeBookFocusManager {
	private RecipeBookFocusManager() {
	}

	public static boolean isShiftKeyDown(Minecraft minecraft) {
		long window = minecraft.getWindow().getWindow();
		return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	public static boolean isControlKeyDown(Minecraft minecraft) {
		long window = minecraft.getWindow().getWindow();
		return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	public static boolean isAltKeyDown(Minecraft minecraft) {
		long window = minecraft.getWindow().getWindow();
		return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
	}

	public static boolean isSpaceKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE);
	}

	static void defocusRecipeBookSearch(Minecraft minecraft, HeldRecipeQueueState state) {
		if (!(minecraft.screen instanceof RecipeUpdateListener recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) recipeBookScreen.getRecipeBookComponent();
		if (componentAccessor != null) {
			EditBox searchBox = componentAccessor.getSearchBox();
			if (searchBox != null && searchBox.isFocused()) {
				searchBox.setFocused(false);
				state.setWasSearchBoxFocusedByMod(true);
			}
		}
	}

	static void defocusRecipeBookSearch(Minecraft minecraft) {
		if (!(minecraft.screen instanceof RecipeUpdateListener recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) recipeBookScreen.getRecipeBookComponent();
		if (componentAccessor != null) {
			EditBox searchBox = componentAccessor.getSearchBox();
			if (searchBox != null && searchBox.isFocused()) {
				searchBox.setFocused(false);
			}
		}
	}

	static void refocusRecipeBookSearch(Minecraft minecraft, HeldRecipeQueueState state) {
		if (!(minecraft.screen instanceof RecipeUpdateListener recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) recipeBookScreen.getRecipeBookComponent();
		if (componentAccessor != null) {
			EditBox searchBox = componentAccessor.getSearchBox();
			if (searchBox != null) {
				searchBox.setFocused(true);
				searchBox.setCursorPosition(searchBox.getValue().length());
				searchBox.setHighlightPos(0);
				focusRecipeBookComponent(recipeBookScreen);
			}
		}
		state.setWasSearchBoxFocusedByMod(false);
	}

	public static void focusRecipeBookComponent(RecipeUpdateListener recipeBookScreen) {
		if (!(recipeBookScreen instanceof Screen screen)) {
			return;
		}
		RecipeBookComponent component = recipeBookScreen.getRecipeBookComponent();
		if (component != null) {
			screen.setFocused(component);
		}
	}

	static RecipeBookClickCapture.HeldRecipeAction findHoveredHeldRecipeAction(Screen screen, double mouseX, double mouseY) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!(screen instanceof RecipeUpdateListener recipeBookScreen)) {
			return null;
		}

		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) recipeBookScreen.getRecipeBookComponent();
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) componentAccessor.getRecipeBookPage();
		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		if (overlay != null && overlay.isVisible() && overlay.getRecipeCollection() != null) {
			for (Object entry : ((OverlayRecipeComponentAccessor) overlay).getRecipeButtons()) {
				if (!(entry instanceof AbstractWidget widget) || !widget.isMouseOver(mouseX, mouseY)) {
					continue;
				}
				Recipe<?> recipe = ((OverlayRecipeButtonAccessor) entry).getRecipe();
				if (recipe == null) {
					continue;
				}
				return new RecipeBookClickCapture.HeldRecipeAction(
					recipe,
					recipe.getId(),
					overlay.getRecipeCollection(),
					ItemStack.EMPTY,
					GLFW.GLFW_MOUSE_BUTTON_LEFT,
					true
				);
			}
			return null;
		}

		for (RecipeButton button : pageAccessor.getButtons()) {
			if (!button.isMouseOver(mouseX, mouseY) || button.getRecipe() == null || button.getCollection() == null) {
				continue;
			}
			return new RecipeBookClickCapture.HeldRecipeAction(
				button.getRecipe(),
				button.getRecipe().getId(),
				button.getCollection(),
				RecipeVariantResolver.resolveDisplayStack(button.getRecipe(), minecraft),
				GLFW.GLFW_MOUSE_BUTTON_LEFT,
				false
			);
		}
		return null;
	}
}

