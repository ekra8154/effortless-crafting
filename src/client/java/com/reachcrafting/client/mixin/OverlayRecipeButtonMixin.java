package com.reachcrafting.client.mixin;

import com.reachcrafting.client.RecipeButtonQueuedCountIndicator;
import com.reachcrafting.client.RecipeButtonNearbyIndicator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent$OverlayRecipeButton")
public abstract class OverlayRecipeButtonMixin {
	@Shadow
	@Final
	private RecipeDisplayId recipe;

	@Shadow
	@Final
	OverlayRecipeComponent field_3113;

	@Inject(method = "renderWidget", at = @At("TAIL"))
	private void reachcrafting$renderQueuedCount(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		RecipeCollection collection = field_3113.getRecipeCollection();
		if (collection == null) {
			return;
		}

		AbstractWidget widget = (AbstractWidget) (Object) this;
		RecipeButtonQueuedCountIndicator.renderOverlayButton(
			guiGraphics,
			widget.getX(),
			widget.getY(),
			widget.getWidth(),
			recipe,
			collection
		);
		RecipeButtonNearbyIndicator.renderOverlayButton(
			guiGraphics,
			widget.getX(),
			widget.getY(),
			widget.getWidth(),
			recipe,
			collection
		);
	}
}
