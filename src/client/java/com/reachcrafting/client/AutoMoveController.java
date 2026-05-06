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
		
		if (AutoCraftController.isBulkModeEnabled() && BulkAutoCraftController.isActive()) {
			sweepAndEjectByProducts(client, menu);
		}

		if (!autoMoveOrganizing) {
			if (resultSlot.hasItem() && resultSlot.mayPickup(client.player)) {
				ItemStack currentResult = resultSlot.getItem();

				if (!autoMoveExpectedStack.isEmpty() && !ItemStack.isSameItemSameComponents(currentResult, autoMoveExpectedStack)) {
					com.reachcrafting.ReachCraftingMod.LOGGER.debug(
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

				boolean shouldEject = false;
				if (ReachCraftingConfig.get().ejectItemsWhenFull()) {
					if (AutoCraftController.isBulkModeEnabled() && BulkAutoCraftController.isActive()) {
						int emptySlots = countEmptyInventorySlots(menu);
						int requiredSlots = BulkAutoCraftController.estimatedRequiredSlotsForNextBatch();
						if (emptySlots <= requiredSlots + 10) {
							com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Ejecting early! emptySlots={} required={}", emptySlots, requiredSlots);
							shouldEject = true;
						} else if (!canFitInInventory(menu, currentResult)) {
							com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Ejecting because inventory full (bulk)");
							shouldEject = true;
						}
					} else {
						if (!canFitInInventory(menu, currentResult)) {
							com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Ejecting because inventory full (normal)");
							shouldEject = true;
						}
					}
				}

				if (shouldEject) {
					com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Performing THROW click on slot {} with button 1", resultSlot.index);
					client.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 1, ClickType.THROW, client.player);
					if (AutoCraftController.isBulkModeEnabled()) {
						BulkAutoCraftController.addEjectedOutput(currentResult.getCount());
					}

					// Eject any by-products left in the grid
					if (AutoCraftController.isBulkModeEnabled()) {
						java.util.Set<String> acceptedIds = BulkAutoCraftController.getAcceptedItemIds();
						if (acceptedIds != null) {
							int gridSlotCount = (menu instanceof net.minecraft.world.inventory.CraftingMenu) ? 9 : 4;
							for (int i = 1; i <= gridSlotCount; i++) {
								Slot gridSlot = menu.getSlot(i);
								if (gridSlot.hasItem()) {
									String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(gridSlot.getItem().getItem()).toString();
									if (!acceptedIds.contains(itemId)) {
										com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Ejecting by-product {} from grid slot {}", itemId, i);
										client.gameMode.handleInventoryMouseClick(menu.containerId, gridSlot.index, 1, ClickType.THROW, client.player);
									}
								}
							}
						}
					}

					pendingAutoMove = false;
					BulkAutoCraftController.onAutoMoveFinished(client, true);
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

			if (ReachCraftingConfig.get().ejectItemsWhenFull() && AutoCraftController.isBulkModeEnabled()) {
				java.util.Set<String> acceptedIds = BulkAutoCraftController.getAcceptedItemIds();
				if (acceptedIds != null) {
					int gridSlotCount = (menu instanceof net.minecraft.world.inventory.CraftingMenu) ? 9 : 4;
					for (int i = 1; i <= gridSlotCount; i++) {
						Slot gridSlot = menu.getSlot(i);
						if (gridSlot.hasItem()) {
							String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(gridSlot.getItem().getItem()).toString();
							if (!acceptedIds.contains(itemId)) {
								com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Ejecting by-product {} from grid slot {}", itemId, i);
								client.gameMode.handleInventoryMouseClick(menu.containerId, gridSlot.index, 1, ClickType.THROW, client.player);
								movesThisTick++;
							}
						}
					}
					if (movesThisTick > 0) {
						autoMoveWaitingTicks = 0;
						return;
					}
				}
			}

			if (resultSlot.hasItem() && ItemStack.isSameItemSameComponents(resultSlot.getItem(), autoMoveTargetStack)) {
				com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Result slot still has items after organizing. Restarting loop.");
				autoMoveOrganizing = false;
				autoMoveWaitingTicks = 0;
				return;
			}

			pendingAutoMove = false;
			autoMoveOrganizing = false;
			autoMoveTargetStack = ItemStack.EMPTY;
			BulkAutoCraftController.onAutoMoveFinished(client, true);
		}
	}

	private static void sweepAndEjectByProducts(Minecraft client, AbstractContainerMenu menu) {
		if (!ReachCraftingConfig.get().ejectItemsWhenFull()) {
			return;
		}

		java.util.Set<String> acceptedIds = BulkAutoCraftController.getAcceptedItemIds();
		java.util.Map<String, Integer> initialCounts = BulkAutoCraftController.getInitialInventoryCounts();
		ItemStack expectedOutput = BulkAutoCraftController.getExpectedOutput();
		
		if (acceptedIds == null || initialCounts == null || expectedOutput.isEmpty()) {
			return;
		}

		String outputId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(expectedOutput.getItem()).toString();
		
		// Map current total counts to decide what is "extra"
		java.util.Map<String, Integer> currentCounts = new java.util.HashMap<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory && slot.hasItem()) {
				String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				currentCounts.merge(itemId, slot.getItem().getCount(), Integer::sum);
			}
		}

		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot == null || !slot.hasItem()) continue;

			ItemStack stack = slot.getItem();
			String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

			if (itemId.equals(outputId) || acceptedIds.contains(itemId)) {
				continue;
			}

			int initialCount = initialCounts.getOrDefault(itemId, 0);
			int currentTotal = currentCounts.getOrDefault(itemId, 0);

			if (currentTotal > initialCount) {
				// This item is a by-product (not output, not ingredient, and count increased)
				// Only eject if the stack we are throwing doesn't take us below the initial count.
				// This is a safety measure to avoid throwing away pre-existing items if they stacked.
				if (currentTotal - stack.getCount() >= initialCount) {
					com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Ejecting by-product {} from inventory slot {}", itemId, slot.index);
					client.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 1, ClickType.THROW, client.player);
					
					// Update current counts so we don't over-eject if there are multiple slots of the same byproduct
					currentCounts.put(itemId, currentTotal - stack.getCount());
				}
			}
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

	private static boolean canFitInInventory(AbstractContainerMenu menu, ItemStack stack) {
		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot == null) continue;
			if (!slot.hasItem()) return true;
			if (ItemStack.isSameItemSameComponents(slot.getItem(), stack) && slot.getItem().getCount() + stack.getCount() <= stack.getMaxStackSize()) {
				return true;
			}
		}
		Slot offhandSlot = findVisibleOffhandSlot(menu);
		if (offhandSlot != null) {
			if (!offhandSlot.hasItem()) return true;
			if (ItemStack.isSameItemSameComponents(offhandSlot.getItem(), stack) && offhandSlot.getItem().getCount() + stack.getCount() <= stack.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	private static int countEmptyInventorySlots(AbstractContainerMenu menu) {
		int empty = 0;
		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot != null && !slot.hasItem()) {
				empty++;
			}
		}
		return empty;
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
