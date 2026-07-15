package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.List;

/**
 * Click-built ingredient "ring" for bulk-crafting recipes with unstackable
 * ingredients on servers that ration the place-recipe packet (see
 * PlaceRecipeBudget).
 *
 * Vanilla's shift-placement BALANCES the grid — every slot gets the same
 * number of crafts' worth — so a recipe with an unstackable ingredient (bow
 * in a dispenser) can only ever be staged one copy at a time, costing one
 * rationed packet per craft. The server will happily CRAFT from an
 * unbalanced grid though: 64 cobblestone per slot + 64 redstone + a single
 * bow is a valid dispenser match, consuming one item per slot per craft.
 *
 * So while a bulk session runs, this class builds that unbalanced ring with
 * ordinary container clicks (drawn from the far larger general packet
 * budget): full stacks into the stackable slots (once per ~64 crafts), one
 * item into each unstackable slot (every craft). While it succeeds, no place
 * packet is spent at all.
 */
final class GridTopUp {

	/** Refill a stackable ring slot once it drains below this count. */
	private static final int RING_LOW_WATER = 4;

	// Safety governor: automation clicks in a trailing window, kept far below
	// Paper's all-packets KICK limit (500 per 7s). If staging would push past
	// this, decline and let the (budgeted) place-packet path carry the cycle.
	private static final int CLICK_WINDOW_MS = 7000;
	private static final int CLICK_WINDOW_CAP = 180;
	private static final java.util.ArrayDeque<Long> recentClicks = new java.util.ArrayDeque<>();

	private GridTopUp() {
	}

	/**
	 * Recipe-aware staged-copies count. The generic heuristic (min count
	 * across NON-EMPTY grid slots) wildly overcounts an unbalanced ring: with
	 * the bow slot empty it reads the cobblestone stacks (62+) as "62 crafts
	 * staged" and credits phantom output. Counted against the recipe's actual
	 * slot requirements, an empty REQUIRED slot means ZERO crafts staged.
	 * For balanced grids this computes the same value as the raw heuristic,
	 * so it is safe to apply to every bulk session.
	 */
	static int recipeAwareStagedCopies(AbstractContainerMenu menu, RecipeIngredientSummary summary, int rawCopies) {
		if (summary == null || menu == null) {
			return rawCopies;
		}
		List<RecipeIngredientSummary.IngredientSlot> slots = summary.slots();
		int gridCount = gridSlotCount(menu);
		if (slots.isEmpty() || gridCount == 0 || slots.size() > gridCount) {
			return rawCopies;
		}
		int staged = Integer.MAX_VALUE;
		for (int i = 0; i < slots.size(); i++) {
			RecipeIngredientSummary.IngredientSlot slot = slots.get(i);
			if (slot.isEmpty()) {
				continue;
			}
			ItemStack inGrid = menu.getSlot(1 + i).getItem();
			if (inGrid.isEmpty()) {
				return 0;
			}
			staged = Math.min(staged, inGrid.getCount());
		}
		return staged == Integer.MAX_VALUE ? 0 : staged;
	}

	private static boolean clickBudgetAllows(int estimatedClicks) {
		long now = System.currentTimeMillis();
		while (!recentClicks.isEmpty() && now - recentClicks.peekFirst() > CLICK_WINDOW_MS) {
			recentClicks.pollFirst();
		}
		if (recentClicks.size() + estimatedClicks > CLICK_WINDOW_CAP) {
			ReachCraftingMod.LOGGER.warn(
				"[grid_topup] click governor engaged ({} clicks in window, +{} requested) - deferring to place packet",
				recentClicks.size(), estimatedClicks);
			return false;
		}
		return true;
	}

	private static void recordClick() {
		recentClicks.addLast(System.currentTimeMillis());
	}

	/**
	 * Read-only check used by the pre-replay flush: does the current grid
	 * content look like this recipe's deliberately staged ring (possibly with
	 * the unstackable slots already consumed)? A recognized ring must be kept,
	 * not flushed — flushing and rebuilding it every cycle costs ~40 clicks
	 * per craft and got the client kicked for packet rate.
	 */
	static boolean isRingForRecipe(Minecraft client, LocalPlayer player, RecipeDisplayId recipeId, RecipeCollection collection) {
		if (client == null || player == null || client.level == null || recipeId == null || collection == null) {
			return false;
		}
		if (PlaceRecipeBudget.isUnlimited(client) || !BulkAutoCraftController.isActive()) {
			return false;
		}
		RecipeIngredientSummary summary = resolveSummary(client, recipeId, collection);
		if (summary == null) {
			return false;
		}
		AbstractContainerMenu menu = player.containerMenu;
		int gridCount = gridSlotCount(menu);
		List<RecipeIngredientSummary.IngredientSlot> slots = summary.slots();
		if (gridCount == 0 || slots.isEmpty() || slots.size() > gridCount) {
			return false;
		}
		boolean sawStack = false;
		for (int i = 0; i < gridCount; i++) {
			ItemStack inGrid = menu.getSlot(1 + i).getItem();
			RecipeIngredientSummary.IngredientSlot slot = i < slots.size() ? slots.get(i) : null;
			if (slot == null || slot.isEmpty()) {
				if (!inGrid.isEmpty()) {
					return false;
				}
				continue;
			}
			if (inGrid.isEmpty()) {
				// Only unstackable slots may be empty (consumed last craft).
				if (slot.maxStackSize() > 1) {
					return false;
				}
				continue;
			}
			if (!slot.itemIds().contains(itemIdOf(inGrid))) {
				return false;
			}
			if (inGrid.getCount() >= 2) {
				sawStack = true;
			}
		}
		// A balanced single-copy grid (vanilla placement) is not a ring; only
		// keep grids that hold genuine stacks.
		return sawStack;
	}

	private static RecipeIngredientSummary resolveSummary(Minecraft client, RecipeDisplayId recipeId, RecipeCollection collection) {
		for (RecipeDisplayEntry entry : collection.getRecipes()) {
			if (entry.id().equals(recipeId)) {
				ContextMap context = SlotDisplayContext.fromLevel(client.level);
				return RecipeIngredientSummary.fromDisplay(entry.display(), context);
			}
		}
		return null;
	}

	/** Overload for call sites that only hold the recipe id + collection. */
	static boolean tryStageInsteadOfPlace(Minecraft client, LocalPlayer player, RecipeDisplayId recipeId, RecipeCollection collection) {
		if (client == null || client.level == null || recipeId == null || collection == null) {
			return false;
		}
		RecipeIngredientSummary summary = resolveSummary(client, recipeId, collection);
		return summary != null && tryStageInsteadOfPlace(client, player, summary);
	}

	/**
	 * Build or maintain the ring for this recipe via clicks. Returns true when
	 * the grid is left fully staged for at least one craft (caller must skip
	 * its handlePlaceRecipe); false leaves the grid for the normal placement
	 * path (partial click work is safe — the placement packet reconciles).
	 */
	static boolean tryStageInsteadOfPlace(Minecraft client, LocalPlayer player, RecipeIngredientSummary summary) {
		if (client == null || player == null || summary == null || client.gameMode == null) {
			return false;
		}
		if (PlaceRecipeBudget.isUnlimited(client)) {
			return false;
		}
		if (!BulkAutoCraftController.isActive()) {
			// Only bulk sessions benefit; a manual single craft should not get
			// stacks dumped into its grid.
			return false;
		}
		AbstractContainerMenu menu = player.containerMenu;
		int gridCount = gridSlotCount(menu);
		if (gridCount == 0 || !menu.getCarried().isEmpty()) {
			return false;
		}
		List<RecipeIngredientSummary.IngredientSlot> slots = summary.slots();
		if (slots.isEmpty() || slots.size() > gridCount) {
			return false;
		}
		boolean hasUnstackable = false;
		boolean hasStackable = false;
		for (RecipeIngredientSummary.IngredientSlot slot : slots) {
			if (slot.isEmpty()) {
				continue;
			}
			if (slot.maxStackSize() <= 1) {
				hasUnstackable = true;
			} else {
				hasStackable = true;
			}
		}
		if (!hasUnstackable || !hasStackable) {
			// All-stackable recipes are already efficient via one balanced
			// shift-placement; all-unstackable ones have no ring to keep.
			return false;
		}
		if (!clickBudgetAllows(24)) {
			return false;
		}

		int ringDeposits = 0;
		int singleInserts = 0;
		for (int i = 0; i < slots.size(); i++) {
			RecipeIngredientSummary.IngredientSlot slot = slots.get(i);
			int gridSlotIndex = 1 + i;
			ItemStack inGrid = menu.getSlot(gridSlotIndex).getItem();
			if (slot.isEmpty()) {
				if (!inGrid.isEmpty()) {
					return false;
				}
				continue;
			}
			if (!inGrid.isEmpty() && !slot.itemIds().contains(itemIdOf(inGrid))) {
				// Foreign item in a recipe slot: let the normal path flush/place.
				return false;
			}
			if (slot.maxStackSize() <= 1) {
				if (inGrid.isEmpty()) {
					if (!insertSingle(client, menu, slot, gridSlotIndex)) {
						return false;
					}
					singleInserts++;
				}
			} else {
				if (inGrid.isEmpty() || inGrid.getCount() < RING_LOW_WATER) {
					if (depositStack(client, menu, slot, gridSlotIndex)) {
						ringDeposits++;
					} else if (inGrid.isEmpty()) {
						// Ring slot empty and nothing to fill it with: the
						// craft cannot proceed via clicks.
						return false;
					}
					// A low-but-nonempty slot with no refill source keeps
					// crafting on its remainder.
				}
			}
		}

		// Every recipe slot must now hold at least one item.
		for (int i = 0; i < slots.size(); i++) {
			if (!slots.get(i).isEmpty() && menu.getSlot(1 + i).getItem().isEmpty()) {
				return false;
			}
		}
		if (ringDeposits > 0 || singleInserts > 0) {
			ReachCraftingMod.LOGGER.info(
				"[grid_topup] staged via clicks ring_deposits={} single_inserts={} place packet skipped",
				ringDeposits, singleInserts
			);
		}
		return true;
	}

	/** Move one item of an unstackable ingredient into the grid slot (2 clicks). */
	private static boolean insertSingle(Minecraft client, AbstractContainerMenu menu, RecipeIngredientSummary.IngredientSlot slot, int gridSlotIndex) {
		int source = findSourceSlot(menu, slot.itemIds(), true);
		if (source < 0) {
			return false;
		}
		click(client, menu, source);
		if (menu.getCarried().isEmpty()) {
			return false;
		}
		click(client, menu, gridSlotIndex);
		if (!menu.getCarried().isEmpty()) {
			click(client, menu, source);
			return false;
		}
		return !menu.getSlot(gridSlotIndex).getItem().isEmpty();
	}

	/** Merge the largest matching inventory stack into a ring slot (2-3 clicks). */
	private static boolean depositStack(Minecraft client, AbstractContainerMenu menu, RecipeIngredientSummary.IngredientSlot slot, int gridSlotIndex) {
		int source = findSourceSlot(menu, slot.itemIds(), false);
		if (source < 0) {
			return false;
		}
		click(client, menu, source);
		if (menu.getCarried().isEmpty()) {
			return false;
		}
		click(client, menu, gridSlotIndex);
		if (!menu.getCarried().isEmpty()) {
			// Merge overflow: return the remainder to its source slot.
			click(client, menu, source);
			if (!menu.getCarried().isEmpty()) {
				return false;
			}
		}
		return !menu.getSlot(gridSlotIndex).getItem().isEmpty();
	}

	private static void click(Minecraft client, AbstractContainerMenu menu, int slotIndex) {
		client.gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, 0, ClickType.PICKUP, client.player);
		recordClick();
	}

	private static int findSourceSlot(AbstractContainerMenu menu, List<String> acceptedItemIds, boolean preferPristine) {
		int first = firstSourceIndex(menu);
		int last = lastSourceIndex(menu);
		int best = -1;
		int bestCount = -1;
		int pristine = -1;
		for (int i = first; i <= last; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (stack.isEmpty() || !acceptedItemIds.contains(itemIdOf(stack))) {
				continue;
			}
			if (preferPristine && pristine < 0
				&& ItemStack.isSameItemSameComponents(stack, stack.getItem().getDefaultInstance())) {
				pristine = i;
			}
			if (stack.getCount() > bestCount) {
				best = i;
				bestCount = stack.getCount();
			}
		}
		return preferPristine && pristine >= 0 ? pristine : best;
	}

	private static String itemIdOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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

	private static int firstSourceIndex(AbstractContainerMenu menu) {
		return menu instanceof CraftingMenu ? 10 : 9;
	}

	private static int lastSourceIndex(AbstractContainerMenu menu) {
		return menu instanceof CraftingMenu ? 45 : 44;
	}
}
