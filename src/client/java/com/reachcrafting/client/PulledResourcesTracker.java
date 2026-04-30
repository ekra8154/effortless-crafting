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
	private static final Map<Integer, String> INITIAL_SLOT_TYPES = new HashMap<>();

	private PulledResourcesTracker() {
	}

	public static void captureInventorySnapshot(LocalPlayer player) {
		if (!INITIAL_COUNTS.isEmpty()) {
			return;
		}
		Inventory inventory = player.getInventory();
		// Count everything that isn't the crafting grid
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				INITIAL_COUNTS.merge(itemId, stack.getCount(), Integer::sum);
				INITIAL_SLOT_TYPES.put(i, itemId);
			}
		}
		com.reachcrafting.ReachCraftingMod.LOGGER.info("[nearby_tracker] captured initial counts and slot types");
	}

	public static void updateSlotType(int slotIndex, String itemId) {
		if (itemId == null || itemId.isEmpty()) {
			INITIAL_SLOT_TYPES.remove(slotIndex);
		} else {
			INITIAL_SLOT_TYPES.put(slotIndex, itemId);
		}
	}

	public static String getInitialSlotType(int slotIndex) {
		return INITIAL_SLOT_TYPES.get(slotIndex);
	}
	
	public static Map<Integer, String> getInitialSlotTypes() {
		return Map.copyOf(INITIAL_SLOT_TYPES);
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
		WITHDRAWN_ITEMS.add(new WithdrawnItem(containerPos, slotIndex, stack.copy()));
	}

	public static List<WithdrawnItem> getWithdrawnItems() {
		return List.copyOf(WITHDRAWN_ITEMS);
	}

	public static void clear() {
		WITHDRAWN_ITEMS.clear();
		INITIAL_COUNTS.clear();
		INITIAL_SLOT_TYPES.clear();
	}

	public static boolean isEmpty() {
		return WITHDRAWN_ITEMS.isEmpty();
	}

	public record WithdrawnItem(BlockPos containerPos, int slotIndex, ItemStack stack) {
	}
}
