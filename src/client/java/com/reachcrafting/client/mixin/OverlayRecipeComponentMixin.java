package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeBookClickCapture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.Recipe;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OverlayRecipeComponent.class)
public abstract class OverlayRecipeComponentMixin {
	@Inject(method = "mouseClicked", at = @At("RETURN"))
	private void reachcrafting$onOverlayRecipeClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		OverlayRecipeComponent overlay = (OverlayRecipeComponent) (Object) this;
		Recipe<?> recipe = overlay.getLastRecipeClicked();
		RecipeCollection collection = overlay.getRecipeCollection();
		if (recipe == null || collection == null) {
			return;
		}

		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			if (cir.getReturnValueZ()) {
				RecipeBookClickCapture.onRecipeButtonRightClicked(recipe, collection, null, true);
			}
			return;
		}

		if (!cir.getReturnValueZ() || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		boolean ctrlDown = Screen.hasControlDown();
		boolean shiftDown = Screen.hasShiftDown();
		boolean altDown = Screen.hasAltDown();
		if (ctrlDown || (altDown && !shiftDown && ReachCraftingConfig.get().altAsRequestKey())) {
			RecipeBookClickCapture.onRecipeButtonClicked(
				recipe,
				collection,
				null,
				button,
				shiftDown,
				ctrlDown,
				altDown,
				true
			);
			return;
		}

		RecipeBookClickCapture.onVanillaRecipeButtonClicked(recipe, collection, null, true, altDown);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void reachcrafting$onRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		OverlayRecipeComponent overlay = (OverlayRecipeComponent) (Object) this;
		if (!overlay.isVisible()) {
			return;
		}

		RecipeCollection collection = overlay.getRecipeCollection();
		if (collection == null) {
			return;
		}

		for (Object buttonObj : ((OverlayRecipeComponentAccessor) overlay).getRecipeButtons()) {
			if (buttonObj instanceof AbstractWidget widget && widget.visible) {
				Recipe<?> recipe = ((OverlayRecipeButtonAccessor) widget).getRecipe();
				com.reachcrafting.client.RecipeButtonQueuedCountIndicator.renderOverlayButton(
					guiGraphics,
					widget.getX(),
					widget.getY(),
					widget.getWidth(),
					recipe,
					collection
				);
			}
		}
	}
}
