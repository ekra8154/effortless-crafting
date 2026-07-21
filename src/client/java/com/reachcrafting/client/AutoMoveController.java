package com.reachcrafting.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class AutoMoveController {
	private static final int BULK_RESULT_WAIT_TIMEOUT_TICKS = 20;
	private static final int ORGANIZE_TARGET_ARRIVAL_WAIT_TICKS = 10;
	// A foreign result must PERSIST this many consecutive ticks before it
	// counts as a recipe change. While a placement stages the grid slot by
	// slot, the partial grid transiently completes other recipes (3 cobble in
	// a row previews cobblestone_slab mid-dispenser-place) and the preview
	// settles to the real output within a tick or two of the last slot
	// arriving. Insta-failing on that window cost a backoff + a phantom drop
	// fed into the place budget's AIMD (9 times per 108-craft soak).
	private static final int FOREIGN_RESULT_DEBOUNCE_TICKS = 5;
	private static int foreignResultTicks = 0;
	private static int autoMoveWaitingTicks = 0;
	private static boolean pendingAutoMove = false;
	private static ItemStack autoMoveTargetStack = ItemStack.EMPTY;
	private static ItemStack autoMoveExpectedStack = ItemStack.EMPTY;
	private static final Map<Integer, Integer> autoMoveSnapshotCounts = new HashMap<>();
	private static boolean autoMoveOrganizing = false;
	private static boolean autoMoveTargetArrivalObserved = false;
	private static boolean directEjectAwaitingSettlement = false;
	private static int directEjectSettlementTicks = 0;
	private static int directEjectPendingCount = 0;
	private static int directEjectAwaitingStagedCopiesTicks = 0;
	private static boolean chainEjectAwaitingRefresh = false;
	private static int chainEjectRefreshTicks = 0;
	private static int chainEjectLastGridTotal = Integer.MAX_VALUE;
	private static int chainEjectRethrowStallTicks = 0;
	private static int chainEjectRefillGapTicks = 0;
	private static int chainEjectPendingCount = 0;
	private static ItemStack chainEjectPendingStack = ItemStack.EMPTY;
	// Grid snapshots taken at the moment of a result-slot THROW. A THROW with
	// a staged grid is vanilla craft-all-drop: how many copies it ACTUALLY
	// crafts is whatever the server honors (all staged on vanilla/Paper; ~1
	// under click-rewriting anti-cheat plugins). Predicted counts lied in
	// both directions in the field — copper grate credited 64 copies per
	// throw the server executed once; sticky piston credited 1 for throws
	// that crafted 60+ (recipe-aware estimator can't read offset-placed
	// shapes). The settlement blocks credit the OBSERVED grid drain instead.
	private static ItemStack[] chainEjectGridBefore = null;
	private static int chainEjectPerCraftCount = 1;
	private static ItemStack[] directEjectGridBefore = null;
	private static int directEjectPerCraftCount = 1;
	// Direct-eject re-throw for craft-one-per-throw servers: native <=1.21.x (and
	// anti-cheat rewriters) execute only ONE craft per result-slot THROW, then
	// refill the result slot from the still-full grid. The old logic threw once
	// and waited for an EMPTY slot, so the refill hung the session forever
	// (26.2 masked it: one throw drains the whole staged batch, slot empties in a
	// tick). We now re-issue the throw while the slot shows the expected output
	// and the grid still has ingredients. directEjectLastGridTotal + the stall
	// counter stop us if the grid ever stops draining (a true reject), so a
	// non-crafting server can't spin the loop.
	private static int directEjectLastGridTotal = Integer.MAX_VALUE;
	private static int directEjectRethrowStallTicks = 0;
	private static final int DIRECT_EJECT_RETHROW_STALL_LIMIT = 60;
	// Consecutive ticks the result slot has NOT shown the expected output while
	// the cursor is empty. Once the last throw stops refilling the slot (grid
	// drained, OR the grid holds only crafting remainders like cake's empty
	// buckets so the recipe no longer forms), this rises past the debounce and
	// we settle. The debounce rides over the ~1-tick empty window right after a
	// throw so we don't settle mid-drain.
	private static int directEjectRefillGapTicks = 0;
	private static final int DIRECT_EJECT_REFILL_GAP_LIMIT = 8;

	private AutoMoveController() {
	}

	static void scheduleAutoMove(ItemStack expectedStack) {
		pendingAutoMove = true;
		autoMoveWaitingTicks = 0;
		foreignResultTicks = 0;
		autoMoveTargetArrivalObserved = false;
		directEjectAwaitingStagedCopiesTicks = 0;
		autoMoveExpectedStack = expectedStack != null ? expectedStack.copy() : ItemStack.EMPTY;
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[auto_move] scheduleAutoMove expected={} bulk_active={} bulk_mode={}",
			ContainerUtils.formatStack(autoMoveExpectedStack),
			BulkAutoCraftController.isActive(),
			AutoCraftController.isBulkModeEnabled()
		);
	}

	private static ItemStack[] snapshotGridSlots(AbstractContainerMenu menu) {
		int gridCount = menu instanceof net.minecraft.world.inventory.CraftingMenu ? 9
			: menu instanceof InventoryMenu ? 4 : 0;
		ItemStack[] snapshot = new ItemStack[gridCount];
		for (int i = 0; i < gridCount; i++) {
			snapshot[i] = menu.getSlot(1 + i).getItem().copy();
		}
		return snapshot;
	}

	// Total ingredient count across the grid slots. Direct-eject settlement uses
	// this to tell "batch drained, done" (0) from "slot refilled, keep throwing"
	// (>0) on craft-one-per-throw servers, and to detect drain progress.
	private static int gridItemTotal(AbstractContainerMenu menu) {
		int gridCount = menu instanceof net.minecraft.world.inventory.CraftingMenu ? 9
			: menu instanceof InventoryMenu ? 4 : 0;
		int total = 0;
		for (int i = 0; i < gridCount; i++) {
			total += menu.getSlot(1 + i).getItem().getCount();
		}
		return total;
	}

	/**
	 * Crafts the server actually executed since {@code before}: the minimum
	 * per-slot drain across every slot that was staged at throw time. An
	 * emptied slot or a slot now holding a different item (crafting
	 * remainder, e.g. cake's buckets) counts as fully drained. Returns -1
	 * when the snapshot is unusable (no occupied slots recorded).
	 */
	private static int observedGridDrainCrafts(AbstractContainerMenu menu, ItemStack[] before) {
		if (before == null || before.length == 0 || menu.slots.size() <= before.length) {
			return -1;
		}
		int drain = Integer.MAX_VALUE;
		boolean sawOccupied = false;
		for (int i = 0; i < before.length; i++) {
			ItemStack was = before[i];
			if (was.isEmpty()) {
				continue;
			}
			sawOccupied = true;
			ItemStack now = menu.getSlot(1 + i).getItem();
			int slotDrain;
			if (now.isEmpty() || !ItemStack.isSameItemSameComponents(now, was)) {
				slotDrain = was.getCount();
			} else {
				slotDrain = Math.max(0, was.getCount() - now.getCount());
			}
			drain = Math.min(drain, slotDrain);
		}
		return sawOccupied ? drain : -1;
	}

	/** Observed-vs-predicted eject credit: prefer what the grid actually
	 * drained; fall back to the prediction only when no snapshot exists. */
	private static int resolveEjectCredit(AbstractContainerMenu menu, ItemStack[] before, int perCraftCount, int predicted, String tag) {
		int observedCrafts = observedGridDrainCrafts(menu, before);
		if (observedCrafts < 0) {
			return predicted;
		}
		int observed = observedCrafts * Math.max(perCraftCount, 1);
		if (observed != predicted) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[auto_move] {} credit corrected: predicted={} observed={} (crafts={} x{})",
				tag, predicted, observed, observedCrafts, perCraftCount);
		}
		return observed;
	}

	static boolean isAutoMovePending() {
		// A GridExtractor batch is in-flight result work: every guard that
		// waits on a pending auto-move must wait on it the same way.
		return pendingAutoMove || GridExtractor.isActive();
	}

	static boolean isAutomatedInteractionRunning() {
		return pendingAutoMove || autoMoveOrganizing || GridExtractor.isActive() || NearbyContainerDryRun.isActiveSessionRunning() || InventoryGridRestoreTracker.isRestoring() || BulkAutoCraftController.isActive() || ChainCraftController.isActive();
	}

	static void settleCompletedWork(Minecraft client) {
		if (client == null || client.player == null || client.player.containerMenu == null) {
			return;
		}
		if (!pendingAutoMove && !autoMoveOrganizing && !directEjectAwaitingSettlement) {
			return;
		}
		if (client.player.containerMenu.slots.isEmpty()) {
			return;
		}

		AbstractContainerMenu menu = client.player.containerMenu;
		Slot resultSlot = menu.getSlot(0);
		boolean resultStillPresent = resultSlot.hasItem();
		boolean carriedStillPresent = !menu.getCarried().isEmpty();
		if (resultStillPresent || carriedStillPresent) {
			return;
		}

		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[auto_move] settling completed work before abort pending={} organizing={} directEjectAwaitingSettlement={} target={} expected={}",
			pendingAutoMove,
			autoMoveOrganizing,
			directEjectAwaitingSettlement,
			ContainerUtils.formatStack(autoMoveTargetStack),
			ContainerUtils.formatStack(autoMoveExpectedStack)
		);

		if (directEjectAwaitingSettlement && AutoCraftController.isBulkModeEnabled() && directEjectPendingCount > 0) {
			int settleCredit = resolveEjectCredit(
				menu, directEjectGridBefore, directEjectPerCraftCount, directEjectPendingCount, "direct eject (early settle)");
			if (settleCredit > 0) {
				BulkAutoCraftController.addEjectedOutput(settleCredit);
			}
		}

		pendingAutoMove = false;
		autoMoveOrganizing = false;
		autoMoveTargetArrivalObserved = false;
		directEjectAwaitingSettlement = false;
		directEjectSettlementTicks = 0;
		directEjectPendingCount = 0;
		directEjectAwaitingStagedCopiesTicks = 0;
		autoMoveWaitingTicks = 0;
		autoMoveTargetStack = ItemStack.EMPTY;
		autoMoveExpectedStack = ItemStack.EMPTY;
		autoMoveSnapshotCounts.clear();

		BulkAutoCraftController.onAutoMoveFinished(client, true);
		ChainCraftController.onAutoMoveFinished(client, true);
	}

	static void abort() {
		// com.reachcrafting.ReachCraftingMod.LOGGER.info(
		// 	"[auto_move] abort pending={} organizing={} directEjectNextTick={} pendingEjected={} target={} expected={}",
		// 	pendingAutoMove,
		// 	autoMoveOrganizing,
		// 	directEjectAwaitingSettlement,
		// 	directEjectPendingCount,
		// 	ContainerUtils.formatStack(autoMoveTargetStack),
		// 	ContainerUtils.formatStack(autoMoveExpectedStack)
		// );
		pendingAutoMove = false;
		autoMoveOrganizing = false;
		autoMoveTargetArrivalObserved = false;
		directEjectAwaitingSettlement = false;
		directEjectSettlementTicks = 0;
		directEjectPendingCount = 0;
		directEjectAwaitingStagedCopiesTicks = 0;
		chainEjectAwaitingRefresh = false;
		chainEjectRefreshTicks = 0;
		chainEjectLastGridTotal = Integer.MAX_VALUE;
		chainEjectRethrowStallTicks = 0;
		chainEjectRefillGapTicks = 0;
		chainEjectPendingCount = 0;
		chainEjectPendingStack = ItemStack.EMPTY;
		chainEjectGridBefore = null;
		directEjectGridBefore = null;
		autoMoveTargetStack = ItemStack.EMPTY;
		autoMoveExpectedStack = ItemStack.EMPTY;
		autoMoveSnapshotCounts.clear();
		OffhandConsolidationController.swapBack(Minecraft.getInstance());
	}

	static void autoMoveResult(Minecraft client) {
		if (GridExtractor.isActive()) {
			// A T1 counted extraction owns the result slot and the cursor;
			// running auto-move concurrently would fight it over both.
			return;
		}
		if (client.player == null || client.player.containerMenu == null) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] autoMoveResult exiting: player_or_menu_missing");
			pendingAutoMove = false;
			directEjectAwaitingStagedCopiesTicks = 0;
			return;
		}

		AbstractContainerMenu menu = client.player.containerMenu;
		if (AutoCraftController.isBulkModeEnabled()) {
			sweepAndEjectByProducts(client, menu);
		}

		if (client.gui.screen() == null || (!(client.gui.screen() instanceof CraftingScreen) && !(client.gui.screen() instanceof InventoryScreen))) {
			return;
		}

		if (menu.slots.isEmpty()) {
			return;
		}

		Slot resultSlot = menu.getSlot(0);
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[auto_move] tick pending={} organizing={} waitTicks={} directEjectNextTick={} pendingEjected={} result={} carried={}",
			pendingAutoMove,
			autoMoveOrganizing,
			autoMoveWaitingTicks,
			directEjectAwaitingSettlement,
			directEjectPendingCount,
			resultSlot.hasItem() ? ContainerUtils.formatStack(resultSlot.getItem()) : "<empty>",
			ContainerUtils.formatStack(menu.getCarried())
		);

		if (directEjectAwaitingSettlement) {
			directEjectSettlementTicks++;
			// A staged ring never leaves the result slot empty: with the key
			// (bow) consumed by the throw, the remaining ring completes a
			// FOREIGN recipe (dropper) and previews it indefinitely. Waiting
			// for an empty slot here hung the whole session (observed: 1200+
			// ticks). The throw is settled once the expected output is gone —
			// either the slot is empty OR it shows the ring's foreign preview.
			int gridTotalNow = gridItemTotal(menu);
			boolean carriedEmpty = menu.getCarried().isEmpty();
			boolean slotHasExpected = resultSlot.hasItem()
				&& !autoMoveExpectedStack.isEmpty()
				&& ItemStack.isSameItemSameComponents(resultSlot.getItem(), autoMoveExpectedStack);
			boolean directEjectForeignPreview = resultSlot.hasItem()
				&& !autoMoveExpectedStack.isEmpty()
				&& !ItemStack.isSameItemSameComponents(resultSlot.getItem(), autoMoveExpectedStack);

			// Craft-one-per-throw servers refill the result slot with our
			// expected output after each throw. Keep re-throwing while the grid
			// still has ingredients — this drains it copy by copy. The stall
			// guard settles anyway if the grid ever stops draining (true reject),
			// so a non-crafting server can't spin the loop. (On 26.2 the first
			// throw drains the whole batch, so the slot is already empty here and
			// this branch never runs.)
			if (carriedEmpty && slotHasExpected && gridTotalNow > 0) {
				if (gridTotalNow < directEjectLastGridTotal) {
					directEjectRethrowStallTicks = 0;
				} else {
					directEjectRethrowStallTicks++;
				}
				directEjectLastGridTotal = gridTotalNow;
				directEjectRefillGapTicks = 0;
				if (directEjectRethrowStallTicks < DIRECT_EJECT_RETHROW_STALL_LIMIT) {
					client.gameMode.handleContainerInput(menu.containerId, resultSlot.index, 1, ContainerInput.THROW, client.player);
					if (directEjectSettlementTicks % 20 == 1) {
						com.reachcrafting.ReachCraftingMod.LOGGER.debug(
							"[auto_move] direct eject re-throw (craft-one server): ticks={} grid_total={} stateId={}",
							directEjectSettlementTicks, gridTotalNow, menu.getStateId());
					}
					return;
				}
				com.reachcrafting.ReachCraftingMod.LOGGER.debug(
					"[auto_move] direct eject re-throw stalled at grid_total={} for {} ticks -> settling",
					gridTotalNow, directEjectRethrowStallTicks);
				// fall through to credit + finish
			} else {
				// Not draining right now. Settle only when the batch is truly
				// done: the slot no longer shows our output AND the grid is
				// depleted (an empty slot with grid>0 is the one-tick post-throw
				// window that refills next tick), or a foreign ring preview holds
				// the slot (bow-less ring), or the safety timeout fires.
				boolean foreignSettled = directEjectForeignPreview
					&& (GridTopUp.isRingAwaitingKeyItem(client, menu)
						// Summary-free fallback: a FOREIGN item holding the
						// result slot for 10+ ticks cannot be our pending
						// output — it is the ring preview even when the
						// session summary is unavailable to prove it.
						|| directEjectSettlementTicks > 10);
				if (carriedEmpty) {
					directEjectRefillGapTicks++;
				}
				boolean settled = carriedEmpty && !slotHasExpected
					&& (gridTotalNow == 0
						|| directEjectRefillGapTicks >= DIRECT_EJECT_REFILL_GAP_LIMIT
						|| foreignSettled);
				if (!settled) {
					if (directEjectSettlementTicks % 20 == 1) {
						com.reachcrafting.ReachCraftingMod.LOGGER.debug(
							"[auto_move] direct eject awaiting settlement: ticks={} result_now={} carried={} grid_total={} refill_gap={}",
							directEjectSettlementTicks,
							resultSlot.hasItem() ? ContainerUtils.formatStack(resultSlot.getItem()) : "<empty>",
							ContainerUtils.formatStack(menu.getCarried()),
							gridTotalNow,
							directEjectRefillGapTicks
						);
					}
					return;
				}
				// fall through to credit + finish
			}
			int directCredit = resolveEjectCredit(
				menu, directEjectGridBefore, directEjectPerCraftCount, directEjectPendingCount, "direct eject");
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[auto_move] direct eject settled: ticks={} crediting count={} (predicted={})",
				directEjectSettlementTicks,
				directCredit,
				directEjectPendingCount
			);
			directEjectAwaitingSettlement = false;
			directEjectSettlementTicks = 0;
			directEjectAwaitingStagedCopiesTicks = 0;
			directEjectLastGridTotal = Integer.MAX_VALUE;
			directEjectRethrowStallTicks = 0;
			directEjectRefillGapTicks = 0;
			pendingAutoMove = false;
			autoMoveOrganizing = false;
			autoMoveTargetArrivalObserved = false;
			autoMoveTargetStack = ItemStack.EMPTY;
			if (AutoCraftController.isBulkModeEnabled() && directCredit > 0) {
				BulkAutoCraftController.addEjectedOutput(directCredit);
			}
			directEjectPendingCount = 0;
			directEjectGridBefore = null;
			BulkAutoCraftController.onAutoMoveFinished(client, true);
			ChainCraftController.onAutoMoveFinished(client, true);
			return;
		}

		// Chain final eject settlement: a result-slot THROW is only counted
		// once the slot is observed empty, proving the server crafted and
		// dropped. Crediting per throw double-counts when the client still
		// shows the pre-throw stack a tick later.
		if (chainEjectAwaitingRefresh) {
			chainEjectRefreshTicks++;
			int chainGridNow = gridItemTotal(menu);
			boolean chainCarriedEmpty = menu.getCarried().isEmpty();
			boolean slotHasThrown = resultSlot.hasItem()
				&& !chainEjectPendingStack.isEmpty()
				&& ItemStack.isSameItemSameComponents(resultSlot.getItem(), chainEjectPendingStack);
			boolean chainForeignPreview = resultSlot.hasItem()
				&& !chainEjectPendingStack.isEmpty()
				&& !ItemStack.isSameItemSameComponents(resultSlot.getItem(), chainEjectPendingStack);
			// Craft-one-per-throw servers refill the result slot with the thrown
			// output after each throw. Re-throw to drain the staged batch instead of
			// waiting out the refresh timeout once per craft (1 craft/sec on native).
			// Mirrors the direct-eject settlement; crediting is by observed grid drain.
			if (chainCarriedEmpty && slotHasThrown && chainGridNow > 0) {
				chainEjectRefillGapTicks = 0;
				if (chainGridNow < chainEjectLastGridTotal) {
					chainEjectRethrowStallTicks = 0;
				} else {
					chainEjectRethrowStallTicks++;
				}
				chainEjectLastGridTotal = chainGridNow;
				if (chainEjectRethrowStallTicks < DIRECT_EJECT_RETHROW_STALL_LIMIT) {
					client.gameMode.handleContainerInput(menu.containerId, resultSlot.index, 1, ContainerInput.THROW, client.player);
					return;
				}
				// grid stopped draining -> settle below
			} else {
				if (chainCarriedEmpty) {
					chainEjectRefillGapTicks++;
				}
				boolean chainForeignSettled = chainForeignPreview
					&& (GridTopUp.isRingAwaitingKeyItem(client, menu) || chainEjectRefreshTicks > 10);
				boolean chainSettled = chainCarriedEmpty && !slotHasThrown
					&& (chainGridNow == 0
						|| chainEjectRefillGapTicks >= DIRECT_EJECT_REFILL_GAP_LIMIT
						|| chainForeignSettled);
				if (!chainSettled) {
					return;
				}
			}
			int chainCredit = resolveEjectCredit(
				menu, chainEjectGridBefore, chainEjectPerCraftCount, chainEjectPendingCount, "chain eject");
			if (chainCredit > 0) {
				BulkChainCraftController.addEjectedOutput(chainEjectPendingStack, chainCredit);
				ChainCraftController.noteFinalOutputEjected(chainCredit);
			}
			chainEjectAwaitingRefresh = false;
			chainEjectRefreshTicks = 0;
			chainEjectLastGridTotal = Integer.MAX_VALUE;
			chainEjectRethrowStallTicks = 0;
			chainEjectRefillGapTicks = 0;
			chainEjectPendingCount = 0;
			chainEjectPendingStack = ItemStack.EMPTY;
			chainEjectGridBefore = null;
			if (!menu.getCarried().isEmpty()) {
				tryResolveCarriedStack(client, menu);
			}
			ejectUnneededGridItems(client, menu);
			if (ChainCraftController.restageFinalStepForRapidEject(client)) {
				autoMoveWaitingTicks = 0;
				return;
			}
			pendingAutoMove = false;
			BulkAutoCraftController.onAutoMoveFinished(client, true);
			ChainCraftController.onAutoMoveFinished(client, true);
			return;
		}

		if (AutoCraftController.isBulkModeEnabled() && BulkAutoCraftController.isActive()) {
			// com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] >> sweepAndEjectByProducts entry. {}", logBottleDistribution(menu));
			sweepAndEjectByProducts(client, menu);
			// com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] << sweepAndEjectByProducts exit.  {}", logBottleDistribution(menu));
		}

		if (!autoMoveOrganizing) {
			if (resultSlot.hasItem() && resultSlot.mayPickup(client.player)) {
				ItemStack currentResult = resultSlot.getItem();

				if (!autoMoveExpectedStack.isEmpty() && !ItemStack.isSameItemSameComponents(currentResult, autoMoveExpectedStack)) {
					if (GridTopUp.isRingAwaitingKeyItem(client, menu)) {
						// The bulk ring's key (unstackable) slot is empty between
						// cycles and the remaining ring previews a foreign recipe
						// (bow-less dispenser ring -> dropper). Expected transient,
						// not a recipe change: keep waiting for the next key insert.
						// Failing here costs a backoff, a ring rebuild, and a
						// phantom drop fed into the place budget's AIMD.
						autoMoveWaitingTicks++;
						if (autoMoveWaitingTicks % 20 == 1) {
							com.reachcrafting.ReachCraftingMod.LOGGER.info(
								"[auto_move] foreign preview over key-empty ring (expected={}, preview={}); waiting",
								ContainerUtils.formatStack(autoMoveExpectedStack),
								ContainerUtils.formatStack(currentResult)
							);
						}
						return;
					}
					foreignResultTicks++;
					if (foreignResultTicks <= FOREIGN_RESULT_DEBOUNCE_TICKS) {
						if (foreignResultTicks == 1) {
							com.reachcrafting.ReachCraftingMod.LOGGER.info(
								"[auto_move] foreign result preview (expected={}, found={}); debouncing",
								ContainerUtils.formatStack(autoMoveExpectedStack),
								ContainerUtils.formatStack(currentResult)
							);
						}
						return;
					}
					foreignResultTicks = 0;
					com.reachcrafting.ReachCraftingMod.LOGGER.info(
						"[auto_move] Recipe changed! Expected: {}, Found: {}. Stopping.",
						ContainerUtils.formatStack(autoMoveExpectedStack),
						ContainerUtils.formatStack(currentResult)
					);
					pendingAutoMove = false;
					autoMoveOrganizing = false;
					autoMoveTargetArrivalObserved = false;
					autoMoveTargetStack = ItemStack.EMPTY;
					BulkAutoCraftController.onAutoMoveFinished(client, false);
					ChainCraftController.onAutoMoveFinished(client, false);
					return;
				}

				foreignResultTicks = 0;

				BulkAutoCraftController.BulkOutputDisposition bulkDisposition =
					BulkAutoCraftController.determineCurrentBatchOutputDisposition(client, currentResult);
				boolean bulkDirectEject = bulkDisposition == BulkAutoCraftController.BulkOutputDisposition.DIRECT_EJECT_BATCH;
				boolean bulkProtectedKeep =
					bulkDisposition == BulkAutoCraftController.BulkOutputDisposition.FINAL_BATCH_KEEP
					|| bulkDisposition == BulkAutoCraftController.BulkOutputDisposition.PARTIAL_STACK_KEEP;
				// The mismatch check above already guarantees currentResult is
				// the expected output when autoMoveExpectedStack is set.
				boolean chainFinalResultEject = ChainCraftController.isRunningFinalStep()
					&& !autoMoveExpectedStack.isEmpty()
					&& ItemStack.isSameItemSameComponents(currentResult, autoMoveExpectedStack);
				boolean chainFinalDirectEject = chainFinalResultEject && BulkChainCraftController.shouldDirectEjectCurrentResult();
				boolean shouldEject = bulkDirectEject || chainFinalDirectEject;
				boolean delayInventoryFullFallbackEject = BulkAutoCraftController.shouldDelayInventoryFullFallbackEject();
				// A THROW on the result slot crafts-and-drops everything the
				// grid has staged, so a staged chain final batch must credit
				// the whole staged amount, not one craft's worth.
				int totalEjected = bulkDirectEject
					? BulkAutoCraftController.predictedDirectEjectOutputCount(client, currentResult)
					: chainFinalResultEject
						? Math.max(BulkAutoCraftController.getCurrentStagedCraftCopies(client), 1) * Math.max(currentResult.getCount(), 1)
						: currentResult.getCount();
				if (!shouldEject
					&& !delayInventoryFullFallbackEject
					&& !bulkProtectedKeep
					&& ReachCraftingConfig.get().ejectItemsWhenFull()
					&& !ChainCraftController.isRunningIntermediateStep()
					&& !canFitInInventory(menu, currentResult)) {
					shouldEject = true;
					com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] shouldEject=true (inv full, cannot fit result)");
				} else if (!shouldEject
					&& delayInventoryFullFallbackEject
					&& !bulkProtectedKeep
					&& ReachCraftingConfig.get().ejectItemsWhenFull()
					&& !ChainCraftController.isRunningIntermediateStep()
					&& !canFitInInventory(menu, currentResult)) {
					com.reachcrafting.ReachCraftingMod.LOGGER.info(
						"[auto_move] delaying inventory-full eject until after organize pass result={} nearby_required={}",
						ContainerUtils.formatStack(currentResult),
						BulkAutoCraftController.nearbyResourcesRequired()
					);
				}

				if (shouldEject) {
					// A visible result with an empty local grid is an unsynced
					// snapshot: the server has staged the batch but its slot
					// updates have not arrived. Throwing now would credit one
					// craft while the server crafts-and-drops the whole staged
					// batch, so the settlement waits for a result refresh that
					// can never come and fails the step (observed: 64-copy
					// trapdoor batches credited as 1). Wait for consistency.
					int chainStagedCopies = chainFinalResultEject
						? BulkAutoCraftController.getCurrentStagedCraftCopies(client)
						: 1;
					if ((bulkDirectEject && totalEjected <= 0) || (chainFinalResultEject && chainStagedCopies <= 0)) {
						directEjectAwaitingStagedCopiesTicks++;
						com.reachcrafting.ReachCraftingMod.LOGGER.info(
							"[auto_move] direct eject waiting for staged copies: waitTicks={} chain_final={} result={} expected={} carried={}",
							directEjectAwaitingStagedCopiesTicks,
							chainFinalResultEject,
							ContainerUtils.formatStack(currentResult),
							ContainerUtils.formatStack(autoMoveExpectedStack),
							ContainerUtils.formatStack(menu.getCarried())
						);
						if (directEjectAwaitingStagedCopiesTicks <= 5) {
							return;
						}
						com.reachcrafting.ReachCraftingMod.LOGGER.info(
							"[auto_move] direct eject staged-copy mismatch persisted; result {}",
							ContainerUtils.formatStack(currentResult)
						);
						if (bulkDirectEject) {
							// Bulk falls back to the keep path; the chain final
							// eject proceeds with the single-craft credit
							// (pre-fix behavior) rather than stalling the run.
							shouldEject = false;
						}
						directEjectAwaitingStagedCopiesTicks = 0;
					} else {
						directEjectAwaitingStagedCopiesTicks = 0;
					}
					if (chainFinalResultEject) {
						totalEjected = Math.max(chainStagedCopies, 1) * Math.max(currentResult.getCount(), 1);
					}
				}

				// Keep-mode rapid loop for the bulk chain final batch: bank
				// each result and restage immediately instead of paying a
				// full settlement round per craft. The batch finishes here
				// too — handing the last craft to the legacy organize path
				// times out on arrival baselines that are a whole batch
				// stale.
				if (!shouldEject
					&& chainFinalResultEject
					&& BulkChainCraftController.isActive()
					&& canFitInInventory(menu, currentResult)) {
					client.gameMode.handleContainerInput(menu.containerId, resultSlot.index, 0, ContainerInput.QUICK_MOVE, client.player);
					if (ChainCraftController.restageFinalStepForRapidEject(client)) {
						autoMoveWaitingTicks = 0;
						return;
					}
					pendingAutoMove = false;
					autoMoveOrganizing = false;
					autoMoveTargetArrivalObserved = false;
					autoMoveTargetStack = ItemStack.EMPTY;
					BulkAutoCraftController.onAutoMoveFinished(client, true);
					ChainCraftController.onAutoMoveFinished(client, true);
					return;
				}

				if (shouldEject) {
					com.reachcrafting.ReachCraftingMod.LOGGER.info(
						"[auto_move] EJECT path: THROW result {} from slot {} predicted_ejected={} bulkDirectEject={} bulkProtectedKeep={}",
						ContainerUtils.formatStack(currentResult),
						resultSlot.index,
						totalEjected,
						bulkDirectEject,
						bulkProtectedKeep
					);
					ItemStack thrownResult = currentResult.copy();
					ItemStack[] gridBeforeThrow = snapshotGridSlots(menu);
					client.gameMode.handleContainerInput(menu.containerId, resultSlot.index, 1, ContainerInput.THROW, client.player);
					if (chainFinalResultEject) {
						// Credit deferred until the result slot is observed
						// empty (see the chainEjectAwaitingRefresh block).
						chainEjectAwaitingRefresh = true;
						chainEjectRefreshTicks = 0;
						chainEjectLastGridTotal = Integer.MAX_VALUE;
						chainEjectRethrowStallTicks = 0;
						chainEjectRefillGapTicks = 0;
						chainEjectPendingCount = totalEjected;
						chainEjectPendingStack = thrownResult;
						chainEjectGridBefore = gridBeforeThrow;
						chainEjectPerCraftCount = Math.max(currentResult.getCount(), 1);
						autoMoveWaitingTicks = 0;
						return;
					}
					if (bulkDirectEject) {
						directEjectPendingCount = totalEjected;
						directEjectAwaitingSettlement = true;
						directEjectSettlementTicks = 0;
						directEjectLastGridTotal = Integer.MAX_VALUE;
						directEjectRethrowStallTicks = 0;
						directEjectRefillGapTicks = 0;
						directEjectGridBefore = gridBeforeThrow;
						directEjectPerCraftCount = Math.max(currentResult.getCount(), 1);
						autoMoveWaitingTicks = 0;
						com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] direct eject queued awaiting settlement: predicted_ejected={}", totalEjected);
						return;
					}
					if (AutoCraftController.isBulkModeEnabled() && totalEjected > 0) {
						BulkAutoCraftController.addEjectedOutput(totalEjected);
						BulkChainCraftController.addEjectedOutput(thrownResult, totalEjected);
						ChainCraftController.noteFinalOutputEjected(totalEjected);
					}

					// Eject any by-products left in the grid
					ejectUnneededGridItems(client, menu);

					// com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] EJECT path done. {}", logBottleDistribution(menu));
					pendingAutoMove = false;
					BulkAutoCraftController.onAutoMoveFinished(client, true);
					ChainCraftController.onAutoMoveFinished(client, true);
					return;
				}
				directEjectAwaitingStagedCopiesTicks = 0;

				if (ChainCraftController.isRunningIntermediateStep() && !canFitInInventory(menu, currentResult)) {
					com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] chain intermediate has no inventory room for {}", ContainerUtils.formatStack(currentResult));
					pendingAutoMove = false;
					autoMoveOrganizing = false;
					autoMoveTargetArrivalObserved = false;
					autoMoveTargetStack = ItemStack.EMPTY;
					ChainCraftController.onAutoMoveFinished(client, false);
					return;
				}

				int slotsNeeded = 0;
				if (AutoCraftController.isBulkModeEnabled()) {
					slotsNeeded = BulkAutoCraftController.estimatedRequiredSlotsForNextBatch();
				} else {
					// Single craft estimate
					slotsNeeded = (menu instanceof net.minecraft.world.inventory.CraftingMenu) ? 9 : 4;
				}

				if (OffhandConsolidationController.prepareSwapIfNeeded(client, currentResult, slotsNeeded)) {
					return;
				}

				if (OffhandConsolidationController.isWarmupDelayActive()) {
					return;
				}

				autoMoveTargetStack = currentResult.copy();
				autoMoveWaitingTicks = 0;
				autoMoveOrganizing = true;
				com.reachcrafting.ReachCraftingMod.LOGGER.info(
					"[auto_move] QUICK_MOVE path starting target={} slotsNeeded={} snapshottingInventory=true",
					ContainerUtils.formatStack(autoMoveTargetStack),
					slotsNeeded
				);

				autoMoveSnapshotCounts.clear();
				for (int i = 0; i < 36; i++) {
					Slot slot = findInventorySlot(menu, i);
					if (slot != null && slot.hasItem()) {
						autoMoveSnapshotCounts.put(i, slot.getItem().getCount());
					}
				}
				Slot offhandSlot = findVisibleOffhandSlot(menu);
				if (offhandSlot != null && offhandSlot.hasItem()) {
					autoMoveSnapshotCounts.put(offhandSlot.index, offhandSlot.getItem().getCount());
				}

				int swappedSlotIndex = OffhandConsolidationController.getSwapSlotIndex(menu);
				if (swappedSlotIndex != -1) {
					Slot s = menu.getSlot(swappedSlotIndex);
					if (s.hasItem()) {
						autoMoveSnapshotCounts.put(s.index, s.getItem().getCount());
					}
				}

				client.gameMode.handleContainerInput(menu.containerId, resultSlot.index, 0, ContainerInput.QUICK_MOVE, client.player);
				com.reachcrafting.ReachCraftingMod.LOGGER.info(
					"[auto_move] SHIFT-CLICK path: post-shiftclick. {} hotbar={}",
					logBottleDistribution(menu),
					logHotbarState(menu)
				);
			} else {
				if (PlaceRecipeBudget.hasPendingFor(menu.containerId)) {
					// Our own budget queue still holds the placement packet —
					// the server hasn't been asked yet, so no result can
					// exist. Don't run down the wait timeout while the send
					// is deferred client-side.
					return;
				}
				if (ChainCraftController.isCurrentBatchObservedComplete()) {
					// A shift-place final step crafts its whole batch in one
					// server action: output is already banked and the grid is
					// spent, so an empty result slot means DONE, not pending.
					com.reachcrafting.ReachCraftingMod.LOGGER.info(
						"[auto_move] chain batch output already complete; finishing without result wait"
					);
					pendingAutoMove = false;
					autoMoveOrganizing = false;
					autoMoveTargetArrivalObserved = false;
					autoMoveTargetStack = ItemStack.EMPTY;
					BulkAutoCraftController.onAutoMoveFinished(client, true);
					ChainCraftController.onAutoMoveFinished(client, true);
					return;
				}
				autoMoveWaitingTicks++;
				int stagedCraftCopies = 0;
				if (BulkAutoCraftController.isActive() && client.gui.screen() != null) {
					stagedCraftCopies = BulkAutoCraftController.getCurrentStagedCraftCopies(client);
				}
				com.reachcrafting.ReachCraftingMod.LOGGER.info(
					"[auto_move] waiting_for_result waitTicks={} bulk_active={} expected={} carried={} staged_copies={} result_now={}",
					autoMoveWaitingTicks,
					BulkAutoCraftController.isActive(),
					ContainerUtils.formatStack(autoMoveExpectedStack),
					ContainerUtils.formatStack(menu.getCarried()),
					stagedCraftCopies,
					resultSlot.hasItem() ? ContainerUtils.formatStack(resultSlot.getItem()) : "<empty>"
				);
				// A server resync can land a foreign stack on the cursor
				// mid-wait (anti-cheat rejecting a flush click returns cake's
				// bucket remainders there). The server then refuses every
				// place packet, no result can ever arrive, and the bulk
				// timeout below requires an EMPTY cursor — an unbreakable
				// stall without this rescue.
				ItemStack carriedNow = menu.getCarried();
				if (!carriedNow.isEmpty()
					&& autoMoveWaitingTicks >= 10 && autoMoveWaitingTicks % 10 == 0
					&& !ItemStack.isSameItemSameComponents(carriedNow, autoMoveExpectedStack)) {
					String carriedId = net.minecraft.core.registries.BuiltInRegistries.ITEM
						.getKey(carriedNow.getItem()).toString();
					Slot rescueSlot = MenuTransferHelper.findPlayerDestinationSlot(client.player, menu, carriedId);
					if (rescueSlot != null) {
						com.reachcrafting.ReachCraftingMod.LOGGER.warn(
							"[auto_move] cursor_rescue depositing stray {} during result wait (waitTicks={})",
							ContainerUtils.formatStack(carriedNow), autoMoveWaitingTicks);
						client.gameMode.handleContainerInput(
							menu.containerId, rescueSlot.index, 0, ContainerInput.PICKUP, client.player);
						GridTopUp.recordClick();
						PlaceRecipeBudget.noteCursorRescue();
					} else {
						com.reachcrafting.ReachCraftingMod.LOGGER.warn(
							"[auto_move] cursor_rescue no deposit slot for stray {} (waitTicks={})",
							ContainerUtils.formatStack(carriedNow), autoMoveWaitingTicks);
					}
				}
				// A chain step is a bulk-paced context even though no flat bulk
				// session is active (chain runs block flat-session arming): the
				// interactive 10-tick timeout is far too tight for a multi-copy
				// final-step place on a busy server tick. Measured: the "failed"
				// lectern place produced its result ~1s later, so the short
				// timeout both wasted the iteration AND fed a phantom drop into
				// the AIMD budget (rate halved 4->2->1/s for nothing).
				int nonBulkTimeoutTicks = ChainCraftController.hasActiveRun() ? 40 : 10;
				if (autoMoveWaitingTicks > nonBulkTimeoutTicks && !BulkAutoCraftController.isActive()) {
					pendingAutoMove = false;
					autoMoveOrganizing = false;
					autoMoveTargetArrivalObserved = false;
					autoMoveTargetStack = ItemStack.EMPTY;
					com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] waiting_for_result timeout in non-bulk mode");
					if (ChainCraftController.hasCurrentBatchObservedAnyCopies()) {
						// Partial batch production proves the place packet
						// landed; the missing tail is accounting (materials
						// ran out a copy early), not a limiter drop.
						com.reachcrafting.ReachCraftingMod.LOGGER.info(
							"[auto_move] timeout with partial batch production - budget unchanged");
					} else {
						PlaceRecipeBudget.onSuspectedDrop();
					}
					BulkAutoCraftController.onAutoMoveFinished(client, false);
					ChainCraftController.onAutoMoveFinished(client, false);
				} else if (autoMoveWaitingTicks > BULK_RESULT_WAIT_TIMEOUT_TICKS
					&& BulkAutoCraftController.isActive()
					&& ((stagedCraftCopies <= 0 && !resultSlot.hasItem() && menu.getCarried().isEmpty())
						// Hard cap: if the gentle conditions (cursor empty etc.)
						// never come true — e.g. a stray carried stack with no
						// deposit slot — the wait must still end rather than
						// spin forever.
						|| autoMoveWaitingTicks > BULK_RESULT_WAIT_TIMEOUT_TICKS + 60)) {
					pendingAutoMove = false;
					autoMoveOrganizing = false;
					autoMoveTargetArrivalObserved = false;
					autoMoveTargetStack = ItemStack.EMPTY;
					com.reachcrafting.ReachCraftingMod.LOGGER.warn(
						"[auto_move] waiting_for_result timeout in bulk mode expected={} staged_copies={} carried={} result_now={}",
						ContainerUtils.formatStack(autoMoveExpectedStack),
						stagedCraftCopies,
						ContainerUtils.formatStack(menu.getCarried()),
						resultSlot.hasItem() ? ContainerUtils.formatStack(resultSlot.getItem()) : "<empty>"
					);
					PlaceRecipeBudget.onSuspectedDrop();
					BulkAutoCraftController.onAutoMoveFinished(client, false);
					ChainCraftController.onAutoMoveFinished(client, false);
				}
				return;
			}
		}

		autoMoveWaitingTicks++;
		int movesThisTick = 0;
		int maxMovesPerTick = 20;

		for (int i = 0; i < 36 && movesThisTick < maxMovesPerTick; i++) {
			Slot sourceSlot = findInventorySlot(menu, i);
			if (sourceSlot != null && sourceSlot.hasItem() && ItemStack.isSameItemSameComponents(sourceSlot.getItem(), autoMoveTargetStack)) {
				int currentCount = sourceSlot.getItem().getCount();
				
				// Never move items OUT of the offhand or the swapped offhand slot
				Slot offhandSlot = findVisibleOffhandSlot(menu);
				if (offhandSlot != null && sourceSlot.index == offhandSlot.index) {
					autoMoveSnapshotCounts.put(i, currentCount);
					continue;
				}
				int swappedSlotIndex = OffhandConsolidationController.getSwapSlotIndex(menu);
				if (swappedSlotIndex != -1 && sourceSlot.index == swappedSlotIndex) {
					autoMoveSnapshotCounts.put(i, currentCount);
					continue;
				}

				int oldCount = autoMoveSnapshotCounts.getOrDefault(i, 0);

				if (currentCount > oldCount) {
					autoMoveTargetArrivalObserved = true;
					com.reachcrafting.ReachCraftingMod.LOGGER.info(
						"[auto_move] source candidate inv={} slot={} oldCount={} currentCount={} hotbar={} snapshot={}",
						i,
						sourceSlot.index,
						oldCount,
						currentCount,
						logHotbarState(menu),
						logHotbarSnapshot()
					);
					if (oldCount > 0) {
						autoMoveSnapshotCounts.put(i, currentCount);
						com.reachcrafting.ReachCraftingMod.LOGGER.info(
							"[auto_move] source candidate ignored because slot existed in snapshot inv={} oldCount={} currentCount={}",
							i,
							oldCount,
							currentCount
						);
						continue;
					}
					if (offhandSlot != null
						&& offhandSlot.hasItem()
						&& ItemStack.isSameItemSameComponents(offhandSlot.getItem(), autoMoveTargetStack)
						&& offhandSlot.getItem().getCount() < offhandSlot.getItem().getMaxStackSize()) {
						client.gameMode.handleContainerInput(menu.containerId, sourceSlot.index, 0, ContainerInput.PICKUP, client.player);
						client.gameMode.handleContainerInput(menu.containerId, offhandSlot.index, 0, ContainerInput.PICKUP, client.player);

						if (!client.player.containerMenu.getCarried().isEmpty()) {
							client.gameMode.handleContainerInput(menu.containerId, sourceSlot.index, 0, ContainerInput.PICKUP, client.player);
						}

						movesThisTick++;
						autoMoveSnapshotCounts.remove(i);
						autoMoveSnapshotCounts.put(offhandSlot.index, Math.max(offhandSlot.getItem().getCount(), currentCount));
						continue;
					}
					
					if (swappedSlotIndex != -1) {
						Slot swapSlot = menu.getSlot(swappedSlotIndex);
						if (swapSlot.hasItem() \u0026\u0026 ItemStack.isSameItemSameComponents(swapSlot.getItem(), autoMoveTargetStack) \u0026\u0026 swapSlot.getItem().getCount() < swapSlot.getItem().getMaxStackSize()) {
							client.gameMode.handleContainerInput(menu.containerId, sourceSlot.index, 0, ContainerInput.PICKUP, client.player);
							client.gameMode.handleContainerInput(menu.containerId, swapSlot.index, 0, ContainerInput.PICKUP, client.player);
							if (!client.player.containerMenu.getCarried().isEmpty()) {
								client.gameMode.handleContainerInput(menu.containerId, sourceSlot.index, 0, ContainerInput.PICKUP, client.player);
							}
							movesThisTick++;
							autoMoveSnapshotCounts.remove(i);
							autoMoveSnapshotCounts.put(swapSlot.index, Math.max(swapSlot.getItem().getCount(), currentCount));
							continue;
						}
					}

					for (int h = 0; h < i && h < 9; h++) {
						Slot targetSlot = findInventorySlot(menu, h);
						if (targetSlot == null) {
							com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] target skip inv={} reason=no_slot", h);
							continue;
						}
						int snapshotCount = autoMoveSnapshotCounts.getOrDefault(h, 0);
						if (snapshotCount > 0) {
							com.reachcrafting.ReachCraftingMod.LOGGER.info(
								"[auto_move] target skip inv={} slot={} reason=in_snapshot snapshotCount={} current={}",
								h,
								targetSlot.index,
								snapshotCount,
								targetSlot.hasItem() ? ContainerUtils.formatStack(targetSlot.getItem()) : "<empty>"
							);
							continue;
						}

						boolean canMove = false;
						if (!targetSlot.hasItem()) {
							canMove = true;
						} else if (ItemStack.isSameItemSameComponents(targetSlot.getItem(), autoMoveTargetStack)) {
							if (targetSlot.getItem().getCount() < targetSlot.getItem().getMaxStackSize()) {
								canMove = true;
							}
						}
						com.reachcrafting.ReachCraftingMod.LOGGER.info(
							"[auto_move] target check sourceInv={} targetInv={} targetSlot={} canMove={} targetCurrent={}",
							i,
							h,
							targetSlot.index,
							canMove,
							targetSlot.hasItem() ? ContainerUtils.formatStack(targetSlot.getItem()) : "<empty>"
						);

						if (canMove) {
							client.gameMode.handleContainerInput(menu.containerId, sourceSlot.index, 0, ContainerInput.PICKUP, client.player);
							client.gameMode.handleContainerInput(menu.containerId, targetSlot.index, 0, ContainerInput.PICKUP, client.player);

							if (!client.player.containerMenu.getCarried().isEmpty()) {
								client.gameMode.handleContainerInput(menu.containerId, sourceSlot.index, 0, ContainerInput.PICKUP, client.player);
							}

							movesThisTick++;
							autoMoveSnapshotCounts.remove(i);
							autoMoveSnapshotCounts.put(h, Math.max(targetSlot.getItem().getCount(), currentCount));
							com.reachcrafting.ReachCraftingMod.LOGGER.info(
								"[auto_move] move executed sourceInv={} targetInv={} hotbarAfter={}",
								i,
								h,
								logHotbarState(menu)
							);
							break;
						}
					}
				}
			}
		}

		if (movesThisTick == 0 && autoMoveWaitingTicks > 1) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[auto_move] organize idle: movesThisTick=0 waitTicks={} target={} result={} carried={}",
				autoMoveWaitingTicks,
				ContainerUtils.formatStack(autoMoveTargetStack),
				resultSlot.hasItem() ? ContainerUtils.formatStack(resultSlot.getItem()) : "<empty>",
				ContainerUtils.formatStack(client.player.containerMenu.getCarried())
			);
			boolean observedTargetArrival = autoMoveTargetArrivalObserved || hasObservedAutoMoveTargetArrival(menu);
			if (!observedTargetArrival && autoMoveWaitingTicks <= ORGANIZE_TARGET_ARRIVAL_WAIT_TICKS) {
				com.reachcrafting.ReachCraftingMod.LOGGER.info(
					"[auto_move] organize waiting for target arrival: waitTicks={} target={} snapshot_known_slots={}",
					autoMoveWaitingTicks,
					ContainerUtils.formatStack(autoMoveTargetStack),
					autoMoveSnapshotCounts.size()
				);
				return;
			}
			if (!autoMoveTargetArrivalObserved && observedTargetArrival) {
				autoMoveTargetArrivalObserved = true;
				com.reachcrafting.ReachCraftingMod.LOGGER.info(
					"[auto_move] organize observed target arrival: waitTicks={} target={}",
					autoMoveWaitingTicks,
					ContainerUtils.formatStack(autoMoveTargetStack)
				);
				return;
			}
			if (!client.player.containerMenu.getCarried().isEmpty() && !tryResolveCarriedStack(client, menu)) {
				return;
			}

			if (ReachCraftingConfig.get().ejectItemsWhenFull() && AutoCraftController.isBulkModeEnabled()) {
				int ejectedSlots = ejectUnneededGridItems(client, menu);
				if (ejectedSlots > 0) {
					movesThisTick += ejectedSlots;
					autoMoveWaitingTicks = 0;
					return;
				}
			}

			if (resultSlot.hasItem() && ItemStack.isSameItemSameComponents(resultSlot.getItem(), autoMoveTargetStack)) {
				if (ReachCraftingConfig.get().ejectItemsWhenFull()
					&& AutoCraftController.isBulkModeEnabled()
					&& !ChainCraftController.isRunningIntermediateStep()
					&& !canFitInInventory(menu, resultSlot.getItem())) {
					ItemStack ejectedStack = resultSlot.getItem().copy();
					int ejectedCount = ChainCraftController.isRunningFinalStep()
						? Math.max(BulkAutoCraftController.getCurrentStagedCraftCopies(client), 1) * Math.max(ejectedStack.getCount(), 1)
						: ejectedStack.getCount();
					com.reachcrafting.ReachCraftingMod.LOGGER.info(
						"[auto_move] organize fallback eject: result still blocked after quick-move target={} count={}",
						ContainerUtils.formatStack(ejectedStack),
						ejectedCount
					);
					client.gameMode.handleContainerInput(menu.containerId, resultSlot.index, 1, ContainerInput.THROW, client.player);
					if (ejectedCount > 0) {
						BulkAutoCraftController.addEjectedOutput(ejectedCount);
						BulkChainCraftController.addEjectedOutput(ejectedStack, ejectedCount);
						ChainCraftController.noteFinalOutputEjected(ejectedCount);
					}
					pendingAutoMove = false;
					autoMoveOrganizing = false;
					autoMoveTargetArrivalObserved = false;
					autoMoveTargetStack = ItemStack.EMPTY;
					BulkAutoCraftController.onAutoMoveFinished(client, true);
					ChainCraftController.onAutoMoveFinished(client, true);
					return;
				}
				com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Result slot still has items after organizing. Quick-moving next result.");
				client.gameMode.handleContainerInput(menu.containerId, resultSlot.index, 0, ContainerInput.QUICK_MOVE, client.player);
				autoMoveTargetArrivalObserved = true;
				autoMoveWaitingTicks = 0;
				return;
			}

			pendingAutoMove = false;
			autoMoveOrganizing = false;
			autoMoveTargetArrivalObserved = false;
			autoMoveTargetStack = ItemStack.EMPTY;
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] organize complete: finishing batch");
			BulkAutoCraftController.onAutoMoveFinished(client, true);
			ChainCraftController.onAutoMoveFinished(client, true);
		}
	}

	private static void sweepAndEjectByProducts(Minecraft client, AbstractContainerMenu menu) {
		if (!ReachCraftingConfig.get().ejectItemsWhenFull()) {
			return;
		}
		if (!BulkAutoCraftController.needsNearbyStagingRoom()) {
			return;
		}

		java.util.Set<String> acceptedIds = BulkAutoCraftController.getAcceptedItemIds();
		java.util.Map<String, Integer> initialCounts = BulkAutoCraftController.getInitialInventoryCounts();
		ItemStack expectedOutput = BulkAutoCraftController.getExpectedOutput();
		
			if (acceptedIds == null || initialCounts == null || expectedOutput.isEmpty()) {
			return;
		}

		
		// Map current total counts to decide what is "extra", including the offhand
		java.util.Map<String, Integer> currentCounts = new java.util.HashMap<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory && slot.hasItem()) {
				String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				currentCounts.merge(itemId, slot.getItem().getCount(), Integer::sum);
			}
		}
		ItemStack offhandItem = client.player.getOffhandItem();
		if (!offhandItem.isEmpty()) {
			String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(offhandItem.getItem()).toString();
			currentCounts.merge(itemId, offhandItem.getCount(), Integer::sum);
		}

		int swappedSlotIndex = OffhandConsolidationController.getSwapSlotIndex(menu);
		Slot visibleOffhand = findVisibleOffhandSlot(menu);

		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot == null || !slot.hasItem()) continue;

			// Never eject from the swapped offhand slot (3x3)
			if (swappedSlotIndex != -1 && slot.index == swappedSlotIndex) {
				continue;
			}
			
			// Never eject from the actual visible offhand slot (2x2)
			if (visibleOffhand != null && slot.index == visibleOffhand.index) {
				continue;
			}

			ItemStack stack = slot.getItem();
			String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			String outputId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(expectedOutput.getItem()).toString();

			// Never eject ingredients
			if (acceptedIds.contains(itemId)) {
				continue;
			}

			if (itemId.equals(outputId) && !BulkAutoCraftController.shouldSweepExpectedOutput()) {
				continue;
			}
			if (itemId.equals(outputId) && BulkAutoCraftController.isProtectedOutputInventorySlot(i)) {
				continue;
			}

			int initialCount = initialCounts.getOrDefault(itemId, 0);
			int currentTotal = currentCounts.getOrDefault(itemId, 0);

			if (currentTotal > initialCount) {
				// This item is a by-product or extra output (count increased since session start)
				int amountEjected = stack.getCount();
				if (currentTotal - amountEjected >= initialCount) {
					com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] Ejecting extra/by-product {} from inventory slot {}", itemId, slot.index);
					client.gameMode.handleContainerInput(menu.containerId, slot.index, 1, ContainerInput.THROW, client.player);
					
					// If this was extra output, report it to the bulk controller so it doesn't think progress stopped
					if (itemId.equals(outputId)) {
						com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Ejected EXTRA OUTPUT: {} matched outputId {} (count={})", itemId, outputId, amountEjected);
						BulkAutoCraftController.addEjectedOutput(amountEjected);
					} else {
						com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Ejected BYPRODUCT: {} (expected outputId: {})", itemId, outputId);
					}

					// Update current counts so we don't over-eject if there are multiple slots of the same byproduct
					currentCounts.put(itemId, currentTotal - amountEjected);
				}
			}
		}
	}

	/**
	 * Throws grid items the active bulk session has no use for — crafting
	 * remainders (milk buckets leave empty buckets) and stray byproducts.
	 * Only acts when a bulk session, flat or chain, can vouch for its
	 * accepted item set. Returns the number of grid slots ejected.
	 */
	private static int ejectUnneededGridItems(Minecraft client, AbstractContainerMenu menu) {
		if (!AutoCraftController.isBulkModeEnabled() || client.gameMode == null || client.player == null) {
			return 0;
		}
		java.util.Set<String> acceptedIds = ContainerUtils.bulkSessionAcceptedItemIds();
		if (acceptedIds == null) {
			return 0;
		}
		int gridSlotCount = (menu instanceof net.minecraft.world.inventory.CraftingMenu) ? 9 : 4;
		int ejectedSlots = 0;
		for (int i = 1; i <= gridSlotCount; i++) {
			Slot gridSlot = menu.getSlot(i);
			if (!gridSlot.hasItem()) {
				continue;
			}
			String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(gridSlot.getItem().getItem()).toString();
			if (!acceptedIds.contains(itemId)) {
				com.reachcrafting.ReachCraftingMod.LOGGER.debug("[auto_move] EJECT grid byproduct {} from grid slot {}", itemId, i);
				client.gameMode.handleContainerInput(menu.containerId, gridSlot.index, 1, ContainerInput.THROW, client.player);
				ejectedSlots++;
			}
		}
		return ejectedSlots;
	}

	private static boolean tryResolveCarriedStack(Minecraft client, AbstractContainerMenu menu) {
		ItemStack carried = client.player.containerMenu.getCarried();
		if (carried.isEmpty()) {
			return true;
		}

		String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(carried.getItem()).toString();
		Slot destination = MenuTransferHelper.findPlayerDestinationSlot(client.player, menu, itemId);
		if (destination != null) {
			int carriedBefore = carried.getCount();
			client.gameMode.handleContainerInput(menu.containerId, destination.index, 0, ContainerInput.PICKUP, client.player);
			ItemStack carriedAfter = client.player.containerMenu.getCarried();
			if (carriedAfter.isEmpty() || carriedAfter.getCount() < carriedBefore) {
				return carriedAfter.isEmpty();
			}
		}

		if (ReachCraftingConfig.get().ejectItemsWhenFull()
			&& AutoCraftController.isBulkModeEnabled()
			&& !ChainCraftController.isRunningIntermediateStep()
			&& ItemStack.isSameItemSameComponents(carried, autoMoveTargetStack)) {
			int ejectedCount = carried.getCount();
			client.gameMode.handleContainerInput(menu.containerId, -999, 0, ContainerInput.PICKUP, client.player);
			if (client.player.containerMenu.getCarried().isEmpty()) {
				BulkAutoCraftController.addEjectedOutput(ejectedCount);
				BulkChainCraftController.addEjectedOutput(carried, ejectedCount);
				ChainCraftController.noteFinalOutputEjected(ejectedCount);
				com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Ejected carried output {} while finalizing batch", ContainerUtils.formatStack(carried));
				return true;
			}
		}

		com.reachcrafting.ReachCraftingMod.LOGGER.info("[auto_move] Waiting for carried stack to resolve before finishing batch: {}", ContainerUtils.formatStack(client.player.containerMenu.getCarried()));
		return false;
	}

	private static boolean hasObservedAutoMoveTargetArrival(AbstractContainerMenu menu) {
		if (autoMoveTargetStack.isEmpty()) {
			return true;
		}

		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot == null || !slot.hasItem() || !ItemStack.isSameItemSameComponents(slot.getItem(), autoMoveTargetStack)) {
				continue;
			}
			int oldCount = autoMoveSnapshotCounts.getOrDefault(i, 0);
			if (slot.getItem().getCount() > oldCount) {
				return true;
			}
		}

		Slot offhandSlot = findVisibleOffhandSlot(menu);
		if (offhandSlot != null
			&& offhandSlot.hasItem()
			&& ItemStack.isSameItemSameComponents(offhandSlot.getItem(), autoMoveTargetStack)) {
			int oldCount = autoMoveSnapshotCounts.getOrDefault(offhandSlot.index, 0);
			if (offhandSlot.getItem().getCount() > oldCount) {
				return true;
			}
		}

		int swappedSlotIndex = OffhandConsolidationController.getSwapSlotIndex(menu);
		if (swappedSlotIndex != -1) {
			Slot swappedSlot = menu.getSlot(swappedSlotIndex);
			if (swappedSlot.hasItem() && ItemStack.isSameItemSameComponents(swappedSlot.getItem(), autoMoveTargetStack)) {
				int oldCount = autoMoveSnapshotCounts.getOrDefault(swappedSlot.index, 0);
				if (swappedSlot.getItem().getCount() > oldCount) {
					return true;
				}
			}
		}

		return false;
	}

	private static Slot findInventorySlot(AbstractContainerMenu menu, int inventoryIndex) {
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory && slot.getContainerSlot() == inventoryIndex) {
				return slot;
			}
		}
		return null;
	}

	private static boolean canFitInInventory(AbstractContainerMenu menu, ItemStack stack) {
		int remaining = stack.getCount();
		int maxStack = stack.getMaxStackSize();
		
		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot == null) continue;
			if (!slot.hasItem()) {
				remaining -= maxStack;
			} else if (ItemStack.isSameItemSameComponents(slot.getItem(), stack)) {
				remaining -= (maxStack - slot.getItem().getCount());
			}
			if (remaining <= 0) return true;
		}

		// Also check the offhand slot if it's visible (2x2)
		Slot offhandSlot = findVisibleOffhandSlot(menu);
		if (offhandSlot != null) {
			if (ItemStack.isSameItemSameComponents(offhandSlot.getItem(), stack)) {
				remaining -= (maxStack - offhandSlot.getItem().getCount());
			}
		}
		
		return remaining <= 0;
	}


	private static Slot findVisibleOffhandSlot(AbstractContainerMenu menu) {
		if (!ReachCraftingConfig.get().inventory2x2OffhandConsolidation()
			|| !(menu instanceof InventoryMenu)
			|| menu.slots.size() <= InventoryMenu.SHIELD_SLOT) {
			return null;
		}
		return menu.getSlot(InventoryMenu.SHIELD_SLOT);
	}


	private static String logBottleDistribution(AbstractContainerMenu menu) {
		StringBuilder sb = new StringBuilder("inv=[");
		boolean first = true;
		for (int i = 0; i < 36; i++) {
			Slot slot = findInventorySlot(menu, i);
			if (slot != null && slot.hasItem()) {
				String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).getPath();
				if (itemId.contains("bottle")) {
					if (!first) sb.append(", ");
					sb.append("inv").append(i).append(":").append(slot.getItem().getCount()).append("x").append(itemId);
					first = false;
				}
			}
		}
		sb.append("] grid=[");
		first = true;
		int gridSlots = (menu instanceof net.minecraft.world.inventory.CraftingMenu) ? 9 : 4;
		for (int i = 1; i <= gridSlots; i++) {
			if (i >= menu.slots.size()) break;
			Slot slot = menu.getSlot(i);
			if (slot.hasItem()) {
				if (!first) sb.append(", ");
				String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).getPath();
				sb.append("g").append(i).append(":").append(slot.getItem().getCount()).append("x").append(itemId);
				first = false;
			}
		}
		sb.append("]");
		return sb.toString();
	}

	private static String logHotbarState(AbstractContainerMenu menu) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < 9; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			Slot slot = findInventorySlot(menu, i);
			if (slot == null || !slot.hasItem()) {
				sb.append(i).append(":empty");
				continue;
			}
			sb.append(i).append(":").append(ContainerUtils.formatStack(slot.getItem()));
		}
		sb.append("]");
		return sb.toString();
	}

	private static String logHotbarSnapshot() {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < 9; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(i).append(":").append(autoMoveSnapshotCounts.getOrDefault(i, 0));
		}
		sb.append("]");
		return sb.toString();
	}
}

