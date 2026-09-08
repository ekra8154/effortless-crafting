package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

/**
 * Output variant switching for a plain (non-bulk) auto craft.
 *
 * <p>Bulk sessions switch family variants inside the session. A plain
 * request is one resolved variant per click, so when that variant cannot
 * cover the whole request the executor crafts what it can and arms a
 * continuation here: once the craft has settled, the original click is
 * replayed for the remainder, the resolver picks the next variant (the
 * drained one has no copies left), and so on until the request is met or no
 * variant is directly craftable. Craft-all requests continue variant by
 * variant until none is left. Gated exactly like bulk: the setting on and
 * {@link BulkAutoCraftController#determineVariantContinuationMode} allowing a
 * family fallback for that click.</p>
 */
final class OutputVariantContinuationController {
	private static final int IDLE_SETTLE_TICKS = 10;
	private static final int CONTEXT_WAIT_LIMIT_TICKS = 200;
	// One step per variant in the family is the most a run can need; a few
	// extra cover a variant that yielded fewer copies than planned.
	private static final int MAX_STEPS = 24;

	private record Pending(
		RecipeBookClickCapture.HeldRecipeAction action,
		int remainingClicks,
		boolean craftAll,
		boolean allowNearby,
		boolean autoCraftRequested
	) {
	}

	private static Pending pending;
	private static boolean firing;
	private static int stepCount;
	private static int idleTicks;
	private static int waitTicks;

	private OutputVariantContinuationController() {
	}

	static boolean isActive() {
		return pending != null || firing;
	}

	/** The executor pass that a fired continuation produced; consumed once per pass. */
	static boolean consumeFiring() {
		boolean wasFiring = firing;
		firing = false;
		return wasFiring;
	}

	static void arm(
		RecipeBookClickCapture.HeldRecipeAction action,
		int remainingClicks,
		boolean craftAll,
		boolean allowNearby,
		boolean autoCraftRequested,
		boolean continuationPass,
		String craftingItemId,
		int craftingCopies
	) {
		stepCount = continuationPass ? stepCount + 1 : 1;
		if (stepCount > MAX_STEPS) {
			end("step_limit", craftingItemId);
			return;
		}
		pending = new Pending(action, remainingClicks, craftAll, allowNearby, autoCraftRequested);
		idleTicks = 0;
		waitTicks = 0;
		ReachCraftingMod.diag(
			"[variant_continuation] armed step={} crafting={} copies={} remaining_clicks={} craft_all={} allow_nearby={}",
			stepCount,
			craftingItemId,
			craftingCopies,
			remainingClicks,
			craftAll,
			allowNearby
		);
	}

	static void end(String reason, String itemId) {
		ReachCraftingMod.diag("[variant_continuation] end reason={} item={} steps={}", reason, itemId, stepCount);
		pending = null;
		firing = false;
		stepCount = 0;
	}

	static void clear() {
		if (pending != null || firing) {
			ReachCraftingMod.diag("[variant_continuation] cleared steps={}", stepCount);
		}
		pending = null;
		firing = false;
		stepCount = 0;
	}

	static void tick(Minecraft client) {
		if (pending == null) {
			return;
		}
		if (client.player == null) {
			clear();
			return;
		}
		boolean context = client.screen instanceof CraftingScreen || client.screen instanceof InventoryScreen;
		boolean idle = context
			&& !NearbyContainerDryRun.isActiveSessionRunning()
			&& !ContainerUtils.isInputQueueActive()
			&& !ContainerUtils.isAutoMovePending()
			&& !ContainerUtils.isAutomatedInteractionRunning()
			&& !GridExtractor.isActive()
			&& !ChainCraftController.isActive()
			&& !RetrieveThenCraftController.isActive()
			&& ContainerUtils.isGridEmpty(client.player.containerMenu);
		if (!idle) {
			idleTicks = 0;
			if (++waitTicks > CONTEXT_WAIT_LIMIT_TICKS) {
				end("context_lost", "<none>");
			}
			return;
		}
		if (++idleTicks < IDLE_SETTLE_TICKS) {
			return;
		}
		Pending next = pending;
		pending = null;
		firing = true;
		ReachCraftingMod.diag(
			"[variant_continuation] fire step={} remaining_clicks={} craft_all={}",
			stepCount,
			next.remainingClicks(),
			next.craftAll()
		);
		AutoCraftController.armHoldSessionForCurrentRequest(next.autoCraftRequested());
		RecipeBookClickCapture.scheduleReplay(
			next.action(),
			next.craftAll() ? 1 : Math.max(next.remainingClicks(), 1),
			next.allowNearby(),
			next.craftAll(),
			false,
			next.autoCraftRequested(),
			true
		);
	}
}
