package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;

/**
 * Outer loop for bulk chain crafting. Each iteration re-plans a fresh chain
 * batch from live inventory + nearby counts and hands it to
 * ChainCraftController, so leftovers and byproducts from earlier batches are
 * consumed before new intermediates are crafted. The loop ends when the
 * requested total is reached, materials run out, or progress stalls.
 */
public final class BulkChainCraftController {
	private static final int MAX_BATCH_FINAL_COPIES = 64;
	private static final int MAX_CONSECUTIVE_STALLED_ITERATIONS = 2;
	private static BulkChainSession activeSession;
	private static int settleDelayTicks;

	private BulkChainCraftController() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(BulkChainCraftController::tick);
	}

	static void start(RecipeVariantResolver.Selection selection, boolean allowNearby, int requestedTotalCopies) {
		if (selection == null || selection.displayStack().isEmpty() || requestedTotalCopies <= 0) {
			return;
		}
		activeSession = BulkChainSession.start(selection, allowNearby, requestedTotalCopies);
		settleDelayTicks = 0;
		BulkDespawnWarning.noteSessionStart();
		// The user just confirmed a bulk session; if a stray hold-state wipe
		// (popup screen swap, alt-release race) dropped the latch in the
		// meantime, re-assert it rather than dying on the first tick. A real
		// mid-session toggle-off still aborts via the tick check.
		if (!AutoCraftController.isBulkModeEnabled()) {
			ReachCraftingMod.LOGGER.info("[bulk_chain] re_latching_bulk_mode_for_confirmed_session");
			AutoCraftController.setEnabledMode(ReachCraftingConfig.AutoCraftMode.BULK);
		}
		ReachCraftingMod.LOGGER.info(
			"[bulk_chain] session_start recipe={} output={} requested_copies={} allow_nearby={}",
			selection.recipeId(),
			ContainerUtils.formatStack(selection.displayStack()),
			requestedTotalCopies,
			allowNearby
		);
	}

	public static boolean isActive() {
		return activeSession != null;
	}

	static void addEjectedOutput(ItemStack ejectedStack, int count) {
		if (activeSession == null || !activeSession.chainRunning() || count <= 0) {
			return;
		}
		// Only the final output counts toward session progress; ejected
		// intermediates are replanned from live counts on the next iteration.
		if (ejectedStack == null
			|| !ItemStack.isSameItemSameComponents(ejectedStack, activeSession.selection().displayStack())) {
			return;
		}
		activeSession = activeSession.withEjected(activeSession.iterationEjectedOutputCount() + count);
		ReachCraftingMod.LOGGER.debug(
			"[bulk_chain] ejected_output_noted count={} iteration_total={}",
			count,
			activeSession.iterationEjectedOutputCount()
		);
	}

	/**
	 * Flat bulk parity for the final chain step: while more iterations
	 * remain, finished output is thrown as it crafts so its slots stay free
	 * for staging; the last iteration keeps its output like FINAL_BATCH_KEEP.
	 */
	static boolean shouldDirectEjectCurrentResult() {
		if (activeSession == null
			|| !activeSession.chainRunning()
			|| !activeSession.allowNearby()
			|| !ChainCraftController.isRunningFinalStep()
			|| !ReachCraftingConfig.get().ejectItemsWhenFull()) {
			return false;
		}
		return activeSession.completedCopies() + activeSession.plannedIterationCopies() < activeSession.requestedTotalCopies();
	}

	static void stop(boolean aborted, String reason) {
		// Never leave an orphaned chain run replaying without an owning session.
		if (ChainCraftController.isActive()) {
			ChainCraftController.abort(false);
		}
		if (activeSession != null) {
			ReachCraftingMod.LOGGER.info(
				"[bulk_chain] session_stop aborted={} reason={} completed={}/{} output={}",
				aborted,
				reason,
				activeSession.completedCopies(),
				activeSession.requestedTotalCopies(),
				ContainerUtils.formatStack(activeSession.selection().displayStack())
			);
			if (activeSession.completedCopies() > 0) {
				ReachCraftingConfig.get().noteRecentRecipe(activeSession.selection().recipeId());
			}
			String status = aborted ? "terminated" : "complete";
			String itemName = activeSession.selection().displayStack().getHoverName().getString();
			int outputPerCraft = Math.max(activeSession.selection().displayStack().getCount(), 1);
			int craftedItems = activeSession.completedCopies() * outputPerCraft;
			ReachCraftingModClient.sendBulkSummaryChat(
				"Bulk chain craft " + status + ": Crafted " + ContainerUtils.formatStackBreakdown(craftedItems) + " " + itemName
			);
		}
		Minecraft client = Minecraft.getInstance();
		AutoCraftController.finishBulkSessionTeardown();
		clear();
		// Return accumulated leftover pulled materials to their chests when
		// the player is still present at the screen. Skipped for the screen
		// close path (its own flush runs right after abortAllSessions and
		// would double-start the return) and for focus loss (the pause
		// screen would immediately abort the return session anyway).
		boolean returnLeftovers = !"abort_all_sessions".equals(reason) && !"window_focus_lost".equals(reason);
		if (returnLeftovers
			&& client.player != null
			&& (client.gui.screen() instanceof CraftingScreen || client.gui.screen() instanceof InventoryScreen)) {
			ContainerUtils.flushCraftingGrid(client, true, false);
		}
	}

	static void clear() {
		activeSession = null;
		settleDelayTicks = 0;
		BulkDespawnWarning.clear();
	}

	private static void tick(Minecraft client) {
		if (activeSession == null) {
			return;
		}
		if (client.player == null) {
			clear();
			return;
		}
		BulkDespawnWarning.tick();
		if (!client.isWindowActive()) {
			stop(true, "window_focus_lost");
			return;
		}
		if (!AutoCraftController.isBulkModeEnabled()) {
			ReachCraftingMod.LOGGER.info("[bulk_chain] bulk_mode_disabled_detail {}", AutoCraftController.describeHoldState());
			stop(true, "bulk_mode_disabled");
			return;
		}
		if (ChainCraftController.isActive()
			|| ContainerUtils.isInputQueueActive()
			|| ContainerUtils.isAutoMovePending()
			|| NearbyContainerDryRun.isActiveSessionRunning()
			|| InventoryGridRestoreTracker.isRestoring()) {
			return;
		}
		if (activeSession.chainRunning()) {
			accountFinishedIteration(client);
			return;
		}
		if (settleDelayTicks > 0) {
			settleDelayTicks--;
			return;
		}
		Screen screen = client.gui.screen();
		if (!(screen instanceof CraftingScreen) && !(screen instanceof InventoryScreen)) {
			stop(true, "context_lost");
			return;
		}
		startNextIteration(client);
	}

	private static void accountFinishedIteration(Minecraft client) {
		BulkChainSession session = activeSession;
		int outputPerCraft = Math.max(session.selection().displayStack().getCount(), 1);
		// Bulk's counter also covers the result slot and cursor, so output
		// stranded there by a full inventory still registers as progress.
		int currentOutputCount = BulkAutoCraftController.countAccessibleOutput(client, session.selection().displayStack());
		int inventoryIncrease = Math.max(0, currentOutputCount - session.iterationBaselineOutputCount());
		int gainedOutputCount = inventoryIncrease + session.iterationEjectedOutputCount();
		int craftedCopies = gainedOutputCount / outputPerCraft;
		ReachCraftingMod.LOGGER.info(
			"[bulk_chain] iteration_finished crafted_copies={} inventory_increase={} ejected={} completed_before={}/{}",
			craftedCopies,
			inventoryIncrease,
			session.iterationEjectedOutputCount(),
			session.completedCopies(),
			session.requestedTotalCopies()
		);

		if (craftedCopies <= 0) {
			int stalled = session.stalledIterations() + 1;
			if (stalled > MAX_CONSECUTIVE_STALLED_ITERATIONS) {
				stop(true, "no_progress_detected");
				return;
			}
			// Retry with a smaller batch: a stalled chain is most often an
			// inventory-fit failure, which a smaller batch can clear.
			activeSession = session.withIterationAccounted(0, stalled, Math.max(1, session.batchCap() / 2));
			settleDelayTicks = 1;
			return;
		}

		// Start the next size search near what just fit (doubled so batches
		// can grow as slots free up) instead of re-walking down from the max
		// every iteration — on a cramped inventory that ladder alone cost
		// six planner passes per batch.
		int nextCap = Math.min(MAX_BATCH_FINAL_COPIES, Math.max(4, session.plannedIterationCopies() * 2));
		activeSession = session.withIterationAccounted(craftedCopies, 0, nextCap);
		if (activeSession.completedCopies() >= activeSession.requestedTotalCopies()) {
			stop(false, "requested_copies_completed");
			return;
		}
		settleDelayTicks = 1;
	}

	private static void startNextIteration(Minecraft client) {
		BulkChainSession session = activeSession;
		int remaining = session.requestedTotalCopies() - session.completedCopies();
		int batchTarget = Math.min(Math.max(remaining, 1), session.batchCap());
		Map<String, Integer> availableCounts = collectAvailableCounts(client, session.allowNearby());
		Optional<ChainCraftPlan> plan = ChainCraftPlanner.planMax(
			client,
			client.player,
			session.selection(),
			availableCounts,
			session.allowNearby(),
			batchTarget,
			true
		);
		if (plan.isEmpty()) {
			stop(false, "materials_exhausted");
			return;
		}

		// Size the batch so staged materials, in-flight intermediates, and
		// outputs all fit in the player inventory. This is what keeps a
		// non-stackable intermediate (64 bows = 64 slots) from clogging the
		// inventory. Bisect for the LARGEST fitting copy count — plain
		// halving overshoots downward and can waste a third of the usable
		// slots every iteration (observed: batches of 16 leaving a whole
		// row idle, and 2 where 3 would fit).
		if (!ChainInventoryFitEstimator.planFits(client, client.player, plan.get(), willEjectFinalOutputs(session, plan.get()))) {
			int failCopies = plan.get().finalRecipeCopies();
			ReachCraftingMod.LOGGER.info("[bulk_chain] batch_shrink from_copies={} reason=inventory_fit", failCopies);
			Optional<ChainCraftPlan> best = Optional.empty();
			int lo = 0;
			int hi = failCopies;
			while (hi - lo > 1) {
				int mid = (lo + hi) / 2;
				Optional<ChainCraftPlan> candidate = ChainCraftPlanner.planMax(
					client,
					client.player,
					session.selection(),
					availableCounts,
					session.allowNearby(),
					mid,
					true
				);
				if (candidate.isEmpty()) {
					hi = mid;
					continue;
				}
				if (ChainInventoryFitEstimator.planFits(client, client.player, candidate.get(), willEjectFinalOutputs(session, candidate.get()))) {
					best = candidate;
					if (candidate.get().finalRecipeCopies() < mid) {
						// Materials cap below mid: this is already the max.
						break;
					}
					lo = candidate.get().finalRecipeCopies();
				} else {
					hi = Math.min(mid, candidate.get().finalRecipeCopies());
				}
			}
			plan = best;
		}
		if (plan.isEmpty()) {
			ReachCraftingModClient.sendChat("Bulk chain craft stopped: not enough inventory space to continue. Free up some slots and request again.");
			stop(true, "inventory_full");
			return;
		}
		int baselineOutputCount = BulkAutoCraftController.countAccessibleOutput(client, session.selection().displayStack());
		activeSession = session.withChainStarted(baselineOutputCount, plan.get().finalRecipeCopies());
		ReachCraftingMod.LOGGER.info(
			"[bulk_chain] iteration_start batch_copies={} steps={} remaining={} batch_cap={}",
			plan.get().finalRecipeCopies(),
			plan.get().steps().size(),
			remaining,
			session.batchCap()
		);
		ChainCraftController.start(plan.get());
		if (!ChainCraftController.isActive()) {
			stop(true, "chain_start_failed");
		}
	}

	private static boolean willEjectFinalOutputs(BulkChainSession session, ChainCraftPlan plan) {
		return session.allowNearby()
			&& ReachCraftingConfig.get().ejectItemsWhenFull()
			&& session.completedCopies() + plan.finalRecipeCopies() < session.requestedTotalCopies();
	}

	// Must stay in lockstep with the chainAvailableCounts construction in
	// RecipeClickExecutor.executeRecipeButtonClick so per-iteration replans
	// see the same availability the original offer was planned against.
	private static Map<String, Integer> collectAvailableCounts(Minecraft client, boolean allowNearby) {
		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(client.player, client.gui.screen());
		Map<String, Integer> counts = new LinkedHashMap<>(availableItems.totalCounts());
		if (allowNearby && ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(
				client.level,
				client.getCameraEntity(),
				client.player.blockInteractionRange()
			);
			counts = AvailableItemSnapshot.mergeCounts(counts, reachableView.aggregateCounts());
		}
		return ContainerUtils.subtractNonPristineLocalStacks(client, counts);
	}

	private record BulkChainSession(
		RecipeVariantResolver.Selection selection,
		boolean allowNearby,
		int requestedTotalCopies,
		int completedCopies,
		int iterationBaselineOutputCount,
		int iterationEjectedOutputCount,
		boolean chainRunning,
		int stalledIterations,
		int batchCap,
		int plannedIterationCopies
	) {
		private static BulkChainSession start(RecipeVariantResolver.Selection selection, boolean allowNearby, int requestedTotalCopies) {
			return new BulkChainSession(selection, allowNearby, requestedTotalCopies, 0, 0, 0, false, 0, MAX_BATCH_FINAL_COPIES, 0);
		}

		BulkChainSession withChainStarted(int baselineOutputCount, int iterationCopies) {
			return new BulkChainSession(selection, allowNearby, requestedTotalCopies, completedCopies, baselineOutputCount, 0, true, stalledIterations, batchCap, iterationCopies);
		}

		BulkChainSession withEjected(int ejectedCount) {
			return new BulkChainSession(selection, allowNearby, requestedTotalCopies, completedCopies, iterationBaselineOutputCount, ejectedCount, chainRunning, stalledIterations, batchCap, plannedIterationCopies);
		}

		BulkChainSession withIterationAccounted(int craftedCopies, int updatedStalledIterations, int updatedBatchCap) {
			// The output baseline is recaptured fresh in withChainStarted, so
			// no observed count needs to carry across iterations.
			return new BulkChainSession(
				selection,
				allowNearby,
				requestedTotalCopies,
				completedCopies + craftedCopies,
				0,
				0,
				false,
				updatedStalledIterations,
				updatedBatchCap,
				0
			);
		}
	}
}
