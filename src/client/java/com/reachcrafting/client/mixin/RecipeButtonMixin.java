package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeButtonNearbyIndicator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
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
		if (RecipeButtonNearbyIndicator.shouldShow(button)) {
			AbstractWidget widget = (AbstractWidget) (Object) this;
			RecipeButtonNearbyIndicator.renderDot(guiGraphics, widget.getX() + 3, widget.getY() + 3);
		} else if (RecipeButtonNearbyIndicator.isChainCraftable(button)) {
			AbstractWidget widget = (AbstractWidget) (Object) this;
			RecipeButtonNearbyIndicator.renderChainDot(guiGraphics, widget.getX() + 3, widget.getY() + 3);
		}

	}
}
