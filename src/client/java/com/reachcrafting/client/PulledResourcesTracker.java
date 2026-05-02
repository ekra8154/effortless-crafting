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
		com.reachcrafting.ReachCraftingMod.LOGGER.info("[nearby_tracker] captured initial inventory snapshots: {}", AvailableItemSnapshot.formatCounts(INITIAL_COUNTS));
	}

	/**
	 * Updates the known 'home' for items in the inventory.
	 * We only update a slot if it contains a DIFFERENT item or MORE of the same item.
	 * We never decrease the snapshot count, as that usually means items were moved to the grid.
	 */
	public static void updateSnapshot(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack current = inventory.getItem(i);
			if (current.isEmpty()) continue;

			ItemStack existing = INITIAL_SLOT_SNAPSHOTS.get(i);
			boolean shouldUpdate = false;
			
			if (existing == null || existing.isEmpty()) {
				shouldUpdate = true;
			} else if (!ItemStack.isSameItemSameComponents(existing, current)) {
				shouldUpdate = true;
			} else if (current.getCount() > existing.getCount()) {
				shouldUpdate = true;
			}

			if (shouldUpdate) {
				INITIAL_SLOT_SNAPSHOTS.put(i, current.copy());
				
				// Also update the global count tracker for nearby pull logic
				String itemId = BuiltInRegistries.ITEM.getKey(current.getItem()).toString();
				INITIAL_COUNTS.merge(itemId, current.getCount(), Integer::max);
			}
		}
	}

	public static Map<Integer, ItemStack> getInitialSlotSnapshots() {
		return Map.copyOf(INITIAL_SLOT_SNAPSHOTS);
	}
	
	public static int getInitialCount(String itemId) {
		return INITIAL_COUNTS.getOrDefault(itemId, 0);
	}

	public static int getExcessCount(String itemId, int currentCount) {
		int initial = INITIAL_COUNTS.getOrDefault(itemId, 0);
		return Math.max(0, currentCount - initial);
	}

	public static void recordWithdrawal(BlockPos containerPos, int slotIndex, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		
		// Consolidate with previous entry if same pos/slot to avoid 1-by-1 return overhead
		if (!WITHDRAWN_ITEMS.isEmpty()) {
			WithdrawnItem last = WITHDRAWN_ITEMS.get(WITHDRAWN_ITEMS.size() - 1);
			if (last.containerPos().equals(containerPos) && last.slotIndex() == slotIndex && ItemStack.isSameItemSameComponents(last.stack(), stack)) {
				last.stack().grow(stack.getCount());
				return;
			}
		}

		com.reachcrafting.ReachCraftingMod.LOGGER.info("[nearby_tracker] Recording withdrawal: {} at {} slot {}", ContainerUtils.formatStack(stack), ContainerUtils.formatPos(containerPos), slotIndex);
		WITHDRAWN_ITEMS.add(new WithdrawnItem(containerPos, slotIndex, stack.copy()));
	}

	public static List<WithdrawnItem> getWithdrawnItems() {
		return List.copyOf(WITHDRAWN_ITEMS);
	}

	public static void clear() {
		WITHDRAWN_ITEMS.clear();
		INITIAL_COUNTS.clear();
		INITIAL_SLOT_SNAPSHOTS.clear();
	}

	public static boolean isEmpty() {
		return WITHDRAWN_ITEMS.isEmpty();
	}

	public record WithdrawnItem(BlockPos containerPos, int slotIndex, ItemStack stack) {
	}
}
