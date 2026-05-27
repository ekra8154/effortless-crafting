package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

final class StagingInventorySimulator {
	private StagingInventorySimulator() {
	}

	static boolean canFit(LocalPlayer player, Map<String, Integer> counts) {
		if (player == null) {
			return false;
		}
		List<ItemStack> inventory = snapshotPlayerInventorySlots(player);
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (entry.getValue() <= 0) {
				continue;
			}
			if (!placeIntoVirtualInventory(inventory, itemPrototype(entry.getKey()), entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	private static List<ItemStack> snapshotPlayerInventorySlots(LocalPlayer player) {
		List<ItemStack> slots = new ArrayList<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			slots.add(stack.copy());
		}
		return slots;
	}

	private static ItemStack itemPrototype(String itemId) {
		if (itemId == null || itemId.isEmpty()) {
			return ItemStack.EMPTY;
		}

		try {
			var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
			return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
		} catch (Exception ignored) {
			return ItemStack.EMPTY;
		}
	}

	private static boolean placeIntoVirtualInventory(List<ItemStack> inventorySlots, ItemStack prototype, int count) {
		if (count <= 0) {
			return true;
		}
		if (prototype == null || prototype.isEmpty()) {
			return false;
		}

		int remaining = count;
		int maxStackSize = Math.max(prototype.getMaxStackSize(), 1);
		for (ItemStack slot : inventorySlots) {
			if (remaining <= 0) {
				break;
			}
			if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, prototype)) {
				continue;
			}

			int room = maxStackSize - slot.getCount();
			if (room <= 0) {
				continue;
			}
			int placed = Math.min(room, remaining);
			slot.grow(placed);
			remaining -= placed;
		}

		for (int i = 0; i < inventorySlots.size() && remaining > 0; i++) {
			if (!inventorySlots.get(i).isEmpty()) {
				continue;
			}

			int placed = Math.min(maxStackSize, remaining);
			ItemStack newStack = prototype.copy();
			newStack.setCount(placed);
			inventorySlots.set(i, newStack);
			remaining -= placed;
		}

		return remaining <= 0;
	}
}
