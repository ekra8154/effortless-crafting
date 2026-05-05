package com.reachcrafting.client;

import java.util.List;
import java.util.Map;
import net.minecraft.world.inventory.Slot;

/**
 * Immutable return plan for one opened container.
 * Planning is separated from execution so ReturnSession does not mix both concerns.
 */
record ReturnPlan(List<PlannedMove> moves, Map<String, Integer> updatedExcess) {
	ReturnPlan {
		moves = List.copyOf(moves);
		updatedExcess = Map.copyOf(updatedExcess);
	}

	record PlannedMove(Slot source, Slot target, int count) {
	}
}
