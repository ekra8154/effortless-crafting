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
	// bucket admits burst + rate*window per window, so the configurable
	// initial rate (default 4.0) + DEFAULT_BURST_CAPACITY sits under that.
	private static final double DEFAULT_BURST_CAPACITY = 3.0;
	private static final double MIN_RATE_PER_SECOND = 0.5;
	private static final double MIN_BURST_CAPACITY = 2.0;
	// Bursts stay small while the rate is in stock-Paper territory (the 4s
	// window tolerates little), and may grow once the server has proven
	// permissive.
	private static final double MAX_BURST_CAPACITY = 10.0;
	// Probing pattern (M4): while the ceiling is untouched (this server has
	// never dropped us) each clean batch multiplies the rate — a permissive
	// server reaches full speed within one session. The first drop records
	// WHERE the wall is (ceiling = 0.9x the failing rate) and probing turns
	// additive below it. After enough consecutive clean batches the ceiling
	// relaxes, so a server whose config was loosened gets re-probed.
	private static final double SLOW_START_MULTIPLIER = 1.25;
	private static final double ADDITIVE_INCREASE_PER_PROGRESS = 0.1;
	private static final int CEILING_RELAX_CLEAN_BATCHES = 300;
	private static final double CEILING_RELAX_MULTIPLIER = 1.5;
	private static final long PERSIST_DEBOUNCE_MS = 10_000;

	private static double ratePerSecond = 4.0;
	private static double burstCapacity = DEFAULT_BURST_CAPACITY;
	private static double ceilingRate = 30.0;
	private static double tokens = DEFAULT_BURST_CAPACITY;
	private static long lastRefillNanos = System.nanoTime();
	// Per-server persistence (PlaceBudgetStore): which server the current
	// in-memory budget belongs to, and whether it has unsaved changes.
	private static String currentServerKey = null;
	private static int cleanProgressSinceDrop = 0;
	private static boolean budgetDirty = false;
	private static long lastPersistMillis = 0;
	// Timestamps of actual place-packet sends. Probing only raises the rate
	// when recent demand UTILIZED most of the current budget: our optimized
	// workloads often send far below the allowance (one packet per T1
	// batch), and climbing on such idle "clean batches" learned a fantasy
	// rate (30/s on stock Paper) that later bursty sessions crashed into.
	private static final ArrayDeque<Long> sendTimes = new ArrayDeque<>();
	private static final long UTILIZATION_WINDOW_MS = 5_000;
	private static final double UTILIZATION_THRESHOLD = 0.7;

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
	 * Point the in-memory budget at the current server, loading its persisted
	 * values on first contact (or falling back to config defaults). Cheap
	 * when the server hasn't changed; called from every public entry point.
	 */
	private static void ensureServerBudgetLoaded(Minecraft client) {
		String key = client.getCurrentServer() != null ? client.getCurrentServer().ip : null;
		if (key == null || key.equals(currentServerKey)) {
			return;
		}
		persistIfDirty(true);
		currentServerKey = key;
		cleanProgressSinceDrop = 0;
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (!config.packetBudgetAdaptive()) {
			// Learning disabled: pin to the configured initial rate, never
			// load or persist. In-session drops may still lower it (safety),
			// but nothing is remembered across servers or restarts.
			ratePerSecond = config.packetBudgetInitialRate();
			burstCapacity = DEFAULT_BURST_CAPACITY;
			ceilingRate = config.packetBudgetInitialRate();
			tokens = Math.min(tokens, burstCapacity);
			budgetDirty = false;
			ReachCraftingMod.LOGGER.info(
				"[place_budget] adaptive OFF; pinned server={} rate={}/s", key,
				String.format("%.2f", ratePerSecond));
			return;
		}
		PlaceBudgetStore.ServerBudget stored = PlaceBudgetStore.load(key);
		if (stored != null) {
			ratePerSecond = clamp(stored.rate(), MIN_RATE_PER_SECOND, config.packetBudgetMaxRate());
			burstCapacity = clamp(stored.burst(), MIN_BURST_CAPACITY, MAX_BURST_CAPACITY);
			ceilingRate = clamp(stored.ceiling(), MIN_RATE_PER_SECOND, config.packetBudgetMaxRate());
			ReachCraftingMod.LOGGER.info(
				"[place_budget] loaded persisted budget server={} rate={}/s burst={} ceiling={}/s",
				key, String.format("%.2f", ratePerSecond), String.format("%.1f", burstCapacity),
				String.format("%.2f", ceilingRate));
		} else {
			ratePerSecond = config.packetBudgetInitialRate();
			burstCapacity = DEFAULT_BURST_CAPACITY;
			ceilingRate = config.packetBudgetMaxRate();
			ReachCraftingMod.LOGGER.info(
				"[place_budget] new server={} starting budget rate={}/s ceiling={}/s",
				key, String.format("%.2f", ratePerSecond), String.format("%.2f", ceilingRate));
		}
		tokens = Math.min(tokens, burstCapacity);
		budgetDirty = false;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static void persistIfDirty(boolean force) {
		if (!budgetDirty || currentServerKey == null || !ReachCraftingConfig.get().packetBudgetAdaptive()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!force && now - lastPersistMillis < PERSIST_DEBOUNCE_MS) {
			return;
		}
		lastPersistMillis = now;
		budgetDirty = false;
		PlaceBudgetStore.save(currentServerKey,
			new PlaceBudgetStore.ServerBudget(ratePerSecond, burstCapacity, ceilingRate, now));
	}

	/**
	 * Mixin gate. Returns true when the send may proceed now; false means the
	 * placement was queued and the caller's packet must be cancelled.
	 */
	public static boolean permitOrDefer(Minecraft client, int containerId, RecipeDisplayId recipeId, boolean useMaxItems) {
		if (flushingDeferred || isUnlimited(client)) {
			return true;
		}
		ensureServerBudgetLoaded(client);
		refill();
		if (deferred.isEmpty() && tokens >= 1.0) {
			ensureCursorClear(client);
			tokens -= 1.0;
			lastSendTick = clientTicks;
			noteSend();
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
		persistIfDirty(false);
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
			noteSend();
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
		ensureServerBudgetLoaded(Minecraft.getInstance());
		// Remember WHERE the wall is: probing turns additive below it, so
		// this server costs at most an occasional drop instead of a sawtooth.
		ceilingRate = Math.max(MIN_RATE_PER_SECOND, ratePerSecond * 0.9);
		ratePerSecond = Math.max(MIN_RATE_PER_SECOND, ratePerSecond * 0.5);
		burstCapacity = Math.max(MIN_BURST_CAPACITY, burstCapacity * 0.75);
		tokens = 0.0;
		cleanProgressSinceDrop = 0;
		budgetDirty = true;
		persistIfDirty(true);
		ReachCraftingMod.LOGGER.warn(
			"[place_budget] suspected server drop - assumed budget lowered to rate={}/s burst={} ceiling={}/s",
			String.format("%.2f", ratePerSecond), String.format("%.1f", burstCapacity),
			String.format("%.2f", ceilingRate)
		);
	}

	/**
	 * A batch made real progress: probe the budget upward. Slow-start
	 * (multiplicative) while this server has never dropped us — a permissive
	 * server reaches the max rate within a session; additive once a ceiling
	 * is known. Long droughts of clean batches relax the ceiling so a
	 * reconfigured server eventually gets re-probed.
	 */
	public static void onSessionProgress() {
		Minecraft client = Minecraft.getInstance();
		if (isUnlimited(client)) {
			return;
		}
		ensureServerBudgetLoaded(client);
		if (!ReachCraftingConfig.get().packetBudgetAdaptive()) {
			return; // learning disabled: never probe up
		}
		if (recentSendRate() < ratePerSecond * UTILIZATION_THRESHOLD) {
			// The current budget was not even used; a "clean batch" at idle
			// proves nothing about the server's limit. Do not raise, do not
			// count toward relaxing the ceiling.
			return;
		}
		double maxRate = ReachCraftingConfig.get().packetBudgetMaxRate();
		double before = ratePerSecond;
		if (ceilingRate >= maxRate) {
			ratePerSecond = Math.min(maxRate, ratePerSecond * SLOW_START_MULTIPLIER);
		} else {
			ratePerSecond = Math.min(ceilingRate, ratePerSecond + ADDITIVE_INCREASE_PER_PROGRESS);
		}
		double burstCap = ratePerSecond <= 5.0 ? DEFAULT_BURST_CAPACITY : Math.min(MAX_BURST_CAPACITY, ratePerSecond);
		burstCapacity = Math.min(burstCap, burstCapacity + 0.2);
		cleanProgressSinceDrop++;
		if (cleanProgressSinceDrop >= CEILING_RELAX_CLEAN_BATCHES && ceilingRate < maxRate) {
			ceilingRate = Math.min(maxRate, ceilingRate * CEILING_RELAX_MULTIPLIER);
			cleanProgressSinceDrop = 0;
			ReachCraftingMod.LOGGER.info(
				"[place_budget] ceiling relaxed to {}/s after {} utilized clean batches",
				String.format("%.2f", ceilingRate), CEILING_RELAX_CLEAN_BATCHES);
		}
		if (ratePerSecond != before) {
			budgetDirty = true;
			ReachCraftingMod.LOGGER.info(
				"[place_budget] probed up rate={}/s burst={} (utilized {}/s)",
				String.format("%.2f", ratePerSecond), String.format("%.1f", burstCapacity),
				String.format("%.2f", recentSendRate()));
		}
	}

	/** Forget the current server's learned budget so it re-probes from the
	 * configured initial rate on next contact. Invoked when the user turns
	 * adaptive learning off (no separate reset control). */
	public static void forgetCurrentServerBudget() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.getCurrentServer() == null) {
			return;
		}
		String key = client.getCurrentServer().ip;
		PlaceBudgetStore.remove(key);
		currentServerKey = null; // force a fresh load on next contact
		budgetDirty = false;
		ReachCraftingMod.LOGGER.info("[place_budget] forgot learned budget for server={} (adaptive turned off)", key);
	}

	/** Human-readable current-server budget for the config screen, or null
	 * when not connected to a rate-limited server. */
	public static String currentServerBudgetSummary() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.getCurrentServer() == null || isUnlimited(client)) {
			return null;
		}
		String key = client.getCurrentServer().ip;
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (!config.packetBudgetAdaptive()) {
			return String.format("%s: pinned at %.1f/s (adaptive learning off)", key, config.packetBudgetInitialRate());
		}
		PlaceBudgetStore.ServerBudget stored = PlaceBudgetStore.load(key);
		if (stored != null) {
			return String.format("%s: learned %.1f/s (probe ceiling %.1f/s)", key, stored.rate(), stored.ceiling());
		}
		// No stored entry does NOT mean "unknown speed" -- it means the budget
		// has never had to change on this server, so it is simply running at
		// the default. Show that, not a bare "not learned".
		return String.format("%s: %.1f/s (default; only adjusts if the server limits recipe placement)",
			key, config.packetBudgetInitialRate());
	}

	private static void noteSend() {
		sendTimes.addLast(System.currentTimeMillis());
	}

	/** Actual place-packet sends per second over the trailing window. */
	private static double recentSendRate() {
		long now = System.currentTimeMillis();
		while (!sendTimes.isEmpty() && now - sendTimes.peekFirst() > UTILIZATION_WINDOW_MS) {
			sendTimes.pollFirst();
		}
		return sendTimes.size() * 1000.0 / UTILIZATION_WINDOW_MS;
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
		ensureServerBudgetLoaded(client);
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
