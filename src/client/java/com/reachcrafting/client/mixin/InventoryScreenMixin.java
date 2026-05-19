package com.reachcrafting.client.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public class InventoryScreenMixin {
	@Inject(method = "containerTick", at = @At("TAIL"))
	private void reachcrafting$onContainerTick(CallbackInfo ci) {
		com.reachcrafting.client.ContainerUtils.tickContainerScreen();
	}
}
