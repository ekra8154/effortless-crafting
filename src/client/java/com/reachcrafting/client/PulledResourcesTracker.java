package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

public final class PulledResourcesTracker {
	private static final List<WithdrawnItem> WITHDRAWN_ITEMS = new ArrayList<>();
	private static final Map<String, Integer> INITIAL_COUNTS = new HashMap<>();
	private static final Map<Integer, ItemStack> INITIAL_SLOT_SNAPSHOTS = new HashMap<>();

	private PulledResourcesTracker() {
	}

	public static void captureInventorySnapshot(LocalPlayer player) {
		if (!INITIAL_SLOT_SNAPSHOTS.isEmpty()) {
			return;
		}
		updateSnapshot(player);
		com.reachcrafting.ReachCraftingMod.LOGGER.debug("[nearby_tracker] captured initial inventory snapshots: {}", AvailableItemSnapshot.formatCounts(INITIAL_COUNTS));
	}

	/**
	 * Updates the known 'home' for items in the inventory.
	 * We only update a slot if it contains a DIFFERENT item or MORE of the same item.
	 * We never decrease the snapshot count, as that usually means items were moved to the grid.
	 */
	public static void updateSnapshot(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		boolean changed = false;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack current = inventory.getItem(i);
			if (current.isEmpty()) continue;

			ItemStack existing = INITIAL_SLOT_SNAPSHOTS.get(i);
			boolean shouldUpdate = false;
			
			if (existing == null || existing.isEmpty()) {
				shouldUpdate = true;
			} else if (!ItemStack.isSameItemSameTags(existing, current)) {
				shouldUpdate = true;
			} else if (current.getCount() > existing.getCount()) {
				shouldUpdate = true;
			}

			if (shouldUpdate) {
				INITIAL_SLOT_SNAPSHOTS.put(i, current.copy());
				changed = true;
			}
		}
		if (changed) {
			rebuildInitialCounts();
		}
	}

	public static Map<Integer, ItemStack> getInitialSlotSnapshots() {
		return Map.copyOf(INITIAL_SLOT_SNAPSHOTS);
	}
	
	public static int getInitialCount(String itemId) {
		return INITIAL_COUNTS.getOrDefault(itemId, 0);
	}

	public static int getProtectedSlotCount(int inventorySlot, ItemStack currentStack) {
		if (currentStack.isEmpty()) {
			return 0;
		}

		ItemStack snapshot = INITIAL_SLOT_SNAPSHOTS.get(inventorySlot);
		if (snapshot == null || snapshot.isEmpty()) {
			return 0;
		}
		if (!ItemStack.isSameItemSameTags(snapshot, currentStack)) {
			return 0;
		}
		return snapshot.getCount();
	}

	public static int getExcessCount(String itemId, int currentCount) {
		int initial = INITIAL_COUNTS.getOrDefault(itemId, 0);
		return Math.max(0, currentCount - initial);
	}

	private static void rebuildInitialCounts() {
		INITIAL_COUNTS.clear();
		for (ItemStack snapshot : INITIAL_SLOT_SNAPSHOTS.values()) {
			if (snapshot.isEmpty()) {
				continue;
			}
			String itemId = BuiltInRegistries.ITEM.getKey(snapshot.getItem()).toString();
			INITIAL_COUNTS.merge(itemId, snapshot.getCount(), Integer::sum);
		}
	}

	public static void recordWithdrawal(BlockPos containerPos, int slotIndex, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		
		// Consolidate with previous entry if same pos/slot to avoid 1-by-1 return overhead
		if (!WITHDRAWN_ITEMS.isEmpty()) {
			WithdrawnItem last = WITHDRAWN_ITEMS.get(WITHDRAWN_ITEMS.size() - 1);
			if (last.containerPos().equals(containerPos) && last.slotIndex() == slotIndex && ItemStack.isSameItemSameTags(last.stack(), stack)) {
				last.stack().grow(stack.getCount());
				return;
			}
		}

		com.reachcrafting.ReachCraftingMod.LOGGER.debug("[nearby_tracker] Recording withdrawal: {} at {} slot {}", ContainerUtils.formatStack(stack), ContainerUtils.formatPos(containerPos), slotIndex);
		WITHDRAWN_ITEMS.add(new WithdrawnItem(containerPos, slotIndex, stack.copy()));
	}

	public static List<WithdrawnItem> getWithdrawnItems() {
		return List.copyOf(WITHDRAWN_ITEMS);
	}

	public static void clear() {
		clearWithdrawals();
		clearSnapshot();
	}

	public static void clearWithdrawals() {
		WITHDRAWN_ITEMS.clear();
	}

	public static void clearSnapshot() {
		INITIAL_COUNTS.clear();
		INITIAL_SLOT_SNAPSHOTS.clear();
	}

	public static boolean isEmpty() {
		return WITHDRAWN_ITEMS.isEmpty();
	}

	public record WithdrawnItem(BlockPos containerPos, int slotIndex, ItemStack stack) {
	}
}

