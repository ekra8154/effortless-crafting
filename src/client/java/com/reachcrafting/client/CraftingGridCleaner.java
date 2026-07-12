package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

final class CraftingGridCleaner {
	private CraftingGridCleaner() {
	}

	static void flushCraftingGrid(Minecraft client, boolean allowScreenChange, boolean isStartingNewCraft) {
		if (client.player == null || client.player.containerMenu == null) {
			return;
		}
		if (!(client.gui.screen() instanceof CraftingScreen) && !(client.gui.screen() instanceof InventoryScreen)) {
			return;
		}

		AbstractContainerMenu menu = client.player.containerMenu;

		boolean retainBulkResources = isStartingNewCraft
			&& (BulkAutoCraftController.shouldRetainPulledResourcesForNextBulkCraft() || BulkChainCraftController.isActive());
		if (allowScreenChange && retainBulkResources) {
			com.reachcrafting.ReachCraftingMod.LOGGER.debug(
				"[grid_flush] Retaining staged nearby resources for next bulk craft (items={}, screen={})",
				PulledResourcesTracker.getWithdrawnItems().size(),
				client.gui.screen() != null ? client.gui.screen().getClass().getSimpleName() : "<none>"
			);
		} else if (allowScreenChange && ReachCraftingConfig.get().putPulledResourcesBack() && !PulledResourcesTracker.isEmpty()) {
			com.reachcrafting.ReachCraftingMod.LOGGER.debug(
				"[grid_flush] Initiating return to chests (items={}, starting_new_craft={}, screen={})",
				PulledResourcesTracker.getWithdrawnItems().size(),
				isStartingNewCraft,
				client.gui.screen() != null ? client.gui.screen().getClass().getSimpleName() : "<none>"
			);
			NearbyContainerDryRun.startReturn(menu, PulledResourcesTracker.getWithdrawnItems(), isStartingNewCraft);
			if (NearbyContainerDryRun.isActiveSessionRunning()) {
				return;
			}
		} else if (allowScreenChange && ReachCraftingConfig.get().putPulledResourcesBack()) {
			com.reachcrafting.ReachCraftingMod.LOGGER.debug("[grid_flush] Skipping return to chests: PulledResourcesTracker is empty");
		}

		InventoryGridRestoreTracker.restore(menu, client.gameMode);
	}
}
