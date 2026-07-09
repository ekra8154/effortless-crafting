package com.reachcrafting.client;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

final class ChainCraftStagingPlanner {
	private ChainCraftStagingPlanner() {
	}

	static Map<String, Integer> missingStagingCounts(Minecraft client, ChainCraftPlan plan) {
		Map<String, Integer> demand = externalDemand(plan);
		Map<String, Integer> localCounts = currentInventoryCounts(client);
		Map<String, Integer> missing = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : demand.entrySet()) {
			int missingCount = entry.getValue() - localCounts.getOrDefault(entry.getKey(), 0);
			if (missingCount > 0) {
				missing.put(entry.getKey(), missingCount);
			}
		}
		return missing;
	}

	static boolean isFullyAvailableLocally(Minecraft client, ChainCraftPlan plan) {
		return missingStagingCounts(client, plan).isEmpty();
	}

	private static Map<String, Integer> externalDemand(ChainCraftPlan plan) {
		Map<String, Integer> demand = new LinkedHashMap<>();
		if (plan == null) {
			return demand;
		}
		for (ChainCraftPlan.Step step : plan.steps()) {
			for (Map.Entry<String, Integer> entry : step.requiredInputs().entrySet()) {
				demand.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}
		for (ChainCraftPlan.Step step : plan.steps()) {
			if (!step.finalStep()) {
				String outputId = BuiltInRegistries.ITEM.getKey(step.displayStack().getItem()).toString();
				subtract(demand, outputId, Math.max(step.displayStack().getCount(), 1) * step.recipeCopies());
			}
		}
		return demand;
	}

	private static Map<String, Integer> currentInventoryCounts(Minecraft client) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		if (client == null || client.player == null || client.player.containerMenu == null) {
			return counts;
		}
		for (Slot slot : client.player.containerMenu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
				continue;
			}
			String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
			counts.merge(itemId, slot.getItem().getCount(), Integer::sum);
		}
		return counts;
	}

	private static void subtract(Map<String, Integer> counts, String itemId, int count) {
		int current = counts.getOrDefault(itemId, 0);
		if (current <= count) {
			counts.remove(itemId);
		} else {
			counts.put(itemId, current - count);
		}
	}
}
