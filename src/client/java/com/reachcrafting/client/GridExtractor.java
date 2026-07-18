package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Counted result extraction for T1 chain steps (all-stackable ingredients).
 *
 * A chain intermediate used to spend one rationed place packet PER COPY (see
 * PlaceRecipeBudget), because the obvious alternative — one shift-place plus
 * a result QUICK_MOVE — crafts EVERYTHING staged, and with shared ingredients
 * (lectern: planks feed both the slab step and the bookshelf step) that
 * overcraft starves later steps.
 *
 * Staging is not consumption though: only crafting consumes. So a T1 step
 * spends ONE place packet (useMaxItems=true, stages maximal stacks) and caps
 * consumption on the extraction side instead. This class PICKUP-clicks the
 * result slot exactly the scheduled number of times — one craft per click,
 * ordinary container clicks drawn from the far larger general packet budget —
 * deposits the output into the inventory, and reports completion through the
 * same onAutoMoveFinished contract the auto-move path uses. Surplus staged
 * ingredients stay in the grid for the existing pre-replay flush to return
 * to the inventory before the next step is placed.
 */
final class GridExtractor {
	// Per-tick burst cap; the shared click-window governor (GridTopUp,
	// 180/7s) is the sustained-rate authority, so this only shapes burst
	// smoothness. 3 made unstackable outputs (2 clicks per bow) the chain
	// bottleneck at ~30 copies/s of budget left unused.
	private static final int MAX_CLICKS_PER_TICK = 5;
	/** How long to wait for the (budget-deferred) placement round trip. */
	private static final int STAGING_WAIT_TIMEOUT_TICKS = 100;
	/** After progress, a quiet result slot this long means the grid is spent. */
	private static final int RESULT_QUIET_FINISH_TICKS = 20;
	private static final int OVERALL_TIMEOUT_TICKS = 400;

	private static boolean active = false;
	private static ItemStack expectedOutput = ItemStack.EMPTY;
	private static int targetCopies = 0;
	private static int craftedCopies = 0;
	private static int totalTicks = 0;
	private static int quietTicks = 0;
	/** Consecutive ticks the result slot has matched the expected output. */
	private static int resultStableTicks = 0;
	/**
	 * T2 key-cycle mode (chain finals with one unstackable ingredient, e.g.
	 * the dispenser's bow): when the result slot empties because the key was
	 * consumed, ask GridTopUp to restage (ring upkeep + next key insert, all
	 * ordinary clicks) instead of just waiting. Null = plain T1 extraction.
	 */
	private static RecipeIngredientSummary keyCycleSummary = null;
	/** The batch's recipe layout, for recipe-aware staged-copies counting. */
	private static RecipeIngredientSummary recipeSummary = null;
	/** Eject-mode chain final: THROW outputs instead of banking them. */
	private static boolean ejectOutputs = false;

	private GridExtractor() {
	}

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(GridExtractor::tick);
	}

	static boolean isActive() {
		return active;
	}

	/**
	 * T1 eligibility: every ingredient stackable, and none leaves a crafting
	 * remainder (bucket/bottle-style returns land in the grid mid-extraction
	 * and would stall the result slot).
	 */
	static boolean isEligibleSummary(RecipeIngredientSummary summary) {
		if (summary == null || summary.slots().isEmpty()) {
			return false;
		}
		boolean sawIngredient = false;
		for (RecipeIngredientSummary.IngredientSlot slot : summary.slots()) {
			if (slot.isEmpty()) {
				continue;
			}
			sawIngredient = true;
			if (slot.maxStackSize() <= 1) {
				return false;
			}
			for (String itemId : slot.itemIds()) {
				var item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(itemId));
				if (item != null && !item.getCraftingRemainder().isEmpty()) {
					return false;
				}
			}
		}
		return sawIngredient;
	}

	/** Arm a T1 extraction of exactly {@code copies} crafts of {@code output}. */
	static void begin(ItemStack output, int copies, RecipeIngredientSummary summary) {
		begin(output, copies, summary, false);
	}

	/**
	 * Arm a batch. {@code summary} drives recipe-aware staged counting for
	 * every mode; with {@code keyCycle} GridTopUp also re-inserts the single
	 * unstackable slot between crafts (T2 chain finals).
	 */
	static void begin(ItemStack output, int copies, RecipeIngredientSummary summary, boolean keyCycle) {
		active = true;
		expectedOutput = output != null ? output.copy() : ItemStack.EMPTY;
		targetCopies = Math.max(copies, 1);
		craftedCopies = 0;
		totalTicks = 0;
		quietTicks = 0;
		resultStableTicks = 0;
		recipeSummary = summary;
		keyCycleSummary = keyCycle ? summary : null;
		// Honor the session's output policy: an eject-mode bulk chain throws
		// final outputs to keep the inventory fluid; banking them instead
		// (observed: 4 stacks of dispensers accumulating) starves later
		// batches of slots and shrinks iteration sizes.
		ejectOutputs = keyCycle && BulkChainCraftController.shouldDirectEjectCurrentResult();
		ReachCraftingMod.LOGGER.info(
			"[grid_extract] armed target_copies={} output={} key_cycle={} eject={}",
			targetCopies,
			ContainerUtils.formatStack(expectedOutput),
			keyCycle,
			ejectOutputs
		);
	}

	private static void tick(Minecraft client) {
		if (!active) {
			return;
		}
		if (client.player == null || client.gameMode == null
			|| (!(client.screen instanceof CraftingScreen) && !(client.screen instanceof InventoryScreen))) {
			finish(client, craftedCopies > 0, "context_lost");
			return;
		}
		totalTicks++;
		if (totalTicks > OVERALL_TIMEOUT_TICKS) {
			finish(client, craftedCopies > 0, "timeout");
			return;
		}
		AbstractContainerMenu menu = client.player.containerMenu;
		Slot resultSlot = menu.getSlot(0);
		int clicksThisTick = 0;
		while (clicksThisTick < MAX_CLICKS_PER_TICK) {
			if (!GridTopUp.clickBudgetAllows(2)) {
				return; // click governor engaged; resume next tick
			}
			ItemStack carried = menu.getCarried();
			if (craftedCopies >= targetCopies) {
				if (!carried.isEmpty()) {
					if (!depositCarried(client, menu)) {
						// No inventory room: the carried stack still counts as
						// produced (settlement reads the cursor) and the chain's
						// stow machinery resolves it before the next step.
						finish(client, true, "deposit_blocked");
						return;
					}
					clicksThisTick++;
					continue;
				}
				finish(client, true, "target_reached");
				return;
			}
			ItemStack result = resultSlot.getItem();
			if (result.isEmpty() || !ItemStack.isSameItemSameComponents(result, expectedOutput)) {
				resultStableTicks = 0;
				// Key-cycle: an empty/foreign result usually just means the
				// key item (bow) was consumed by the last craft — restage via
				// clicks (ring upkeep + next key insert) and the predicted
				// result reappears immediately. A cursor stack must be banked
				// first: GridTopUp declines over a non-empty cursor.
				if (keyCycleSummary != null) {
					if (!carried.isEmpty()) {
						if (!depositCarried(client, menu)) {
							finish(client, craftedCopies > 0, "deposit_blocked");
							return;
						}
						clicksThisTick++;
						continue;
					}
					if (GridTopUp.tryStageInsteadOfPlace(client, client.player, keyCycleSummary)) {
						quietTicks = 0;
						clicksThisTick++; // staging spent clicks; recount next loop
						continue;
					}
				}
				// T1 with progress and a verifiably empty grid: spent, done —
				// no need to burn the quiet-tick window (client prediction
				// empties the grid synchronously with the last quick-move).
				if (keyCycleSummary == null && craftedCopies > 0 && rawStagedCopies(menu) == 0 && carried.isEmpty()) {
					finish(client, true, "grid_spent");
					return;
				}
				// Either the budget-deferred placement has not landed yet, a
				// transient preview is settling, or the staged grid is spent.
				quietTicks++;
				if (craftedCopies == 0 && quietTicks > STAGING_WAIT_TIMEOUT_TICKS) {
					finish(client, false, "staging_never_arrived");
				} else if (craftedCopies > 0 && quietTicks > RESULT_QUIET_FINISH_TICKS) {
					finish(client, true, "grid_spent");
				}
				return;
			}
			quietTicks = 0;
			resultStableTicks++;
			int remaining = targetCopies - craftedCopies;
			if (ejectOutputs) {
				// T2 eject: the ring holds exactly ONE key item (bow), so a
				// THROW crafts-and-drops exactly one copy — no staged-count
				// estimate needed, and the inventory is never touched.
				ItemStack thrown = result.copy();
				client.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 1, ClickType.THROW, client.player);
				GridTopUp.recordClick();
				int thrownItems = Math.max(thrown.getCount(), 1);
				BulkChainCraftController.addEjectedOutput(thrown, thrownItems);
				ChainCraftController.noteFinalOutputEjected(thrownItems);
				craftedCopies++;
				clicksThisTick++;
				continue;
			}
			// Fast path: ONE QUICK_MOVE (vanilla shift-click) consumes the
			// whole staged batch when it fits the remaining target. The craft
			// count must be RELIABLE first, and both estimators lie in a
			// window each:
			// - the recipe-aware map misreads shaped rows the server placed
			//   at an offset (returned 0 -> "1" -> craft-all overshot 36/9);
			// - the raw min over non-empty slots reads an EMPTY grid in the
			//   tick where the server's result update lands before the grid
			//   slot updates (also "1" -> craft-all).
			// So: skip the first matching tick entirely (grid still syncing;
			// the counted pickup below crafts a safe single copy), then take
			// the MAX of both estimators — overestimating skips the fast
			// path, which only costs clicks, never materials — and require a
			// genuine >=2 reading.
			int staged = Math.max(
				GridTopUp.recipeAwareStagedCopies(menu, recipeSummary, 0),
				rawStagedCopies(menu));
			// Anti-clog: unstackable outputs land one per inventory slot, so
			// craft-all needs VERIFIED room; stackable outputs merge freely.
			boolean fastPathSafe = expectedOutput.getMaxStackSize() > 1
				|| emptyInventorySlots(menu) >= staged;
			if (resultStableTicks >= 2 && staged >= 2 && staged <= remaining && fastPathSafe) {
				client.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 0, ClickType.QUICK_MOVE, client.player);
				GridTopUp.recordClick();
				craftedCopies += staged;
				clicksThisTick++;
				continue;
			}
			if (!carried.isEmpty()
				&& (!ItemStack.isSameItemSameComponents(carried, result)
					|| carried.getCount() + result.getCount() > carried.getMaxStackSize())) {
				// Cursor cannot absorb the next craft: bank it first.
				if (!depositCarried(client, menu)) {
					finish(client, true, "deposit_blocked");
					return;
				}
				clicksThisTick++;
				continue;
			}
			client.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 0, ClickType.PICKUP, client.player);
			GridTopUp.recordClick();
			craftedCopies++;
			clicksThisTick++;
		}
	}

	/**
	 * Copies staged right now = the smallest stack among occupied grid slots.
	 * Valid whenever the result slot shows the expected output (grid complete);
	 * position-agnostic, unlike the summary-index mapping.
	 */
	private static int rawStagedCopies(AbstractContainerMenu menu) {
		int gridCount = menu instanceof net.minecraft.world.inventory.CraftingMenu ? 9
			: menu instanceof net.minecraft.world.inventory.InventoryMenu ? 4 : 0;
		int staged = Integer.MAX_VALUE;
		for (int i = 1; i <= gridCount; i++) {
			ItemStack inGrid = menu.getSlot(i).getItem();
			if (!inGrid.isEmpty()) {
				staged = Math.min(staged, inGrid.getCount());
			}
		}
		return staged == Integer.MAX_VALUE ? 0 : staged;
	}

	private static int emptyInventorySlots(AbstractContainerMenu menu) {
		int empty = 0;
		for (Slot slot : menu.slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory && !slot.hasItem()) {
				empty++;
			}
		}
		return empty;
	}

	private static boolean depositCarried(Minecraft client, AbstractContainerMenu menu) {
		ItemStack carried = menu.getCarried();
		if (carried.isEmpty()) {
			return true;
		}
		String itemId = BuiltInRegistries.ITEM.getKey(carried.getItem()).toString();
		Slot destination = MenuTransferHelper.findPlayerDestinationSlot(client.player, menu, itemId);
		if (destination == null) {
			return false;
		}
		client.gameMode.handleInventoryMouseClick(menu.containerId, destination.index, 0, ClickType.PICKUP, client.player);
		GridTopUp.recordClick();
		return true;
	}

	private static void finish(Minecraft client, boolean success, String reason) {
		ReachCraftingMod.LOGGER.info(
			"[grid_extract] finished success={} reason={} crafted={}/{} ticks={}",
			success,
			reason,
			craftedCopies,
			targetCopies,
			totalTicks
		);
		active = false;
		expectedOutput = ItemStack.EMPTY;
		targetCopies = 0;
		craftedCopies = 0;
		totalTicks = 0;
		quietTicks = 0;
		resultStableTicks = 0;
		keyCycleSummary = null;
		recipeSummary = null;
		ejectOutputs = false;
		ChainCraftController.onAutoMoveFinished(client, success);
	}
}
