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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class InventoryGridRestoreTracker {
	// Tracks where an item IN A GRID SLOT originally came from.
	// gridSlotIndex -> originalInventorySlotIndex
	private static final Map<Integer, Integer> GRID_TO_ORIGINAL_SLOT = new HashMap<>();
	
	// Tracks the "last picked up" slot to link it to a destination.
	private static int lastInventorySlotClicked = -1;
	private static boolean restoring = false;

	private InventoryGridRestoreTracker() {
	}

	public static void recordPotentialSource(int slotId, ClickType clickType, AbstractContainerMenu menu) {
		if (menu == null || slotId < 0 || slotId >= menu.slots.size()) return;

		Slot slot = menu.getSlot(slotId);
		if (slot.container instanceof Inventory) {
			if (clickType == ClickType.PICKUP) {
				lastInventorySlotClicked = slotId;
				ReachCraftingMod.LOGGER.debug("[grid_restore] Recorded potential source slot: {}", slotId);
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
				ReachCraftingMod.LOGGER.debug("[grid_restore] Linked grid slot {} to original inventory slot {}", slotId, lastInventorySlotClicked);
			} else if (clickType == ClickType.QUICK_CRAFT && lastInventorySlotClicked != -1) {
				// Dragging phase (button & 3): 1 = add slot, 2 = end drag
				int phase = button & 3;
				if (phase == 1 || phase == 2) {
					GRID_TO_ORIGINAL_SLOT.put(slotId, lastInventorySlotClicked);
					ReachCraftingMod.LOGGER.debug("[grid_restore] Linked grid slot {} to original inventory slot {} (via drag)", slotId, lastInventorySlotClicked);
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
		com.reachcrafting.ReachCraftingMod.LOGGER.debug("[grid_restore] Starting restoration sequence for menu {}", menu.getClass().getSimpleName());
		restoring = true;
		try {
			if (menu == null || gameMode == null) {
				clear();
				return;
			}

			boolean useSmartRestore = ReachCraftingConfig.get().restoreInventoryItemPositions();
			if (!useSmartRestore) {
				compactGridIntoInventory(menu, gameMode);
				clear();
				return;
			}

			Map<Integer, ItemStack> snapshots = PulledResourcesTracker.getInitialSlotSnapshots();
			if (!GRID_TO_ORIGINAL_SLOT.isEmpty() || !snapshots.isEmpty()) {
				Minecraft client = Minecraft.getInstance();
				if (client.player == null) return;

				Set<Integer> processedGridSlots = new HashSet<>();

				List<Map.Entry<Integer, ItemStack>> orderedSnapshots = snapshots.entrySet().stream()
					.sorted(Map.Entry.comparingByKey())
					.toList();
				logSmartRestoreState("pre", menu, client.player, orderedSnapshots);

				// PASS 1: Restore the original snapshot baseline exactly.
				for (Map.Entry<Integer, ItemStack> entry : orderedSnapshots) {
					int invIdx = entry.getKey();
					ItemStack snapshot = entry.getValue();
					int targetMenuIdx = inventoryIndexToMenuIndex(menu, invIdx);
					if (targetMenuIdx != -1) {
						restoreSnapshotBaseline(menu, gameMode, targetMenuIdx, snapshot, processedGridSlots);
					}
				}
				logSmartRestoreState("post_baseline", menu, client.player, orderedSnapshots);

				// PASS 2: Use same-item overflow to top off the restored snapshot slots one at a time.
				for (Map.Entry<Integer, ItemStack> entry : orderedSnapshots) {
					int invIdx = entry.getKey();
					ItemStack snapshot = entry.getValue();
					int targetMenuIdx = inventoryIndexToMenuIndex(menu, invIdx);
					if (targetMenuIdx != -1) {
						topOffSnapshotSlot(menu, gameMode, targetMenuIdx, snapshot, processedGridSlots);
					}
				}
				logSmartRestoreState("post_topoff", menu, client.player, orderedSnapshots);

				// PASS 3: Return remaining grid items to their mapped inventory slots where possible.
				for (int gridIdx = 0; gridIdx < menu.slots.size(); gridIdx++) {
					if (!isGridSlot(menu, gridIdx) || processedGridSlots.contains(gridIdx)) {
						continue;
					}
					Slot gridSlot = menu.getSlot(gridIdx);
					if (!gridSlot.hasItem()) {
						continue;
					}
					int targetMenuIdx = GRID_TO_ORIGINAL_SLOT.getOrDefault(gridIdx, -1);
					if (targetMenuIdx != -1) {
						moveItems(menu, gameMode, gridIdx, targetMenuIdx);
						if (!gridSlot.hasItem()) {
							processedGridSlots.add(gridIdx);
						}
					}
				}
				logSmartRestoreState("post_mapped", menu, client.player, orderedSnapshots);
			}

			// PASS 4: Leftover Consolidation
			// Compact any remaining grid stacks into inventory so overflow keeps filling restored slots before spilling elsewhere.
			compactGridIntoInventory(menu, gameMode);
			Minecraft client = Minecraft.getInstance();
			if (client.player != null) {
				List<Map.Entry<Integer, ItemStack>> orderedSnapshots = PulledResourcesTracker.getInitialSlotSnapshots().entrySet().stream()
					.sorted(Map.Entry.comparingByKey())
					.toList();
				logSmartRestoreState("post_compact", menu, client.player, orderedSnapshots);
			}
		} catch (Exception e) {
			com.reachcrafting.ReachCraftingMod.LOGGER.error("[grid_restore] Error during restore: ", e);
		} finally {
			restoring = false;
		}
		clear();
	}

	public static boolean isRestoring() {
		return restoring;
	}

	private static void logSmartRestoreState(String phase, AbstractContainerMenu menu, net.minecraft.client.player.LocalPlayer player, List<Map.Entry<Integer, ItemStack>> orderedSnapshots) {
		if (orderedSnapshots.isEmpty()) {
			return;
		}
		Set<String> trackedItemIds = new LinkedHashSet<>();
		StringBuilder targets = new StringBuilder();
		boolean first = true;
		for (Map.Entry<Integer, ItemStack> entry : orderedSnapshots) {
			ItemStack snapshot = entry.getValue();
			if (snapshot.isEmpty()) {
				continue;
			}
			String itemId = BuiltInRegistries.ITEM.getKey(snapshot.getItem()).toString();
			trackedItemIds.add(itemId);
			int targetMenuIdx = inventoryIndexToMenuIndex(menu, entry.getKey());
			Slot target = targetMenuIdx >= 0 ? menu.getSlot(targetMenuIdx) : null;
			int currentCount = target != null && target.hasItem() && ItemStack.isSameItemSameComponents(target.getItem(), snapshot) ? target.getItem().getCount() : 0;
			if (!first) {
				targets.append(", ");
			}
			first = false;
			targets.append("inv").append(entry.getKey()).append("=").append(currentCount).append("/").append(snapshot.getCount());
		}
		ReachCraftingMod.LOGGER.debug(
			"[grid_restore] phase={} targets={} inventory_slots={} grid={}",
			phase,
			targets,
			AvailableItemSnapshot.formatInventorySlots(player, trackedItemIds),
			summarizeGrid(menu)
		);
	}

	private static String summarizeGrid(AbstractContainerMenu menu) {
		Map<String, Integer> gridCounts = new HashMap<>();
		for (int gridIdx = 0; gridIdx < menu.slots.size(); gridIdx++) {
			if (!isGridSlot(menu, gridIdx)) {
				continue;
			}
			ItemStack stack = menu.getSlot(gridIdx).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			gridCounts.merge(itemId, stack.getCount(), Integer::sum);
		}
		return AvailableItemSnapshot.formatCounts(gridCounts);
	}

	private static void restoreSnapshotBaseline(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, int targetMenuIdx, ItemStack snapshot, Set<Integer> processedGridSlots) {
		Slot target = menu.getSlot(targetMenuIdx);
		if (target.hasItem() && !ItemStack.isSameItemSameComponents(target.getItem(), snapshot)) {
			return;
		}

		int currentCount = target.hasItem() ? target.getItem().getCount() : 0;
		int missing = snapshot.getCount() - currentCount;
		if (missing <= 0) {
			return;
		}

		moveMatchingGridItemsToTargetPrecisely(menu, gameMode, targetMenuIdx, snapshot, missing, processedGridSlots);
	}

	private static void topOffSnapshotSlot(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, int targetMenuIdx, ItemStack snapshot, Set<Integer> processedGridSlots) {
		Slot target = menu.getSlot(targetMenuIdx);
		if (!target.hasItem() || !ItemStack.isSameItemSameComponents(target.getItem(), snapshot)) {
			return;
		}

		int maxTargetCount = Math.min(target.getMaxStackSize(), snapshot.getMaxStackSize());
		int missing = maxTargetCount - target.getItem().getCount();
		if (missing <= 0) {
			return;
		}

		moveMatchingGridItemsToTarget(menu, gameMode, targetMenuIdx, snapshot, missing, processedGridSlots);
	}

	private static void moveMatchingGridItemsToTarget(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, int targetMenuIdx, ItemStack desiredStack, int requestedCount, Set<Integer> processedGridSlots) {
		if (requestedCount <= 0) {
			return;
		}

		int remaining = requestedCount;
		for (int gridIdx = 0; gridIdx < menu.slots.size() && remaining > 0; gridIdx++) {
			if (!isGridSlot(menu, gridIdx) || processedGridSlots.contains(gridIdx)) {
				continue;
			}

			Slot gridSlot = menu.getSlot(gridIdx);
			if (!gridSlot.hasItem() || !ItemStack.isSameItemSameComponents(gridSlot.getItem(), desiredStack)) {
				continue;
			}

			int moved = moveExactCountFromGrid(menu, gameMode, gridIdx, targetMenuIdx, remaining);
			if (moved > 0) {
				remaining -= moved;
				if (!gridSlot.hasItem()) {
					processedGridSlots.add(gridIdx);
				}
			}
		}
	}

	private static void moveMatchingGridItemsToTargetPrecisely(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, int targetMenuIdx, ItemStack desiredStack, int requestedCount, Set<Integer> processedGridSlots) {
		if (requestedCount <= 0) {
			return;
		}

		int remaining = requestedCount;
		for (int gridIdx = 0; gridIdx < menu.slots.size() && remaining > 0; gridIdx++) {
			if (!isGridSlot(menu, gridIdx) || processedGridSlots.contains(gridIdx)) {
				continue;
			}

			Slot gridSlot = menu.getSlot(gridIdx);
			if (!gridSlot.hasItem() || !ItemStack.isSameItemSameComponents(gridSlot.getItem(), desiredStack)) {
				continue;
			}

			int moved = moveExactCountFromGridPrecisely(menu, gameMode, gridIdx, targetMenuIdx, remaining);
			if (moved > 0) {
				remaining -= moved;
				if (!gridSlot.hasItem()) {
					processedGridSlots.add(gridIdx);
				}
			}
		}
	}

	private static int moveExactCountFromGrid(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, int sourceIdx, int targetIdx, int requestedCount) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || requestedCount <= 0) {
			return 0;
		}

		Slot source = menu.getSlot(sourceIdx);
		Slot target = menu.getSlot(targetIdx);
		if (!source.hasItem()) {
			return 0;
		}

		ItemStack sourceStack = source.getItem();
		int maxTargetCount = Math.min(target.getMaxStackSize(), sourceStack.getMaxStackSize());
		int currentTargetCount = target.hasItem() ? target.getItem().getCount() : 0;
		int moveCount = Math.min(requestedCount, Math.min(sourceStack.getCount(), maxTargetCount - currentTargetCount));
		if (moveCount <= 0) {
			return 0;
		}

		gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, 0, ClickType.PICKUP, client.player);
		int remainder = sourceStack.getCount() - moveCount;
		for (int i = 0; i < remainder; i++) {
			gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, 1, ClickType.PICKUP, client.player);
		}
		gameMode.handleInventoryMouseClick(menu.containerId, targetIdx, 0, ClickType.PICKUP, client.player);
		if (!client.player.containerMenu.getCarried().isEmpty()) {
			gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, 0, ClickType.PICKUP, client.player);
		}
		return moveCount;
	}

	private static int moveExactCountFromGridPrecisely(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, int sourceIdx, int targetIdx, int requestedCount) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || requestedCount <= 0) {
			return 0;
		}

		Slot source = menu.getSlot(sourceIdx);
		Slot target = menu.getSlot(targetIdx);
		if (!source.hasItem()) {
			return 0;
		}

		ItemStack sourceStack = source.getItem();
		int maxTargetCount = Math.min(target.getMaxStackSize(), sourceStack.getMaxStackSize());
		int currentTargetCount = target.hasItem() ? target.getItem().getCount() : 0;
		int moveCount = Math.min(requestedCount, Math.min(sourceStack.getCount(), maxTargetCount - currentTargetCount));
		if (moveCount <= 0) {
			return 0;
		}

		gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, 0, ClickType.PICKUP, client.player);
		for (int i = 0; i < moveCount; i++) {
			gameMode.handleInventoryMouseClick(menu.containerId, targetIdx, 1, ClickType.PICKUP, client.player);
		}
		if (!client.player.containerMenu.getCarried().isEmpty()) {
			gameMode.handleInventoryMouseClick(menu.containerId, sourceIdx, 0, ClickType.PICKUP, client.player);
		}
		return moveCount;
	}

	public static void compactTrackedInventoryStacks(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, Set<String> trackedItemIds) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || menu == null || gameMode == null || trackedItemIds == null || trackedItemIds.isEmpty()) {
			return;
		}

		Set<String> remainingIds = new LinkedHashSet<>(trackedItemIds);
		boolean moved;
		do {
			moved = false;
			for (String itemId : remainingIds) {
				if (compactOneInventoryStack(menu, gameMode, client, itemId)) {
					moved = true;
				}
			}
		} while (moved);
	}

	private static boolean compactOneInventoryStack(AbstractContainerMenu menu, MultiPlayerGameMode gameMode, Minecraft client, String itemId) {
		Slot target = null;
		int targetSpace = 0;
		Slot source = null;
		int sourceCount = 0;

		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
				continue;
			}
			String slotItemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
			if (!itemId.equals(slotItemId)) {
				continue;
			}

			int max = Math.min(slot.getMaxStackSize(), slot.getItem().getMaxStackSize());
			int count = slot.getItem().getCount();
			if (count < max) {
				int space = max - count;
				if (target == null || count > target.getItem().getCount()) {
					target = slot;
					targetSpace = space;
				}
			}

			if (source == null || count < sourceCount) {
				source = slot;
				sourceCount = count;
			}
		}

		if (target == null || source == null || target == source) {
			return false;
		}

		int moveCount = Math.min(targetSpace, sourceCount);
		if (moveCount <= 0) {
			return false;
		}

		gameMode.handleInventoryMouseClick(menu.containerId, source.index, 0, ClickType.PICKUP, client.player);
		int remainder = sourceCount - moveCount;
		for (int i = 0; i < remainder; i++) {
			gameMode.handleInventoryMouseClick(menu.containerId, source.index, 1, ClickType.PICKUP, client.player);
		}
		gameMode.handleInventoryMouseClick(menu.containerId, target.index, 0, ClickType.PICKUP, client.player);
		if (!client.player.containerMenu.getCarried().isEmpty()) {
			gameMode.handleInventoryMouseClick(menu.containerId, source.index, 0, ClickType.PICKUP, client.player);
		}
		return true;
	}

	private static void compactGridIntoInventory(AbstractContainerMenu menu, MultiPlayerGameMode gameMode) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		for (int gridIdx = 0; gridIdx < menu.slots.size(); gridIdx++) {
			if (!isGridSlot(menu, gridIdx)) {
				continue;
			}

			Slot gridSlot = menu.getSlot(gridIdx);
			if (!gridSlot.hasItem()) {
				continue;
			}

			String itemId = BuiltInRegistries.ITEM.getKey(gridSlot.getItem().getItem()).toString();
			String itemName = BuiltInRegistries.ITEM.getKey(gridSlot.getItem().getItem()).getPath();
			int itemCount = gridSlot.getItem().getCount();

			// During bulk crafting, throw byproducts directly instead of
			// moving them to inventory. This prevents the last craft's
			// byproducts (e.g. glass bottles) from fragmenting in inventory.
			if (AutoCraftController.isBulkModeEnabled() && BulkAutoCraftController.isActive()) {
				java.util.Set<String> acceptedIds = BulkAutoCraftController.getAcceptedItemIds();
				if (acceptedIds != null && !acceptedIds.contains(itemId)) {
					ReachCraftingMod.LOGGER.info("[grid_flush] THROW byproduct from grid slot {}: {}x{}", gridIdx, itemCount, itemName);
					gameMode.handleInventoryMouseClick(menu.containerId, gridSlot.index, 1, ClickType.THROW, client.player);
					continue;
				}
			}

			ReachCraftingMod.LOGGER.info("[grid_flush] Flushing grid slot {}: {}x{}", gridIdx, itemCount, itemName);

			// Pick up all items from this grid slot
			gameMode.handleInventoryMouseClick(menu.containerId, gridIdx, 0, ClickType.PICKUP, client.player);
			if (client.player.containerMenu.getCarried().isEmpty()) {
				continue;
			}

			// Phase 1: Merge into existing matching stacks
			// Scan hotbar (inventory indices 0-8) first, then main inventory (9-35)
			for (int invIdx = 0; invIdx < 36 && !client.player.containerMenu.getCarried().isEmpty(); invIdx++) {
				int menuIdx = inventoryIndexToMenuIndex(menu, invIdx);
				if (menuIdx == -1) continue;

				Slot target = menu.getSlot(menuIdx);
				if (!target.hasItem()) continue;

				ItemStack carried = client.player.containerMenu.getCarried();
				if (!ItemStack.isSameItemSameComponents(target.getItem(), carried)) continue;

				int maxStack = Math.min(target.getMaxStackSize(), carried.getMaxStackSize());
				if (target.getItem().getCount() >= maxStack) continue;

				ReachCraftingMod.LOGGER.info("[grid_flush] Phase1 merge: inv {} (menu {}) has {}x{}, merging carried {}",
					invIdx, menuIdx, target.getItem().getCount(), itemName, carried.getCount());
				gameMode.handleInventoryMouseClick(menu.containerId, menuIdx, 0, ClickType.PICKUP, client.player);
			}

			// Phase 2: Deposit into empty hotbar slots left-to-right (inventory indices 0-8)
			for (int invIdx = 0; invIdx < 9 && !client.player.containerMenu.getCarried().isEmpty(); invIdx++) {
				int menuIdx = inventoryIndexToMenuIndex(menu, invIdx);
				if (menuIdx == -1) continue;

				Slot target = menu.getSlot(menuIdx);
				if (target.hasItem()) continue;

				ReachCraftingMod.LOGGER.info("[grid_flush] Phase2 empty hotbar: inv {} (menu {}), depositing {}",
					invIdx, menuIdx, client.player.containerMenu.getCarried().getCount());
				gameMode.handleInventoryMouseClick(menu.containerId, menuIdx, 0, ClickType.PICKUP, client.player);
			}

			// Phase 3: If still carrying, put back in grid and shift-click (vanilla logic)
			if (!client.player.containerMenu.getCarried().isEmpty()) {
				ReachCraftingMod.LOGGER.info("[grid_flush] Phase3 shift-click fallback: remaining {}",
					client.player.containerMenu.getCarried().getCount());
				gameMode.handleInventoryMouseClick(menu.containerId, gridIdx, 0, ClickType.PICKUP, client.player);
				if (menu.getSlot(gridIdx).hasItem()) {
					gameMode.handleInventoryMouseClick(menu.containerId, gridIdx, 0, ClickType.QUICK_MOVE, client.player);
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
