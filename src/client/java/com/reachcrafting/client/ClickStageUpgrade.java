package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Upgrades a placement-fed craft to click staging once the click window has
 * room again.
 *
 * When the click governor declines, the craft falls back to one rationed
 * placement packet per copy - about 4/s, so a 55-copy craft takes ~13s. That
 * decision used to be made once and then committed to for the whole craft,
 * even though the governor's window keeps freeing continuously: by the time a
 * few placements have dribbled out, there is usually room to click-stage the
 * rest. Aborting such a craft and immediately re-requesting it almost always
 * took the fast path, which is what proved the budget was there all along.
 *
 * So this does not WAIT - waiting for the window is what silently dropped
 * crafts before (see the revert in ab6c965). The slow path starts normally and
 * runs underneath; each tick this checks whether clicking has become
 * affordable and, if so, swaps the remaining placements for clicks. If
 * anything about the upgrade fails the queued placements are restored, so the
 * worst case is simply the slow path we already had.
 */
final class ClickStageUpgrade {

	// Long enough for a saturated window to free up (it is a 7s sliding
	// expiry) without outliving the craft that armed it.
	private static final int ARMED_TICKS = 160;

	private static RecipeHolder<?> recipe;
	private static RecipeCollection collection;
	private static int containerId;
	private static int targetCopies;
	private static int recipeIndex;
	private static int ticksRemaining;

	private ClickStageUpgrade() {
	}

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(ClickStageUpgrade::tick);
	}

	static void arm(RecipeHolder<?> holder, RecipeCollection recipeCollection, int menuContainerId, int copies, int index) {
		recipe = holder;
		collection = recipeCollection;
		containerId = menuContainerId;
		targetCopies = copies;
		recipeIndex = index;
		ticksRemaining = ARMED_TICKS;
	}

	static void clear() {
		recipe = null;
		collection = null;
		ticksRemaining = 0;
	}

	private static void tick(Minecraft client) {
		if (recipe == null) {
			return;
		}
		if (--ticksRemaining <= 0 || client.player == null || client.gameMode == null) {
			clear();
			return;
		}
		if (client.player.containerMenu.containerId != containerId) {
			clear(); // different screen: this craft is over one way or another
			return;
		}
		int pending = PlaceRecipeBudget.pendingCount();
		if (pending <= 0) {
			clear(); // the queue drained on its own; nothing left to upgrade
			return;
		}

		RecipeIngredientSummary summary = GridTopUp.resolveSummary(client, recipe.id(), collection);
		if (summary == null) {
			clear();
			return;
		}
		List<String> slotChoices =
			ManualRecipePlacer.resolveSlotChoicesFromInventory(client.player.containerMenu, summary, false);
		if (slotChoices.isEmpty()) {
			return; // inputs not reachable right now; keep waiting
		}
		if (!GridTopUp.clickBudgetAllowsQuietly(estimateClicks(slotChoices))) {
			return; // still saturated - the slow path keeps running underneath
		}

		// Committed: drop the queued placements so they cannot interleave with
		// the clicks, then stage. Restore them if the staging does not happen,
		// so a failed upgrade costs nothing but the attempt.
		int dropped = PlaceRecipeBudget.clearDeferred();
		int staged = ManualRecipePlacer.placeCrafts(
			client, summary, slotChoices, targetCopies, false, "idx=" + recipeIndex, true);
		if (staged > 0) {
			ReachCraftingMod.LOGGER.info(
				"[recipe_place] click_stage_upgraded idx={} staged={} target={} placements_cancelled={}",
				recipeIndex, staged, targetCopies, dropped
			);
			clear();
			return;
		}
		ReachCraftingMod.LOGGER.info(
			"[recipe_place] click_stage_upgrade_failed idx={} restoring={} placements", recipeIndex, dropped);
		for (int i = 0; i < dropped; i++) {
			client.gameMode.handlePlaceRecipe(containerId, recipe, false);
		}
		clear();
	}

	/** Mirrors SearchSession's per-ingredient-group cost model. */
	private static int estimateClicks(List<String> slotChoices) {
		java.util.Map<String, Integer> groupSizes = new java.util.LinkedHashMap<>();
		for (String choice : slotChoices) {
			if (choice != null) {
				groupSizes.merge(choice, 1, Integer::sum);
			}
		}
		int estimate = 0;
		for (int groupSlots : groupSizes.values()) {
			int perRound = Math.max(1, 64 / groupSlots);
			int rounds = groupSlots > 1 ? targetCopies / perRound : 0;
			int remainder = targetCopies - rounds * perRound;
			int perSlot = Math.min(remainder, Math.max(1, 64 - remainder));
			estimate += rounds * (groupSlots + 4) + groupSlots * (perSlot + 2);
		}
		return estimate;
	}
}
