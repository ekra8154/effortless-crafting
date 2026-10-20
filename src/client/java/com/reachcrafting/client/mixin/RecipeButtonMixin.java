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
	/**
	 * Revolving recipe buttons cycle on a clock vanilla only stops while Ctrl
	 * is held. A queue rebuilds its request from the button on every scroll,
	 * so a cycle mid-queue swapped the variant (moss to pale moss): hold the
	 * clock while a request is queued.
	 */
	@org.spongepowered.asm.mixin.injection.Redirect(method = "renderWidget", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;hasControlDown()Z"))
	private boolean reachcrafting$holdVariantCycleWhileQueuing() {
		if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
			return true;
		}
		ReachCraftingConfig config = ReachCraftingConfig.get();
		return config.enabled() && config.freezeRevolvingWhileQueuing()
			&& com.reachcrafting.client.ContainerUtils.isInputQueueActive();
	}

	@Inject(method = "renderWidget", at = @At("TAIL"))
	private void reachcrafting$renderNearbyIndicator(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		RecipeButton button = (RecipeButton) (Object) this;
		com.reachcrafting.client.RecipeButtonQueuedCountIndicator.render(guiGraphics, button);
		RecipeButtonNearbyIndicator.renderButton(guiGraphics, button);
	}
}
