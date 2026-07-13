package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side grid placement for self-referential chain steps (recipes whose
 * accepted inputs include their own output — the re-dye family: dyed
 * bundles, shulker boxes, beds, wool). handlePlaceRecipe lets the SERVER
 * choose the ingredients, and no vanilla packet can exclude the freshly
 * crafted output or the player's intentionally dyed variants from that
 * choice; the only way to honor the plan is to place its exact chosen
 * inputs ourselves with ordinary container clicks. Sources are restricted
 * to PRISTINE stacks (no components beyond the item default) so filled
 * bundles or shulker boxes in use as storage are never consumed.
 */
final class ManualRecipePlacer {
	private ManualRecipePlacer() {
	}

	/**
	 * Stages up to maxCopies crafts of the step's planned inputs into the
	 * crafting grid. Returns the number of copies actually staged; 0 means
	 * nothing was placed (busy cursor, occupied grid, or no pristine inputs).
	 */
	static int placeStepCrafts(Minecraft client, ChainCraftPlan.Step step, int maxCopies) {
		LocalPlayer player = client.player;
		if (player == null || client.gameMode == null || maxCopies <= 0) {
			return 0;
		}
		AbstractContainerMenu menu = player.containerMenu;
		int gridSlotCount = gridSlotCount(menu);
		if (gridSlotCount == 0 || !menu.getCarried().isEmpty()) {
			return 0;
		}
		for (int i = 1; i <= gridSlotCount; i++) {
			if (menu.getSlot(i).hasItem()) {
				ReachCraftingMod.LOGGER.info("[manual_place] grid_not_empty slot={}", i);
				return 0;
			}
		}

		List<String> slotItemIds = resolvePerSlotInputs(step);
		if (slotItemIds.stream().allMatch(java.util.Objects::isNull) || slotItemIds.size() > gridSlotCount) {
			return 0;
		}

		Map<String, Integer> slotsPerItem = new LinkedHashMap<>();
		for (String itemId : slotItemIds) {
			if (itemId != null) {
				slotsPerItem.merge(itemId, 1, Integer::sum);
			}
		}
		int copies = maxCopies;
		for (Map.Entry<String, Integer> entry : slotsPerItem.entrySet()) {
			copies = Math.min(copies, maxStackSizeFor(entry.getKey()));
			copies = Math.min(copies, countPristine(menu, gridSlotCount, entry.getKey()) / entry.getValue());
		}
		if (copies <= 0) {
			ReachCraftingMod.LOGGER.info(
				"[manual_place] no_pristine_inputs output={} needed={}",
				ContainerUtils.formatStack(step.displayStack()),
				slotsPerItem
			);
			return 0;
		}

		// Slot index maps positionally onto the grid (recipe displays list
		// slots row-major, INCLUDING empties for shaped recipes), so shaped
		// self-referential recipes like smithing-template duplication keep
		// their pattern. Shapeless displays carry no empties, so this
		// degrades to sequential filling for them.
		for (int slotIndex = 0; slotIndex < slotItemIds.size(); slotIndex++) {
			String slotItemId = slotItemIds.get(slotIndex);
			if (slotItemId == null) {
				continue;
			}
			int gridIndex = 1 + slotIndex;
			if (!moveIntoGridSlot(client, menu, gridSlotCount, slotItemId, copies, gridIndex)) {
				// Partial placements are left for the step-failure grid flush.
				ReachCraftingMod.LOGGER.warn("[manual_place] move_failed item={} grid_slot={}", slotItemId, gridIndex);
				return 0;
			}
		}
		ReachCraftingMod.LOGGER.info(
			"[manual_place] staged output={} copies={} slots={}",
			ContainerUtils.formatStack(step.displayStack()),
			copies,
			slotItemIds
		);
		return copies;
	}

	/**
	 * Reconstructs the plan's per-slot input choice from the step's
	 * aggregated required inputs: each non-empty ingredient slot consumed
	 * exactly one item per craft, so allocating the per-craft counts across
	 * the slots recovers which item the planner chose for each. The returned
	 * list is positional — empty ingredient slots yield null entries so
	 * shaped patterns survive.
	 */
	private static List<String> resolvePerSlotInputs(ChainCraftPlan.Step step) {
		int stepCopies = Math.max(step.recipeCopies(), 1);
		Map<String, Integer> perCraftRemaining = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : step.requiredInputs().entrySet()) {
			perCraftRemaining.put(entry.getKey(), Math.max(entry.getValue() / stepCopies, 1));
		}
		List<String> slotItemIds = new ArrayList<>();
		for (RecipeIngredientSummary.IngredientSlot slot : step.ingredientSummary().slots()) {
			if (slot.isEmpty()) {
				slotItemIds.add(null);
				continue;
			}
			String chosen = null;
			for (String itemId : slot.itemIds()) {
				if (perCraftRemaining.getOrDefault(itemId, 0) > 0) {
					chosen = itemId;
					break;
				}
			}
			if (chosen == null) {
				return List.of();
			}
			perCraftRemaining.merge(chosen, -1, Integer::sum);
			slotItemIds.add(chosen);
		}
		return slotItemIds;
	}

	private static boolean moveIntoGridSlot(
		Minecraft client,
		AbstractContainerMenu menu,
		int gridSlotCount,
		String itemId,
		int count,
		int gridSlotIndex
	) {
		int placed = 0;
		int attempts = 0;
		while (placed < count) {
			if (++attempts > count + 4) {
				return false;
			}
			int sourceIndex = findPristineSource(menu, gridSlotCount, itemId);
			if (sourceIndex == -1) {
				return false;
			}
			client.gameMode.handleInventoryMouseClick(menu.containerId, sourceIndex, 0, ClickType.PICKUP, client.player);
			ItemStack carried = menu.getCarried();
			if (carried.isEmpty()) {
				return false;
			}
			int needed = count - placed;
			if (carried.getCount() <= needed) {
				// Left-click places the whole carried stack. This is also the
				// ONLY safe gesture for items with special container behavior:
				// right-clicking with a bundle on the cursor is the bundle
				// dump/insert gesture and silently no-ops on an empty slot.
				client.gameMode.handleInventoryMouseClick(menu.containerId, gridSlotIndex, 0, ClickType.PICKUP, client.player);
			} else {
				// Oversized normal stack (e.g. a stack of dye when one is
				// needed): right-click drops one item per click, then the
				// remainder goes back to its source slot.
				for (int i = 0; i < needed; i++) {
					client.gameMode.handleInventoryMouseClick(menu.containerId, gridSlotIndex, 1, ClickType.PICKUP, client.player);
				}
				if (!menu.getCarried().isEmpty()) {
					client.gameMode.handleInventoryMouseClick(menu.containerId, sourceIndex, 0, ClickType.PICKUP, client.player);
				}
			}
			// The client applies the same vanilla click logic the server
			// will, so verifying against the local grid catches any gesture
			// that did not actually place (and avoids spinning forever).
			int nowPlaced = countInGridSlot(menu, gridSlotIndex, itemId);
			if (nowPlaced <= placed) {
				return false;
			}
			placed = nowPlaced;
			if (placed < count && !menu.getCarried().isEmpty()) {
				return false;
			}
		}
		return menu.getCarried().isEmpty();
	}

	private static int countInGridSlot(AbstractContainerMenu menu, int gridSlotIndex, String itemId) {
		ItemStack stack = menu.getSlot(gridSlotIndex).getItem();
		if (stack.isEmpty() || !itemId.equals(itemIdOf(stack))) {
			return 0;
		}
		return stack.getCount();
	}

	private static int findPristineSource(AbstractContainerMenu menu, int gridSlotCount, String itemId) {
		for (int index = firstSourceIndex(menu); index <= lastSourceIndex(menu); index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (!stack.isEmpty() && itemId.equals(itemIdOf(stack)) && isPristine(stack)) {
				return index;
			}
		}
		return -1;
	}

	private static int countPristine(AbstractContainerMenu menu, int gridSlotCount, String itemId) {
		int total = 0;
		for (int index = firstSourceIndex(menu); index <= lastSourceIndex(menu); index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (!stack.isEmpty() && itemId.equals(itemIdOf(stack)) && isPristine(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** No component patch beyond the item default — not a filled bundle or shulker, not renamed. */
	static boolean isPristine(ItemStack stack) {
		return ItemStack.isSameItemSameComponents(stack, stack.getItem().getDefaultInstance());
	}

	private static String itemIdOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static int maxStackSizeFor(String itemId) {
		try {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
			return item == null ? 1 : Math.max(item.getDefaultInstance().getMaxStackSize(), 1);
		} catch (Exception ignored) {
			return 1;
		}
	}

	private static int gridSlotCount(AbstractContainerMenu menu) {
		if (menu instanceof CraftingMenu) {
			return 9;
		}
		if (menu instanceof InventoryMenu) {
			return 4;
		}
		return 0;
	}

	// CraftingMenu: 0 result, 1-9 grid, 10-45 player inventory.
	// InventoryMenu: 0 result, 1-4 grid, 5-8 armor, 9-44 main+hotbar, 45 offhand.
	private static int firstSourceIndex(AbstractContainerMenu menu) {
		return menu instanceof CraftingMenu ? 10 : 9;
	}

	private static int lastSourceIndex(AbstractContainerMenu menu) {
		return menu instanceof CraftingMenu ? 45 : 44;
	}
}
