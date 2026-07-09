package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeButtonNearbyIndicator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeButton.class)
public abstract class RecipeButtonMixin {
	@Inject(method = "renderWidget", at = @At("TAIL"))
	private void reachcrafting$renderNearbyIndicator(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		RecipeButton button = (RecipeButton) (Object) this;
		com.reachcrafting.client.RecipeButtonQueuedCountIndicator.render(guiGraphics, button);
		RecipeButtonNearbyIndicator.renderButton(guiGraphics, button);
	}
}
