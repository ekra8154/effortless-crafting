package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
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
	private void reachcrafting$interceptRecipeButtonClick(
		MouseButtonEvent click,
		int left,
		int top,
		int width,
		int height,
		boolean filtering,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
			if (overlay != null && overlay.isVisible()) {
				return;
			}

			for (RecipeButton button : this.buttons) {
				if (!button.isMouseOver(click.x(), click.y())) {
					continue;
				}

				// Vanilla right-click on a revolving recipe button opens the
				// explicit variant chooser. Let vanilla keep full ownership of
				// that gesture on the main recipe page and only use our
				// right-click clear behavior for single-variant buttons or the
				// explicit overlay buttons.
				if (button.getCollection() != null && button.getCollection().getRecipes().size() > 1) {
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

		if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		boolean ctrlDown = (click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		boolean shiftDown = (click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
		boolean altDown = (click.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
		if (shiftDown) {
			RecipeBookClickCapture.defocusRecipeBookSearch(net.minecraft.client.Minecraft.getInstance());
		}

		OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
		if (overlay != null && overlay.isVisible()) {
			return;
		}

		boolean interceptWithMod = ctrlDown
			|| (shiftDown && RecipeBookClickCapture.isBulkModeEnabled())
			|| (altDown && !shiftDown && ReachCraftingConfig.get().altAsRequestKey());
		if (!interceptWithMod) {
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
				shiftDown,
				ctrlDown,
				altDown,
				false
			);
			cir.setReturnValue(true);
			return;
		}
	}

	@Inject(
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;IIIIZ)Z",
		at = @At("RETURN")
	)
	private void reachcrafting$afterVanillaRecipeButtonClick(
		MouseButtonEvent click,
		int left,
		int top,
		int width,
		int height,
		boolean filtering,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!ReachCraftingConfig.get().enabled() || !cir.getReturnValueZ() || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
		if (overlay != null && overlay.isVisible()) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[recipe_page] skipping after-vanilla handler because overlay is visible");
			return;
		}

		boolean ctrlDown = (click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		if (ctrlDown) {
			return;
		}
		boolean altDown = (click.modifiers() & GLFW.GLFW_MOD_ALT) != 0;

		for (RecipeButton button : this.buttons) {
			if (!button.isMouseOver(click.x(), click.y())) {
				continue;
			}

			RecipeBookClickCapture.onVanillaRecipeButtonClicked(
				button.getCurrentRecipe(),
				button.getCollection(),
				button.getDisplayStack(),
				false,
				altDown
			);
			return;
		}
	}
}
