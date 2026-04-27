package com.reachcrafting.client.mixin;

import com.reachcrafting.client.RecipeButtonNearbyIndicator;
import com.reachcrafting.client.RecipeButtonQueuedCountIndicator;
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
		RecipeButton button = (RecipeButton) (Object) this;
		if (RecipeButtonNearbyIndicator.shouldShow(button)) {
			AbstractWidget widget = (AbstractWidget) (Object) this;
			int x = widget.getX() + 3;
			int y = widget.getY() + 3;

			int outer = 0xCC8B3A10;
			int inner = 0xFFFFB24A;

			guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
			guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
			guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

			guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
		}

		RecipeButtonQueuedCountIndicator.render(guiGraphics, button);
	}
}
