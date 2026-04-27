package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

public final class IngredientPlanning {
	private IngredientPlanning() {
	}

	public static PlanResult plan(
		RecipeIngredientSummary ingredientSummary,
		Map<String, Integer> inventoryCounts,
		List<ItemStack> gridStacks,
		Map<String, Integer> usableCounts,
		Map<String, Integer> preferenceTotals,
		int copiesPerSlot,
		Policy policy
	) {
		Map<String, Integer> remainingUsable = new LinkedHashMap<>(usableCounts);
		Map<String, Integer> missingExact = new LinkedHashMap<>();
		Map<String, Integer> missingFlexible = new LinkedHashMap<>();
		List<String> missingItemIds = new ArrayList<>();
		List<SlotTarget> slotTargets = new ArrayList<>();
		Map<GroupKey, List<SlotState>> groupedSlots = groupSlots(ingredientSummary, normalizeGridStacksForPlanning(ingredientSummary, gridStacks));

		for (List<SlotState> groupSlots : groupedSlots.values()) {
			planGroup(groupSlots, inventoryCounts, remainingUsable, preferenceTotals, copiesPerSlot, policy, missingExact, missingFlexible, missingItemIds, slotTargets);
		}

		String compactMissingSummary = summarizeMissing(missingExact, missingFlexible);
		return new PlanResult(
			Map.copyOf(missingExact),
			Map.copyOf(missingFlexible),
			List.copyOf(missingItemIds),
			List.copyOf(slotTargets),
			compactMissingSummary,
			!missingExact.isEmpty() || !missingFlexible.isEmpty()
		);
	}

	public static int computeMaxCraftCopies(
		RecipeIngredientSummary ingredientSummary,
		Map<String, Integer> inventoryCounts,
		List<ItemStack> gridStacks,
		Map<String, Integer> usableCounts,
		Map<String, Integer> preferenceTotals,
		Policy policy
	) {
		int upperBound = minSlotMaxStackSize(ingredientSummary);
		if (upperBound <= 0) {
			return 0;
		}

		int low = 0;
		int high = upperBound;
		while (low < high) {
			int mid = (low + high + 1) / 2;
			PlanResult plan = plan(ingredientSummary, inventoryCounts, gridStacks, usableCounts, preferenceTotals, mid, policy);
			if (!plan.hasMissingIngredients()) {
				low = mid;
			} else {
				high = mid - 1;
			}
		}
		return low;
	}

	public static Policy defaultPolicy() {
		return new Policy(CountPreference.HIGHEST_TOTAL, false);
	}

	private static void planGroup(
		List<SlotState> groupSlots,
		Map<String, Integer> inventoryCounts,
		Map<String, Integer> remainingUsable,
		Map<String, Integer> preferenceTotals,
		int copiesPerSlot,
		Policy policy,
		Map<String, Integer> missingExact,
		Map<String, Integer> missingFlexible,
		List<String> missingItemIds,
		List<SlotTarget> slotTargets
	) {
		if (groupSlots.isEmpty()) {
			return;
		}
		if (groupSlots.getFirst().ingredientSlot().isExact()) {
			planExactGroup(groupSlots, remainingUsable, copiesPerSlot, missingExact, missingItemIds, slotTargets);
			return;
		}

		List<SlotState> emptySlots = new ArrayList<>();
		LinkedHashSet<String> committedVariants = new LinkedHashSet<>();

		for (SlotState slotState : groupSlots) {
			if (slotState.currentItemId() == null) {
				emptySlots.add(slotState);
				continue;
			}

			committedVariants.add(slotState.currentItemId());
			int currentCount = slotState.currentCount();
			int targetCount = Math.max(currentCount, copiesPerSlot);
			int missingCount = Math.max(copiesPerSlot - currentCount, 0);
			if (missingCount > 0) {
				consumeOrMarkMissing(slotState.currentItemId(), missingCount, remainingUsable, missingExact, missingItemIds);
			}
			slotTargets.add(new SlotTarget(slotState.slotIndex(), slotState.ingredientSlot(), slotState.currentItemId(), targetCount));
		}

		if (emptySlots.isEmpty() || copiesPerSlot <= 0) {
			return;
		}

		List<String> orderedVariants = orderVariants(
			groupSlots.getFirst().ingredientSlot().itemIds(),
			committedVariants,
			inventoryCounts,
			preferenceTotals,
			policy
		);
		if (orderedVariants.isEmpty()) {
			recordFlexibleMissing(groupSlots.getFirst().ingredientSlot().display(), copiesPerSlot, emptySlots.size(), missingFlexible);
			return;
		}

		String singleVariant = findSingleVariantForAllSlots(orderedVariants, remainingUsable, copiesPerSlot, emptySlots.size());
		if (singleVariant != null) {
			assignSlots(emptySlots, singleVariant, copiesPerSlot, remainingUsable, slotTargets);
			return;
		}

		List<SlotState> unassigned = new ArrayList<>(emptySlots);
		for (String variant : orderedVariants) {
			int capacity = remainingUsable.getOrDefault(variant, 0) / copiesPerSlot;
			while (capacity > 0 && !unassigned.isEmpty()) {
				SlotState slotState = unassigned.removeFirst();
				assignSlot(slotState, variant, copiesPerSlot, remainingUsable, slotTargets);
				capacity--;
			}
			if (unassigned.isEmpty()) {
				return;
			}
		}

		if (!unassigned.isEmpty()) {
			recordFlexibleMissing(groupSlots.getFirst().ingredientSlot().display(), copiesPerSlot, unassigned.size(), missingFlexible);
		}
	}

	private static void planExactGroup(
		List<SlotState> groupSlots,
		Map<String, Integer> remainingUsable,
		int copiesPerSlot,
		Map<String, Integer> missingExact,
		List<String> missingItemIds,
		List<SlotTarget> slotTargets
	) {
		String itemId = groupSlots.getFirst().ingredientSlot().itemIds().getFirst();
		for (SlotState slotState : groupSlots) {
			int currentCount = slotState.currentItemId() == null ? 0 : slotState.currentCount();
			int targetCount = Math.max(currentCount, copiesPerSlot);
			int missingCount = Math.max(copiesPerSlot - currentCount, 0);
			if (missingCount > 0) {
				consumeOrMarkMissing(itemId, missingCount, remainingUsable, missingExact, missingItemIds);
			}
			slotTargets.add(new SlotTarget(slotState.slotIndex(), slotState.ingredientSlot(), itemId, targetCount));
		}
	}

	private static Map<GroupKey, List<SlotState>> groupSlots(RecipeIngredientSummary ingredientSummary, List<ItemStack> gridStacks) {
		Map<GroupKey, List<SlotState>> groupedSlots = new LinkedHashMap<>();
		List<RecipeIngredientSummary.IngredientSlot> slots = ingredientSummary.slots();
		for (int index = 0; index < slots.size(); index++) {
			RecipeIngredientSummary.IngredientSlot ingredientSlot = slots.get(index);
			if (ingredientSlot.isEmpty()) {
				continue;
			}

			ItemStack gridStack = index < gridStacks.size() ? gridStacks.get(index) : ItemStack.EMPTY;
			String currentItemId = null;
			int currentCount = 0;
			if (!gridStack.isEmpty()) {
				String gridItemId = BuiltInRegistries.ITEM.getKey(gridStack.getItem()).toString();
				if (ingredientSlot.itemIds().contains(gridItemId)) {
					currentItemId = gridItemId;
					currentCount = gridStack.getCount();
				}
			}

			GroupKey groupKey = new GroupKey(ingredientSlot.itemIds());
			groupedSlots.computeIfAbsent(groupKey, ignored -> new ArrayList<>())
				.add(new SlotState(index + 1, ingredientSlot, currentItemId, currentCount));
		}
		return groupedSlots;
	}

	private static List<ItemStack> normalizeGridStacksForPlanning(RecipeIngredientSummary ingredientSummary, List<ItemStack> gridStacks) {
		if (ingredientSummary.slots().size() == gridStacks.size()) {
			return gridStacks;
		}

		List<ItemStack> occupiedStacks = new ArrayList<>();
		for (ItemStack stack : gridStacks) {
			if (!stack.isEmpty()) {
				occupiedStacks.add(stack);
			}
		}
		return occupiedStacks;
	}

	private static List<String> orderVariants(
		List<String> acceptedVariants,
		LinkedHashSet<String> committedVariants,
		Map<String, Integer> inventoryCounts,
		Map<String, Integer> preferenceTotals,
		Policy policy
	) {
		LinkedHashSet<String> ordered = new LinkedHashSet<>();
		ordered.addAll(committedVariants);

		acceptedVariants.stream()
			.filter(itemId -> inventoryCounts.getOrDefault(itemId, 0) > 0)
			.sorted(compareByPreference(preferenceTotals, policy))
			.forEach(ordered::add);

		acceptedVariants.stream()
			.sorted(compareByPreference(preferenceTotals, policy))
			.forEach(ordered::add);

		return List.copyOf(ordered);
	}

	private static Comparator<String> compareByPreference(Map<String, Integer> preferenceTotals, Policy policy) {
		Comparator<String> byCount = Comparator.comparingInt((String itemId) -> preferenceTotals.getOrDefault(itemId, 0));
		if (policy.countPreference() == CountPreference.HIGHEST_TOTAL) {
			byCount = byCount.reversed();
		}
		return byCount.thenComparing(Comparator.naturalOrder());
	}

	private static String findSingleVariantForAllSlots(
		List<String> orderedVariants,
		Map<String, Integer> remainingUsable,
		int copiesPerSlot,
		int slotCount
	) {
		int totalNeeded = copiesPerSlot * slotCount;
		for (String variant : orderedVariants) {
			if (remainingUsable.getOrDefault(variant, 0) >= totalNeeded) {
				return variant;
			}
		}
		return null;
	}

	private static void assignSlots(
		List<SlotState> slots,
		String variant,
		int copiesPerSlot,
		Map<String, Integer> remainingUsable,
		List<SlotTarget> slotTargets
	) {
		for (SlotState slotState : slots) {
			assignSlot(slotState, variant, copiesPerSlot, remainingUsable, slotTargets);
		}
	}

	private static void assignSlot(
		SlotState slotState,
		String variant,
		int copiesPerSlot,
		Map<String, Integer> remainingUsable,
		List<SlotTarget> slotTargets
	) {
		consume(remainingUsable, variant, copiesPerSlot);
		slotTargets.add(new SlotTarget(slotState.slotIndex(), slotState.ingredientSlot(), variant, copiesPerSlot));
	}

	private static void consumeOrMarkMissing(
		String itemId,
		int count,
		Map<String, Integer> remainingUsable,
		Map<String, Integer> missingExact,
		List<String> missingItemIds
	) {
		int available = remainingUsable.getOrDefault(itemId, 0);
		int consumed = Math.min(available, count);
		if (consumed > 0) {
			consume(remainingUsable, itemId, consumed);
		}
		int missing = count - consumed;
		if (missing <= 0) {
			return;
		}
		missingExact.merge(itemId, missing, Integer::sum);
		for (int i = 0; i < missing; i++) {
			missingItemIds.add(itemId);
		}
	}

	private static void recordFlexibleMissing(String display, int copiesPerSlot, int slotCount, Map<String, Integer> missingFlexible) {
		missingFlexible.merge(display, copiesPerSlot * slotCount, Integer::sum);
	}

	private static void consume(Map<String, Integer> remainingUsable, String itemId, int count) {
		int available = remainingUsable.getOrDefault(itemId, 0);
		if (available <= count) {
			remainingUsable.remove(itemId);
			return;
		}
		remainingUsable.put(itemId, available - count);
	}

	private static int minSlotMaxStackSize(RecipeIngredientSummary ingredientSummary) {
		int minStackSize = Integer.MAX_VALUE;
		for (RecipeIngredientSummary.IngredientSlot slot : ingredientSummary.slots()) {
			if (slot.isEmpty()) {
				continue;
			}
			minStackSize = Math.min(minStackSize, Math.max(slot.maxStackSize(), 1));
		}
		return minStackSize == Integer.MAX_VALUE ? 0 : minStackSize;
	}

	private static String summarizeMissing(Map<String, Integer> missingExact, Map<String, Integer> missingFlexible) {
		List<String> missingParts = new ArrayList<>();
		missingExact.forEach((itemId, count) -> missingParts.add(count + "x " + itemId));
		missingFlexible.forEach((display, count) -> missingParts.add(count + "x any of " + display));
		StringJoiner joiner = new StringJoiner(", ");
		missingParts.forEach(joiner::add);
		return joiner.length() == 0 ? "<none>" : joiner.toString();
	}

	public record Policy(CountPreference countPreference, boolean redistributeToCraftWhenNeeded) {
		public Policy {
			Objects.requireNonNull(countPreference, "countPreference");
		}
	}

	public enum CountPreference {
		HIGHEST_TOTAL,
		LOWEST_TOTAL
	}

	public record PlanResult(
		Map<String, Integer> exactMissingCounts,
		Map<String, Integer> flexibleMissingCounts,
		List<String> missingItemIds,
		List<SlotTarget> slotTargets,
		String compactMissingSummary,
		boolean hasMissingIngredients
	) {
		public boolean hasUnresolvedFlexibleMissing() {
			return !flexibleMissingCounts.isEmpty();
		}
	}

	public record SlotTarget(int slotIndex, RecipeIngredientSummary.IngredientSlot ingredientSlot, String itemId, int targetCount) {
	}

	private record GroupKey(List<String> itemIds) {
		private GroupKey {
			itemIds = List.copyOf(itemIds);
		}
	}

	private record SlotState(int slotIndex, RecipeIngredientSummary.IngredientSlot ingredientSlot, String currentItemId, int currentCount) {
	}
}
