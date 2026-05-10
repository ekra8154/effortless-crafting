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
		return pendingAutoMove || autoMoveOrganizing || NearbyContainerDryRun.isActiveSessionRunning() || InventoryGridRestoreTracker.isRestoring() || BulkAutoCraftController.isActive();
	}

	static void abort() {
		pendingAutoMove = false;
		autoMoveOrganizing = false;
		autoMoveTargetStack = ItemStack.EMPTY;
		autoMoveExpectedStack = ItemStack.EMPTY;
		autoMoveSnapshotCounts.clear();
		OffhandConsolidationController.swapBack(Minecraft.getInstance());
	}

	static void autoMoveResult(Minecraft client) {
		if (client.player == null || client.player.containerMenu == null) {
			pendingAutoMove = false;
			return;
		}

		AbstractContainerMenu menu = client.player.containerMenu;
		if (AutoCraftController.isBulkModeEnabled()) {
			sweepAndEjectByProducts(client, menu);
		}

		if (client.screen == null || (!(client.screen instanceof CraftingScreen) && !(client.screen instanceof InventoryScreen))) {
			return;
		}

		if (menu.slots.isEmpty()) {
			return;
		}

		Slot resultSlot = menu.getSlot(0);
		
		if (AutoCraftController.isBulkModeEnabled() && BulkAutoCraftController.isActive()) {
			// com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] >> sweepAndEjectByProducts entry. {}", logBottleDistribution(menu));
			sweepAndEjectByProducts(client, menu);
			// com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] << sweepAndEjectByProducts exit.  {}", logBottleDistribution(menu));
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
				if (ReachCraftingConfig.get().ejectItemsWhenFull() && !canFitInInventory(menu, currentResult)) {
					shouldEject = true;
					com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] shouldEject=true (inv full, cannot fit result)");
				}

				if (shouldEject) {
					com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] EJECT path: THROW result {} from slot {}", ContainerUtils.formatStack(currentResult), resultSlot.index);
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
										com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] EJECT grid byproduct {} from grid slot {}", itemId, i);
										client.gameMode.handleInventoryMouseClick(menu.containerId, gridSlot.index, 1, ClickType.THROW, client.player);
									}
								}
							}
						}
					}

					// com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] EJECT path done. {}", logBottleDistribution(menu));
					pendingAutoMove = false;
					BulkAutoCraftController.onAutoMoveFinished(client, true);
					return;
				}

				int slotsNeeded = 0;
				if (AutoCraftController.isBulkModeEnabled()) {
					slotsNeeded = BulkAutoCraftController.estimatedRequiredSlotsForNextBatch();
				} else {
					// Single craft estimate
					slotsNeeded = (menu instanceof net.minecraft.world.inventory.CraftingMenu) ? 9 : 4;
				}

				if (OffhandConsolidationController.prepareSwapIfNeeded(client, currentResult, slotsNeeded)) {
					return;
				}

				if (OffhandConsolidationController.isWarmupDelayActive()) {
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

				int swappedSlotIndex = OffhandConsolidationController.getSwapSlotIndex(menu);
				if (swappedSlotIndex != -1) {
					Slot s = menu.getSlot(swappedSlotIndex);
					if (s.hasItem()) {
						autoMoveSnapshotCounts.put(s.index, s.getItem().getCount());
					}
				}

				client.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 0, ClickType.QUICK_MOVE, client.player);
				com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] SHIFT-CLICK path: post-shiftclick. {}", logBottleDistribution(menu));
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
				
				// Never move items OUT of the offhand or the swapped offhand slot
				Slot offhandSlot = findVisibleOffhandSlot(menu);
				if (offhandSlot != null && sourceSlot.index == offhandSlot.index) {
					autoMoveSnapshotCounts.put(i, currentCount);
					continue;
				}
				int swappedSlotIndex = OffhandConsolidationController.getSwapSlotIndex(menu);
				if (swappedSlotIndex != -1 && sourceSlot.index == swappedSlotIndex) {
					autoMoveSnapshotCounts.put(i, currentCount);
					continue;
				}

				int oldCount = autoMoveSnapshotCounts.getOrDefault(i, 0);

				if (currentCount > oldCount) {
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
					
					if (swappedSlotIndex != -1) {
						Slot swapSlot = menu.getSlot(swappedSlotIndex);
						if (swapSlot.hasItem() \u0026\u0026 ItemStack.isSameItemSameComponents(swapSlot.getItem(), autoMoveTargetStack) \u0026\u0026 swapSlot.getItem().getCount() < swapSlot.getItem().getMaxStackSize()) {
							client.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, ClickType.PICKUP, client.player);
							client.gameMode.handleInventoryMouseClick(menu.containerId, swapSlot.index, 0, ClickType.PICKUP, client.player);
							if (!client.player.containerMenu.getCarried().isEmpty()) {
								client.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, ClickType.PICKUP, client.player);
							}
							movesThisTick++;
							autoMoveSnapshotCounts.put(i, 0);
							autoMoveSnapshotCounts.put(swapSlot.index, swapSlot.getItem().getCount() + currentCount);
							continue;
						}
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
			if (!client.player.containerMenu.getCarried().isEmpty() && !tryResolveCarriedStack(client, menu)) {
				return;
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
								com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] ORGANIZE eject grid byproduct {} from grid slot {}", itemId, i);
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
				com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Result slot still has items after organizing. Restarting loop.");
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

		
		// Map current total counts to decide what is "extra", including the offhand
		java.util.Map<String, Integer> currentCounts = new java.util.HashMap<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory && slot.hasItem()) {
				String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				currentCounts.merge(itemId, slot.getItem().getCount(), Integer::sum);
			}
		}
		ItemStack offhandItem = client.player.getOffhandItem();
		if (!offhandItem.isEmpty()) {
			String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(offhandItem.getItem()).toString();
			currentCounts.merge(itemId, offhandItem.getCount(), Integer::sum);
		}

		int swappedSlotIndex = OffhandConsolidationController.getSwapSlotIndex(menu);
		Slot visibleOffhand = findVisibleOffhandSlot(menu);

		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot == null || !slot.hasItem()) continue;

			// Never eject from the swapped offhand slot (3x3)
			if (swappedSlotIndex != -1 && slot.index == swappedSlotIndex) {
				continue;
			}
			
			// Never eject from the actual visible offhand slot (2x2)
			if (visibleOffhand != null && slot.index == visibleOffhand.index) {
				continue;
			}

			ItemStack stack = slot.getItem();
			String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

			// Never eject ingredients
			if (acceptedIds.contains(itemId)) {
				continue;
			}

			int initialCount = initialCounts.getOrDefault(itemId, 0);
			int currentTotal = currentCounts.getOrDefault(itemId, 0);

			if (currentTotal > initialCount) {
				// This item is a by-product or extra output (count increased since session start)
				int amountEjected = stack.getCount();
				if (currentTotal - amountEjected >= initialCount) {
					com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Ejecting extra/by-product {} from inventory slot {}", itemId, slot.index);
					client.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 1, ClickType.THROW, client.player);
					
					// If this was extra output, report it to the bulk controller so it doesn't think progress stopped
					String outputId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(BulkAutoCraftController.getExpectedOutput().getItem()).toString();
					if (itemId.equals(outputId)) {
						com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Ejected EXTRA OUTPUT: {} matched outputId {} (count={})", itemId, outputId, amountEjected);
						BulkAutoCraftController.addEjectedOutput(amountEjected);
					} else {
						com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Ejected BYPRODUCT: {} (expected outputId: {})", itemId, outputId);
					}

					// Update current counts so we don't over-eject if there are multiple slots of the same byproduct
					currentCounts.put(itemId, currentTotal - amountEjected);
				}
			}
		}
	}

	private static boolean tryResolveCarriedStack(Minecraft client, AbstractContainerMenu menu) {
		ItemStack carried = client.player.containerMenu.getCarried();
		if (carried.isEmpty()) {
			return true;
		}

		String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(carried.getItem()).toString();
		Slot destination = MenuTransferHelper.findPlayerDestinationSlot(client.player, menu, itemId);
		if (destination != null) {
			int carriedBefore = carried.getCount();
			client.gameMode.handleInventoryMouseClick(menu.containerId, destination.index, 0, ClickType.PICKUP, client.player);
			ItemStack carriedAfter = client.player.containerMenu.getCarried();
			if (carriedAfter.isEmpty() || carriedAfter.getCount() < carriedBefore) {
				return carriedAfter.isEmpty();
			}
		}

		if (ReachCraftingConfig.get().ejectItemsWhenFull()
			&& AutoCraftController.isBulkModeEnabled()
			&& ItemStack.isSameItemSameComponents(carried, autoMoveTargetStack)) {
			int ejectedCount = carried.getCount();
			client.gameMode.handleInventoryMouseClick(menu.containerId, -999, 0, ClickType.PICKUP, client.player);
			if (client.player.containerMenu.getCarried().isEmpty()) {
				BulkAutoCraftController.addEjectedOutput(ejectedCount);
				com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Ejected carried output {} while finalizing batch", ContainerUtils.formatStack(carried));
				return true;
			}
		}

		com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Waiting for carried stack to resolve before finishing batch: {}", ContainerUtils.formatStack(client.player.containerMenu.getCarried()));
		return false;
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
		int remaining = stack.getCount();
		int maxStack = stack.getMaxStackSize();
		
		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot == null) continue;
			if (!slot.hasItem()) {
				remaining -= maxStack;
			} else if (ItemStack.isSameItemSameComponents(slot.getItem(), stack)) {
				remaining -= (maxStack - slot.getItem().getCount());
			}
			if (remaining <= 0) return true;
		}

		// Also check the offhand slot if it's visible (2x2)
		Slot offhandSlot = findVisibleOffhandSlot(menu);
		if (offhandSlot != null) {
			if (!offhandSlot.hasItem()) {
				remaining -= maxStack;
			} else if (ItemStack.isSameItemSameComponents(offhandSlot.getItem(), stack)) {
				remaining -= (maxStack - offhandSlot.getItem().getCount());
			}
		}
		
		return remaining <= 0;
	}


	private static Slot findVisibleOffhandSlot(AbstractContainerMenu menu) {
		if (!ReachCraftingConfig.get().inventory2x2OffhandConsolidation()
			|| !(menu instanceof InventoryMenu)
			|| menu.slots.size() <= InventoryMenu.SHIELD_SLOT) {
			return null;
		}
		return menu.getSlot(InventoryMenu.SHIELD_SLOT);
	}


	private static String logBottleDistribution(AbstractContainerMenu menu) {
		StringBuilder sb = new StringBuilder("inv=[");
		boolean first = true;
		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot != null && slot.hasItem()) {
				String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).getPath();
				if (itemId.contains("bottle")) {
					if (!first) sb.append(", ");
					sb.append("inv").append(i).append(":").append(slot.getItem().getCount()).append("x").append(itemId);
					first = false;
				}
			}
		}
		sb.append("] grid=[");
		first = true;
		int gridSlots = (menu instanceof net.minecraft.world.inventory.CraftingMenu) ? 9 : 4;
		for (int i = 1; i <= gridSlots; i++) {
			if (i >= menu.slots.size()) break;
			Slot slot = menu.getSlot(i);
			if (slot.hasItem()) {
				if (!first) sb.append(", ");
				String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).getPath();
				sb.append("g").append(i).append(":").append(slot.getItem().getCount()).append("x").append(itemId);
				first = false;
			}
		}
		sb.append("]");
		return sb.toString();
	}
}
