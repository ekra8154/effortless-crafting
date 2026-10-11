package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Global stray-cursor rescue for bulk sessions.
 *
 * Some servers' anti-cheat rewrites automation clicks: a grid-flush burst
 * reports success client-side, then the server rejects part of it and the
 * resync lands the crafting remainder (cake's empty buckets) back on the
 * cursor — AFTER whichever subsystem clicked has moved on. Several wait
 * gates require an empty cursor (SearchSession's settle check, the bulk
 * result-wait timeout, vanilla's own refusal to place recipes over a
 * non-empty cursor), so a stray carried stack freezes the whole pipeline
 * with nothing logging. Local rescues exist at the auto-move result wait
 * and before every place send, but the stall was observed in waits that
 * precede both. This watchdog covers every wait at once: while a bulk or
 * bulk-chain session is active, a carried stack that sits unchanged with
 * no automation clicks in flight is deposited back into the inventory.
 *
 * Only stacks the automation left there count. A stack the player picked up
 * themselves is theirs to hold: before this rule, any stack held for ~1 s
 * within 10 s of a craft was "rescued" back into the inventory, so moving
 * freshly crafted items snapped them back to their slot, and each rescue
 * restarted the 10 s window, so it kept happening until the screen closed.
 */
public final class CursorRescueWatchdog {

	/** Consecutive ticks the same stray stack must sit before rescue. */
	private static final int STABLE_TICKS_BEFORE_RESCUE = 15;
	/** Quiet time since the last automation click: a click sequence in
	 * flight legitimately holds items on the cursor between ticks. */
	private static final long QUIET_MILLIS_BEFORE_RESCUE = 500;
	/** With no session active, only rescue this soon after automation
	 * clicked — later, a held stack is the user's own doing. */
	private static final long RECENT_AUTOMATION_MILLIS = 10_000;

	private static ItemStack observedCarried = ItemStack.EMPTY;
	private static int stableTicks = 0;
	/** The observed stack appeared after a physical mouse press, not after
	 * an automation click: the player put it on the cursor. */
	private static boolean observedIsPlayers = false;
	private static long lastPlayerInputMillis = 0;

	/** Called for every physical mouse button event (MouseHandlerMixin). */
	public static void notePlayerInput() {
		lastPlayerInputMillis = System.currentTimeMillis();
	}

	private CursorRescueWatchdog() {
	}

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(CursorRescueWatchdog::tick);
	}

	private static void tick(Minecraft client) {
		if (client.player == null || client.gameMode == null) {
			reset();
			return;
		}
		if (!(client.gui.screen() instanceof CraftingScreen) && !(client.gui.screen() instanceof InventoryScreen)) {
			reset();
			return;
		}
		// Rescue while a session is running — or shortly after ANY automation
		// click. The observed stall had the session silently dead by the time
		// the resync landed the buckets, so "session active" alone misses it;
		// a stray stack appearing within seconds of our own clicks is our
		// bounced mess whichever subsystem already gave up. Past that window
		// a held stack is the user's own business.
		boolean sessionActive = BulkAutoCraftController.isActive() || BulkChainCraftController.isActive();
		if (!sessionActive && GridTopUp.millisSinceLastAutomationClick() > RECENT_AUTOMATION_MILLIS) {
			reset();
			return;
		}
		AbstractContainerMenu menu = client.player.containerMenu;
		ItemStack carried = menu.getCarried();
		if (carried.isEmpty()) {
			reset();
			return;
		}
		if (!ItemStack.isSameItemSameComponents(carried, observedCarried)
			|| carried.getCount() != observedCarried.getCount()) {
			observedCarried = carried.copy();
			stableTicks = 0;
			// Whoever acted last owns the new cursor stack. A pickup lands on
			// the cursor the moment the press is handled, so a press newer
			// than our last automation click means the player is holding it.
			observedIsPlayers = lastPlayerInputMillis != 0
				&& lastPlayerInputMillis >= GridTopUp.lastAutomationClickMillis();
			return;
		}
		if (observedIsPlayers) {
			return;
		}
		stableTicks++;
		if (stableTicks < STABLE_TICKS_BEFORE_RESCUE
			|| GridTopUp.millisSinceLastAutomationClick() < QUIET_MILLIS_BEFORE_RESCUE) {
			return;
		}
		// Retry cadence: one attempt per second while the stall persists.
		if ((stableTicks - STABLE_TICKS_BEFORE_RESCUE) % 20 != 0) {
			return;
		}
		String itemId = BuiltInRegistries.ITEM.getKey(carried.getItem()).toString();
		Slot destination = MenuTransferHelper.findPlayerDestinationSlot(client.player, menu, itemId);
		if (destination == null) {
			ReachCraftingMod.LOGGER.warn(
				"[cursor_watchdog] stray {} on cursor for {} ticks but no deposit slot",
				ContainerUtils.formatStack(carried), stableTicks);
			return;
		}
		ReachCraftingMod.LOGGER.warn(
			"[cursor_watchdog] cursor_rescue depositing stray {} (idle {} ticks, session active)",
			ContainerUtils.formatStack(carried), stableTicks);
		client.gameMode.handleContainerInput(
			menu.containerId, destination.index, 0, ContainerInput.PICKUP, client.player);
		GridTopUp.recordRescueClick();
		PlaceRecipeBudget.noteCursorRescue();
	}

	private static void reset() {
		observedCarried = ItemStack.EMPTY;
		stableTicks = 0;
		observedIsPlayers = false;
	}
}
