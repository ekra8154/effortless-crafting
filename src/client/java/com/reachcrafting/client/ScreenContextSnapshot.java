package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

record ScreenContextSnapshot(
	ScreenKind kind,
	BlockPos craftingTablePos,
	RecipeBookSnapshot recipeBookState,
	List<ItemStack> gridStacks,
	double mouseX,
	double mouseY
) {
	static ScreenContextSnapshot capture(Minecraft client, Entity cameraEntity, double reachDistance, AvailableItemSnapshot localItems) {
		if (client.screen instanceof CraftingScreen craftingScreen) {
			return new ScreenContextSnapshot(
				ScreenKind.CRAFTING_TABLE_3X3,
				BaseCraftSession.findNearestCraftingTable(client.level, cameraEntity, reachDistance),
				RecipeBookSnapshot.capture(craftingScreen),
				copyStacks(localItems.gridStacks()),
				client.mouseHandler.xpos(),
				client.mouseHandler.ypos()
			);
		}
		if (client.screen instanceof InventoryScreen inventoryScreen) {
			return new ScreenContextSnapshot(
				ScreenKind.INVENTORY_2X2,
				null,
				RecipeBookSnapshot.capture(inventoryScreen),
				copyStacks(localItems.gridStacks()),
				client.mouseHandler.xpos(),
				client.mouseHandler.ypos()
			);
		}
		return new ScreenContextSnapshot(ScreenKind.NONE, null, RecipeBookSnapshot.empty(), List.of(), client.mouseHandler.xpos(), client.mouseHandler.ypos());
	}

	boolean hasReservedGrid() {
		return gridStacks.stream().anyMatch(stack -> !stack.isEmpty());
	}

	ScreenContextSnapshot withClearedGrid() {
		List<ItemStack> cleared = new ArrayList<>(gridStacks.size());
		for (int i = 0; i < gridStacks.size(); i++) {
			cleared.add(ItemStack.EMPTY);
		}
		return new ScreenContextSnapshot(kind, craftingTablePos, recipeBookState, List.copyOf(cleared), mouseX, mouseY);
	}

	boolean reservedGridMatches(RecipeIngredientSummary ingredientSummary) {
		List<RecipeIngredientSummary.IngredientSlot> ingredientSlots = ingredientSummary.slots();
		List<ItemStack> occupiedGridStacks = gridStacks.stream().filter(stack -> !stack.isEmpty()).toList();
		List<RecipeIngredientSummary.IngredientSlot> nonEmptyIngredientSlots = ingredientSlots.stream().filter(slot -> !slot.isEmpty()).toList();

		if (occupiedGridStacks.size() != nonEmptyIngredientSlots.size()) {
			return false;
		}

		if (ingredientSlots.size() == gridStacks.size()) {
			for (int i = 0; i < gridStacks.size(); i++) {
				ItemStack stack = gridStacks.get(i);
				RecipeIngredientSummary.IngredientSlot ingredientSlot = ingredientSlots.get(i);
				if (stack.isEmpty()) {
					if (!ingredientSlot.isEmpty()) {
						return false;
					}
					continue;
				}
				if (ingredientSlot.isEmpty()) {
					return false;
				}

				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				if (!ingredientSlot.itemIds().contains(itemId)) {
					return false;
				}
			}
			return true;
		}

		List<RecipeIngredientSummary.IngredientSlot> unmatchedSlots = new ArrayList<>(nonEmptyIngredientSlots);
		for (ItemStack stack : occupiedGridStacks) {
			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			boolean matched = false;
			Iterator<RecipeIngredientSummary.IngredientSlot> iterator = unmatchedSlots.iterator();
			while (iterator.hasNext()) {
				RecipeIngredientSummary.IngredientSlot slot = iterator.next();
				if (slot.itemIds().contains(itemId)) {
					iterator.remove();
					matched = true;
					break;
				}
			}

			if (!matched) {
				return false;
			}
		}

		return unmatchedSlots.isEmpty();
	}

	boolean reservedGridMatchesCollection(Level level, net.minecraft.client.gui.screens.recipebook.RecipeCollection recipeCollection) {
		if (recipeCollection == null || level == null) {
			return false;
		}

		ContextMap context = SlotDisplayContext.fromLevel(level);
		for (var entry : recipeCollection.getRecipes()) {
			if (reservedGridMatches(RecipeIngredientSummary.fromDisplay(entry.display(), context))) {
				return true;
			}
		}
		return false;
	}

	String gridSummary() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (ItemStack stack : gridStacks) {
			if (!stack.isEmpty()) {
				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				counts.merge(itemId, stack.getCount(), Integer::sum);
			}
		}
		return AvailableItemSnapshot.formatCounts(counts);
	}

	private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
		List<ItemStack> copies = new ArrayList<>(stacks.size());
		for (ItemStack stack : stacks) {
			copies.add(stack.copy());
		}
		return List.copyOf(copies);
	}
}
