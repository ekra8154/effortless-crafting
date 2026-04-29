package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

public final class PulledResourcesTracker {
	private static final List<WithdrawnItem> WITHDRAWN_ITEMS = new ArrayList<>();

	private PulledResourcesTracker() {
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
	}

	public record WithdrawnItem(BlockPos containerPos, int slotIndex, ItemStack stack) {
	}
}
