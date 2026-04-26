package com.reachcrafting.client.mixin;

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
		if (!cir.getReturnValueZ() || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		OverlayRecipeComponent overlay = (OverlayRecipeComponent) (Object) this;
		RecipeDisplayId recipeId = overlay.getLastRecipeClicked();
		RecipeCollection collection = overlay.getRecipeCollection();
		if (recipeId == null || collection == null) {
			return;
		}

		RecipeBookClickCapture.onRecipeButtonClicked(
			recipeId,
			collection,
			null,
			click.button(),
			(click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0,
			(click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0
		);
	}
}
