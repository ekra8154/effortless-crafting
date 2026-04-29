package com.reachcrafting.client.mixin;

import com.reachcrafting.client.RecipeBookClickCapture;
import java.util.List;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookPage.class)
public abstract class RecipeBookPageMixin {
	@Shadow
	@Final
	private List<RecipeButton> buttons;

	@Inject(
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;IIIIZ)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void reachcrafting$interceptCtrlRecipeButtonClick(
		MouseButtonEvent click,
		int left,
		int top,
		int width,
		int height,
		boolean filtering,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
			if (overlay != null && overlay.isVisible()) {
				return;
			}

			for (RecipeButton button : this.buttons) {
				if (!button.isMouseOver(click.x(), click.y())) {
					continue;
				}

				if (RecipeBookClickCapture.onRecipeButtonRightClicked(
					button.getCurrentRecipe(),
					button.getCollection(),
					button.getDisplayStack(),
					false
				)) {
					cir.setReturnValue(true);
				}
				return;
			}
		}

		if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || (click.modifiers() & GLFW.GLFW_MOD_CONTROL) == 0) {
			return;
		}

		OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
		if (overlay != null && overlay.isVisible()) {
			return;
		}

		for (RecipeButton button : this.buttons) {
			if (!button.isMouseOver(click.x(), click.y())) {
				continue;
			}

			RecipeBookClickCapture.onRecipeButtonClicked(
				button.getCurrentRecipe(),
				button.getCollection(),
				button.getDisplayStack(),
				click.button(),
				(click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0,
				true,
				false
			);
			cir.setReturnValue(true);
			return;
		}
	}

	@Inject(
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;IIIIZ)Z",
		at = @At(value = "RETURN", ordinal = 3)
	)
	private void reachcrafting$onRecipeButtonClicked(
		MouseButtonEvent click,
		int left,
		int top,
		int width,
		int height,
		boolean filtering,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!cir.getReturnValueZ() || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || (click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
			return;
		}

		OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
		if (overlay != null && overlay.isVisible()) {
			return;
		}

		for (RecipeButton button : this.buttons) {
			if (!button.isMouseOver(click.x(), click.y())) {
				continue;
			}

			RecipeBookClickCapture.onRecipeButtonClicked(
				button.getCurrentRecipe(),
				button.getCollection(),
				button.getDisplayStack(),
				click.button(),
				(click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0,
				(click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0,
				false
			);
			return;
		}
	}
}
