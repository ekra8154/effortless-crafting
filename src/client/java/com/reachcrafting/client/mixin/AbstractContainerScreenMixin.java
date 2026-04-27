package com.reachcrafting.client.mixin;

import com.reachcrafting.client.NearbyContainerCache;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin<T extends AbstractContainerMenu> {
	@Shadow
	protected T menu;

	@Inject(method = "removed", at = @At("HEAD"))
	private void reachcrafting$cacheContainerOnClose(CallbackInfo ci) {
		NearbyContainerCache.onContainerScreenRemoved(this.menu);
	}
}
