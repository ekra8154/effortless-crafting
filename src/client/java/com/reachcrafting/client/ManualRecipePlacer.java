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
		return placeCrafts(client, summary, slotItemIds, maxCopies, pristineOnly, label, false);
	}

	/**
	 * @param allowPartiallyStaged accept a grid that already holds some of this
	 *     recipe's own inputs and top it up, instead of requiring it empty.
	 *     Used when upgrading a placement-fed craft to clicks mid-flight: the
	 *     placements that already landed count toward the target.
	 */
	static int placeCrafts(
		Minecraft client,
		RecipeIngredientSummary summary,
		List<String> slotItemIds,
		int maxCopies,
		boolean pristineOnly,
		String label,
		boolean allowPartiallyStaged
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
		// The grid must be empty, or - when topping up a partly-placed craft -
		// hold ONLY this recipe's own inputs, none of them already past the
		// target. Anything else is foreign content this must not consume.
		for (int i = 1; i <= gridSlotCount; i++) {
			ItemStack inGrid = menu.getSlot(i).getItem();
			if (inGrid.isEmpty()) {
				continue;
			}
			int mapped = gridIndices.indexOf(i);
			String expected = mapped >= 0 ? slotItemIds.get(mapped) : null;
			if (!allowPartiallyStaged || expected == null
				|| !expected.equals(itemIdOf(inGrid)) || inGrid.getCount() > maxCopies) {
				ReachCraftingMod.LOGGER.info("[manual_place] grid_not_empty slot={}", i);
				return 0;
			}
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
			// Copies already sitting in this item's grid slots are staged
			// work, not something the inventory still has to supply.
			int alreadyEach = Integer.MAX_VALUE;
			for (int slotIndex = 0; slotIndex < slotItemIds.size(); slotIndex++) {
				if (entry.getKey().equals(slotItemIds.get(slotIndex))) {
					alreadyEach = Math.min(alreadyEach,
						countInGridSlot(menu, gridIndices.get(slotIndex), entry.getKey()));
				}
			}
			if (alreadyEach == Integer.MAX_VALUE) {
				alreadyEach = 0;
			}
			copies = Math.min(copies,
				alreadyEach + countSources(menu, entry.getKey(), pristineOnly) / entry.getValue());
		}
		if (copies <= 0) {
			ReachCraftingMod.LOGGER.info(
				"[manual_place] no_inputs output={} needed={} pristine_only={}",
				label, slotsPerItem, pristineOnly
			);
			return 0;
		}

		// Slots wanting the SAME item are filled together: one left-drag splits
		// a carried stack evenly across them, so the bulk of the fill costs
		// ~(3 + slots) clicks per round instead of one click per item per
		// slot. The per-slot loop then tops up whatever the drag could not
		// divide evenly. Furnace (8 cobblestone slots, 20 copies) drops from
		// ~176 clicks to ~60.
		int clickWindowBefore = GridTopUp.clickWindowCount();
		Map<String, List<Integer>> groupTargets = new LinkedHashMap<>();
		for (int slotIndex = 0; slotIndex < slotItemIds.size(); slotIndex++) {
			String slotItemId = slotItemIds.get(slotIndex);
			if (slotItemId == null) {
				continue;
			}
			groupTargets.computeIfAbsent(slotItemId, key -> new ArrayList<>()).add(gridIndices.get(slotIndex));
		}
		for (Map.Entry<String, List<Integer>> group : groupTargets.entrySet()) {
			String slotItemId = group.getKey();
			List<Integer> targets = group.getValue();
			if (targets.size() > 1) {
				dragFillGroup(client, menu, slotItemId, copies, targets, pristineOnly);
			}
			for (int gridIndex : targets) {
				if (!moveIntoGridSlot(client, menu, slotItemId, copies, gridIndex, pristineOnly)) {
					// Partial placements are left for the caller's grid flush.
					ReachCraftingMod.LOGGER.warn("[manual_place] move_failed item={} grid_slot={}", slotItemId, gridIndex);
					return 0;
				}
			}
		}
		// Report what this craft actually cost against the governor. Without
		// this the window count only ever appeared on a DECLINE, so headroom
		// had to be inferred from the algorithm instead of measured - and the
		// per-slot cost of SINGLE-slot ingredient groups (no drag partner, so
		// one click per item) is invisible until it causes a decline.
		int clickWindowAfter = GridTopUp.clickWindowCount();
		ReachCraftingMod.LOGGER.info(
			"[manual_place] staged output={} copies={} clicks={} window={}/{} groups={} slots={} grid_slots={}",
			label,
			copies,
			clickWindowAfter - clickWindowBefore,
			clickWindowAfter,
			GridTopUp.clickWindowCap(),
			groupTargets.size(),
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

	/**
	 * Fill several same-item grid slots toward targetPerSlot using vanilla
	 * left-drag (QUICK_CRAFT), which divides the carried stack evenly across
	 * the dragged slots. Best-effort: it only runs rounds that divide cleanly
	 * without overshooting, and leaves any remainder to the per-slot top-up.
	 * The cursor is always returned empty, so a bail-out is safe.
	 */
	private static void dragFillGroup(
		Minecraft client,
		AbstractContainerMenu menu,
		String itemId,
		int targetPerSlot,
		List<Integer> targets,
		boolean pristineOnly
	) {
		int slots = targets.size();
		// Each round adds floor(carried/slots) per slot, so the count strictly
		// rises; the guard only bounds a pathological no-progress case.
		for (int round = 0; round < 8; round++) {
			if (!menu.getCarried().isEmpty()) {
				return;
			}
			int lowest = Integer.MAX_VALUE;
			for (int target : targets) {
				lowest = Math.min(lowest, countInGridSlot(menu, target, itemId));
			}
			int remainingEach = targetPerSlot - lowest;
			if (remainingEach <= 0) {
				return;
			}
			int sourceIndex = findSource(menu, itemId, pristineOnly);
			if (sourceIndex == -1) {
				return;
			}
			if (menu.getSlot(sourceIndex).getItem().getCount() < slots) {
				return; // not even one each: the per-slot path is cheaper
			}
			client.gameMode.handleContainerInput(menu.containerId, sourceIndex, 0, ContainerInput.PICKUP, client.player);
			GridTopUp.recordClick();
			ItemStack carried = menu.getCarried();
			if (carried.isEmpty()) {
				return;
			}
			int each = carried.getCount() / slots;
			if (each <= 0 || each > remainingEach) {
				// Would place nothing, or overshoot the exact count. Put the
				// stack back and let the per-slot top-up finish precisely.
				client.gameMode.handleContainerInput(menu.containerId, sourceIndex, 0, ContainerInput.PICKUP, client.player);
				GridTopUp.recordClick();
				return;
			}
			// QUICK_CRAFT protocol: header 0 = start, 1 = add slot, 2 = end;
			// type 0 (left drag) splits the carried stack evenly.
			client.gameMode.handleContainerInput(menu.containerId, -999, 0, ContainerInput.QUICK_CRAFT, client.player);
			GridTopUp.recordClick();
			for (int target : targets) {
				client.gameMode.handleContainerInput(menu.containerId, target, 1, ContainerInput.QUICK_CRAFT, client.player);
				GridTopUp.recordClick();
			}
			client.gameMode.handleContainerInput(menu.containerId, -999, 2, ContainerInput.QUICK_CRAFT, client.player);
			GridTopUp.recordClick();
			if (!menu.getCarried().isEmpty()) {
				// Remainder (carried % slots) goes back where it came from.
				client.gameMode.handleContainerInput(menu.containerId, sourceIndex, 0, ContainerInput.PICKUP, client.player);
				GridTopUp.recordClick();
			}
		}
	}

	private static boolean moveIntoGridSlot(
		Minecraft client,
		AbstractContainerMenu menu,
		String itemId,
		int count,
		int gridSlotIndex,
		boolean pristineOnly
	) {
		// count is the ABSOLUTE target for the slot, not an amount to add, so a
		// slot the drag pass already partly filled is topped up rather than
		// overshot.
		int placed = countInGridSlot(menu, gridSlotIndex, itemId);
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
			} else if (carried.getCount() - needed < needed) {
				// Cheaper to shed the EXCESS than to place what is wanted:
				// right-click the source to drop items back one at a time,
				// then left-click the whole remainder into the grid. Wanting
				// 46 of a 64 stack costs 18 clicks this way instead of 46.
				// (Same gesture InventoryGridRestoreTracker uses to move an
				// exact count.)
				int excess = carried.getCount() - needed;
				for (int i = 0; i < excess; i++) {
					client.gameMode.handleContainerInput(menu.containerId, sourceIndex, 1, ContainerInput.PICKUP, client.player);
					GridTopUp.recordClick();
				}
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
