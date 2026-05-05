package com.reachcrafting.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class AutoMoveController {
	private static int autoMoveWaitingTicks = 0;
	private static boolean pendingAutoMove = false;
	private static ItemStack autoMoveTargetStack = ItemStack.EMPTY;
	private static ItemStack autoMoveExpectedStack = ItemStack.EMPTY;
	private static final Map<Integer, Integer> autoMoveSnapshotCounts = new HashMap<>();
	private static boolean autoMoveOrganizing = false;

	private AutoMoveController() {
	}

	static void scheduleAutoMove(ItemStack expectedStack) {
		pendingAutoMove = true;
		autoMoveWaitingTicks = 0;
		autoMoveExpectedStack = expectedStack != null ? expectedStack.copy() : ItemStack.EMPTY;
	}

	static boolean isAutoMovePending() {
		return pendingAutoMove;
	}

	static boolean isAutomatedInteractionRunning() {
		return pendingAutoMove || autoMoveOrganizing || NearbyContainerDryRun.isActiveSessionRunning() || InventoryGridRestoreTracker.isRestoring();
	}

	static void autoMoveResult(Minecraft client) {
		if (client.player == null || client.player.containerMenu == null || client.screen == null) {
			return;
		}

		if (!(client.screen instanceof CraftingScreen) && !(client.screen instanceof InventoryScreen)) {
			return;
		}

		AbstractContainerMenu menu = client.player.containerMenu;
		if (menu.slots.isEmpty()) {
			return;
		}

		Slot resultSlot = menu.getSlot(0);

		if (!autoMoveOrganizing) {
			if (resultSlot.hasItem() && resultSlot.mayPickup(client.player)) {
				ItemStack currentResult = resultSlot.getItem();

				if (!autoMoveExpectedStack.isEmpty() && !ItemStack.isSameItemSameComponents(currentResult, autoMoveExpectedStack)) {
					com.reachcrafting.ReachCraftingMod.LOGGER.info(
						"[auto_move] Recipe changed! Expected: {}, Found: {}. Stopping.",
						ContainerUtils.formatStack(autoMoveExpectedStack),
						ContainerUtils.formatStack(currentResult)
					);
					pendingAutoMove = false;
					autoMoveOrganizing = false;
					autoMoveTargetStack = ItemStack.EMPTY;
					BulkAutoCraftController.onAutoMoveFinished(client, false);
					return;
				}

				autoMoveTargetStack = currentResult.copy();
				autoMoveWaitingTicks = 0;
				autoMoveOrganizing = true;

				autoMoveSnapshotCounts.clear();
				for (int i = 0; i < 36; i++) {
					Slot slot = findInventorySlot(menu, i);
					if (slot != null && slot.hasItem()) {
						autoMoveSnapshotCounts.put(i, slot.getItem().getCount());
					}
				}
				Slot offhandSlot = findVisibleOffhandSlot(menu);
				if (offhandSlot != null && offhandSlot.hasItem()) {
					autoMoveSnapshotCounts.put(offhandSlot.index, offhandSlot.getItem().getCount());
				}

				client.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 0, ClickType.QUICK_MOVE, client.player);
			} else {
				autoMoveWaitingTicks++;
				if (autoMoveWaitingTicks > 10) {
					pendingAutoMove = false;
					autoMoveOrganizing = false;
					autoMoveTargetStack = ItemStack.EMPTY;
					BulkAutoCraftController.onAutoMoveFinished(client, false);
				}
				return;
			}
		}

		autoMoveWaitingTicks++;
		int movesThisTick = 0;
		int maxMovesPerTick = 20;

		for (int i = 0; i < 36 && movesThisTick < maxMovesPerTick; i++) {
			Slot sourceSlot = findInventorySlot(menu, i);
			if (sourceSlot != null && sourceSlot.hasItem() && ItemStack.isSameItemSameComponents(sourceSlot.getItem(), autoMoveTargetStack)) {
				int currentCount = sourceSlot.getItem().getCount();
				int oldCount = autoMoveSnapshotCounts.getOrDefault(i, 0);

				if (currentCount > oldCount) {
					Slot offhandSlot = findVisibleOffhandSlot(menu);
					if (offhandSlot != null
						&& offhandSlot.hasItem()
						&& ItemStack.isSameItemSameComponents(offhandSlot.getItem(), autoMoveTargetStack)
						&& offhandSlot.getItem().getCount() < offhandSlot.getItem().getMaxStackSize()) {
						client.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, ClickType.PICKUP, client.player);
						client.gameMode.handleInventoryMouseClick(menu.containerId, offhandSlot.index, 0, ClickType.PICKUP, client.player);

						if (!client.player.containerMenu.getCarried().isEmpty()) {
							client.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, ClickType.PICKUP, client.player);
						}

						movesThisTick++;
						autoMoveSnapshotCounts.put(i, 0);
						autoMoveSnapshotCounts.put(offhandSlot.index, offhandSlot.getItem().getCount() + currentCount);
						continue;
					}

					for (int h = 0; h < i && h < 9; h++) {
						Slot targetSlot = findInventorySlot(menu, h);
						if (targetSlot == null) {
							continue;
						}

						boolean canMove = false;
						if (!targetSlot.hasItem()) {
							canMove = true;
						} else if (ItemStack.isSameItemSameComponents(targetSlot.getItem(), autoMoveTargetStack)) {
							if (targetSlot.getItem().getCount() < targetSlot.getItem().getMaxStackSize()) {
								canMove = true;
							}
						}

						if (canMove) {
							client.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, ClickType.PICKUP, client.player);
							client.gameMode.handleInventoryMouseClick(menu.containerId, targetSlot.index, 0, ClickType.PICKUP, client.player);

							if (!client.player.containerMenu.getCarried().isEmpty()) {
								client.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, ClickType.PICKUP, client.player);
							}

							movesThisTick++;
							autoMoveSnapshotCounts.put(i, 0);
							autoMoveSnapshotCounts.put(h, targetSlot.getItem().getCount() + currentCount);
							break;
						}
					}
				}
			}
		}

		if (movesThisTick == 0 && autoMoveWaitingTicks > 1) {
			if (!client.player.containerMenu.getCarried().isEmpty()) {
				for (int i = 0; i < 36; i++) {
					Slot slot = findInventorySlot(menu, i);
					if (slot != null && !slot.hasItem()) {
						client.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, client.player);
						break;
					}
				}
			}
			pendingAutoMove = false;
			autoMoveOrganizing = false;
			autoMoveTargetStack = ItemStack.EMPTY;
			BulkAutoCraftController.onAutoMoveFinished(client, true);
		}
	}

	private static Slot findInventorySlot(AbstractContainerMenu menu, int inventoryIndex) {
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory && slot.getContainerSlot() == inventoryIndex) {
				return slot;
			}
		}
		return null;
	}

	private static Slot findVisibleOffhandSlot(AbstractContainerMenu menu) {
		if (!ReachCraftingConfig.get().inventory2x2OffhandConsolidation()
			|| !(menu instanceof InventoryMenu)
			|| menu.slots.size() <= InventoryMenu.SHIELD_SLOT) {
			return null;
		}
		return menu.getSlot(InventoryMenu.SHIELD_SLOT);
	}
}
