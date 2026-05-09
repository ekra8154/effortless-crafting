package com.reachcrafting.client;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public record AvailableItemSnapshot(
	Map<String, Integer> inventoryCounts,
	Map<String, Integer> gridCounts,
	Map<String, Integer> totalCounts,
	List<ItemStack> gridStacks
) {
	public static AvailableItemSnapshot capture(LocalPlayer player, Screen screen) {
		Map<String, Integer> inventoryCounts = new LinkedHashMap<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			addStack(inventoryCounts, stack);
		}

		Map<String, Integer> gridCounts = new LinkedHashMap<>();
		List<ItemStack> gridStacks = new ArrayList<>();
		if (screen instanceof AbstractContainerScreen<?> containerScreen) {
			AbstractContainerMenu menu = containerScreen.getMenu();
			int gridSlotCount = screen instanceof InventoryScreen ? 4 : screen instanceof CraftingScreen ? 9 : 0;
			for (int slotIndex = 1; slotIndex <= gridSlotCount; slotIndex++) {
				ItemStack gridStack = menu.getSlot(slotIndex).getItem();
				addStack(gridCounts, gridStack);
				gridStacks.add(gridStack.copy());
			}
		}

		Map<String, Integer> totalCounts = new LinkedHashMap<>(inventoryCounts);
		for (Map.Entry<String, Integer> entry : gridCounts.entrySet()) {
			totalCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
		}

		return new AvailableItemSnapshot(
			Map.copyOf(inventoryCounts),
			Map.copyOf(gridCounts),
			Map.copyOf(totalCounts),
			List.copyOf(gridStacks)
		);
	}

	public String inventorySummary() {
		return formatCounts(inventoryCounts);
	}

	public String gridSummary() {
		return formatCounts(gridCounts);
	}

	public boolean hasReservedGrid() {
		return gridStacks.stream().anyMatch(stack -> !stack.isEmpty());
	}

	public static Map<String, Integer> mergeCounts(Map<String, Integer> baseCounts, Map<String, Integer> extraCounts) {
		Map<String, Integer> merged = new LinkedHashMap<>(baseCounts);
		for (Map.Entry<String, Integer> entry : extraCounts.entrySet()) {
			merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
		}
		return Map.copyOf(merged);
	}

	private static void addStack(Map<String, Integer> counts, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}

		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		counts.merge(itemId, stack.getCount(), Integer::sum);
	}

	public static String formatCounts(Map<String, Integer> counts) {
		if (counts.isEmpty()) {
			return "<empty>";
		}

		StringJoiner joiner = new StringJoiner(", ");
		counts.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> joiner.add(entry.getValue() + "x " + ContainerUtils.getItemName(entry.getKey())));
		return joiner.toString();
	}

	public static String formatInventorySlots(LocalPlayer player) {
		return formatInventorySlots(player, null);
	}

	public static String formatInventorySlots(LocalPlayer player, Set<String> itemFilter) {
		if (player == null) {
			return "<no-player>";
		}

		StringJoiner joiner = new StringJoiner(", ");
		List<ItemStack> items = player.getInventory().getNonEquipmentItems();
		for (int i = 0; i < items.size(); i++) {
			ItemStack stack = items.get(i);
			if (stack.isEmpty()) {
				continue;
			}

			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if (itemFilter != null && !itemFilter.isEmpty() && !itemFilter.contains(itemId)) {
				continue;
			}

			joiner.add("inv" + i + "=" + stack.getCount() + "x " + itemId);
		}
		String formatted = joiner.toString();
		return formatted.isEmpty() ? "<empty>" : formatted;
	}
}
