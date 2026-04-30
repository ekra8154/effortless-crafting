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
import org.lwjgl.glfw.GLFW;
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
					ItemStack carried = menu.getCarried();
					if (!carried.isEmpty()) {
						String carriedId = BuiltInRegistries.ITEM.getKey(carried.getItem()).toString();
						
						// Important: snapshot uses INVENTORY indices, not MENU indices
						int destInvIdx = slot.getContainerSlot();
						int sourceInvIdx = menu.getSlot(lastInventorySlotClicked).getContainerSlot();
						
						PulledResourcesTracker.updateSlotType(destInvIdx, carriedId);
						PulledResourcesTracker.updateSlotType(sourceInvIdx, null);
						ReachCraftingMod.LOGGER.info("[grid_restore] Re-captured manual movement: {} from inv_slot {} to inv_slot {}", carriedId, sourceInvIdx, destInvIdx);
					}

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
		try {
			if (menu == null || gameMode == null || !ReachCraftingConfig.get().restoreInventoryItemPositions()) {
				clear();
				return;
			}

			// Check if we have anything at all to restore
			if (GRID_TO_ORIGINAL_SLOT.isEmpty() && PulledResourcesTracker.getInitialSlotTypes().isEmpty()) {
				return;
			}

			Minecraft client = Minecraft.getInstance();
			if (client.player == null) return;

			// 1. Identify all item types currently in the grid
			Set<String> gridItemTypes = new HashSet<>();
			for (int gridIdx = 0; gridIdx < menu.slots.size(); gridIdx++) {
				if (isGridSlot(menu, gridIdx)) {
					Slot s = menu.getSlot(gridIdx);
					if (s.hasItem()) {
						String itemId = BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString();
						gridItemTypes.add(itemId);
					}
				}
			}

			if (gridItemTypes.isEmpty()) {
				clear();
				return;
			}

			// 2. For each grid item type, "sweep" all instances back to its primary home
			for (String itemId : gridItemTypes) {
				int homeMenuIdx = findHomeMenuIdx(menu, itemId);
				if (homeMenuIdx == -1) continue;

				// Sweep from inventory first (to consolidate existing stacks)
				for (Slot s : menu.slots) {
					if (s.container instanceof Inventory && s.index != homeMenuIdx && s.hasItem()) {
						String slotItemId = BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString();
						if (slotItemId.equals(itemId)) {
							moveItems(menu, gameMode, s.index, homeMenuIdx);
						}
					}
				}

				// Sweep from grid
				for (int gridIdx = 0; gridIdx < menu.slots.size(); gridIdx++) {
					if (isGridSlot(menu, gridIdx)) {
						Slot s = menu.getSlot(gridIdx);
						if (s.hasItem()) {
							String slotItemId = BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString();
							if (slotItemId.equals(itemId)) {
								moveItems(menu, gameMode, gridIdx, homeMenuIdx);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			com.reachcrafting.ReachCraftingMod.LOGGER.error("[grid_restore] Error during restore: ", e);
		}
		clear();
	}

	private static int findHomeMenuIdx(AbstractContainerMenu menu, String itemId) {
		// First priority: Exact mapping from a current grid slot
		for (int gridIdx = 0; gridIdx < menu.slots.size(); gridIdx++) {
			if (isGridSlot(menu, gridIdx)) {
				Slot s = menu.getSlot(gridIdx);
				if (s.hasItem() && BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString().equals(itemId)) {
					int originalMenuIdx = GRID_TO_ORIGINAL_SLOT.getOrDefault(gridIdx, -1);
					if (originalMenuIdx != -1) {
						ReachCraftingMod.LOGGER.info("[grid_restore] Using exact mapping for {}: GridSlot {} -> HomeSlot {}", itemId, gridIdx, originalMenuIdx);
						return originalMenuIdx;
					}
				}
			}
		}

		// Second priority: Snapshot home
		for (Map.Entry<Integer, String> entry : PulledResourcesTracker.getInitialSlotTypes().entrySet()) {
			if (itemId.equals(entry.getValue())) {
				int targetInvIdx = entry.getKey();
				for (Slot s : menu.slots) {
					if (s.container instanceof Inventory && s.getContainerSlot() == targetInvIdx) {
						ReachCraftingMod.LOGGER.info("[grid_restore] Using snapshot mapping for {}: InvSlot {} -> HomeSlot {}", itemId, targetInvIdx, s.index);
						return s.index;
					}
				}
			}
		}
		return -1;
	}

	private static void moveItems(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, int sourceIdx, int targetIdx) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		
		Slot source = menu.getSlot(sourceIdx);
		
		if (!source.hasItem()) return;

		// Pick up from source
		gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, client.player);
		
		// Place into target
		if (!client.player.containerMenu.getCarried().isEmpty()) {
			gameMode.handleInventoryMouseClick(menu.containerId, targetIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, client.player);
		}
		
		// If still carrying (target was full or swapped), find an empty inventory slot
		if (!client.player.containerMenu.getCarried().isEmpty()) {
			int emptySlotIdx = -1;
			for (Slot s : menu.slots) {
				if (s.container instanceof Inventory && !s.hasItem() && s.mayPlace(client.player.containerMenu.getCarried())) {
					emptySlotIdx = s.index;
					break;
				}
			}
			
			if (emptySlotIdx != -1) {
				// com.reachcrafting.ReachCraftingMod.LOGGER.info("[grid_restore] Placing overflow into empty inventory slot {}", emptySlotIdx);
				gameMode.handleInventoryMouseClick(menu.containerId, emptySlotIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, client.player);
			} else {
				// Absolute fallback: put back in source
				// com.reachcrafting.ReachCraftingMod.LOGGER.warn("[grid_restore] No empty slot for overflow! Putting back in {}", sourceIdx);
				gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, client.player);
			}
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
