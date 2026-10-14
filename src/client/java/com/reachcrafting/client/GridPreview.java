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
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

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
 * <p>Chain crafts, retrieve-first and Retrieval Mode releases are not
 * previewed yet: compute returns null for them.</p>
 *
 * <p>Backport: nothing here touches the GUI; GridPreviewRenderer holds the
 * version-specific drawing.</p>
 */
final class GridPreview {
	/** A commitment older than this belongs to some other release. */
	private static final long COMMITMENT_TTL_MILLIS = 10_000L;

	record PreviewSlot(int menuSlotIndex, ItemStack stack, boolean fromNearby) {
	}

	/**
	 * @param slotItemIds the planned item per ingredient slot (null for an
	 *     empty pattern cell), in RecipeIngredientSummary order - exactly what
	 *     ManualRecipePlacer.placeCrafts takes
	 * @param copies per-slot count of the first batch
	 */
	record Plan(
		RecipeDisplayId recipeId,
		RecipeIngredientSummary summary,
		List<String> slotItemIds,
		int copies,
		List<PreviewSlot> slots,
		ItemStack result
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
	private static boolean loggedFailure;

	private GridPreview() {
	}

	/** The preview for the recipe queued right now, or null. Cheap per frame: recomputed only when an input changes. */
	static Plan current(Minecraft client) {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		LocalPlayer player = client.player;
		Screen screen = client.gui.screen();
		if (!config.enabled() || !config.showGridPreview() || player == null || client.level == null
			|| (!(screen instanceof CraftingScreen) && !(screen instanceof InventoryScreen))) {
			return null;
		}
		RecipeBookClickCapture.PendingHeldRecipe pending = RecipeBookClickCapture.getPendingHeldRecipe();
		if (pending == null || pending.clickCount() <= 0 || isWorkRunning()) {
			return null;
		}
		// The release resolves these from the same keys (RecipeBookInputController.resolveRequest).
		boolean allowNearby = (RecipeBookFocusManager.isControlKeyDown(client) || config.nearbyAlwaysAllowed())
			&& config.enableNearbyContainerUsage();
		boolean autoCraft = config.altAsRequestKey() && RecipeBookFocusManager.isAltKeyDown(client);

		List<Object> key = cacheKey(client, player, pending, allowNearby, autoCraft);
		if (!key.equals(cachedKey)) {
			cachedKey = key;
			cachedPlan = computeSafely(client, player, screen, pending.action(), pending.clickCount(), allowNearby, autoCraft);
		}
		return cachedPlan;
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
		cachedKey = null;
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (!config.enabled() || !config.showGridPreview() || client.player == null || action == null) {
			return;
		}
		Plan plan = computeSafely(client, client.player, client.gui.screen(), action, clickCount, allowNearby, autoCraft);
		if (plan == null) {
			return;
		}
		commitment = plan;
		commitmentMillis = System.currentTimeMillis();
		ReachCraftingMod.diag(
			"[grid_preview] commit recipe={} copies={} slots={}",
			plan.recipeId(),
			plan.copies(),
			plan.slotItemIds()
		);
	}

	static void clearCommitment() {
		commitment = null;
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
	static boolean tryPlaceCommitted(Minecraft client, RecipeDisplayId recipeId, String label, boolean beforeSession, int maxCopies) {
		Plan plan = commitment;
		if (plan == null || client.player == null) {
			return false;
		}
		if (ChainCraftController.isActive() || BulkChainCraftController.isActive() || RetrieveThenCraftController.isActive()) {
			// Previews never cover these flows; a commitment still around
			// belongs to a release that diverted into one.
			commitment = null;
			ReachCraftingMod.diag("[grid_preview] commitment_dropped reason=other_flow recipe={} at={}", plan.recipeId(), label);
			return false;
		}
		if (System.currentTimeMillis() - commitmentMillis > COMMITMENT_TTL_MILLIS) {
			commitment = null;
			ReachCraftingMod.diag("[grid_preview] commitment_expired recipe={}", plan.recipeId());
			return false;
		}
		if (!plan.recipeId().equals(recipeId)) {
			if (beforeSession) {
				return false;
			}
			commitment = null;
			ReachCraftingMod.diag("[grid_preview] commitment_mismatch planned={} placing={} at={}", plan.recipeId(), recipeId, label);
			return false;
		}
		if (plan.copies() > maxCopies) {
			commitment = null;
			ReachCraftingMod.diag("[grid_preview] commitment_exceeds_target recipe={} planned={} target={} at={}", plan.recipeId(), plan.copies(), maxCopies, label);
			return false;
		}
		AbstractContainerMenu menu = client.player.containerMenu;
		Map<String, Integer> needed = new LinkedHashMap<>();
		for (String itemId : plan.slotItemIds()) {
			if (itemId != null) {
				needed.merge(itemId, plan.copies(), Integer::sum);
			}
		}
		for (Map.Entry<String, Integer> entry : needed.entrySet()) {
			if (ManualRecipePlacer.countSources(menu, entry.getKey(), false) < entry.getValue()) {
				if (beforeSession) {
					return false;
				}
				commitment = null;
				ReachCraftingMod.diag(
					"[grid_preview] commitment_inputs_missing recipe={} item={} need={} have={} at={}",
					plan.recipeId(),
					entry.getKey(),
					entry.getValue(),
					ManualRecipePlacer.countSources(menu, entry.getKey(), false),
					label
				);
				return false;
			}
		}
		commitment = null;
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
		int staged = ManualRecipePlacer.placeCrafts(client, plan.summary(), plan.slotItemIds(), plan.copies(), false, "preview " + label);
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

	private static List<Object> cacheKey(
		Minecraft client,
		LocalPlayer player,
		RecipeBookClickCapture.PendingHeldRecipe pending,
		boolean allowNearby,
		boolean autoCraft
	) {
		AbstractContainerMenu menu = player.containerMenu;
		List<ItemStack> gridStacks = new ArrayList<>();
		int gridSlotCount = gridSlotCount(client.gui.screen());
		for (int i = 1; i <= gridSlotCount && i < menu.slots.size(); i++) {
			gridStacks.add(menu.getSlot(i).getItem());
		}
		int gridHash = 1;
		for (ItemStack stack : gridStacks) {
			gridHash = 31 * gridHash + (stack.isEmpty() ? 0 : Objects.hash(stack.getItem(), stack.getCount()));
		}
		ReachCraftingConfig config = ReachCraftingConfig.get();
		Object nearbyView = allowNearby && config.cacheContainersForFasterSearch()
			? NearbyContainerCache.getReachableView(client.level, client.getCameraEntity(), player.blockInteractionRange())
			: null;
		return List.of(
			pending.action().recipeId().index(),
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
			return null;
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
		if (gridSlotCount == 0 || clickCount <= 0 || ExistingOutputRetrievalController.isEnabled()) {
			return null;
		}
		ReachCraftingConfig config = ReachCraftingConfig.get();
		boolean bulk = AutoCraftController.isBulkModeEnabled();
		int requested = Math.max(clickCount, 1);

		// The release flushes the grid into the inventory before it runs, so
		// plan against inventory + grid with an empty grid.
		Map<String, Integer> local = AvailableItemSnapshot.capture(player, screen).totalCounts();
		AvailableItemSnapshot flushed = new AvailableItemSnapshot(local, Map.of(), local, List.of());

		// Lockstep: RecipeClickExecutor's first resolve (a release is a
		// forced dry run, so variant switching is allowed).
		RecipeVariantResolver.Selection selected = RecipeVariantResolver.resolve(
			client,
			player,
			action.recipeId(),
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
			return null;
		}
		NearbyContainerCache.ReachableView view = allowNearby && config.cacheContainersForFasterSearch()
			? NearbyContainerCache.getReachableView(client.level, client.getCameraEntity(), player.blockInteractionRange())
			: null;
		Map<String, Integer> availableCounts = view == null
			? local
			: AvailableItemSnapshot.mergeCounts(local, view.countsFor(selected.ingredientSummary().acceptedItemIds()));

		if (RecipeClickExecutor.queueReleaseRetrievesFirst(client, player, action, selected, allowNearby, requested)) {
			return null;
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
		if (!continuesVariants && RecipeClickExecutor.queueReleaseOffersChain(
			client, player, action, flushed, selected, deficit, allowNearby, autoCraft, requested)) {
			return null;
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
				totals = AvailableItemSnapshot.mergeCounts(local, view.countsFor(scanAcceptedItemIds(client, selected, action.collection())));
			}
			RecipeVariantResolver.Selection resolved = RecipeVariantResolver.resolve(
				client,
				player,
				selected.recipeId(),
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
			return null;
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
				return null;
			}
			slotItemIds.add(itemId);
			batchCap = Math.min(batchCap, Math.max(prototype.getMaxStackSize(), 1));
		}
		int copies = Math.min(target, batchCap);
		// ManualRecipePlacer places through this same map, so the preview
		// cells are the cells the committed batch lands in.
		List<Integer> gridIndices = summary.gridSlotIndices(gridSlotCount);
		if (copies <= 0 || gridIndices.size() != slotItemIds.size()) {
			return null;
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
			slots.add(new PreviewSlot(gridIndices.get(i), prototype(itemId).copyWithCount(copies), have < copies));
		}
		return new Plan(selection.recipeId(), summary, List.copyOf(slotItemIds), copies, List.copyOf(slots), selection.displayStack().copy());
	}

	/** Lockstep: SearchSession's scanAcceptedItemIds. */
	private static Set<String> scanAcceptedItemIds(Minecraft client, RecipeVariantResolver.Selection selected, RecipeCollection collection) {
		Set<String> accepted = new HashSet<>(selected.ingredientSummary().acceptedItemIds());
		if (collection != null && (ReachCraftingConfig.get().toPlanningPolicy().redistributeToCraftWhenNeeded()
			|| ReachCraftingConfig.get().revolvingCraftHandling() != ReachCraftingConfig.RevolvingCraftHandling.SPECIFIC_VARIANT_ONLY)) {
			ContextMap context = SlotDisplayContext.fromLevel(client.level);
			for (var entry : collection.getRecipes()) {
				accepted.addAll(RecipeIngredientSummary.fromDisplay(entry.display(), context).acceptedItemIds());
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
			var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
			return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
		} catch (Exception ignored) {
			return ItemStack.EMPTY;
		}
	}
}
