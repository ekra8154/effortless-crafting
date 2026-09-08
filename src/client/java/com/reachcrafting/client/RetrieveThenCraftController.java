package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/**
 * The retrieve-first step of a Ctrl-assisted recipe click.
 *
 * <p>When the {@code existingOutputHandling} setting is not CRAFT_ONLY, the
 * executor hands the click here instead of crafting. A fill-only retrieval
 * session pulls the output from nearby containers up to the request (uncapped
 * for a craft-all); once it has resumed the crafting screen the shortfall is
 * either crafted (RETRIEVE_THEN_CRAFT), offered (RETRIEVE_THEN_ASK), or left
 * alone (RETRIEVE_ONLY). The remainder is the original click replayed with
 * {@code retrievalDone} set, so the executor skips this step the second time.
 * A retrieval that found nothing crafts the full request straight away in every
 * mode: with nothing nearby there was never a retrieval to be had.</p>
 */
final class RetrieveThenCraftController {
	private static final int CONTEXT_WAIT_LIMIT_TICKS = 100;

	record FollowUp(
		RecipeBookClickCapture.HeldRecipeAction action,
		int requestedClicks,
		boolean allowNearby,
		boolean craftAll,
		boolean refillableBulkMaxMode,
		boolean autoCraftRequested,
		int outputPerCraft,
		int targetItems,
		String outputItemId,
		ItemStack displayStack,
		ReachCraftingConfig.ExistingOutputHandling handling,
		RecipeDisplayId resolvedRecipeId,
		boolean fillOnly,
		boolean variantRetried
	) {
	}

	private record Result(FollowUp followUp, int retrieved, boolean aborted) {
	}

	private record PendingReplay(FollowUp followUp, int clicks, String outcome, int retrieved) {
	}

	private static FollowUp active;
	private static Result pendingResult;
	private static int contextWaitTicks;
	private static PendingReplay pendingReplay;

	private RetrieveThenCraftController() {
	}

	static boolean isActive() {
		return active != null || pendingResult != null || pendingReplay != null;
	}

	/** Executor shortcut: the cache is complete for reach and holds none of the output. */
	static void logNoneNearby(String outputItemId, int targetItems, ReachCraftingConfig.ExistingOutputHandling handling) {
		ReachCraftingMod.diag(
			"[retrieve_then_craft] rtc_complete outcome=none_nearby via=cache item={} target={} retrieved=0 shortfall={} remainder_clicks=0 handling={}",
			outputItemId,
			targetItems,
			targetItems,
			handling
		);
	}

	static void start(FollowUp followUp) {
		boolean fillOnly = followUp.fillOnly();
		active = followUp;
		pendingResult = null;
		pendingReplay = null;
		ReachCraftingMod.diag(
			"[retrieve_then_craft] start item={} target={} clicks={} craft_all={} fill_only={} handling={} variant_retry={}",
			followUp.outputItemId(),
			followUp.targetItems(),
			followUp.requestedClicks(),
			followUp.craftAll(),
			fillOnly,
			followUp.handling(),
			followUp.variantRetried()
		);
		NearbyContainerDryRun.startExistingOutputRetrieval(new ExistingOutputRetrievalRequest(
			followUp.action().recipeId(),
			followUp.resolvedRecipeId(),
			followUp.action().collection(),
			followUp.action().explicitVariantSelection(),
			followUp.outputItemId(),
			followUp.outputItemId() + " x" + followUp.displayStack().getCount(),
			followUp.displayStack().copy(),
			followUp.targetItems(),
			fillOnly,
			followUp
		));
		if (!NearbyContainerDryRun.isRetrievalSessionRunning() && active == followUp) {
			// The session refused to start (hands busy, cursor occupied). Fall
			// back to the craft the player asked for rather than eating the click.
			ReachCraftingMod.diag("[retrieve_then_craft] session_refused item={}", followUp.outputItemId());
			active = null;
			pendingResult = new Result(followUp, 0, false);
			contextWaitTicks = 0;
		}
	}

	/** Called by the retrieval session when it ends, normally or aborted. */
	static void onRetrievalFinished(FollowUp followUp, int retrieved, boolean aborted) {
		if (active != followUp) {
			return;
		}
		active = null;
		pendingResult = new Result(followUp, retrieved, aborted);
		contextWaitTicks = 0;
	}

	static void tick(Minecraft client) {
		if (pendingReplay != null) {
			tickPendingReplay(client);
			return;
		}
		if (pendingResult == null) {
			return;
		}
		if (client.player == null) {
			pendingResult = null;
			return;
		}
		if (!isCraftingContext(client)) {
			// The session resumes the crafting screen before it reports, so
			// this is only ever a few ticks of settling; give up if it is not.
			if (++contextWaitTicks > CONTEXT_WAIT_LIMIT_TICKS) {
				logComplete(pendingResult.followUp(), "context_lost", pendingResult.retrieved(), 0);
				pendingResult = null;
			}
			return;
		}
		Result result = pendingResult;
		pendingResult = null;
		decide(result);
	}

	private static void decide(Result result) {
		FollowUp followUp = result.followUp();
		int retrieved = result.retrieved();
		if (result.aborted()) {
			logComplete(followUp, "aborted", retrieved, 0);
			return;
		}
		if (retrieved <= 0) {
			// Nothing of THIS variant nearby. The discovery just warmed the
			// cache, so the revolving-variant fallback that had nothing to go
			// on at click time can be asked once more: dark oak stairs
			// requested, oak stairs found in a chest -> retrieve those.
			FollowUp alternate = followUp.variantRetried() ? null : resolveAlternateVariant(followUp);
			if (alternate != null) {
				ReachCraftingMod.diag(
					"[retrieve_then_craft] variant_retry from={} to={} target={}",
					followUp.outputItemId(),
					alternate.outputItemId(),
					alternate.targetItems()
				);
				start(alternate);
				return;
			}
			// Nothing nearby after all: same as the cache shortcut, an
			// ordinary craft of the request.
			scheduleRemainder(followUp, followUp.requestedClicks(), "none_nearby", 0);
			return;
		}
		boolean uncapped = followUp.craftAll();
		if (!uncapped && retrieved >= followUp.targetItems()) {
			logComplete(followUp, "satisfied", retrieved, 0);
			return;
		}
		int shortfall = uncapped ? -1 : followUp.targetItems() - retrieved;
		int remainderClicks = uncapped
			? Math.max(followUp.requestedClicks(), 1)
			: Math.max((shortfall + followUp.outputPerCraft() - 1) / followUp.outputPerCraft(), 1);
		switch (followUp.handling()) {
			case RETRIEVE_ONLY -> logComplete(followUp, "retrieve_only", retrieved, 0);
			case RETRIEVE_THEN_CRAFT -> {
				if (!uncapped) {
					ReachCraftingModClient.sendChat(Component.translatable(
						"message.reachcrafting.retrieve_then_craft.crafting_remainder",
						shortfall,
						ContainerUtils.getItemName(followUp.outputItemId())
					).getString());
				}
				scheduleRemainder(followUp, remainderClicks, "crafted_remainder", retrieved);
			}
			case RETRIEVE_THEN_ASK -> {
				String itemName = ContainerUtils.getItemName(followUp.outputItemId());
				Component message = uncapped
					? Component.translatable("popup.reachcrafting.retrieve_then_craft.max_message", retrieved, itemName)
					: Component.translatable("popup.reachcrafting.retrieve_then_craft.message", retrieved, followUp.targetItems(), itemName, shortfall);
				ReachCraftingMod.diag(
					"[retrieve_then_craft] popup item={} retrieved={} target={} shortfall={} remainder_clicks={}",
					followUp.outputItemId(),
					retrieved,
					followUp.targetItems(),
					shortfall,
					remainderClicks
				);
				ChainCraftPopupController.showConfirm(
					Component.translatable("popup.reachcrafting.retrieve_then_craft.title"),
					message,
					() -> scheduleRemainder(followUp, remainderClicks, "crafted_remainder", retrieved),
					() -> logComplete(followUp, "declined", retrieved, remainderClicks)
				);
			}
			case CRAFT_ONLY -> logComplete(followUp, "craft_only", retrieved, 0);
		}
	}

	/** The variant the retrieval resolver picks now that the cache is warm, or null if it is the same one (or none). */
	private static FollowUp resolveAlternateVariant(FollowUp followUp) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null || client.getCameraEntity() == null) {
			return null;
		}
		java.util.Map<String, Integer> nearbyTotals = NearbyContainerCache.getReachableView(
			client.level, client.getCameraEntity(), client.player.blockInteractionRange()).aggregateCounts();
		RecipeVariantResolver.Selection selection = RecipeVariantResolver.resolveRetrievalVariant(
			client,
			client.player,
			followUp.action().recipeId(),
			followUp.action().collection(),
			followUp.action().displayStack().copy(),
			followUp.action().explicitVariantSelection(),
			true,
			AvailableItemSnapshot.empty(),
			nearbyTotals,
			nearbyTotals,
			followUp.craftAll(),
			false,
			Math.max(followUp.requestedClicks(), 1)
		);
		if (selection == null || selection.displayStack().isEmpty()) {
			return null;
		}
		ItemStack stack = selection.displayStack().copy();
		String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		if (itemId.equals(followUp.outputItemId()) || nearbyTotals.getOrDefault(itemId, 0) <= 0) {
			return null;
		}
		int outputPerCraft = Math.max(stack.getCount(), 1);
		int targetItems = followUp.craftAll() ? RecipeClickExecutor.bulkRecipeQueueLimit() : Math.max(followUp.requestedClicks(), 1) * outputPerCraft;
		return new FollowUp(
			followUp.action(),
			followUp.requestedClicks(),
			followUp.allowNearby(),
			followUp.craftAll(),
			followUp.refillableBulkMaxMode(),
			followUp.autoCraftRequested(),
			outputPerCraft,
			targetItems,
			itemId,
			stack,
			followUp.handling(),
			selection.recipeId(),
			followUp.fillOnly(),
			true
		);
	}

	private static void scheduleRemainder(FollowUp followUp, int clicks, String outcome, int retrieved) {
		pendingReplay = new PendingReplay(followUp, Math.max(clicks, 1), outcome, retrieved);
		contextWaitTicks = 0;
	}

	private static void tickPendingReplay(Minecraft client) {
		if (client.player == null) {
			pendingReplay = null;
			return;
		}
		if (!isCraftingContext(client)
			|| NearbyContainerDryRun.isActiveSessionRunning()
			|| ContainerUtils.isInputQueueActive()
			|| ContainerUtils.isAutoMovePending()) {
			if (++contextWaitTicks > CONTEXT_WAIT_LIMIT_TICKS) {
				logComplete(pendingReplay.followUp(), "context_lost", pendingReplay.retrieved(), 0);
				pendingReplay = null;
			}
			return;
		}
		PendingReplay replay = pendingReplay;
		pendingReplay = null;
		FollowUp followUp = replay.followUp();
		logComplete(followUp, replay.outcome(), replay.retrieved(), replay.clicks());
		AutoCraftController.armHoldSessionForCurrentRequest(followUp.autoCraftRequested());
		RecipeBookClickCapture.scheduleReplay(
			followUp.action(),
			replay.clicks(),
			followUp.allowNearby(),
			followUp.craftAll(),
			followUp.refillableBulkMaxMode(),
			followUp.autoCraftRequested(),
			true
		);
	}

	private static boolean isCraftingContext(Minecraft client) {
		return client.screen instanceof CraftingScreen || client.screen instanceof InventoryScreen;
	}

	/** One line per decided click, in a fixed key=value shape the e2e driver parses. */
	private static void logComplete(FollowUp followUp, String outcome, int retrieved, int remainderClicks) {
		int shortfall = followUp.craftAll() || retrieved < 0 ? -1 : Math.max(followUp.targetItems() - retrieved, 0);
		ReachCraftingMod.diag(
			"[retrieve_then_craft] rtc_complete outcome={} via=session item={} target={} retrieved={} shortfall={} remainder_clicks={} handling={}",
			outcome,
			followUp.outputItemId(),
			followUp.targetItems(),
			retrieved,
			shortfall,
			remainderClicks,
			followUp.handling()
		);
	}
}
