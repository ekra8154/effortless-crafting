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
	private static final int MAX_CLICKS_PER_TICK = 3;
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

	/** Arm extraction of exactly {@code copies} crafts of {@code output}. */
	static void begin(ItemStack output, int copies) {
		active = true;
		expectedOutput = output != null ? output.copy() : ItemStack.EMPTY;
		targetCopies = Math.max(copies, 1);
		craftedCopies = 0;
		totalTicks = 0;
		quietTicks = 0;
		ReachCraftingMod.LOGGER.info(
			"[grid_extract] armed target_copies={} output={}",
			targetCopies,
			ContainerUtils.formatStack(expectedOutput)
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
		ChainCraftController.onAutoMoveFinished(client, success);
	}
}
