package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Queue preview: while a recipe is queued, the crafting grid shows exactly
 * which item its release will put in each slot and how many (the first batch,
 * for bulk).
 *
 * <p>Exactness contract. The plan is computed with the same resolver and
 * ingredient planner the release runs (see the lockstep notes in compute and
 * the queueRelease* predicates in RecipeClickExecutor). It is frozen at
 * release as a commitment, and the release's first placement consumes it and
 * places those exact items itself through ManualRecipePlacer. It cannot
 * leave that choice to the server: a vanilla place-recipe packet picks among
 * equivalent items (oak vs birch planks) in a hash order the client cannot
 * see on a dedicated server. Later bulk batches use the normal paths.</p>
 *
 * <p>A release that opens a chain offer previews the chain's final step
 * (the first iteration's, for a bulk chain); its commitment arms when that
 * same chain starts and is consumed by the final step's first batch.
 * Retrieve-first releases preview the craft of the shortfall. Retrieval Mode
 * releases craft nothing and show nothing.</p>
 *
 * <p>Backport: nothing here touches the GUI; GridPreviewRenderer holds the
 * version-specific drawing.</p>
 */
final class GridPreview {
	/** A commitment older than this belongs to some other release. */
	private static final long COMMITMENT_TTL_MILLIS = 60_000L;

	/** Where a previewed slot's items come from. */
	enum Source {
		INVENTORY,
		NEARBY,
		/** Crafted by an earlier step of the chain. */
		CHAIN
	}

	record PreviewSlot(int menuSlotIndex, ItemStack stack, Source source) {
	}

	/**
	 * @param slotItemIds the planned item per ingredient slot (null for an
	 *     empty pattern cell), in RecipeIngredientSummary order - exactly what
	 *     ManualRecipePlacer.placeCrafts takes
	 * @param copies per-slot count of the first batch (0 when nothing is crafted)
	 * @param result what the result slot shows: the batch's output plus
	 *     retrieve-first's haul, i.e. what this release really produces
	 * @param retrievedItems the part of result that comes from containers
	 * @param shortOfRequest the plan cannot make everything the queue asks
	 *     for; RecipeOutputCounter turns the queued total orange
	 * @param expectedCrafts crafts the whole release makes by this plan (all
	 *     batches, after any retrieval); PromptExpectation compares offers
	 *     against it
	 */
	record Plan(
		ResourceLocation recipeId,
		RecipeIngredientSummary summary,
		List<String> slotItemIds,
		int copies,
		List<PreviewSlot> slots,
		ItemStack result,
		boolean chain,
		int retrievedItems,
		boolean shortOfRequest,
		int expectedCrafts
	) {
		PreviewSlot slotAt(int menuSlotIndex) {
			for (PreviewSlot slot : slots) {
				if (slot.menuSlotIndex() == menuSlotIndex) {
					return slot;
				}
			}
			return null;
		}
	}

	private static List<Object> cachedKey;
	private static Plan cachedPlan;
	private static Plan commitment;
	private static long commitmentMillis;
	/** A chain commitment applies only once its own chain has started (onChainStarted). */
	private static boolean chainArmed;
	private static boolean loggedFailure;
	/** Why the last compute returned no plan; logged on each recompute so a missing preview explains itself. */
	private static String hiddenReason;
	/** The last compute hid the preview because nothing queued can be made (cannotCraft). */
	private static boolean hiddenCannotCraft;
	/** hiddenCannotCraft for cachedKey's compute. */
	private static boolean cachedCannotCraft;
	/** What the latest queuedPlan() call found: no plan, because nothing queued can be made. */
	private static boolean currentCannotCraft;
	/** The queued recipe's indicator at the queued count, for cachedKey's compute (null: keep the 1-craft state). */
	private static RecipeButtonNearbyIndicator.IndicatorState cachedIndicator;
	/** cachedIndicator as the latest queuedPlan() call found it (null when it found no queue to judge). */
	private static RecipeButtonNearbyIndicator.IndicatorState currentIndicator;

	private GridPreview() {
	}

	/** The preview for the recipe queued right now, or null when Queue Preview is off. */
	static Plan current(Minecraft client) {
		return ReachCraftingConfig.get().showGridPreview() ? queuedPlan(client) : null;
	}

	/**
	 * The plan for the recipe queued right now, or null, whether or not the
	 * preview draws it: the queued total's color reads it too. Cheap per
	 * frame: recomputed only when an input changes.
	 */
	private static Plan queuedPlan(Minecraft client) {
		currentCannotCraft = false;
		currentIndicator = null;
		ReachCraftingConfig config = ReachCraftingConfig.get();
		LocalPlayer player = client.player;
		Screen screen = client.screen;
		if (!config.enabled() || player == null || client.level == null
			|| (!(screen instanceof CraftingScreen) && !(screen instanceof InventoryScreen))) {
			return null;
		}
		RecipeBookClickCapture.PendingHeldRecipe pending = RecipeBookClickCapture.getPendingHeldRecipe();
		if (pending == null || pending.clickCount() <= 0 || isWorkRunning()) {
			return null;
		}
		// The release resolves these from the same keys (RecipeBookInputController.resolveRequest).
		boolean ctrl = RecipeBookFocusManager.isControlKeyDown(client);
		boolean allowNearby = (ctrl || config.nearbyAlwaysAllowed())
			&& config.enableNearbyContainerUsage();
		boolean autoCraft = config.altAsRequestKey() && RecipeBookFocusManager.isAltKeyDown(client);
		// Lockstep: the release asks DynamicAutoCraft the same question.
		boolean dynamic = DynamicAutoCraft.applies(autoCraft, ctrl);

		List<Object> key = new ArrayList<>(cacheKey(client, player, pending, allowNearby || dynamic, autoCraft));
		key.add(dynamic);
		if (!key.equals(cachedKey)) {
			cachedKey = key;
			if (dynamic) {
				allowNearby = DynamicAutoCraft.allowNearby(client, pending.action(), pending.clickCount(), false);
			}
			hiddenReason = null;
			hiddenCannotCraft = false;
			cachedPlan = computeSafely(client, player, screen, pending.action(), pending.clickCount(), allowNearby, autoCraft);
			cachedCannotCraft = cachedPlan == null && hiddenCannotCraft;
			cachedIndicator = indicatorFor(client, pending, cachedPlan, cachedCannotCraft, allowNearby, autoCraft);
			if (cachedPlan == null) {
				ReachCraftingMod.diag(
					"[grid_preview] hidden reason={} recipe={} count={} nearby={} auto_craft={}",
					hiddenReason,
					pending.action().recipeId(),
					pending.clickCount(),
					allowNearby,
					autoCraft
				);
			} else {
				ReachCraftingMod.diag(
					"[grid_preview] shown recipe={} copies={} slots={} nearby={}",
					cachedPlan.recipeId(),
					cachedPlan.copies(),
					cachedPlan.slotItemIds(),
					allowNearby
				);
			}
		}
		currentCannotCraft = cachedCannotCraft;
		currentIndicator = cachedIndicator;
		return cachedPlan;
	}

	/**
	 * RecipeButtonNearbyIndicator: the indicator a queued recipe's button
	 * shows for the queued count instead of one craft, or null to keep the
	 * one-craft state (another recipe, nothing queued, or no plan to judge).
	 */
	static RecipeButtonNearbyIndicator.IndicatorState queuedIndicatorState(Minecraft client, RecipeCollection collection) {
		RecipeBookClickCapture.PendingHeldRecipe pending = RecipeBookClickCapture.getPendingHeldRecipe();
		if (pending == null || collection == null || pending.action().collection() != collection) {
			return null;
		}
		queuedPlan(client);
		return currentIndicator;
	}

	/** The indicator state that describes how the release makes the queue: the plan's way, at the queued count. */
	private static RecipeButtonNearbyIndicator.IndicatorState indicatorFor(
		Minecraft client,
		RecipeBookClickCapture.PendingHeldRecipe pending,
		Plan plan,
		boolean cannotCraft,
		boolean allowNearby,
		boolean autoCraft
	) {
		if (plan == null) {
			return cannotCraft ? RecipeButtonNearbyIndicator.IndicatorState.NONE : null;
		}
		if (plan.chain()) {
			// The final step's slots do not show where the earlier steps'
			// materials come from, so ask whether the inventory alone chains it.
			if (!allowNearby) {
				return RecipeButtonNearbyIndicator.IndicatorState.CHAIN_LOCAL;
			}
			Plan local = planFor(client, pending.action(), pending.clickCount(), false, autoCraft);
			return local != null && local.chain() && !local.shortOfRequest()
				? RecipeButtonNearbyIndicator.IndicatorState.CHAIN_LOCAL
				: RecipeButtonNearbyIndicator.IndicatorState.CHAIN_NEARBY;
		}
		if (plan.slots().isEmpty()) {
			// A retrieval covers it: no craft to describe.
			return null;
		}
		for (PreviewSlot slot : plan.slots()) {
			if (slot.source() == Source.NEARBY) {
				return RecipeButtonNearbyIndicator.IndicatorState.NEARBY;
			}
		}
		return RecipeButtonNearbyIndicator.IndicatorState.LOCAL;
	}

	/** One plan, outside the queue: what DynamicAutoCraft weighs each way with. */
	static Plan planFor(
		Minecraft client,
		RecipeBookClickCapture.HeldRecipeAction action,
		int clickCount,
		boolean allowNearby,
		boolean autoCraft
	) {
		if (client.player == null || client.level == null || action == null) {
			return null;
		}
		return computeSafely(client, client.player, client.screen, action, clickCount, allowNearby, autoCraft);
	}

	/** Called by the queue release, before it flushes the grid: freezes the plan its first placement must follow. */
	static void commitForRelease(
		Minecraft client,
		RecipeBookClickCapture.HeldRecipeAction action,
		int clickCount,
		boolean allowNearby,
		boolean autoCraft
	) {
		commitment = null;
		chainArmed = false;
		cachedKey = null;
		PromptExpectation.clear();
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (!config.enabled() || !config.showGridPreview() || client.player == null || action == null) {
			return;
		}
		Plan plan = computeSafely(client, client.player, client.screen, action, clickCount, allowNearby, autoCraft);
		// What the player was shown, which the release's prompts compare to.
		PromptExpectation.recordPreview(plan);
		if (plan == null || plan.slots().isEmpty()) {
			// Nothing to place (a retrieval that covers the request).
			return;
		}
		commitment = plan;
		commitmentMillis = System.currentTimeMillis();
		ReachCraftingMod.diag(
			"[grid_preview] commit recipe={} copies={} slots={} chain={}",
			plan.recipeId(),
			plan.copies(),
			plan.slotItemIds(),
			plan.chain()
		);
	}

	/**
	 * RecipeOutputCounter: the queued total is more than the release can
	 * make, by the plan or, with no plan, because none of it can be made.
	 * Independent of the Queue Preview setting.
	 */
	static boolean isCurrentShortOfRequest(Minecraft client) {
		Plan plan = queuedPlan(client);
		return plan != null ? plan.shortOfRequest() : currentCannotCraft;
	}

	static void clearCommitment() {
		commitment = null;
		chainArmed = false;
	}

	/**
	 * ChainCraftController.start: a chain commitment arms only for the chain
	 * it previewed (same final recipe, same per-slot inputs). Any other chain
	 * starting means the commitment is stale; a flat commitment never covers
	 * a chain.
	 */
	static void onChainStarted(ChainCraftPlan chainPlan) {
		Plan plan = commitment;
		if (plan == null) {
			return;
		}
		if (!plan.chain() || chainArmed || chainPlan == null || chainPlan.steps().isEmpty()) {
			clearCommitment();
			ReachCraftingMod.diag("[grid_preview] commitment_dropped reason=other_chain recipe={}", plan.recipeId());
			return;
		}
		ChainCraftPlan.Step finalStep = chainPlan.steps().get(chainPlan.steps().size() - 1);
		if (!finalStep.recipeId().equals(plan.recipeId())
			|| !ManualRecipePlacer.resolvePerSlotInputs(finalStep).equals(plan.slotItemIds())) {
			clearCommitment();
			ReachCraftingMod.diag(
				"[grid_preview] commitment_dropped reason=chain_mismatch planned={} started={} slots={}",
				plan.recipeId(),
				finalStep.recipeId(),
				ManualRecipePlacer.resolvePerSlotInputs(finalStep)
			);
			return;
		}
		chainArmed = true;
		ReachCraftingMod.diag("[grid_preview] chain_armed recipe={} copies={}", plan.recipeId(), plan.copies());
	}

	/**
	 * Places the committed first batch if this placement belongs to it.
	 * Every placement branch a queue release can reach asks this first; a
	 * false return leaves that branch to place as it always has.
	 *
	 * @param beforeSession true for a branch that runs before SearchSession
	 *     (the bulk ring bypass): there a different recipe (the session may
	 *     still re-resolve the variant) or missing inputs (nearby pulls not
	 *     done yet) mean "not this branch", so the commitment is kept for the
	 *     session instead of dropped
	 * @param maxCopies the copies the calling branch is about to stage; a
	 *     commitment for more belongs to some other request (a retrieve-first
	 *     remainder, say) and must not over-craft this one
	 */
	static boolean tryPlaceCommitted(Minecraft client, ResourceLocation recipeId, String label, boolean beforeSession, int maxCopies) {
		Plan plan = commitment;
		if (plan == null || client.player == null) {
			return false;
		}
		if (plan.chain()) {
			// Intermediate steps place through the same branches: wait for
			// the final step of the chain this commitment armed for.
			if (!chainArmed || !ChainCraftController.isRunningFinalStep()) {
				return false;
			}
			// Lockstep: ChainCraftRun.nextBatchCopies, capped like placeCrafts.
			maxCopies = Math.min(maxCopies, ChainCraftController.currentScheduledBatchCopies());
		} else if (ChainCraftController.isActive() || BulkChainCraftController.isActive() || RetrieveThenCraftController.isActive()) {
			// A flat commitment still around belongs to a release that
			// diverted into one of these flows.
			clearCommitment();
			ReachCraftingMod.diag("[grid_preview] commitment_dropped reason=other_flow recipe={} at={}", plan.recipeId(), label);
			return false;
		}
		// An armed chain commitment lives as long as its chain, which can run
		// for minutes before its final step; only a loose one expires.
		if (!chainArmed && System.currentTimeMillis() - commitmentMillis > COMMITMENT_TTL_MILLIS) {
			clearCommitment();
			ReachCraftingMod.diag("[grid_preview] commitment_expired recipe={}", plan.recipeId());
			return false;
		}
		if (!plan.recipeId().equals(recipeId)) {
			if (beforeSession) {
				return false;
			}
			clearCommitment();
			ReachCraftingMod.diag("[grid_preview] commitment_mismatch planned={} placing={} at={}", plan.recipeId(), recipeId, label);
			return false;
		}
		if (plan.copies() > maxCopies) {
			clearCommitment();
			ReachCraftingMod.diag("[grid_preview] commitment_exceeds_target recipe={} planned={} target={} at={}", plan.recipeId(), plan.copies(), maxCopies, label);
			return false;
		}
		if (plan.shortOfRequest() && plan.copies() < maxCopies) {
			// The preview planned from the container cache and fell short; the
			// session has since found more (a container the cache had not
			// seen), so placing only the previewed copies would leave the rest
			// of the request uncrafted.
			clearCommitment();
			ReachCraftingMod.diag("[grid_preview] commitment_short_of_target recipe={} planned={} target={} at={}", plan.recipeId(), plan.copies(), maxCopies, label);
			return false;
		}
		AbstractContainerMenu menu = client.player.containerMenu;
		Map<String, Integer> needed = new LinkedHashMap<>();
		for (String itemId : plan.slotItemIds()) {
			if (itemId != null) {
				needed.merge(itemId, plan.copies(), Integer::sum);
			}
		}
		// Chain steps place pristine stacks only (ManualRecipePlacer.placeStepCrafts).
		boolean pristineOnly = plan.chain();
		for (Map.Entry<String, Integer> entry : needed.entrySet()) {
			if (ManualRecipePlacer.countSources(menu, entry.getKey(), pristineOnly) < entry.getValue()) {
				if (beforeSession) {
					return false;
				}
				clearCommitment();
				ReachCraftingMod.diag(
					"[grid_preview] commitment_inputs_missing recipe={} item={} need={} have={} at={}",
					plan.recipeId(),
					entry.getKey(),
					entry.getValue(),
					ManualRecipePlacer.countSources(menu, entry.getKey(), pristineOnly),
					label
				);
				return false;
			}
		}
		clearCommitment();
		if (!menu.getCarried().isEmpty() || !ContainerUtils.isGridEmpty(menu)) {
			ReachCraftingMod.diag("[grid_preview] commitment_grid_busy recipe={} at={}", plan.recipeId(), label);
			return false;
		}
		int estimatedClicks = ManualRecipePlacer.estimateClicks(plan.slotItemIds(), plan.copies());
		if (!GridTopUp.clickBudgetAllows(estimatedClicks)) {
			ReachCraftingMod.diag(
				"[grid_preview] commitment_budget_declined recipe={} estimated_clicks={} window={} at={}",
				plan.recipeId(),
				estimatedClicks,
				GridTopUp.clickWindowCount(),
				label
			);
			return false;
		}
		int staged = ManualRecipePlacer.placeCrafts(client, plan.summary(), plan.slotItemIds(), plan.copies(), pristineOnly, "preview " + label);
		ReachCraftingMod.diag(
			"[grid_preview] committed_place recipe={} planned={} staged={} at={}",
			plan.recipeId(),
			plan.copies(),
			staged,
			label
		);
		return staged > 0;
	}

	/**
	 * ContainerUtils.isAnySessionActive minus the pending queue itself (which
	 * it counts as active, and which is exactly when the preview shows).
	 */
	private static boolean isWorkRunning() {
		return RecipeBookClickCapture.getReplayBatch() != null
			|| AutoMoveController.isAutomatedInteractionRunning()
			|| NearbyContainerDryRun.isActiveSessionRunning()
			|| InventoryGridRestoreTracker.isRestoring()
			|| BulkAutoCraftController.isActive()
			|| ChainCraftController.isActive()
			|| BulkChainCraftController.isActive();
	}

	/**
	 * SearchSession plans its pulls before the committed batch is placed. If
	 * the plan changed since the preview (a retrieve-first scan opened chests
	 * the cache had not seen, so the counts moved), pull the previewed items
	 * instead, as long as every slot accepts them and enough are available.
	 * Otherwise the session keeps its own plan and the commitment, finding
	 * its inputs missing, falls back to the normal placement.
	 */
	static List<IngredientPlanning.SlotTarget> alignToCommitment(
		ResourceLocation recipeId,
		List<IngredientPlanning.SlotTarget> slotTargets,
		Map<String, Integer> available
	) {
		Plan plan = commitment;
		if (plan == null || !plan.recipeId().equals(recipeId) || slotTargets.isEmpty()) {
			return slotTargets;
		}
		List<IngredientPlanning.SlotTarget> aligned = new ArrayList<>(slotTargets.size());
		Map<String, Integer> needed = new LinkedHashMap<>();
		boolean changed = false;
		for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
			int index = slotTarget.slotIndex() - 1;
			String committed = index >= 0 && index < plan.slotItemIds().size() ? plan.slotItemIds().get(index) : null;
			if (committed == null || slotTarget.itemId() == null || !slotTarget.ingredientSlot().itemIds().contains(committed)) {
				return slotTargets;
			}
			changed |= !committed.equals(slotTarget.itemId());
			aligned.add(new IngredientPlanning.SlotTarget(slotTarget.slotIndex(), slotTarget.ingredientSlot(), committed, slotTarget.targetCount()));
			needed.merge(committed, slotTarget.targetCount(), Integer::sum);
		}
		for (Map.Entry<String, Integer> entry : needed.entrySet()) {
			if (available.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
				return slotTargets;
			}
		}
		if (changed) {
			ReachCraftingMod.diag("[grid_preview] session_plan_aligned recipe={} slots={}", recipeId, plan.slotItemIds());
		}
		return List.copyOf(aligned);
	}

	private static List<Object> cacheKey(
		Minecraft client,
		LocalPlayer player,
		RecipeBookClickCapture.PendingHeldRecipe pending,
		boolean allowNearby,
		boolean autoCraft
	) {
		AbstractContainerMenu menu = player.containerMenu;
		List<ItemStack> gridStacks = new ArrayList<>();
		int gridSlotCount = gridSlotCount(client.screen);
		for (int i = 1; i <= gridSlotCount && i < menu.slots.size(); i++) {
			gridStacks.add(menu.getSlot(i).getItem());
		}
		int gridHash = 1;
		for (ItemStack stack : gridStacks) {
			gridHash = 31 * gridHash + (stack.isEmpty() ? 0 : Objects.hash(stack.getItem(), stack.getCount()));
		}
		ReachCraftingConfig config = ReachCraftingConfig.get();
		Object nearbyView = allowNearby && config.cacheContainersForFasterSearch()
			? NearbyContainerCache.getReachableView(client.level, client.getCameraEntity(), (net.minecraft.client.Minecraft.getInstance().gameMode != null ? net.minecraft.client.Minecraft.getInstance().gameMode.getPickRange() : 4.5D))
			: null;
		return List.of(
			pending.action().recipeId(),
			pending.action().explicitVariantSelection(),
			pending.clickCount(),
			allowNearby,
			autoCraft,
			AutoCraftController.isBulkModeEnabled(),
			gridSlotCount,
			InventoryFingerprint.totals(player),
			gridHash,
			nearbyView == null ? 0 : System.identityHashCode(nearbyView),
			System.identityHashCode(config)
		);
	}

	private static Plan computeSafely(
		Minecraft client,
		LocalPlayer player,
		Screen screen,
		RecipeBookClickCapture.HeldRecipeAction action,
		int clickCount,
		boolean allowNearby,
		boolean autoCraft
	) {
		try {
			return compute(client, player, screen, action, clickCount, allowNearby, autoCraft);
		} catch (RuntimeException e) {
			// A preview must never break the screen it decorates.
			if (!loggedFailure) {
				loggedFailure = true;
				ReachCraftingMod.LOGGER.warn("[grid_preview] compute failed; preview hidden", e);
			}
			return hidden("compute_failed");
		}
	}

	private static Plan compute(
		Minecraft client,
		LocalPlayer player,
		Screen screen,
		RecipeBookClickCapture.HeldRecipeAction action,
		int clickCount,
		boolean allowNearby,
		boolean autoCraft
	) {
		int gridSlotCount = gridSlotCount(screen);
		if (gridSlotCount == 0 || clickCount <= 0) {
			return hidden("no_grid");
		}
		if (ExistingOutputRetrievalController.isEnabled()) {
			return hidden("retrieval_mode");
		}
		ReachCraftingConfig config = ReachCraftingConfig.get();
		boolean bulk = AutoCraftController.isBulkModeEnabled();
		int requested = Math.max(clickCount, 1);

		// The release flushes the grid into the inventory before it runs, so
		// plan against inventory + grid with an empty grid.
		Map<String, Integer> local = AvailableItemSnapshot.capture(player, screen).totalCounts();
		AvailableItemSnapshot flushed = new AvailableItemSnapshot(local, Map.of(), local, List.of(), Map.of());

		// Lockstep: RecipeClickExecutor's first resolve (a release is a
		// forced dry run, so variant switching is allowed).
		RecipeVariantResolver.Selection selected = RecipeVariantResolver.resolve(
			client,
			player,
			action.recipe(),
			action.collection(),
			action.displayStack() != null ? action.displayStack().copy() : ItemStack.EMPTY,
			action.explicitVariantSelection(),
			true,
			flushed,
			local,
			local,
			false,
			false,
			requested
		);
		if (selected == null) {
			return hidden("no_variant");
		}
		NearbyContainerCache.ReachableView view = allowNearby && config.cacheContainersForFasterSearch()
			? NearbyContainerCache.getReachableView(client.level, client.getCameraEntity(), (net.minecraft.client.Minecraft.getInstance().gameMode != null ? net.minecraft.client.Minecraft.getInstance().gameMode.getPickRange() : 4.5D))
			: null;
		Map<String, Integer> availableCounts = view == null
			? local
			: AvailableItemSnapshot.mergeCounts(local, view.craftableCountsFor(selected.ingredientSummary().acceptedItemIds()));

		// Retrieve-first: the release pulls existing output before crafting
		// and crafts only the shortfall (RetrieveThenCraftController.decide).
		// Planned from what the cache knows; unopened containers count as
		// empty, so with nothing cached the full request is crafted.
		RecipeClickExecutor.QueueRetrieveFirst retrieveFirst =
			RecipeClickExecutor.predictQueueRetrieveFirst(client, player, action, selected, allowNearby, requested);
		RecipeClickExecutor.QueueRetrieveFirst retrieved = RecipeClickExecutor.QueueRetrieveFirst.NONE;
		if (retrieveFirst.runs() && retrieveFirst.retrievedItems() > 0) {
			retrieved = retrieveFirst;
			int shortfall = retrieveFirst.targetItems() - retrieveFirst.retrievedItems();
			if (shortfall <= 0 || config.existingOutputHandling() == ReachCraftingConfig.ExistingOutputHandling.RETRIEVE_ONLY) {
				// Nothing is crafted: the result slot alone shows what the
				// retrieval brings.
				return new Plan(
					selected.recipeId(),
					selected.ingredientSummary(),
					List.of(),
					0,
					List.of(),
					retrieveFirst.retrieveStack().copyWithCount(retrieveFirst.retrievedItems()),
					false,
					retrieveFirst.retrievedItems(),
					retrieveFirst.retrievedItems() < retrieveFirst.targetItems(),
					0
				);
			}
			requested = (shortfall + retrieveFirst.outputPerCraft() - 1) / retrieveFirst.outputPerCraft();
		}
		RecipeDeficitReport deficit = RecipeDeficitReport.from(selected.ingredientSummary(), availableCounts, List.of(), requested);
		boolean continuesVariants = false;
		if (RecipeClickExecutor.queueReleaseContinuesVariants(action, selected, autoCraft)) {
			int possible = deficit.possibleCopies();
			if (possible > 0 && possible < requested) {
				// The release crafts this variant's share, then continues
				// with siblings: the grid shows the first share.
				requested = possible;
				continuesVariants = true;
			}
		}
		if (!continuesVariants) {
			java.util.Optional<RecipeClickExecutor.ChainCraftOffer> chainOffer = RecipeClickExecutor.predictQueueChainOffer(
				client, player, action, flushed, selected, deficit, allowNearby, autoCraft, requested);
			if (chainOffer.isPresent()) {
				return finish(
					computeChainFinalStep(client, gridSlotCount, chainOffer.get(), allowNearby, bulk, local),
					requested,
					chainOffer.get().plan().finalRecipeCopies(),
					retrieved,
					true
				);
			}
		}

		// Lockstep: the nearby bulk branch of RecipeClickExecutor places
		// straight from the inventory when it already holds one copy; every
		// other release runs SearchSession, which pulls first.
		boolean localDirect = allowNearby
			&& bulk
			&& !RecipeDeficitReport.from(selected.ingredientSummary(), local, List.of(), 1).hasMissingIngredients();
		RecipeVariantResolver.Selection selection = selected;
		Map<String, Integer> totals = local;
		if (!localDirect) {
			// Lockstep: SearchSession.start seeds from the cache, then
			// buildSearchPlanDecision re-resolves (no reserved grid).
			boolean sessionNearby = allowNearby && config.enableNearbyContainerUsage();
			if (sessionNearby && view != null && !view.isEmpty()) {
				totals = AvailableItemSnapshot.mergeCounts(local, view.craftableCountsFor(scanAcceptedItemIds(selected, action.collection(), gridSlotCount)));
			}
			RecipeVariantResolver.Selection resolved = RecipeVariantResolver.resolve(
				client,
				player,
				selected.recipe(),
				action.collection(),
				ItemStack.EMPTY,
				action.explicitVariantSelection(),
				sessionNearby,
				flushed,
				totals,
				totals,
				false,
				false,
				requested
			);
			if (resolved != null) {
				selection = resolved;
			}
		}

		RecipeIngredientSummary summary = selection.ingredientSummary();
		IngredientPlanning.Policy policy = PlanningPreferences.policyFor(config.toPlanningPolicy(), selection, action.collection());
		Map<String, Integer> preferenceTotals = PlanningPreferences.preferenceTotalsFor(totals, selection, action.collection());
		int maxCopies = IngredientPlanning.computeMaxCraftCopies(summary, local, List.of(), totals, preferenceTotals, policy);
		int target = Math.min(requested, maxCopies);
		IngredientPlanning.PlanResult planned = IngredientPlanning.plan(summary, local, List.of(), totals, preferenceTotals, target, policy);
		if (!localDirect && bulk && target > 0) {
			// Lockstep: SearchSession.clampTargetCopiesToInventoryCapacity.
			int clamped = clampToInventoryCapacity(player, summary, local, totals, preferenceTotals, policy, target);
			if (clamped != target) {
				target = clamped;
				planned = IngredientPlanning.plan(summary, local, List.of(), totals, preferenceTotals, target, policy);
			}
		}
		if (target <= 0 || planned.hasMissingIngredients()) {
			Map<String, Integer> acceptedTotals = new LinkedHashMap<>();
			for (String itemId : summary.acceptedItemIds()) {
				acceptedTotals.put(itemId, totals.getOrDefault(itemId, 0));
			}
			return cannotCraft("missing_ingredients target=" + target + " max=" + maxCopies
				+ " output=" + selection.outputItemId()
				+ " needs=[" + summary.compactSummary() + "]"
				+ " have=" + acceptedTotals);
		}

		List<String> slotItemIds = new ArrayList<>();
		int batchCap = Integer.MAX_VALUE;
		for (int i = 0; i < summary.slots().size(); i++) {
			if (summary.slots().get(i).isEmpty()) {
				slotItemIds.add(null);
				continue;
			}
			String itemId = null;
			for (IngredientPlanning.SlotTarget slotTarget : planned.slotTargets()) {
				if (slotTarget.slotIndex() == i + 1) {
					itemId = slotTarget.itemId();
					break;
				}
			}
			ItemStack prototype = prototype(itemId);
			if (prototype.isEmpty()) {
				return hidden("unplanned_slot " + i);
			}
			slotItemIds.add(itemId);
			batchCap = Math.min(batchCap, Math.max(prototype.getMaxStackSize(), 1));
		}
		int copies = Math.min(target, batchCap);
		// ManualRecipePlacer places through this same map, so the preview
		// cells are the cells the committed batch lands in.
		List<Integer> gridIndices = summary.gridSlotIndices(gridSlotCount);
		if (copies <= 0 || gridIndices.size() != slotItemIds.size()) {
			return hidden("shape_unmappable");
		}

		Map<String, Integer> localLeft = new LinkedHashMap<>(local);
		List<PreviewSlot> slots = new ArrayList<>();
		for (int i = 0; i < slotItemIds.size(); i++) {
			String itemId = slotItemIds.get(i);
			if (itemId == null) {
				continue;
			}
			int have = localLeft.getOrDefault(itemId, 0);
			localLeft.put(itemId, Math.max(have - copies, 0));
			slots.add(new PreviewSlot(gridIndices.get(i), prototype(itemId).copyWithCount(copies), have < copies ? Source.NEARBY : Source.INVENTORY));
		}
		// Not List.copyOf: a shaped pattern's gaps (a furnace's middle) are
		// null entries, and List.copyOf rejects nulls.
		// What the whole release can make: a local-direct bulk places its
		// first batch from the inventory, but later batches pull too.
		int achievableCopies = localDirect ? deficit.possibleCopies() : maxCopies;
		return finish(new Plan(
			selection.recipeId(),
			summary,
			java.util.Collections.unmodifiableList(slotItemIds),
			copies,
			List.copyOf(slots),
			selection.displayStack().copy(),
			false,
			0,
			false,
			0
		), requested, achievableCopies, retrieved, !continuesVariants);
	}

	/**
	 * The final step of the chain a release would offer: what the chain's
	 * last craft puts in the grid (its first batch). A bulk chain runs
	 * iteration by iteration, so this is the first iteration's final step,
	 * planned by the same function the session runs.
	 */
	private static Plan computeChainFinalStep(
		Minecraft client,
		int gridSlotCount,
		RecipeClickExecutor.ChainCraftOffer offer,
		boolean allowNearby,
		boolean bulk,
		Map<String, Integer> local
	) {
		ChainCraftPlan chainPlan = offer.plan();
		if (bulk) {
			if (!ReachCraftingConfig.get().enableBulkChainCrafting()) {
				return hidden("bulk_chain_disabled");
			}
			// Lockstep: ChainCraftPopupController.handleBulkChainPlan sizes the
			// session to the offer, BulkChainCraftController plans iteration one.
			int targetCopies = chainPlan.finalRecipeCopies();
			BulkChainCraftController.IterationPlan iteration = BulkChainCraftController.planIteration(
				client,
				offer.selection(),
				allowNearby,
				BulkChainCraftController.firstIterationBatchTarget(targetCopies),
				0,
				targetCopies
			);
			if (iteration.plan().isEmpty()) {
				return iteration.failure() == BulkChainCraftController.IterationFailure.MATERIALS
					? cannotCraft("bulk_chain_no_iteration " + iteration.failure())
					: hidden("bulk_chain_no_iteration " + iteration.failure());
			}
			chainPlan = iteration.plan().get();
		}
		if (chainPlan.steps().isEmpty()) {
			return hidden("chain_empty");
		}
		ChainCraftPlan.Step finalStep = chainPlan.steps().get(chainPlan.steps().size() - 1);
		// Lockstep: ManualRecipePlacer.placeStepCrafts places these per slot.
		List<String> slotItemIds = ManualRecipePlacer.resolvePerSlotInputs(finalStep);
		if (slotItemIds.isEmpty()) {
			return hidden("chain_final_unresolved");
		}
		int batchCap = Integer.MAX_VALUE;
		for (String itemId : slotItemIds) {
			if (itemId == null) {
				continue;
			}
			ItemStack prototype = prototype(itemId);
			if (prototype.isEmpty()) {
				return hidden("chain_final_unknown_item " + itemId);
			}
			batchCap = Math.min(batchCap, Math.max(prototype.getMaxStackSize(), 1));
		}
		// Lockstep: ChainCraftRun.nextBatchCopies (at most 64 a batch).
		int copies = Math.min(Math.min(Math.max(finalStep.recipeCopies(), 1), 64), batchCap);
		RecipeIngredientSummary summary = finalStep.ingredientSummary();
		List<Integer> gridIndices = summary.gridSlotIndices(gridSlotCount);
		if (copies <= 0 || gridIndices.size() != slotItemIds.size()) {
			return hidden("shape_unmappable");
		}

		Set<String> craftedByChain = new HashSet<>();
		for (ChainCraftPlan.Step step : chainPlan.steps()) {
			if (!step.finalStep()) {
				craftedByChain.add(BuiltInRegistries.ITEM.getKey(step.displayStack().getItem()).toString());
			}
		}
		Map<String, Integer> localLeft = new LinkedHashMap<>(local);
		List<PreviewSlot> slots = new ArrayList<>();
		for (int i = 0; i < slotItemIds.size(); i++) {
			String itemId = slotItemIds.get(i);
			if (itemId == null) {
				continue;
			}
			int have = localLeft.getOrDefault(itemId, 0);
			localLeft.put(itemId, Math.max(have - copies, 0));
			Source source = have >= copies
				? Source.INVENTORY
				: craftedByChain.contains(itemId) ? Source.CHAIN : Source.NEARBY;
			slots.add(new PreviewSlot(gridIndices.get(i), prototype(itemId).copyWithCount(copies), source));
		}
		return new Plan(
			finalStep.recipeId(),
			summary,
			java.util.Collections.unmodifiableList(new ArrayList<>(slotItemIds)),
			copies,
			List.copyOf(slots),
			finalStep.displayStack().copy(),
			true,
			0,
			false,
			0
		);
	}

	/**
	 * Sizes the result slot to what the release produces (the batch, plus
	 * retrieve-first's haul) and flags when that falls short of the request.
	 * plan.result() comes in as one craft's output. Retrieve-first's haul is
	 * added when it is the same item the craft makes (a retrieval can settle
	 * on another variant, which has no slot of its own to show in).
	 *
	 * @param requestedCopies crafts the release asks for (after retrieve-first)
	 * @param achievableCopies crafts the plan can make in all
	 * @param flagShort false where a shortfall is not final (output variant
	 *     continuation goes on to sibling variants)
	 */
	private static Plan finish(
		Plan plan,
		int requestedCopies,
		int achievableCopies,
		RecipeClickExecutor.QueueRetrieveFirst retrieved,
		boolean flagShort
	) {
		if (plan == null) {
			return null;
		}
		ItemStack perCraft = plan.result();
		int outputPerCraft = Math.max(perCraft.getCount(), 1);
		int retrievedItems = retrieved.retrievedItems() > 0 && ItemStack.isSameItemSameTags(perCraft, retrieved.retrieveStack())
			? retrieved.retrievedItems()
			: 0;
		int requestedItems = Math.max(requestedCopies, 1) * outputPerCraft + retrievedItems;
		int achievableItems = Math.min(Math.max(achievableCopies, 0), Math.max(requestedCopies, 1)) * outputPerCraft + retrievedItems;
		return new Plan(
			plan.recipeId(),
			plan.summary(),
			plan.slotItemIds(),
			plan.copies(),
			plan.slots(),
			perCraft.copyWithCount(plan.copies() * outputPerCraft + retrievedItems),
			plan.chain(),
			retrievedItems,
			flagShort && achievableItems < requestedItems,
			Math.min(Math.max(achievableCopies, 0), Math.max(requestedCopies, 1))
		);
	}

	private static Plan hidden(String reason) {
		hiddenReason = reason;
		return null;
	}

	/** hidden(), for a release that would make none of the queued crafts: the queued total shows as short. */
	private static Plan cannotCraft(String reason) {
		hiddenCannotCraft = true;
		return hidden(reason);
	}

	/** Lockstep: SearchSession's scanAcceptedItemIds. */
	private static Set<String> scanAcceptedItemIds(RecipeVariantResolver.Selection selected, RecipeCollection collection, int gridSlotCount) {
		Set<String> accepted = new HashSet<>(selected.ingredientSummary().acceptedItemIds());
		if (collection != null && (ReachCraftingConfig.get().toPlanningPolicy().redistributeToCraftWhenNeeded()
			|| ReachCraftingConfig.get().revolvingCraftHandling() != ReachCraftingConfig.RevolvingCraftHandling.SPECIFIC_VARIANT_ONLY)) {
			int craftingGridSize = Math.max(gridSlotCount, 4);
			for (var entry : collection.getRecipes()) {
				accepted.addAll(RecipeIngredientSummary.fromRecipe(entry, craftingGridSize).acceptedItemIds());
			}
		}
		return Set.copyOf(accepted);
	}

	/** Lockstep: SearchSession.clampTargetCopiesToInventoryCapacity and canStageCurrentPlan. */
	private static int clampToInventoryCapacity(
		LocalPlayer player,
		RecipeIngredientSummary summary,
		Map<String, Integer> local,
		Map<String, Integer> totals,
		Map<String, Integer> preferenceTotals,
		IngredientPlanning.Policy policy,
		int requestedCopies
	) {
		int low = 0;
		int high = requestedCopies;
		while (low < high) {
			int mid = (low + high + 1) / 2;
			IngredientPlanning.PlanResult midPlan = IngredientPlanning.plan(summary, local, List.of(), totals, preferenceTotals, mid, policy);
			if (midPlan.hasMissingIngredients() || !canStage(player, midPlan.slotTargets(), local)) {
				high = mid - 1;
			} else {
				low = mid;
			}
		}
		return low;
	}

	private static boolean canStage(LocalPlayer player, List<IngredientPlanning.SlotTarget> slotTargets, Map<String, Integer> alreadyCovered) {
		Map<String, Integer> desired = new LinkedHashMap<>();
		for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
			if (slotTarget.itemId() != null && slotTarget.targetCount() > 0) {
				desired.merge(slotTarget.itemId(), slotTarget.targetCount(), Integer::sum);
			}
		}
		Map<String, Integer> toStage = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : desired.entrySet()) {
			int missing = entry.getValue() - alreadyCovered.getOrDefault(entry.getKey(), 0);
			if (missing > 0) {
				toStage.put(entry.getKey(), missing);
			}
		}
		return StagingInventorySimulator.canFit(player, toStage);
	}

	private static int gridSlotCount(Screen screen) {
		if (screen instanceof CraftingScreen) {
			return 9;
		}
		if (screen instanceof InventoryScreen) {
			return 4;
		}
		return 0;
	}

	private static ItemStack prototype(String itemId) {
		if (itemId == null || itemId.isEmpty()) {
			return ItemStack.EMPTY;
		}
		try {
			var item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
			return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
		} catch (Exception ignored) {
			return ItemStack.EMPTY;
		}
	}
}
