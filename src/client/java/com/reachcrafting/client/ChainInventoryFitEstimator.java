package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Conservative slot-level simulation of a chain plan against the player
 * inventory. A plan fits when the staged base materials and every step's
 * outputs can be placed without running out of slots, assuming the worst
 * case that a step's outputs land before its inputs are freed. This is what
 * bounds bulk chain batches for non-stackable intermediates (e.g. 64 bows
 * for a dispenser batch would need 64 slots).
 */
final class ChainInventoryFitEstimator {
	private ChainInventoryFitEstimator() {
	}

	/**
	 * Empty slots held back from the simulation. Auto-move needs shuffle
	 * room, and a batch that fits exactly strands its last item on the
	 * cursor (observed with a full-inventory bow batch).
	 */
	private static final int SAFETY_MARGIN_SLOTS = 2;

	static boolean planFits(Minecraft client, LocalPlayer player, ChainCraftPlan plan, boolean finalOutputsEjected) {
		if (player == null || plan == null || plan.steps().isEmpty()) {
			return false;
		}
		List<ItemStack> slots = snapshotPlayerInventorySlots(player);
		reserveEmptySlots(slots, SAFETY_MARGIN_SLOTS);
		Map<String, Integer> missingStaged = ChainCraftStagingPlanner.missingStagingCounts(client, plan);
		for (Map.Entry<String, Integer> entry : missingStaged.entrySet()) {
			if (!place(slots, entry.getKey(), entry.getValue())) {
				return false;
			}
		}
		for (ChainCraftPlan.Step step : plan.steps()) {
			if (step.displayStack().isEmpty()) {
				continue;
			}
			// Final outputs thrown as they craft never occupy inventory
			// slots, which allows larger batches when ejection is on.
			if (!(step.finalStep() && finalOutputsEjected)) {
				String outputId = BuiltInRegistries.ITEM.getKey(step.displayStack().getItem()).toString();
				int producedCount = Math.max(step.displayStack().getCount(), 1) * Math.max(step.recipeCopies(), 1);
				// Worst case: all of the step's outputs coexist with its
				// not-yet-consumed inputs before any input slot frees up.
				if (!place(slots, outputId, producedCount)) {
					return false;
				}
			}
			for (Map.Entry<String, Integer> input : step.requiredInputs().entrySet()) {
				remove(slots, input.getKey(), input.getValue());
			}
		}
		return true;
	}

	private static void reserveEmptySlots(List<ItemStack> slots, int margin) {
		int reserved = 0;
		for (int i = slots.size() - 1; i >= 0 && reserved < margin; i--) {
			if (slots.get(i).isEmpty()) {
				slots.remove(i);
				reserved++;
			}
		}
	}

	private static List<ItemStack> snapshotPlayerInventorySlots(LocalPlayer player) {
		List<ItemStack> slots = new ArrayList<>();
		for (ItemStack stack : player.getInventory().items) {
			slots.add(stack.copy());
		}
		return slots;
	}

	private static ItemStack itemPrototype(String itemId) {
		if (itemId == null || itemId.isEmpty()) {
			return ItemStack.EMPTY;
		}
		try {
			var item = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(itemId));
			return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
		} catch (Exception ignored) {
			return ItemStack.EMPTY;
		}
	}

	private static boolean place(List<ItemStack> slots, String itemId, int count) {
		if (count <= 0) {
			return true;
		}
		ItemStack prototype = itemPrototype(itemId);
		if (prototype.isEmpty()) {
			return false;
		}

		int remaining = count;
		int maxStackSize = Math.max(prototype.getMaxStackSize(), 1);
		for (ItemStack slot : slots) {
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

		for (int i = 0; i < slots.size() && remaining > 0; i++) {
			if (!slots.get(i).isEmpty()) {
				continue;
			}
			int placed = Math.min(maxStackSize, remaining);
			ItemStack newStack = prototype.copy();
			newStack.setCount(placed);
			slots.set(i, newStack);
			remaining -= placed;
		}

		return remaining <= 0;
	}

	private static void remove(List<ItemStack> slots, String itemId, int count) {
		if (count <= 0) {
			return;
		}
		ItemStack prototype = itemPrototype(itemId);
		if (prototype.isEmpty()) {
			return;
		}
		int remaining = count;
		for (int i = 0; i < slots.size() && remaining > 0; i++) {
			ItemStack slot = slots.get(i);
			if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, prototype)) {
				continue;
			}
			int taken = Math.min(slot.getCount(), remaining);
			slot.shrink(taken);
			remaining -= taken;
			if (slot.isEmpty()) {
				slots.set(i, ItemStack.EMPTY);
			}
		}
	}
}
