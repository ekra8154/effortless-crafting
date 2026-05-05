package com.reachcrafting.client;

import java.util.List;
import java.util.Map;
import net.minecraft.world.inventory.Slot;

/**
 * Immutable withdrawal plan for one opened nearby container.
 */
record WithdrawalPlan(
	List<PlannedMove> moves,
	Map<String, Integer> withdrawnCounts,
	Map<String, Integer> remainingNeeds,
	boolean inventorySpaceBlocked
) {
	WithdrawalPlan {
		moves = List.copyOf(moves);
		withdrawnCounts = Map.copyOf(withdrawnCounts);
		remainingNeeds = Map.copyOf(remainingNeeds);
	}

	record PlannedMove(Slot source, Slot target, String itemId, int count) {
	}
}
