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
import net.minecraft.world.inventory.ContainerInput;
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
		List<String> slotItemIds = resolvePerSlotInputs(step);
		// Self-referential steps must never consume the player's dyed or
		// filled variants, so sources stay restricted to pristine stacks.
		return placeCrafts(
			client,
			step.ingredientSummary(),
			slotItemIds,
			maxCopies,
			true,
			ContainerUtils.formatStack(step.displayStack())
		);
	}

	/**
	 * Stages up to maxCopies crafts of an explicit per-slot input choice into
	 * the crafting grid using ordinary container clicks. slotItemIds is
	 * positional over the summary's ingredient slots (null = empty slot).
	 * Returns the number of copies actually staged; 0 means nothing was placed
	 * and the caller must fall back to its normal placement path.
	 *
	 * <p>Clicks are the cheaper currency here: servers ration place_recipe hard
	 * (Paper's default drops above 5/s) while container clicks run against the
	 * far larger general budget, so staging N copies by clicking costs a
	 * fraction of what N single placement packets do - and unlike a placement,
	 * it can hit an exact sub-grid count without overstaging. Cheaper is not
	 * free: every click is recorded with GridTopUp's governor (280 per 7s,
	 * under Paper's all-packets kick limit), and callers staging large counts
	 * should clear the estimate with clickBudgetAllows first.
	 */
	static int placeCrafts(
		Minecraft client,
		RecipeIngredientSummary summary,
		List<String> slotItemIds,
		int maxCopies,
		boolean pristineOnly,
		String label
	) {
		LocalPlayer player = client.player;
		if (player == null || client.gameMode == null || maxCopies <= 0 || summary == null) {
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

		if (slotItemIds.isEmpty() || slotItemIds.stream().allMatch(java.util.Objects::isNull)) {
			return 0;
		}
		// Ingredient slots are row-major within the recipe's OWN bounding box,
		// so a narrower shaped recipe has to be offset onto the real grid
		// (2x2 in a 3x3 menu -> slots 1,2,4,5). gridSlotIndices owns that map.
		List<Integer> gridIndices = summary.gridSlotIndices(gridSlotCount);
		if (gridIndices.size() != slotItemIds.size()) {
			ReachCraftingMod.LOGGER.info(
				"[manual_place] shape_unmappable output={} slots={} mapped={} grid={}",
				label, slotItemIds.size(), gridIndices.size(), gridSlotCount
			);
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
			copies = Math.min(copies, countSources(menu, entry.getKey(), pristineOnly) / entry.getValue());
		}
		if (copies <= 0) {
			ReachCraftingMod.LOGGER.info(
				"[manual_place] no_inputs output={} needed={} pristine_only={}",
				label, slotsPerItem, pristineOnly
			);
			return 0;
		}

		for (int slotIndex = 0; slotIndex < slotItemIds.size(); slotIndex++) {
			String slotItemId = slotItemIds.get(slotIndex);
			if (slotItemId == null) {
				continue;
			}
			int gridIndex = gridIndices.get(slotIndex);
			if (!moveIntoGridSlot(client, menu, slotItemId, copies, gridIndex, pristineOnly)) {
				// Partial placements are left for the caller's grid flush.
				ReachCraftingMod.LOGGER.warn("[manual_place] move_failed item={} grid_slot={}", slotItemId, gridIndex);
				return 0;
			}
		}
		ReachCraftingMod.LOGGER.info(
			"[manual_place] staged output={} copies={} slots={} grid_slots={}",
			label,
			copies,
			slotItemIds,
			gridIndices
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
		String itemId,
		int count,
		int gridSlotIndex,
		boolean pristineOnly
	) {
		int placed = 0;
		int attempts = 0;
		while (placed < count) {
			if (++attempts > count + 4) {
				return false;
			}
			int sourceIndex = findSource(menu, itemId, pristineOnly);
			if (sourceIndex == -1) {
				return false;
			}
			client.gameMode.handleContainerInput(menu.containerId, sourceIndex, 0, ContainerInput.PICKUP, client.player);
			GridTopUp.recordClick();
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
				client.gameMode.handleContainerInput(menu.containerId, gridSlotIndex, 0, ContainerInput.PICKUP, client.player);
				GridTopUp.recordClick();
			} else {
				// Oversized normal stack (e.g. a stack of dye when one is
				// needed): right-click drops one item per click, then the
				// remainder goes back to its source slot.
				for (int i = 0; i < needed; i++) {
					client.gameMode.handleContainerInput(menu.containerId, gridSlotIndex, 1, ContainerInput.PICKUP, client.player);
					GridTopUp.recordClick();
				}
				if (!menu.getCarried().isEmpty()) {
					client.gameMode.handleContainerInput(menu.containerId, sourceIndex, 0, ContainerInput.PICKUP, client.player);
					GridTopUp.recordClick();
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

	/**
	 * Picks a concrete input for each ingredient slot from what the player is
	 * actually carrying, preferring the most plentiful accepted item so the
	 * staged copy count is not capped by an arbitrary alternative. Returns an
	 * empty list when any non-empty slot has no available input, which leaves
	 * the caller on its normal placement path.
	 */
	static List<String> resolveSlotChoicesFromInventory(
		AbstractContainerMenu menu,
		RecipeIngredientSummary summary,
		boolean pristineOnly
	) {
		List<String> slotItemIds = new ArrayList<>();
		for (RecipeIngredientSummary.IngredientSlot slot : summary.slots()) {
			if (slot.isEmpty()) {
				slotItemIds.add(null);
				continue;
			}
			String best = null;
			int bestCount = 0;
			for (String itemId : slot.itemIds()) {
				int available = countSources(menu, itemId, pristineOnly);
				if (available > bestCount) {
					best = itemId;
					bestCount = available;
				}
			}
			if (best == null) {
				return List.of();
			}
			slotItemIds.add(best);
		}
		return slotItemIds;
	}

	private static boolean isUsableSource(ItemStack stack, String itemId, boolean pristineOnly) {
		return !stack.isEmpty()
			&& itemId.equals(itemIdOf(stack))
			&& (!pristineOnly || isPristine(stack));
	}

	private static int findSource(AbstractContainerMenu menu, String itemId, boolean pristineOnly) {
		for (int index = firstSourceIndex(menu); index <= lastSourceIndex(menu); index++) {
			if (isUsableSource(menu.getSlot(index).getItem(), itemId, pristineOnly)) {
				return index;
			}
		}
		return -1;
	}

	static int countSources(AbstractContainerMenu menu, String itemId, boolean pristineOnly) {
		int total = 0;
		for (int index = firstSourceIndex(menu); index <= lastSourceIndex(menu); index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (isUsableSource(stack, itemId, pristineOnly)) {
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
