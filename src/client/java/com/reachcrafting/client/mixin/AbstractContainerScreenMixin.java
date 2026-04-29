package com.reachcrafting.client.mixin;

import com.reachcrafting.client.NearbyContainerCache;
import com.reachcrafting.client.ReachCraftingConfig;
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

	@Inject(method = "init", at = @At("HEAD"))
	private void reachcrafting$captureInventoryOnOpen(CallbackInfo ci) {
		if (ReachCraftingConfig.get().putPulledResourcesBack() 
			&& com.reachcrafting.client.PulledResourcesTracker.isEmpty()
			&& ((Object) this instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen || (Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[nearby_return] Detected screen open, capturing snapshot...");
			com.reachcrafting.client.PulledResourcesTracker.captureInventorySnapshot(net.minecraft.client.Minecraft.getInstance().player);
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void reachcrafting$cacheContainerOnClose(CallbackInfo ci) {
		NearbyContainerCache.onContainerScreenRemoved(this.menu);
		
		if (ReachCraftingConfig.get().putPulledResourcesBack() 
			&& !com.reachcrafting.client.NearbyContainerDryRun.isActiveSessionRunning()
			&& ((Object) this instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen || (Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
			int withdrawnCount = com.reachcrafting.client.PulledResourcesTracker.getWithdrawnItems().size();
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[nearby_return] Screen closed, initiating return of {} tracked withdrawals", withdrawnCount);
			com.reachcrafting.client.NearbyContainerDryRun.startReturn(this.menu, com.reachcrafting.client.PulledResourcesTracker.getWithdrawnItems());
		} else if (!com.reachcrafting.client.NearbyContainerDryRun.isActiveSessionRunning()) {
			com.reachcrafting.client.PulledResourcesTracker.clear();
		}
	}
}
