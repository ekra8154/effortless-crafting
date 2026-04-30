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
		// ONLY clear and snapshot if we aren't in the middle of an automated session!
		if (!com.reachcrafting.client.NearbyContainerDryRun.isActiveSessionRunning()) {
			com.reachcrafting.client.InventoryGridRestoreTracker.clear();
			com.reachcrafting.client.PulledResourcesTracker.clear();

			if ((ReachCraftingConfig.get().putPulledResourcesBack() || ReachCraftingConfig.get().restoreInventoryItemPositions())
				&& ((Object) this instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen || (Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
				com.reachcrafting.ReachCraftingMod.LOGGER.info("[grid_restore] Capturing fresh inventory snapshot...");
				com.reachcrafting.client.PulledResourcesTracker.captureInventorySnapshot(net.minecraft.client.Minecraft.getInstance().player);
			}
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void reachcrafting$onClose(CallbackInfo ci) {
		com.reachcrafting.ReachCraftingMod.LOGGER.info("[grid_restore] Screen onClose triggered for {}", this.getClass().getName());

		if (com.reachcrafting.client.NearbyContainerDryRun.isActiveSessionRunning()) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[grid_restore] Aborting active session.");
			com.reachcrafting.client.NearbyContainerDryRun.abortActiveSession();
		}

		if ((Object) this instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen || (Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
			if (ReachCraftingConfig.get().restoreInventoryItemPositions()) {
				com.reachcrafting.client.InventoryGridRestoreTracker.restore(this.menu, net.minecraft.client.Minecraft.getInstance().gameMode);
			}

			if (ReachCraftingConfig.get().putPulledResourcesBack()) {
				int withdrawnCount = com.reachcrafting.client.PulledResourcesTracker.getWithdrawnItems().size();
				if (withdrawnCount > 0) {
					com.reachcrafting.ReachCraftingMod.LOGGER.info("[nearby_return] Initiating return session.");
					com.reachcrafting.client.NearbyContainerDryRun.startReturn(this.menu, com.reachcrafting.client.PulledResourcesTracker.getWithdrawnItems());
				}
			}
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void reachcrafting$cacheContainerOnClose(CallbackInfo ci) {
		NearbyContainerCache.onContainerScreenRemoved(this.menu);
		
		if (!com.reachcrafting.client.NearbyContainerDryRun.isActiveSessionRunning()) {
			com.reachcrafting.client.PulledResourcesTracker.clear();
			com.reachcrafting.client.InventoryGridRestoreTracker.clear();
		}
	}
}
