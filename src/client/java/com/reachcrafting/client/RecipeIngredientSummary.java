package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

public record RecipeIngredientSummary(List<IngredientSlot> slots, List<String> rawSlots, String compactSummary) {
	public static RecipeIngredientSummary fromDisplay(Object display, ContextMap context) {
		List<SlotDisplay> ingredients = extractIngredients(display);
		List<IngredientSlot> slots = new ArrayList<>();
		List<String> rawSlots = new ArrayList<>();
		Map<String, Integer> aggregate = new LinkedHashMap<>();

		for (SlotDisplay ingredient : ingredients) {
			IngredientSlot slot = describeSlot(ingredient.resolveForStacks(context));
			slots.add(slot);
			rawSlots.add(slot.display());
			if (!slot.isEmpty()) {
				aggregate.merge(slot.display(), 1, Integer::sum);
			}
		}

		StringJoiner compact = new StringJoiner(", ");
		for (Map.Entry<String, Integer> entry : aggregate.entrySet()) {
			compact.add(entry.getValue() + "x " + entry.getKey());
		}

		String compactSummary = compact.length() == 0 ? "<no ingredients>" : compact.toString();
		return new RecipeIngredientSummary(List.copyOf(slots), List.copyOf(rawSlots), compactSummary);
	}

	public Set<String> acceptedItemIds() {
		Set<String> acceptedItemIds = new LinkedHashSet<>();
		for (IngredientSlot slot : slots) {
			acceptedItemIds.addAll(slot.itemIds());
		}
		return Set.copyOf(acceptedItemIds);
	}

	public int estimateRequiredInventorySlots(int craftCopies) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<String, Integer> stackLimits = new LinkedHashMap<>();
		for (IngredientSlot slot : slots) {
			if (slot.isEmpty()) continue;
			counts.put(slot.display(), counts.getOrDefault(slot.display(), 0) + 1);
			stackLimits.put(slot.display(), slot.maxStackSize());
		}

		int requiredSlots = 0;
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			int countPerCraft = entry.getValue();
			int maxStackSize = stackLimits.get(entry.getKey());
			int totalItems = countPerCraft * craftCopies;
			requiredSlots += (totalItems + maxStackSize - 1) / maxStackSize;
		}
		return requiredSlots;
	}

	private static List<SlotDisplay> extractIngredients(Object display) {
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			return shaped.ingredients();
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return shapeless.ingredients();
		}

		return List.of();
	}

	private static IngredientSlot describeSlot(List<ItemStack> resolvedStacks) {
		List<String> itemIds = resolvedStacks.stream()
			.filter(stack -> !stack.isEmpty())
			.map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
			.distinct()
			.sorted(Comparator.naturalOrder())
			.toList();
		int stackLimit = resolvedStacks.stream()
			.filter(stack -> !stack.isEmpty())
			.mapToInt(ItemStack::getMaxStackSize)
			.min()
			.orElse(64);

		if (itemIds.isEmpty()) {
			return new IngredientSlot(List.of(), "<empty>", 0);
		}
		if (itemIds.size() == 1) {
			return new IngredientSlot(itemIds, itemIds.get(0), stackLimit);
		}

		return new IngredientSlot(itemIds, summarizeAlternatives(itemIds), stackLimit);
	}

	private static String summarizeAlternatives(List<String> itemIds) {
		String family = commonVariantFamily(itemIds);
		if (family != null) {
			return family;
		}

		StringJoiner joiner = new StringJoiner(" | ", "[", "]");
		itemIds.stream()
			.filter(Objects::nonNull)
			.forEach(joiner::add);
		return joiner.toString();
	}

	private static String commonVariantFamily(List<String> itemIds) {
		if (itemIds.size() < 4) {
			return null;
		}

		String namespace = null;
		String suffix = null;
		for (String itemId : itemIds) {
			if (itemId == null) {
				return null;
			}
			int separator = itemId.indexOf(':');
			if (separator <= 0 || separator >= itemId.length() - 1) {
				return null;
			}
			String itemNamespace = itemId.substring(0, separator);
			String path = itemId.substring(separator + 1);
			int underscore = path.lastIndexOf('_');
			if (underscore <= 0 || underscore >= path.length() - 1) {
				return null;
			}
			String itemSuffix = path.substring(underscore + 1);
			if (namespace == null) {
				namespace = itemNamespace;
				suffix = itemSuffix;
				continue;
			}
			if (!namespace.equals(itemNamespace) || !suffix.equals(itemSuffix)) {
				return null;
			}
		}

		return namespace + ":" + suffix;
	}

	public record IngredientSlot(List<String> itemIds, String display, int maxStackSize) {
		public IngredientSlot {
			itemIds = List.copyOf(itemIds);
		}

		public boolean isEmpty() {
			return itemIds.isEmpty();
		}

		public boolean isExact() {
			return itemIds.size() == 1;
		}

		public int copiesForPlacement(boolean craftAll) {
			if (!craftAll) {
				return 1;
			}
			return Math.max(maxStackSize, 1);
		}
	}
}
