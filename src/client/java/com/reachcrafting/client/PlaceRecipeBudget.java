package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import java.util.ArrayDeque;

/**
 * Client-side token bucket for {@code ServerboundPlaceRecipePacket} sends.
 *
 * Dedicated servers ration this packet: stock Paper drops anything beyond an
 * average of 5/s over a 4s window (paper-global.yml packet-limiter,
 * minecraft:place_recipe, action DROP) and kicks at a sustained 20/s
 * (spam-limiter recipe-spam-limit). A dropped placement is silent — the grid
 * never populates and the result-slot wait times out — which used to kill
 * whole bulk sessions ("Crafted 19 Dispenser in ~5s").
 *
 * Every handlePlaceRecipe call is intercepted in MultiPlayerGameModeMixin and
 * routed through {@link #permitOrDefer}: sends inside the budget pass through
 * untouched, sends beyond it are queued and flushed as tokens refill. The
 * defaults sit safely under Paper's stock limits; if a result-slot timeout
 * fires anyway (stricter server), {@link #onSuspectedDrop} halves the assumed
 * rate and {@link #onSessionProgress} slowly probes back up (AIMD), so the
 * budget converges toward whatever the current server actually enforces.
 *
 * Integrated servers (singleplayer / LAN host) have no limiter and bypass all
 * of this.
 */
public final class PlaceRecipeBudget {
	// Paper's stock limiter admits 20 place packets per 4s window. A token
	// bucket admits burst + rate*window per window, so keep
	// DEFAULT_BURST_CAPACITY + DEFAULT_RATE_PER_SECOND*4 <= ~19 for margin.
	private static final double DEFAULT_RATE_PER_SECOND = 4.0;
	private static final double DEFAULT_BURST_CAPACITY = 3.0;
	private static final double MIN_RATE_PER_SECOND = 0.5;
	private static final double MIN_BURST_CAPACITY = 2.0;

	private static double ratePerSecond = DEFAULT_RATE_PER_SECOND;
	private static double burstCapacity = DEFAULT_BURST_CAPACITY;
	private static double tokens = DEFAULT_BURST_CAPACITY;
	private static long lastRefillNanos = System.nanoTime();

	private record PendingPlace(int containerId, RecipeDisplayId recipeId, boolean useMaxItems) {
	}

	private static final ArrayDeque<PendingPlace> deferred = new ArrayDeque<>();
	private static boolean flushingDeferred = false;
	// Client-tick clock for drop attribution: a result-slot timeout is only a
	// drop signature if we actually sent a placement recently.
	private static long clientTicks = 0;
	private static long lastSendTick = Long.MIN_VALUE;
	private static long lastDeferralLogTick = Long.MIN_VALUE;
	private static final int DROP_ATTRIBUTION_WINDOW_TICKS = 45;
	// A cursor rescue near a timeout means the timeout was (most likely) the
	// occupied cursor no-oping our place packet, not the server's limiter.
	private static long lastCursorRescueTick = Long.MIN_VALUE;
	private static final int CURSOR_RESCUE_ATTRIBUTION_WINDOW_TICKS = 30;

	private PlaceRecipeBudget() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(PlaceRecipeBudget::tick);
	}

	static boolean isUnlimited(Minecraft client) {
		return client.hasSingleplayerServer();
	}

	/**
	 * Mixin gate. Returns true when the send may proceed now; false means the
	 * placement was queued and the caller's packet must be cancelled.
	 */
	public static boolean permitOrDefer(Minecraft client, int containerId, RecipeDisplayId recipeId, boolean useMaxItems) {
		if (flushingDeferred || isUnlimited(client)) {
			return true;
		}
		refill();
		if (deferred.isEmpty() && tokens >= 1.0) {
			ensureCursorClear(client);
			tokens -= 1.0;
			lastSendTick = clientTicks;
			return true;
		}
		deferred.addLast(new PendingPlace(containerId, recipeId, useMaxItems));
		if (clientTicks - lastDeferralLogTick >= 20) {
			lastDeferralLogTick = clientTicks;
			ReachCraftingMod.LOGGER.info(
				"[place_budget] deferring container={} queue={} tokens={} rate={}/s",
				containerId, deferred.size(), String.format("%.1f", tokens), String.format("%.1f", ratePerSecond)
			);
		}
		return false;
	}

	private static void tick(Minecraft client) {
		clientTicks++;
		if (deferred.isEmpty()) {
			return;
		}
		if (client.player == null || client.gameMode == null) {
			deferred.clear();
			return;
		}
		refill();
		while (!deferred.isEmpty() && tokens >= 1.0) {
			PendingPlace pending = deferred.pollFirst();
			if (pending.containerId() != client.player.containerMenu.containerId) {
				ReachCraftingMod.LOGGER.info(
					"[place_budget] dropped stale deferred place container={} current={}",
					pending.containerId(), client.player.containerMenu.containerId
				);
				continue;
			}
			ensureCursorClear(client);
			tokens -= 1.0;
			lastSendTick = clientTicks;
			flushingDeferred = true;
			try {
				client.gameMode.handlePlaceRecipe(pending.containerId(), pending.recipeId(), pending.useMaxItems());
			} finally {
				flushingDeferred = false;
			}
		}
	}

	/** True while a placement for this container is still queued client-side
	 * (i.e. the server has not been asked yet, so no result can exist). */
	public static boolean hasPendingFor(int containerId) {
		for (PendingPlace pending : deferred) {
			if (pending.containerId() == containerId) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The server refuses recipe placement while the player's cursor holds an
	 * item (vanilla ServerPlaceRecipe bails on a non-empty carried stack), and
	 * the refusal is SILENT — indistinguishable from a limiter drop. Observed
	 * on a large server whose anti-cheat rewrites automation clicks: the
	 * resync lands a flushed crafting remainder (cake's empty buckets) back
	 * on the cursor after the flush already reported success client-side.
	 * Deposit the stray stack before every place send.
	 */
	private static void ensureCursorClear(Minecraft client) {
		var player = client.player;
		if (player == null || client.gameMode == null) {
			return;
		}
		var menu = player.containerMenu;
		net.minecraft.world.item.ItemStack carried = menu.getCarried();
		if (carried.isEmpty()) {
			return;
		}
		String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(carried.getItem()).toString();
		net.minecraft.world.inventory.Slot destination = MenuTransferHelper.findPlayerDestinationSlot(player, menu, itemId);
		if (destination == null) {
			ReachCraftingMod.LOGGER.warn(
				"[place_budget] cursor_occupied no deposit slot for {} - place may no-op",
				ContainerUtils.formatStack(carried));
			return;
		}
		ReachCraftingMod.LOGGER.warn(
			"[place_budget] cursor_rescue depositing {} before place send",
			ContainerUtils.formatStack(carried));
		client.gameMode.handleInventoryMouseClick(
			menu.containerId, destination.index, 0,
			net.minecraft.world.inventory.ClickType.PICKUP, player);
		GridTopUp.recordClick();
		noteCursorRescue();
	}

	/** A stray carried stack was just deposited: a result timeout inside the
	 * attribution window is explained by the occupied cursor, not the limiter. */
	public static void noteCursorRescue() {
		lastCursorRescueTick = clientTicks;
	}

	/** A result-slot wait timed out after a sent placement: the server most
	 * likely dropped the packet. Halve the assumed budget (AIMD decrease) and
	 * treat the window as saturated. */
	public static void onSuspectedDrop() {
		if (isUnlimited(Minecraft.getInstance())) {
			return;
		}
		if (lastCursorRescueTick != Long.MIN_VALUE
			&& clientTicks - lastCursorRescueTick <= CURSOR_RESCUE_ATTRIBUTION_WINDOW_TICKS) {
			ReachCraftingMod.LOGGER.info(
				"[place_budget] timeout after cursor rescue ({} ticks ago) - occupied cursor, not a limiter drop; budget unchanged",
				clientTicks - lastCursorRescueTick);
			return;
		}
		if (clientTicks - lastSendTick > DROP_ATTRIBUTION_WINDOW_TICKS) {
			// No placement was sent recently, so this timeout can't be a
			// dropped place packet (e.g. a click-staged chain step failed for
			// its own reasons). Don't punish the budget for it.
			ReachCraftingMod.LOGGER.info(
				"[place_budget] timeout without recent place send ({} ticks ago) - budget unchanged",
				clientTicks - lastSendTick);
			return;
		}
		ratePerSecond = Math.max(MIN_RATE_PER_SECOND, ratePerSecond * 0.5);
		burstCapacity = Math.max(MIN_BURST_CAPACITY, burstCapacity * 0.75);
		tokens = 0.0;
		ReachCraftingMod.LOGGER.warn(
			"[place_budget] suspected server drop - assumed budget lowered to rate={}/s burst={}",
			String.format("%.2f", ratePerSecond), String.format("%.1f", burstCapacity)
		);
	}

	/** A batch made real progress: probe the budget back up gently (AIMD increase). */
	public static void onSessionProgress() {
		ratePerSecond = Math.min(DEFAULT_RATE_PER_SECOND, ratePerSecond + 0.1);
		burstCapacity = Math.min(DEFAULT_BURST_CAPACITY, burstCapacity + 0.2);
	}

	/** Ticks to wait before retrying after a suspected drop: long enough to
	 * accumulate a handful of tokens at the current assumed rate. */
	public static int suggestedBackoffTicks() {
		double neededTokens = Math.max(0.0, 6.0 - tokens);
		int ticks = (int) Math.ceil(neededTokens / Math.max(ratePerSecond, 0.1) * 20.0) + 20;
		return Math.max(40, Math.min(ticks, 300));
	}

	/** Backoff for a stalled bulk-chain iteration. */
	public static int stallBackoffTicks(Minecraft client) {
		if (isUnlimited(client)) {
			return 1;
		}
		return suggestedBackoffTicks();
	}

	/**
	 * Place packets affordable over the next ~5 seconds (current tokens plus
	 * refill). Callers price their own work against this — chain iterations
	 * compute the actual per-plan packet cost (T1 steps cost one packet per
	 * batch, legacy steps one per copy) instead of assuming a fixed
	 * placements-per-copy ratio.
	 */
	public static double affordablePlaces(Minecraft client) {
		if (isUnlimited(client)) {
			return Double.MAX_VALUE;
		}
		refill();
		return tokens + ratePerSecond * 5.0;
	}

	private static void refill() {
		long now = System.nanoTime();
		double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
		lastRefillNanos = now;
		tokens = Math.min(burstCapacity, tokens + elapsedSeconds * ratePerSecond);
	}
}
