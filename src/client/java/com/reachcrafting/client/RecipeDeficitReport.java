package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public record RecipeDeficitReport(String compactMissingSummary, boolean hasMissingIngredients) {
	public static RecipeDeficitReport from(RecipeIngredientSummary ingredientSummary, AvailableItemSnapshot availableItems) {
		Map<String, Integer> remaining = new LinkedHashMap<>(availableItems.totalCounts());
		Map<String, Integer> missingExact = new LinkedHashMap<>();
		Map<String, Integer> missingFlexible = new LinkedHashMap<>();

		for (RecipeIngredientSummary.IngredientSlot slot : ingredientSummary.slots()) {
			if (slot.isEmpty()) {
				continue;
			}

			if (slot.isExact()) {
				String itemId = slot.itemIds().get(0);
				if (!consume(remaining, itemId)) {
					missingExact.merge(itemId, 1, Integer::sum);
				}
				continue;
			}

			String matchedItem = firstAvailableOption(slot.itemIds(), remaining);
			if (matchedItem == null) {
				missingFlexible.merge(slot.display(), 1, Integer::sum);
				continue;
			}

			consume(remaining, matchedItem);
		}

		List<String> missingParts = new ArrayList<>();
		missingExact.forEach((itemId, count) -> missingParts.add(count + "x " + itemId));
		missingFlexible.forEach((display, count) -> missingParts.add(count + "x any of " + display));

		StringJoiner joiner = new StringJoiner(", ");
		missingParts.forEach(joiner::add);
		String compactMissingSummary = joiner.length() == 0 ? "<none>" : joiner.toString();
		return new RecipeDeficitReport(compactMissingSummary, !missingParts.isEmpty());
	}

	private static String firstAvailableOption(List<String> itemIds, Map<String, Integer> remaining) {
		for (String itemId : itemIds) {
			if (remaining.getOrDefault(itemId, 0) > 0) {
				return itemId;
			}
		}
		return null;
	}

	private static boolean consume(Map<String, Integer> remaining, String itemId) {
		int available = remaining.getOrDefault(itemId, 0);
		if (available <= 0) {
			return false;
		}
		if (available == 1) {
			remaining.remove(itemId);
		} else {
			remaining.put(itemId, available - 1);
		}
		return true;
	}
}
