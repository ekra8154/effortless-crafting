package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Detects fat client ticks while our automation runs. The field-reported
 * "game freeze at the end of every batch" (items visibly frozen midair) is
 * the iteration-seam replan — chain-cache recompute, planner construction
 * over ~1000 candidates, inventory-fit simulation, recipe-book refresh —
 * stacking synchronously into one or two ticks. Dev builds carry [perf]
 * telemetry to attribute it; release builds do not, so this watchdog logs
 * the stall itself (magnitude + when) in every environment.
 */
final class TickStallWatchdog {

	/** Ticks run every 50ms; a gap past this many ms means frames stalled. */
	private static final long STALL_THRESHOLD_MS = 150;

	private static long lastTickEndMillis = 0;

	private TickStallWatchdog() {
	}

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			long now = System.currentTimeMillis();
			long gap = lastTickEndMillis > 0 ? now - lastTickEndMillis : 0;
			lastTickEndMillis = now;
			if (gap < STALL_THRESHOLD_MS) {
				return;
			}
			boolean chainActive = BulkChainCraftController.isActive() || ChainCraftController.isActive();
			boolean bulkActive = BulkAutoCraftController.isActive();
			if (!chainActive && !bulkActive) {
				// Not our session: world loads, GC, other mods. Stay quiet.
				return;
			}
			ReachCraftingMod.LOGGER.warn(
				"[tick_stall] gap_ms={} chain_active={} bulk_active={} extractor_active={} automove_pending={}",
				gap, chainActive, bulkActive, GridExtractor.isActive(), AutoMoveController.isAutoMovePending());
		});
	}
}
