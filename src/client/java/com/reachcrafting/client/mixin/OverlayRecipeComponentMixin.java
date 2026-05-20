package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeBookClickCapture;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OverlayRecipeComponent.class)
public abstract class OverlayRecipeComponentMixin {
	@Inject(
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
		at = @At("RETURN")
	)
	private void reachcrafting$onOverlayRecipeClicked(MouseButtonEvent click, boolean filtering, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		OverlayRecipeComponent overlay = (OverlayRecipeComponent) (Object) this;
		RecipeDisplayId recipeId = overlay.getLastRecipeClicked();
		RecipeCollection collection = overlay.getRecipeCollection();
		if (recipeId == null || collection == null) {
			return;
		}

		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			if (cir.getReturnValueZ()) {
				RecipeBookClickCapture.onRecipeButtonRightClicked(
					recipeId,
					collection,
					null,
					true
				);
			}
			return;
		}

		if (!cir.getReturnValueZ() || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		boolean ctrlDown = (click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		boolean shiftDown = (click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
		boolean altDown = (click.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
		if (ctrlDown
			|| (shiftDown && RecipeBookClickCapture.isBulkModeEnabled())
			|| (altDown && !shiftDown && ReachCraftingConfig.get().altAsRequestKey())) {
			RecipeBookClickCapture.onRecipeButtonClicked(
				recipeId,
				collection,
				null,
				click.button(),
				shiftDown,
				ctrlDown,
				altDown,
				true
			);
			return;
		}

		RecipeBookClickCapture.onVanillaRecipeButtonClicked(
			recipeId,
			collection,
			null,
			true,
			altDown
		);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void reachcrafting$onRender(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		OverlayRecipeComponent overlay = (OverlayRecipeComponent) (Object) this;
		if (!overlay.isVisible()) return;

		RecipeCollection collection = overlay.getRecipeCollection();
		if (collection == null) return;

		java.util.List<Object> buttons = ((OverlayRecipeComponentAccessor) overlay).getRecipeButtons();
		for (Object buttonObj : buttons) {
			if (buttonObj instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
				if (widget.visible) {
					net.minecraft.world.item.crafting.display.RecipeDisplayId recipeId = ((OverlayRecipeButtonAccessor) widget).getRecipe();
					com.reachcrafting.client.RecipeButtonQueuedCountIndicator.renderOverlayButton(
						guiGraphics,
						widget.getX(),
						widget.getY(),
						widget.getWidth(),
						recipeId,
						collection
					);
				}
			}
		}
	}
}
