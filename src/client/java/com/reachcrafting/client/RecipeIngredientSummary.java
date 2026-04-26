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

		if (itemIds.isEmpty()) {
			return new IngredientSlot(List.of(), "<empty>");
		}
		if (itemIds.size() == 1) {
			return new IngredientSlot(itemIds, itemIds.get(0));
		}

		StringJoiner joiner = new StringJoiner(" | ", "[", "]");
		itemIds.stream()
			.filter(Objects::nonNull)
			.forEach(joiner::add);
		return new IngredientSlot(itemIds, joiner.toString());
	}

	public record IngredientSlot(List<String> itemIds, String display) {
		public IngredientSlot {
			itemIds = List.copyOf(itemIds);
		}

		public boolean isEmpty() {
			return itemIds.isEmpty();
		}

		public boolean isExact() {
			return itemIds.size() == 1;
		}
	}
}
