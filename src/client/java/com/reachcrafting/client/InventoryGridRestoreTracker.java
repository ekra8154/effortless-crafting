package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.HashSet;
import java.util.Set;

public final class InventoryGridRestoreTracker {
	// Tracks where an item IN A GRID SLOT originally came from.
	// gridSlotIndex -> originalInventorySlotIndex
	private static final Map<Integer, Integer> GRID_TO_ORIGINAL_SLOT = new HashMap<>();
	
	// Tracks the "last picked up" slot to link it to a destination.
	private static int lastInventorySlotClicked = -1;

	private InventoryGridRestoreTracker() {
	}

	public static void recordPotentialSource(int slotId, ClickType clickType, AbstractContainerMenu menu) {
		if (menu == null || slotId < 0 || slotId >= menu.slots.size()) return;

		Slot slot = menu.getSlot(slotId);
		if (slot.container instanceof Inventory) {
			if (clickType == ClickType.PICKUP) {
				lastInventorySlotClicked = slotId;
				ReachCraftingMod.LOGGER.info("[grid_restore] Recorded potential source slot: {}", slotId);
			}
		}
	}

	public static void recordPotentialDestination(int slotId, int button, ClickType clickType, AbstractContainerMenu menu) {
		if (menu == null || slotId < 0 || slotId >= menu.slots.size()) return;

		boolean isGrid = isGridSlot(menu, slotId);
		Slot slot = menu.getSlot(slotId);
		boolean isInventory = slot.container instanceof Inventory;

		if (isGrid) {
			if (clickType == ClickType.PICKUP && lastInventorySlotClicked != -1) {
				GRID_TO_ORIGINAL_SLOT.put(slotId, lastInventorySlotClicked);
				ReachCraftingMod.LOGGER.info("[grid_restore] Linked grid slot {} to original inventory slot {}", slotId, lastInventorySlotClicked);
			} else if (clickType == ClickType.QUICK_CRAFT && lastInventorySlotClicked != -1) {
				// Dragging phase (button & 3): 1 = add slot, 2 = end drag
				int phase = button & 3;
				if (phase == 1 || phase == 2) {
					GRID_TO_ORIGINAL_SLOT.put(slotId, lastInventorySlotClicked);
					ReachCraftingMod.LOGGER.info("[grid_restore] Linked grid slot {} to original inventory slot {} (via drag)", slotId, lastInventorySlotClicked);
				}
			}
		} else if (isInventory) {
			if (clickType == ClickType.PICKUP) {
				// Moving between inventory slots.
				if (lastInventorySlotClicked != -1) {
					updateOriginalSlotMappings(lastInventorySlotClicked, slotId);
				}
				lastInventorySlotClicked = -1;
			}
		} else {
			if (clickType == ClickType.PICKUP) {
				lastInventorySlotClicked = -1;
			}
		}
	}

	private static void updateOriginalSlotMappings(int oldMenuIdx, int newMenuIdx) {
		for (Map.Entry<Integer, Integer> entry : GRID_TO_ORIGINAL_SLOT.entrySet()) {
			if (entry.getValue() == oldMenuIdx) {
				entry.setValue(newMenuIdx);
			}
		}
	}

	public static void recordModMove(int sourceMenuIdx, int targetMenuIdx) {
		GRID_TO_ORIGINAL_SLOT.put(targetMenuIdx, sourceMenuIdx);
	}

	public static void clear() {
		GRID_TO_ORIGINAL_SLOT.clear();
		lastInventorySlotClicked = -1;
	}

	public static void restore(AbstractContainerMenu menu, MultiPlayerGameMode gameMode) {
		com.reachcrafting.ReachCraftingMod.LOGGER.info("[grid_restore] Starting restoration sequence for menu {}", menu.getClass().getSimpleName());
		try {
			if (menu == null || gameMode == null) {
				clear();
				return;
			}

			boolean useSmartRestore = ReachCraftingConfig.get().restoreInventoryItemPositions();
			Map<Integer, ItemStack> snapshots = PulledResourcesTracker.getInitialSlotSnapshots();
			if (useSmartRestore && !GRID_TO_ORIGINAL_SLOT.isEmpty() || !snapshots.isEmpty()) {
				Minecraft client = Minecraft.getInstance();
				if (client.player == null) return;

				// PASS 1: Direct Mapping Restoration
				// Use explicit mappings from GRID_TO_ORIGINAL_SLOT (for items the mod moved or the user clicked)
				Set<Integer> processedGridSlots = new HashSet<>();
				for (int gridIdx = 0; gridIdx < menu.slots.size(); gridIdx++) {
					if (isGridSlot(menu, gridIdx)) {
						Slot gridSlot = menu.getSlot(gridIdx);
						if (gridSlot.hasItem()) {
							int targetMenuIdx = GRID_TO_ORIGINAL_SLOT.getOrDefault(gridIdx, -1);
							if (targetMenuIdx != -1) {
								moveItems(menu, gameMode, gridIdx, targetMenuIdx);
								if (!gridSlot.hasItem()) {
									processedGridSlots.add(gridIdx);
								}
							}
						}
					}
				}

				// PASS 2: Snapshot-Based Distributed Restoration
				// For each slot that had an item in the snapshot, try to fill it back to its original count
				for (Map.Entry<Integer, ItemStack> entry : snapshots.entrySet()) {
					int invIdx = entry.getKey();
					ItemStack snapshot = entry.getValue();
					String itemId = BuiltInRegistries.ITEM.getKey(snapshot.getItem()).toString();
					int targetMenuIdx = inventoryIndexToMenuIndex(menu, invIdx);
					
					if (targetMenuIdx != -1) {
						fillSlotFromGrid(menu, gameMode, targetMenuIdx, snapshot.getCount(), itemId, processedGridSlots);
					}
				}
			}

			// PASS 3: Leftover Consolidation
			// Anything still in the grid (e.g. newly crafted items or extras) gets Shift-Clicked
			Minecraft client = Minecraft.getInstance();
			for (int gridIdx = 0; gridIdx < menu.slots.size(); gridIdx++) {
				if (isGridSlot(menu, gridIdx)) {
					Slot s = menu.getSlot(gridIdx);
					if (s.hasItem()) {
						gameMode.handleInventoryMouseClick(menu.containerId, gridIdx, 0, ClickType.QUICK_MOVE, client.player);
					}
				}
			}
		} catch (Exception e) {
			com.reachcrafting.ReachCraftingMod.LOGGER.error("[grid_restore] Error during restore: ", e);
		}
		clear();
	}

	private static void fillSlotFromGrid(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, int targetMenuIdx, int targetCount, String itemId, Set<Integer> excludedGridSlots) {
		Minecraft client = Minecraft.getInstance();
		Slot target = menu.getSlot(targetMenuIdx);
		
		// Only fill if it's the right item type or empty
		if (target.hasItem()) {
			String currentId = BuiltInRegistries.ITEM.getKey(target.getItem().getItem()).toString();
			if (!currentId.equals(itemId)) return;
		}

		for (int gridIdx = 0; gridIdx < menu.slots.size(); gridIdx++) {
			if (isGridSlot(menu, gridIdx) && !excludedGridSlots.contains(gridIdx)) {
				Slot gridSlot = menu.getSlot(gridIdx);
				if (gridSlot.hasItem()) {
					String gridItemId = BuiltInRegistries.ITEM.getKey(gridSlot.getItem().getItem()).toString();
					if (gridItemId.equals(itemId)) {
						int currentCount = target.hasItem() ? target.getItem().getCount() : 0;
						if (currentCount >= targetCount) return;

						// Pick up from grid
						gameMode.handleInventoryMouseClick(menu.containerId, gridIdx, 0, ClickType.PICKUP, client.player);
						
						// Try to place into target
						// Note: Minecraft will only place as many as fit/needed for a stack merge
						gameMode.handleInventoryMouseClick(menu.containerId, targetMenuIdx, 0, ClickType.PICKUP, client.player);
						
						// If still carrying (target was full or perfect), put back in grid
						if (!client.player.containerMenu.getCarried().isEmpty()) {
							gameMode.handleInventoryMouseClick(menu.containerId, gridIdx, 0, ClickType.PICKUP, client.player);
						}
						
						if (!gridSlot.hasItem()) {
							excludedGridSlots.add(gridIdx);
						}
					}
				}
			}
		}
	}

	private static int inventoryIndexToMenuIndex(AbstractContainerMenu menu, int invIdx) {
		for (Slot s : menu.slots) {
			if (s.container instanceof Inventory && s.getContainerSlot() == invIdx) {
				return s.index;
			}
		}
		return -1;
	}

	private static void moveItems(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, int sourceIdx, int targetIdx) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		
		Slot source = menu.getSlot(sourceIdx);
		if (!source.hasItem()) return;

		// 1. Pick up from source
		gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, 0, ClickType.PICKUP, client.player);
		
		// 2. Try to place into target (only if it won't cause a swap)
		if (!client.player.containerMenu.getCarried().isEmpty()) {
			Slot target = menu.getSlot(targetIdx);
			boolean canMerge = !target.hasItem() || 
				(ItemStack.isSameItemSameComponents(target.getItem(), client.player.containerMenu.getCarried()) 
				 && target.getItem().getCount() < target.getItem().getMaxStackSize());
            
			if (canMerge) {
				gameMode.handleInventoryMouseClick(menu.containerId, targetIdx, 0, ClickType.PICKUP, client.player);
			}
		}
		
		// 3. If still carrying (target was full), put back in source and Shift-Click it to consolidate
		if (!client.player.containerMenu.getCarried().isEmpty()) {
			gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, 0, ClickType.PICKUP, client.player);
			gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, 0, ClickType.QUICK_MOVE, client.player);
		}
	}

	public static boolean isGridSlot(AbstractContainerMenu menu, int slotId) {
		if (menu instanceof CraftingMenu) {
			return slotId >= 1 && slotId <= 9;
		}
		if (menu instanceof InventoryMenu) {
			return slotId >= 1 && slotId <= 4;
		}
		return false;
	}
}
