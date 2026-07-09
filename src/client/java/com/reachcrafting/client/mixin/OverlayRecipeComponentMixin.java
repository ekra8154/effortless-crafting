package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeBookClickCapture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OverlayRecipeComponent.class)
public abstract class OverlayRecipeComponentMixin {
	/**
	 * Intercepts modded overlay clicks (ctrl / bulk-shift / alt request) BEFORE vanilla places the
	 * recipe, so the mod owns the click and vanilla's placement is suppressed entirely.
	 */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$interceptOverlayRecipeClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled() || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		OverlayRecipeComponent overlay = (OverlayRecipeComponent) (Object) this;
		RecipeCollection collection = overlay.getRecipeCollection();
		RecipeHolder<?> recipe = reachcrafting$findHoveredRecipe(overlay, mouseX, mouseY);
		if (recipe == null || collection == null) {
			return;
		}

		boolean ctrlDown = Screen.hasControlDown();
		boolean shiftDown = Screen.hasShiftDown();
		boolean altDown = Screen.hasAltDown();
		boolean interceptWithMod = ctrlDown
			|| (shiftDown && RecipeBookClickCapture.isBulkModeEnabled())
			|| (altDown && ReachCraftingConfig.get().altAsRequestKey());
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[overlay_click] head recipe={} ctrl={} shift={} alt={} intercept={} collection_size={}",
			recipe.id(),
			ctrlDown,
			shiftDown,
			altDown,
			interceptWithMod,
			collection.getRecipes().size()
		);
		if (!interceptWithMod) {
			return;
		}

		RecipeBookClickCapture.suppressNextVanillaRecipeClick();
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
		cir.setReturnValue(true);
	}

	@Inject(method = "mouseClicked", at = @At("RETURN"))
	private void reachcrafting$onOverlayRecipeClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		OverlayRecipeComponent overlay = (OverlayRecipeComponent) (Object) this;
		RecipeHolder<?> recipe = overlay.getLastRecipeClicked();
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
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[overlay_click] return recipe={} ctrl={} shift={} alt={} vanillaAccepted={}",
			recipe.id(),
			ctrlDown,
			shiftDown,
			altDown,
			cir.getReturnValueZ()
		);
		if (ctrlDown
			|| (shiftDown && RecipeBookClickCapture.isBulkModeEnabled())
			|| (altDown && ReachCraftingConfig.get().altAsRequestKey())) {
			return;
		}

		RecipeBookClickCapture.onVanillaRecipeButtonClicked(recipe, collection, null, true, altDown);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void reachcrafting$onRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		OverlayRecipeComponent overlay = (OverlayRecipeComponent) (Object) this;
		if (!overlay.isVisible()) return;

		RecipeCollection collection = overlay.getRecipeCollection();
		if (collection == null) return;

		java.util.List<Object> buttons = ((OverlayRecipeComponentAccessor) overlay).getRecipeButtons();
		for (Object buttonObj : buttons) {
			if (buttonObj instanceof AbstractWidget widget) {
				if (widget.visible) {
					RecipeHolder<?> recipe = ((OverlayRecipeButtonAccessor) widget).getRecipe();
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

	private RecipeHolder<?> reachcrafting$findHoveredRecipe(OverlayRecipeComponent overlay, double mouseX, double mouseY) {
		for (Object buttonObj : ((OverlayRecipeComponentAccessor) overlay).getRecipeButtons()) {
			if (buttonObj instanceof AbstractWidget widget && widget.visible && widget.isMouseOver(mouseX, mouseY)) {
				return ((OverlayRecipeButtonAccessor) widget).getRecipe();
			}
		}
		return null;
	}
}
