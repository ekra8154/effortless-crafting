package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeBookClickCapture;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.components.AbstractWidget;
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
		at = @At("HEAD"),
		cancellable = true
	)
	private void reachcrafting$interceptOverlayRecipeClicked(MouseButtonEvent click, boolean filtering, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled() || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		OverlayRecipeComponent overlay = (OverlayRecipeComponent) (Object) this;
		RecipeCollection collection = overlay.getRecipeCollection();
		RecipeDisplayId recipeId = reachcrafting$findHoveredRecipeId(overlay, click.x(), click.y());
		if (recipeId == null || collection == null) {
			return;
		}

		boolean ctrlDown = (click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		boolean shiftDown = (click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
		boolean altDown = (click.modifiers() & GLFW.GLFW_MOD_ALT) != 0;
		boolean interceptWithMod = ctrlDown
			|| (shiftDown && RecipeBookClickCapture.isBulkModeEnabled())
			|| (altDown && ReachCraftingConfig.get().altAsRequestKey());
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[overlay_click] head recipe={} ctrl={} shift={} alt={} intercept={} collection_size={}",
			recipeId,
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
			recipeId,
			collection,
			null,
			click.button(),
			shiftDown,
			ctrlDown,
			altDown,
			true
		);
		cir.setReturnValue(true);
	}

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
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[overlay_click] return recipe={} ctrl={} shift={} alt={} vanillaAccepted={}",
			recipeId,
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

	private RecipeDisplayId reachcrafting$findHoveredRecipeId(OverlayRecipeComponent overlay, double mouseX, double mouseY) {
		for (Object buttonObj : ((OverlayRecipeComponentAccessor) overlay).getRecipeButtons()) {
			if (buttonObj instanceof AbstractWidget widget && widget.visible && widget.isMouseOver(mouseX, mouseY)) {
				return ((OverlayRecipeButtonAccessor) widget).getRecipe();
			}
		}
		return null;
	}
}
