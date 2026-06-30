package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeBookClickCapture;
import java.util.List;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.Screen;
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
		method = "mouseClicked(DDIIIII)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void reachcrafting$interceptRecipeButtonClick(
		double mouseX,
		double mouseY,
		int mouseButton,
		int left,
		int top,
		int width,
		int height,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (mouseButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
			if (overlay != null && overlay.isVisible()) {
				return;
			}

			for (RecipeButton button : this.buttons) {
				if (!button.isMouseOver(mouseX, mouseY)) {
					continue;
				}

				// Vanilla right-click on a revolving recipe button opens the
				// explicit variant chooser. Let vanilla keep full ownership of
				// that gesture on the main recipe page and only use our
				// right-click clear behavior for single-variant buttons or the
				// explicit overlay buttons.
				if (button.getCollection() != null && button.getCollection().getRecipes().size() > 1) {
					if (com.reachcrafting.client.ContainerUtils.isExistingOutputRetrievalEnabled()
						&& com.reachcrafting.client.RetrievalOutputVariantOverlay.openForButton(button)) {
						cir.setReturnValue(true);
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

		if (mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		boolean ctrlDown = Screen.hasControlDown();
		boolean shiftDown = Screen.hasShiftDown();
		boolean altDown = Screen.hasAltDown();
		if (shiftDown) {
			RecipeBookClickCapture.defocusRecipeBookSearch(net.minecraft.client.Minecraft.getInstance());
		}

		OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
		if (overlay != null && overlay.isVisible()) {
			return;
		}

		boolean interceptWithMod = ctrlDown
			|| com.reachcrafting.client.ContainerUtils.isExistingOutputRetrievalEnabled()
			|| (shiftDown && RecipeBookClickCapture.isBulkModeEnabled())
			|| (altDown && ReachCraftingConfig.get().altAsRequestKey());
		if (!interceptWithMod) {
			return;
		}

		for (RecipeButton button : this.buttons) {
			if (!button.isMouseOver(mouseX, mouseY)) {
				continue;
			}

			RecipeBookClickCapture.onRecipeButtonClicked(
				button.getCurrentRecipe(),
				button.getCollection(),
				button.getDisplayStack(),
				mouseButton,
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
		method = "mouseClicked(DDIIIII)Z",
		at = @At("RETURN")
	)
	private void reachcrafting$afterVanillaRecipeButtonClick(
		double mouseX,
		double mouseY,
		int mouseButton,
		int left,
		int top,
		int width,
		int height,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!ReachCraftingConfig.get().enabled() || !cir.getReturnValueZ() || mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		OverlayRecipeComponent overlay = ((RecipeBookPageAccessor) (Object) this).getOverlay();
		if (overlay != null && overlay.isVisible()) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[recipe_page] skipping after-vanilla handler because overlay is visible");
			return;
		}

		boolean ctrlDown = Screen.hasControlDown();
		if (ctrlDown || com.reachcrafting.client.ContainerUtils.isExistingOutputRetrievalEnabled()) {
			return;
		}
		boolean altDown = Screen.hasAltDown();

		for (RecipeButton button : this.buttons) {
			if (!button.isMouseOver(mouseX, mouseY)) {
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
