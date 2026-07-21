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

	// Safety governor: automation clicks in a trailing window, kept below
	// Paper's all-packets KICK limit (500 per 7s). If staging would push past
	// this, decline and let the (budgeted) place-packet path carry the cycle.
	// 180 throttled M3's full T2 chain pace (ring upkeep + key inserts +
	// throws sustained ~23 clicks/s) and the resulting stall cascaded into a
	// session abort; 280 (56% of the kick limit, >200 packets of headroom
	// for movement/other traffic) clears it. The suite's kicks==0 assertion
	// is the regression guard for this margin.
	private static final int CLICK_WINDOW_MS = 7000;
	private static final int CLICK_WINDOW_CAP = 280;
	private static final java.util.ArrayDeque<Long> recentClicks = new java.util.ArrayDeque<>();

	// Whether the most recent tryStageInsteadOfPlace returned false ONLY
	// because the click governor declined. The extractor must tell this apart
	// from a genuinely dead ring: a saturated window drains at ~40 clicks/s,
	// so waiting a few ticks resumes the ring at 3 clicks/craft — while
	// treating it as "grid spent" flushes the ring and falls back to one
	// place packet + ~20 clicks per craft.
	private static boolean lastStageDeclineWasBudget = false;

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
				// The naive slot->grid map (summary slot i -> grid slot 1+i)
				// only holds when the recipe fills the grid contiguously. A
				// SHAPED recipe smaller than the grid (honey_block: 2x2 in a
				// 3x3 menu occupies grid slots 1,2,4,5 — slot 3 is a shape gap)
				// hits an empty slot here and used to report 0 staged copies,
				// which stalled the DIRECT_EJECT fast path for ~6 ticks EVERY
				// batch before it fell back to the slow organize path. The
				// layout-agnostic rawCopies (min over occupied slots) is
				// correct for these; use it rather than under-reporting 0.
				return rawCopies;
			}
			staged = Math.min(staged, inGrid.getCount());
		}
		return staged == Integer.MAX_VALUE ? rawCopies : Math.max(staged, rawCopies);
	}

	/**
	 * Sessions the ring may serve: flat bulk (M1), and the FINAL step of a
	 * bulk chain (M3) — the dispenser step there paid one rationed packet per
	 * copy, exactly the pattern the ring exists to break. Plain single-craft
	 * chains stay excluded: no one wants stacks dumped into a one-off grid.
	 */
	private static boolean ringSessionActive() {
		return BulkAutoCraftController.isActive()
			|| (BulkChainCraftController.isActive() && ChainCraftController.isRunningFinalStep());
	}

	/**
	 * Key-cycle (T2) eligibility for GridExtractor: exactly one unstackable
	 * ingredient slot (the "key" cycled per craft) plus at least one
	 * stackable slot the ring can stage in bulk.
	 */
	static boolean isKeyCycleEligible(RecipeIngredientSummary summary) {
		if (summary == null || summary.slots().isEmpty()) {
			return false;
		}
		int unstackable = 0;
		boolean stackable = false;
		for (RecipeIngredientSummary.IngredientSlot slot : summary.slots()) {
			if (slot.isEmpty()) {
				continue;
			}
			if (slot.maxStackSize() <= 1) {
				unstackable++;
			} else {
				stackable = true;
			}
		}
		return unstackable == 1 && stackable;
	}

	/** Shared with GridExtractor: automation clicks draw from one governor. */
	static boolean clickBudgetAllows(int estimatedClicks) {
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

	/** Current trailing-window click count (diagnostics for slow-craft logs). */
	static int clickWindowCount() {
		long now = System.currentTimeMillis();
		while (!recentClicks.isEmpty() && now - recentClicks.peekFirst() > CLICK_WINDOW_MS) {
			recentClicks.pollFirst();
		}
		return recentClicks.size();
	}

	static void recordClick() {
		long now = System.currentTimeMillis();
		recentClicks.addLast(now);
		lastAutomationClickMillis = now;
	}

	private static long lastAutomationClickMillis = 0;

	/** Millis since ANY automation click (governor-recorded). The cursor
	 * watchdog uses this to tell a stray carried stack (server resync landed
	 * it while we idle) from a click sequence currently in flight. */
	static long millisSinceLastAutomationClick() {
		return lastAutomationClickMillis == 0
			? Long.MAX_VALUE
			: System.currentTimeMillis() - lastAutomationClickMillis;
	}

	/** Did the last staging attempt fail purely on the click governor? */
	static boolean lastStageDeclineWasBudget() {
		return lastStageDeclineWasBudget;
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
		if (PlaceRecipeBudget.isUnlimited(client) || !ringSessionActive()) {
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
		// Only single-unstackable-slot recipes use the ring (see
		// tryStageInsteadOfPlace); don't preserve grids for anything else.
		if (slots.stream().filter(s -> !s.isEmpty() && s.maxStackSize() <= 1).count() != 1) {
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

	/**
	 * Is the grid the active bulk session's ring with its single unstackable
	 * (key) slot currently EMPTY? In that between-cycles window the remaining
	 * ring often completes a FOREIGN recipe (a bow-less dispenser ring is a
	 * valid dropper match) and the result slot previews it. Callers must treat
	 * that preview as "waiting for the next key item", never as a recipe
	 * change: failing the batch on it costs a backoff, a ring flush+rebuild,
	 * and a phantom drop fed into the place budget's AIMD.
	 */
	static boolean isRingAwaitingKeyItem(Minecraft client, AbstractContainerMenu menu) {
		if (client == null || menu == null || !BulkAutoCraftController.isActive()) {
			return false;
		}
		RecipeIngredientSummary summary = BulkAutoCraftController.activeSessionSummary();
		if (summary == null) {
			return false;
		}
		List<RecipeIngredientSummary.IngredientSlot> slots = summary.slots();
		int gridCount = gridSlotCount(menu);
		if (slots.isEmpty() || gridCount == 0 || slots.size() > gridCount) {
			return false;
		}
		if (slots.stream().filter(s -> !s.isEmpty() && s.maxStackSize() <= 1).count() != 1) {
			return false;
		}
		boolean keyEmpty = false;
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
			if (slot.maxStackSize() <= 1) {
				// Key item present: any foreign result is a genuine mismatch.
				if (!inGrid.isEmpty()) {
					return false;
				}
				keyEmpty = true;
				continue;
			}
			if (inGrid.isEmpty() || !slot.itemIds().contains(itemIdOf(inGrid))) {
				return false;
			}
			if (inGrid.getCount() >= 2) {
				sawStack = true;
			}
		}
		// Same signature rule as isRingForRecipe: a balanced single-copy grid
		// is not a ring.
		return keyEmpty && sawStack;
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
		lastStageDeclineWasBudget = false;
		if (client == null || player == null || summary == null || client.gameMode == null) {
			return declined("null_input");
		}
		if (PlaceRecipeBudget.isUnlimited(client)) {
			return false; // silent: SP / unlimited servers never use the ring
		}
		if (!ringSessionActive()) {
			// Only bulk sessions benefit; a manual single craft should not get
			// stacks dumped into its grid.
			return declined("bulk_inactive");
		}
		AbstractContainerMenu menu = player.containerMenu;
		int gridCount = gridSlotCount(menu);
		if (gridCount == 0 || !menu.getCarried().isEmpty()) {
			return declined(gridCount == 0 ? "no_grid" : "carried_nonempty");
		}
		List<RecipeIngredientSummary.IngredientSlot> slots = summary.slots();
		if (slots.isEmpty() || slots.size() > gridCount) {
			return declined("slot_layout");
		}
		int unstackableSlots = 0;
		boolean hasStackable = false;
		for (RecipeIngredientSummary.IngredientSlot slot : slots) {
			if (slot.isEmpty()) {
				continue;
			}
			if (slot.maxStackSize() <= 1) {
				unstackableSlots++;
			} else {
				hasStackable = true;
			}
		}
		if (unstackableSlots == 0 || !hasStackable) {
			// All-stackable recipes are already efficient via one balanced
			// shift-placement; all-unstackable ones have no ring to keep.
			return declined(unstackableSlots == 0 ? "all_stackable" : "all_unstackable");
		}
		if (unstackableSlots > 1) {
			// The ring model cycles ONE unstackable slot per craft (dispenser's
			// bow). Recipes with several unstackable slots (cake: 3 milk
			// buckets) need a different staging strategy; falling back to the
			// normal placement path here matches pre-ring (release) behavior
			// and avoids leaving an incomplete grid.
			return declined("multi_unstackable");
		}
		// Estimate what THIS cycle will actually click: cold build ~24, ring
		// refill (spread/deposits) ~12, steady-state key insert 2. The old
		// flat 8-per-cycle estimate made the governor refuse steady cycles
		// that cost 2, stranding an intact ring at a saturated window.
		boolean gridHoldsRingStacks = false;
		for (int i = 1; i <= gridCount; i++) {
			if (menu.getSlot(i).getItem().getCount() >= 2) {
				gridHoldsRingStacks = true;
				break;
			}
		}
		boolean refillNeeded = false;
		for (int i = 0; i < slots.size(); i++) {
			RecipeIngredientSummary.IngredientSlot slot = slots.get(i);
			if (slot.isEmpty() || slot.maxStackSize() <= 1) {
				continue;
			}
			ItemStack inGrid = menu.getSlot(1 + i).getItem();
			if (inGrid.isEmpty() || inGrid.getCount() < RING_LOW_WATER) {
				refillNeeded = true;
				break;
			}
		}
		// NOTE: refills are NOT deferrable under a tight budget. Skipping
		// low-water refills to keep crafting on remainders was tried and let
		// slots drain to empty mid-batch — an empty stackable slot breaks the
		// ring invariant (isRingForRecipe / isRingAwaitingKeyItem require all
		// stackable slots non-empty), sending settlement into foreign-preview
		// retries and leaving the grid visibly uneven. A ~2s clean plateau
		// wait beats that.
		int estimatedClicks = !gridHoldsRingStacks ? 24 : refillNeeded ? 12 : 2;
		if (!clickBudgetAllows(estimatedClicks)) {
			lastStageDeclineWasBudget = true;
			return false; // clickBudgetAllows already logged the governor warn
		}

		int ringDeposits = 0;
		int singleInserts = 0;
		for (int i = 0; i < slots.size(); i++) {
			RecipeIngredientSummary.IngredientSlot slot = slots.get(i);
			int gridSlotIndex = 1 + i;
			ItemStack inGrid = menu.getSlot(gridSlotIndex).getItem();
			if (slot.isEmpty()) {
				if (!inGrid.isEmpty()) {
					return declined("item_in_nonrecipe_slot_" + gridSlotIndex);
				}
				continue;
			}
			if (!inGrid.isEmpty() && !slot.itemIds().contains(itemIdOf(inGrid))) {
				// Foreign item in a recipe slot: let the normal path flush/place.
				return declined("foreign_item_slot_" + gridSlotIndex);
			}
			if (slot.maxStackSize() <= 1) {
				if (inGrid.isEmpty()) {
					if (!insertSingle(client, menu, slot, gridSlotIndex)) {
						return declined("insert_single_failed_slot_" + gridSlotIndex);
					}
					singleInserts++;
				}
			} else {
				// A spread for an earlier slot of the same ingredient may have
				// already filled this one — re-read before deciding.
				ItemStack current = menu.getSlot(gridSlotIndex).getItem();
				if (current.isEmpty() || current.getCount() < RING_LOW_WATER) {
					// Allocation matters: with fewer source stacks than needy
					// group slots (typical under nearby-withdrawal, which
					// stocks batch-sized amounts), one-whole-stack-per-slot
					// deposits exhaust the sources on the first slots and
					// strand the rest. Decide UP FRONT: scarce -> drag-split
					// a stack evenly across the whole group; plentiful ->
					// whole-stack deposits as before.
					boolean staged = groupSourcesScarce(menu, slots, i)
						? spreadIntoGroupSlots(client, menu, slots, i) || depositStack(client, menu, slot, gridSlotIndex)
						: depositStack(client, menu, slot, gridSlotIndex) || spreadIntoGroupSlots(client, menu, slots, i);
					if (staged) {
						ringDeposits++;
					} else if (menu.getSlot(gridSlotIndex).getItem().isEmpty()) {
						// Ring slot empty and nothing to fill it with: the
						// craft cannot proceed via clicks.
						return declined("deposit_failed_slot_" + gridSlotIndex);
					}
					// A low-but-nonempty slot with no refill source keeps
					// crafting on its remainder.
				}
			}
		}

		// Every recipe slot must now hold at least one item.
		for (int i = 0; i < slots.size(); i++) {
			if (!slots.get(i).isEmpty() && menu.getSlot(1 + i).getItem().isEmpty()) {
				return declined("slot_still_empty_" + (1 + i));
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

	/** Log why the ring path stood down; the caller falls back to a place packet. */
	private static boolean declined(String reason) {
		ReachCraftingMod.LOGGER.info("[grid_topup] stage declined reason={}", reason);
		return false;
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

	/** Fewer matching inventory stacks than empty-or-low slots in the group? */
	private static boolean groupSourcesScarce(
		AbstractContainerMenu menu,
		List<RecipeIngredientSummary.IngredientSlot> slots,
		int slotOrdinal
	) {
		RecipeIngredientSummary.IngredientSlot reference = slots.get(slotOrdinal);
		int needySlots = 0;
		for (int j = 0; j < slots.size(); j++) {
			RecipeIngredientSummary.IngredientSlot other = slots.get(j);
			if (other.isEmpty() || other.maxStackSize() <= 1) {
				continue;
			}
			if (!java.util.Collections.disjoint(other.itemIds(), reference.itemIds())) {
				ItemStack inGrid = menu.getSlot(1 + j).getItem();
				if (inGrid.isEmpty() || inGrid.getCount() < RING_LOW_WATER) {
					needySlots++;
				}
			}
		}
		int sources = 0;
		for (int i = firstSourceIndex(menu); i <= lastSourceIndex(menu); i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (!stack.isEmpty() && reference.itemIds().contains(itemIdOf(stack))) {
				sources++;
			}
		}
		return sources < needySlots;
	}

	/**
	 * Drag-split (vanilla QUICK_CRAFT) one source stack evenly across every
	 * empty-or-low grid slot in the same ingredient group. This is the ring's
	 * answer to a withdrawal-sized inventory: 2-3 cobble stacks cannot fill
	 * seven slots one-whole-stack-each, but ONE stack spread across all seven
	 * (9 apiece) stages 9 crafts. ~4 + targets clicks.
	 */
	private static boolean spreadIntoGroupSlots(
		Minecraft client,
		AbstractContainerMenu menu,
		List<RecipeIngredientSummary.IngredientSlot> slots,
		int slotOrdinal
	) {
		RecipeIngredientSummary.IngredientSlot reference = slots.get(slotOrdinal);
		java.util.List<Integer> targets = new java.util.ArrayList<>();
		for (int j = 0; j < slots.size(); j++) {
			RecipeIngredientSummary.IngredientSlot other = slots.get(j);
			if (other.isEmpty() || other.maxStackSize() <= 1) {
				continue;
			}
			if (!java.util.Collections.disjoint(other.itemIds(), reference.itemIds())) {
				ItemStack inGrid = menu.getSlot(1 + j).getItem();
				if (inGrid.isEmpty() || inGrid.getCount() < RING_LOW_WATER) {
					targets.add(1 + j);
				}
			}
		}
		if (targets.isEmpty()) {
			return false;
		}
		int source = findSourceSlot(menu, reference.itemIds(), false);
		if (source < 0) {
			return false;
		}
		click(client, menu, source);
		if (menu.getCarried().isEmpty()) {
			return false;
		}
		int containerId = menu.containerId;
		// QUICK_CRAFT protocol: header 0 = start, 1 = add slot, 2 = end;
		// type 0 (left drag) splits the carried stack evenly.
		client.gameMode.handleInventoryMouseClick(containerId, -999, 0, ClickType.QUICK_CRAFT, client.player);
		recordClick();
		for (int target : targets) {
			client.gameMode.handleInventoryMouseClick(containerId, target, 1, ClickType.QUICK_CRAFT, client.player);
			recordClick();
		}
		client.gameMode.handleInventoryMouseClick(containerId, -999, 2, ClickType.QUICK_CRAFT, client.player);
		recordClick();
		if (!menu.getCarried().isEmpty()) {
			// Remainder (count % targets) goes back where it came from.
			click(client, menu, source);
			if (!menu.getCarried().isEmpty()) {
				return false;
			}
		}
		ReachCraftingMod.LOGGER.info(
			"[grid_topup] spread source_slot={} across {} ring slots",
			source,
			targets.size()
		);
		return !menu.getSlot(1 + slotOrdinal).getItem().isEmpty();
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
