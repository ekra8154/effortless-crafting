package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ChainCraftPopupController;
import net.minecraft.client.gui.components.PopupScreen;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class PopupScreenMixin {
	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$confirmChainCraftFromKeyboard(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof PopupScreen popup) || !ChainCraftPopupController.isChainCraftPopup(popup)) {
			return;
		}
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
			if (ChainCraftPopupController.confirm(popup)) {
				cir.setReturnValue(true);
			}
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void reachcrafting$clearChainCraftPopup(CallbackInfo ci) {
		if ((Object) this instanceof PopupScreen popup) {
			ChainCraftPopupController.closed(popup);
		}
	}
}
