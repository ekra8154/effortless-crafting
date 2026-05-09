package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * SearchSession owns nearby-container craft discovery and withdrawal for one recipe action.
 * Invariants: tick only advances through explicit SearchState transitions; planning is computed once
 * per withdraw pass and applied through helper transitions rather than ad hoc field mutation.
 */
final class SearchSession extends BaseCraftSession {
	private static final int OPEN_TIMEOUT_TICKS = 40;
	private static final int RESUME_DELAY_TICKS = 5; // Increased from 2 to 10 to prevent race conditions
	private static final int REOPEN_TIMEOUT_TICKS = 20;
	private static final int RESTORE_TIMEOUT_TICKS = 10;
	private static final int MAX_REOPEN_ATTEMPTS = 3;

	private final RecipeCollection recipeCollection;
	private final boolean explicitVariantSelection;
	private final int recipeIndex;
	private final RecipeDisplayId initialRequestedRecipeId;
	private final String initialRequestedOutputLabel;
	private RecipeDisplayId recipeId;
	private String outputLabel;
	private RecipeIngredientSummary ingredientSummary;
	private final AvailableItemSnapshot localItems;
	private final boolean craftAll;
	private final int requestedSingleClicks;
	private final IngredientPlanning.Policy planningPolicy;
	private final RecipeDeficitReport initialDeficit;
	private final List<String> remainingItemIds;
	private final ScreenContextSnapshot originalContext;
	private final Set<String> scanAcceptedItemIds;
	private NearbyContainerCache.ReachableView reachableView;
	private final List<BlockPos> candidates;
	private List<BlockPos> activeCandidates = List.of();
	private final Set<BlockPos> visited = new HashSet<>();
	private final Map<String, Integer> discoveredNearby = new LinkedHashMap<>();
	private final Map<String, Integer> withdrawnItems = new LinkedHashMap<>();
	private final boolean allowNearby;
	private final boolean useCachedSearch;
	private List<IngredientPlanning.SlotTarget> plannedTargets = List.of();

	private int nextCandidateIndex;
	private int timeoutTicks;
	private int restoreTicksRemaining;
	private int reopenAttemptsRemaining;
	private int seedWaitTicksRemaining;
	private int scannedContainers;
	private String lastRestoreFailure = "<none>";
	private int targetCopiesPerSlot;
	private BlockPos pendingContainerPos;
	private boolean inventorySpaceBlocked;
	private boolean redistributeThisRun;
	private boolean discoveryFallbackStarted;
	private String blockedCommittedLayoutMissingSummary;
	private SearchPhase phase;
	private SearchState state = SearchState.OPEN_NEXT;

	SearchSession(
		NearbyCraftCoordinator coordinator,
		Minecraft client,
		LocalPlayer player,
		Level level,
		MultiPlayerGameMode gameMode,
		Entity cameraEntity,
		SearchRequest request
	) {
		super(coordinator, client, player, level, gameMode, cameraEntity);
		this.recipeCollection = request.recipeCollection();
		this.explicitVariantSelection = request.explicitVariantSelection();
		this.initialRequestedRecipeId = request.recipeId();
		this.initialRequestedOutputLabel = request.outputLabel();
		this.recipeId = request.recipeId();
		this.recipeIndex = request.recipeIndex();
		this.outputLabel = request.outputLabel();
		this.ingredientSummary = request.ingredientSummary();
		this.localItems = request.localItems();
		this.craftAll = request.craftAll();
		this.requestedSingleClicks = Math.max(request.requestedSingleClicks(), 1);
		this.allowNearby = request.allowNearby() && ReachCraftingConfig.get().enableNearbyContainerUsage();
		this.originalContext = ScreenContextSnapshot.capture(client, cameraEntity, player.blockInteractionRange(), this.localItems);
		this.planningPolicy = ReachCraftingConfig.get().toPlanningPolicy();
		Set<String> accepted = new HashSet<>(this.ingredientSummary.acceptedItemIds());
		if (recipeCollection != null && (planningPolicy.redistributeToCraftWhenNeeded() || ReachCraftingConfig.get().revolvingCraftHandling() != ReachCraftingConfig.RevolvingCraftHandling.SPECIFIC_VARIANT_ONLY)) {
			ContextMap context = SlotDisplayContext.fromLevel(client.level);
			for (var entry : recipeCollection.getRecipes()) {
				accepted.addAll(RecipeIngredientSummary.fromDisplay(entry.display(), context).acceptedItemIds());
			}
		}
		this.scanAcceptedItemIds = Set.copyOf(accepted);
		this.initialDeficit = RecipeDeficitReport.from(
			this.ingredientSummary,
			this.localItems.inventoryCounts(),
			originalContext.gridStacks(),
			this.craftAll
		);
		this.remainingItemIds = new ArrayList<>(initialDeficit.missingItemIds());
		this.reachableView = NearbyContainerCache.getReachableView(level, cameraEntity, player.blockInteractionRange());
		this.candidates = this.allowNearby ? findCandidates(level, cameraEntity, player.blockInteractionRange()) : List.of();
		this.useCachedSearch = this.allowNearby
			&& ReachCraftingConfig.get().cacheContainersForFasterSearch()
			&& !this.reachableView.isEmpty();
		this.targetCopiesPerSlot = this.craftAll ? 0 : this.requestedSingleClicks;
		this.reopenAttemptsRemaining = MAX_REOPEN_ATTEMPTS;
		this.restoreTicksRemaining = RESTORE_TIMEOUT_TICKS;
		this.seedWaitTicksRemaining = REOPEN_TIMEOUT_TICKS;
		this.redistributeThisRun = false;
		this.blockedCommittedLayoutMissingSummary = null;
		this.phase = SearchPhase.DISCOVERY;
	}

	boolean canStart() {
		return !player.isSpectator() && !player.isHandsBusy() && player.containerMenu.getCarried().isEmpty();
	}

	@Override
	public void start() {
		ReachCraftingMod.LOGGER.debug(
			"[nearby_scan] idx={} start missing={} candidates={} craft_all={} phase={} planned={}",
			recipeIndex,
			initialDeficit.compactMissingSummary(),
			candidates.size(),
			craftAll,
			phase.name().toLowerCase(),
			summarizeRemainingItems(remainingItemIds)
		);

		PulledResourcesTracker.clearWithdrawals(); // Safety: clear stale withdrawals, but keep the baseline inventory snapshot for return planning

		if (useCachedSearch) {
			refreshReachableView();
			discoveredNearby.clear();
			discoveredNearby.putAll(reachableView.countsFor(scanAcceptedItemIds));
			ReachCraftingMod.LOGGER.info(
				"[nearby_cache] idx={} seeded cached_items={} snapshots={} reachable_containers={} requested_recipe={} requested_output={}",
				recipeIndex,
				AvailableItemSnapshot.formatCounts(discoveredNearby),
				reachableView.snapshotsByKey().size(),
				reachableView.nearestAccessByKey().size(),
				initialRequestedRecipeId,
				initialRequestedOutputLabel
			);
			ReachCraftingMod.LOGGER.debug(
				"[nearby_cache] idx={} seeded_from_cache={}",
				recipeIndex,
				AvailableItemSnapshot.formatCounts(discoveredNearby)
			);
			beginWithdrawPhaseOrResume();
			return;
		}

		if (allowNearby) {
			sendDebugChat("Scanning nearby containers...");
		}

		phase = SearchPhase.DISCOVERY;
		if (BulkAutoCraftController.isActive()) {
			BulkAutoCraftController.noteDiscoveryPerformed();
		}
		activeCandidates = candidates;
		nextCandidateIndex = 0;
		pendingContainerPos = null;
		state = SearchState.OPEN_NEXT;
	}

	boolean expandReservedGridInPlace() {
	if (!originalContext.hasReservedGrid() || !originalContext.reservedGridMatches(ingredientSummary)) {
		return false;
	}

		Map<String, Integer> availableCounts = localItems.inventoryCounts();
		int maxReachableCopies = IngredientPlanning.computeMaxCraftCopies(
			ingredientSummary,
			localItems.inventoryCounts(),
			originalContext.gridStacks(),
			availableCounts,
			availableCounts,
			planningPolicy
		);
		targetCopiesPerSlot = craftAll
			? maxReachableCopies
			: Math.min(currentReservedCraftCopies() + requestedSingleClicks, maxReachableCopies);
		if (targetCopiesPerSlot <= 0) {
			return false;
		}
		if (!hasInventoryForReservedGridExpansion()) {
			return false;
		}

		lastRestoreFailure = "<none>";
		boolean expanded = expandReservedGrid();
		if (!expanded) {
			ReachCraftingMod.LOGGER.warn(
				"[nearby_restore] idx={} in_place_expand_failed reason={}",
				recipeIndex,
				lastRestoreFailure
			);
			return false;
		}

		sendDebugChat("Updated grid: " + outputLabel);
		return true;
	}

	private boolean hasInventoryForReservedGridExpansion() {
		Map<String, Integer> neededCounts = new LinkedHashMap<>();
		Map<String, Integer> inventoryCounts = currentInventoryCounts();
		for (ItemStack stack : originalContext.gridStacks()) {
			if (stack.isEmpty()) {
				continue;
			}

			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			int currentCount = stack.getCount();
			int desiredCount = Math.min(stack.getMaxStackSize(), Math.max(targetCopiesPerSlot, currentCount));
			int missing = Math.max(desiredCount - currentCount, 0);
			if (missing > 0) {
				neededCounts.merge(itemId, missing, Integer::sum);
			}
		}

		for (Map.Entry<String, Integer> entry : neededCounts.entrySet()) {
			if (inventoryCounts.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public void tick() {
		if (client.player != player || client.level != level) {
			finishSession(false);
			return;
		}

		if (state == SearchState.OPEN_NEXT) {
			if (phase == SearchPhase.WITHDRAW && remainingItemIds.isEmpty()) {
				beginResume();
				return;
			}
			if (!openNextContainer()) {
				if (phase == SearchPhase.DISCOVERY) {
					beginWithdrawPhaseOrResume();
				} else if (shouldStartFallbackDiscovery()) {
					beginFallbackDiscovery();
				} else {
					beginResume();
				}
			}
			return;
		}

		if (state == SearchState.WAITING_FOR_CONTAINER) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				onOpenFailed("timeout");
			}
			return;
		}

		if (state == SearchState.RESUME_CONTEXT) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				resumeOriginalContext();
				timeoutTicks = REOPEN_TIMEOUT_TICKS;
				state = SearchState.WAITING_FOR_REOPEN;
			}
			return;
		}

		if (state == SearchState.WAITING_FOR_REOPEN) {
			if (isOriginalContextReady()) {
				if (tryFinishAfterResume()) {
					return;
				}
				return;
			}

			timeoutTicks--;
			if (timeoutTicks <= 0) {
				if (originalContext.kind() != ScreenKind.NONE && reopenAttemptsRemaining > 0) {
					reopenAttemptsRemaining--;
					ReachCraftingMod.LOGGER.debug(
						"[nearby_restore] idx={} reopen_retry kind={} attempts_left={}",
						recipeIndex,
						originalContext.kind().name().toLowerCase(),
						reopenAttemptsRemaining
					);
					resumeOriginalContext();
					timeoutTicks = REOPEN_TIMEOUT_TICKS;
					return;
				}

				ReachCraftingMod.LOGGER.warn(
					"[nearby_restore] idx={} reopen_failed kind={} screen={} attempts_exhausted",
					recipeIndex,
					originalContext.kind().name().toLowerCase(),
					client.screen == null ? "<none>" : client.screen.getClass().getSimpleName()
				);
				tryFinishAfterResume();
			}
		}
	}

	@Override
	public void onContainerContentsInitialized(AbstractContainerMenu menu) {
		if (state != SearchState.WAITING_FOR_CONTAINER) {
			return;
		}
		if (client.screen instanceof InventoryScreen || client.screen instanceof CraftingScreen) {
			return;
		}
		if (menu.containerId == player.inventoryMenu.containerId) {
			return;
		}

		Map<String, Integer> allItems = ContainerUtils.collectAllItems(menu);
		NearbyContainerCache.recordObservedContents(level, pendingContainerPos, allItems);
		Map<String, Integer> usefulItems = collectUsefulItems(menu, scanAcceptedItemIds);
		boolean shouldMergeDiscovery = phase == SearchPhase.DISCOVERY || !useCachedSearch;
		if (!usefulItems.isEmpty() && shouldMergeDiscovery) {
			usefulItems.forEach((itemId, count) -> discoveredNearby.merge(itemId, count, Integer::sum));
			ReachCraftingMod.LOGGER.info(
				"[nearby_discovery] idx={} pos={} merged_items={} merged_totals={} selected_recipe={} selected_output={}",
				recipeIndex,
				ContainerUtils.formatPos(pendingContainerPos),
				AvailableItemSnapshot.formatCounts(usefulItems),
				AvailableItemSnapshot.formatCounts(discoveredNearby),
				recipeId,
				outputLabel
			);
		}
		if (!usefulItems.isEmpty()) {
			ReachCraftingMod.LOGGER.debug(
				"[nearby_container] idx={} pos={} found={}",
				recipeIndex,
				ContainerUtils.formatPos(pendingContainerPos),
				AvailableItemSnapshot.formatCounts(usefulItems)
			);
		}

		scannedContainers++;
		if (phase == SearchPhase.WITHDRAW) {
			withdrawItemsFromContainer(menu);
		}
		player.closeContainer();
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = SearchState.OPEN_NEXT;
	}

	@Override
	public void onOpenFailed(String reason) {
		ReachCraftingMod.LOGGER.debug(
			"[nearby_container] idx={} pos={} skipped={}",
			recipeIndex,
			ContainerUtils.formatPos(pendingContainerPos),
			reason
		);
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = SearchState.OPEN_NEXT;
	}

	private boolean openNextContainer() {
		if (activeCandidates.isEmpty()) {
			activeCandidates = phase == SearchPhase.DISCOVERY ? candidates : buildWithdrawCandidates();
		}

		Vec3 eyePos = cameraEntity.getEyePosition(0);
		while (nextCandidateIndex < activeCandidates.size()) {
			BlockPos pos = activeCandidates.get(nextCandidateIndex++);
			if (visited.contains(pos)) {
				continue;
			}

			BlockState blockState = level.getBlockState(pos);
			if (!InWorldFilterManager.isContainerActive(level, pos, blockState)) {
				continue;
			}
			if (!ContainerUtils.canAttemptOpen(level, pos, blockState)) {
				continue;
			}
			if (ContainerUtils.squaredDistanceToBlock(eyePos, pos) > Mth.square(player.blockInteractionRange())) {
				continue;
			}

			markVisited(pos);
			Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, pos);
			Direction face = Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
			BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);
			boolean wasSneaking = player.isShiftKeyDown() || (player.input != null && player.input.keyPresses != null && player.input.keyPresses.shift());
			withSuppressedSecondaryUse(() -> {
				if (wasSneaking) {
					sendShiftOverride(client, player, false);
					player.setShiftKeyDown(false);
				}
				gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
				if (wasSneaking) {
					player.setShiftKeyDown(true);
					sendShiftOverride(client, player, true);
				}
			});
			pendingContainerPos = pos;
			timeoutTicks = OPEN_TIMEOUT_TICKS;
			state = SearchState.WAITING_FOR_CONTAINER;
			return true;
		}

		return false;
	}

	private void withdrawItemsFromContainer(AbstractContainerMenu menu) {
		if (remainingItemIds.isEmpty()) {
			return;
		}

		WithdrawalPlan plan = buildWithdrawalPlan(menu);
		Map<String, Integer> executedWithdrawals = executeWithdrawalPlan(menu, plan);
		applyWithdrawalResults(executedWithdrawals, plan.inventorySpaceBlocked());
	}

	private void markVisited(BlockPos pos) {
		visited.add(pos);
		ContainerUtils.getOtherHalfOfLargeChest(level, pos).ifPresent(visited::add);
	}

	private void beginResume() {
		if (!allowNearby && isOriginalContextReady()) {
			state = SearchState.WAITING_FOR_REOPEN;
			timeoutTicks = 0;
			return;
		}
		player.closeContainer();
		timeoutTicks = RESUME_DELAY_TICKS;
		restoreTicksRemaining = RESTORE_TIMEOUT_TICKS;
		reopenAttemptsRemaining = MAX_REOPEN_ATTEMPTS;
		state = SearchState.RESUME_CONTEXT;
	}

	private void beginWithdrawPhaseOrResume() {
		refreshReachableView();
		if (useCachedSearch) {
			discoveredNearby.clear();
			discoveredNearby.putAll(reachableView.countsFor(scanAcceptedItemIds));
		}
		applySearchPlanDecision(buildSearchPlanDecision());
	}

	private SearchPlanDecision buildSearchPlanDecision() {
		Map<String, Integer> inventoryCounts = currentInventoryCounts();
		Map<String, Integer> inventoryAndNearby = AvailableItemSnapshot.mergeCounts(inventoryCounts, discoveredNearby);
		boolean reservedGridMatchesCollection = originalContext.hasReservedGrid() && originalContext.reservedGridMatchesCollection(client.level, recipeCollection);
		boolean committedReservedGrid = originalContext.hasReservedGrid()
			&& (originalContext.reservedGridMatches(ingredientSummary) || reservedGridMatchesCollection);
		// Initial check to see if we can continue with the current layout at the requested count
		Map<String, Integer> initialMissing = committedReservedGrid ? subtractAvailableCounts(computeReservedGridNeededCounts(), inventoryAndNearby) : Map.of();
		boolean committedLayoutCanContinue = committedReservedGrid && initialMissing.isEmpty();
		boolean rotatingFamilyRedistribute = planningPolicy.redistributeToCraftWhenNeeded()
			&& reservedGridMatchesCollection
			&& recipeCollection != null
			&& recipeCollection.getRecipes().size() > 1;
		boolean allowReservedGridVariantSwitch = rotatingFamilyRedistribute
			|| (planningPolicy.redistributeToCraftWhenNeeded()
				&& reservedGridMatchesCollection
				&& (craftAll || !committedLayoutCanContinue));
		boolean redistributeReservedGrid = planningPolicy.redistributeToCraftWhenNeeded() && (craftAll || requestedSingleClicks > 1 || rotatingFamilyRedistribute);
		Map<String, Integer> gridCounts = countStacks(originalContext.gridStacks());
		Map<String, Integer> totalAvailable = redistributeReservedGrid
			? AvailableItemSnapshot.mergeCounts(inventoryAndNearby, gridCounts)
			: inventoryAndNearby;
		Map<String, Integer> planningInventoryCounts = redistributeReservedGrid 
			? AvailableItemSnapshot.mergeCounts(inventoryCounts, gridCounts) 
			: inventoryCounts;
		List<ItemStack> planningGridStacks = redistributeReservedGrid ? List.of() : originalContext.gridStacks();
		AvailableItemSnapshot resolverItems = redistributeReservedGrid
			? new AvailableItemSnapshot(Map.copyOf(totalAvailable), Map.of(), Map.copyOf(totalAvailable), List.of())
			: new AvailableItemSnapshot(
				Map.copyOf(inventoryCounts),
				localItems.gridCounts(),
				AvailableItemSnapshot.mergeCounts(inventoryCounts, localItems.gridCounts()),
				localItems.gridStacks()
			);
		int desiredVariantCopies = craftAll
			? 1
			: allowReservedGridVariantSwitch
				? currentReservedCraftCopies() + requestedSingleClicks
				: requestedSingleClicks;
		RecipeVariantResolver.Selection resolvedSelection = null;
		if (!redistributeReservedGrid && committedReservedGrid) {
			// Lock to current grid variant
			resolvedSelection = RecipeVariantResolver.resolveMatchForGrid(
				client,
				player,
				recipeCollection,
				originalContext.gridStacks(),
				resolverItems,
				totalAvailable,
				totalAvailable,
				craftAll,
				desiredVariantCopies
			);
			ReachCraftingMod.LOGGER.debug("[recipe_variant] locked_to_grid_variant idx={} mode=locked_expansion match={}", recipeIndex, resolvedSelection != null);
		} else {
			resolvedSelection = RecipeVariantResolver.resolve(
				client,
				player,
				initialRequestedRecipeId,
				recipeCollection,
				ItemStack.EMPTY,
				explicitVariantSelection,
				allowNearby,
				resolverItems,
				totalAvailable,
				totalAvailable,
				craftAll,
				allowReservedGridVariantSwitch,
				desiredVariantCopies
			);
		}

		if (resolvedSelection != null) {
			boolean variantChanged = !resolvedSelection.recipeId().equals(recipeId);
			if (variantChanged || (!redistributeReservedGrid && committedReservedGrid)) {
				ReachCraftingMod.LOGGER.debug(
					"[recipe_variant] clicked_idx={} selected_idx={} mode={} output={}",
					recipeIndex,
					resolvedSelection.recipeId().index(),
					redistributeReservedGrid ? ReachCraftingConfig.get().revolvingCraftHandling().name().toLowerCase() : "locked_expansion",
					resolvedSelection.outputLabel()
				);
				recipeId = resolvedSelection.recipeId();
				outputLabel = resolvedSelection.outputLabel();
				ingredientSummary = resolvedSelection.ingredientSummary();
			}
			if (BulkAutoCraftController.isActive() && requestedSingleClicks > 1) {
				BulkAutoCraftController.startOrUpdate(
					new RecipeBookClickCapture.HeldRecipeAction(
						recipeId,
						recipeCollection,
						ItemStack.EMPTY,
						org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
						explicitVariantSelection
					),
					requestedSingleClicks,
					allowNearby,
					resolveBulkVariantContinuationMode(resolvedSelection),
					resolvedSelection.displayStack(),
					ingredientSummary
				);
			}
		}
		boolean hasUnscanned = reachableView.snapshotsByKey().size() < reachableView.nearestAccessByKey().size();
		ReachCraftingMod.LOGGER.info(
			"[recipe_variant] idx={} phase={} requested_recipe={} current_recipe={} resolved_recipe={} requested_output={} resolved_output={} total_available={} remaining={} unscanned_containers={} snapshots={} reachable_containers={}",
			recipeIndex,
			phase.name().toLowerCase(),
			initialRequestedRecipeId,
			recipeId,
			resolvedSelection == null ? "<null>" : resolvedSelection.recipeId(),
			initialRequestedOutputLabel,
			resolvedSelection == null ? outputLabel : resolvedSelection.outputLabel(),
			AvailableItemSnapshot.formatCounts(inventoryAndNearby),
			summarizeRemainingItems(remainingItemIds),
			hasUnscanned,
			reachableView.snapshotsByKey().size(),
			reachableView.nearestAccessByKey().size()
		);
		IngredientPlanning.Policy effectivePlanningPolicy = planningPolicyForSelection(resolvedSelection);
		Map<String, Integer> effectivePreferenceTotals = preferenceTotalsForSelection(totalAvailable, resolvedSelection);
		int desiredTargetCopies = committedReservedGrid
			? currentReservedCraftCopies() + requestedSingleClicks
			: requestedSingleClicks;
		int redistributedMaxCopies = redistributeReservedGrid
			? IngredientPlanning.computeMaxCraftCopies(
				ingredientSummary,
				planningInventoryCounts,
				planningGridStacks,
				totalAvailable,
				effectivePreferenceTotals,
				effectivePlanningPolicy
			)
			: 0;
		int maxReachableCopies = IngredientPlanning.computeMaxCraftCopies(
			ingredientSummary,
			planningInventoryCounts,
			planningGridStacks,
			totalAvailable,
			effectivePreferenceTotals,
			effectivePlanningPolicy
		);
		targetCopiesPerSlot = craftAll
			? maxReachableCopies
			: redistributeReservedGrid
				? Math.min(desiredTargetCopies, redistributedMaxCopies)
				: Math.min(desiredTargetCopies, maxReachableCopies);
		IngredientPlanning.PlanResult plannedResult = IngredientPlanning.plan(
			ingredientSummary,
			planningInventoryCounts,
			planningGridStacks,
			totalAvailable,
			effectivePreferenceTotals,
			targetCopiesPerSlot,
			effectivePlanningPolicy
		);
		if (!originalContext.hasReservedGrid()
			&& AutoCraftController.isBulkModeEnabled()
			&& targetCopiesPerSlot > 0) {
			int capacityLimitedCopies = clampTargetCopiesToInventoryCapacity(
				planningInventoryCounts,
				planningGridStacks,
				totalAvailable,
				effectivePreferenceTotals,
				effectivePlanningPolicy,
				targetCopiesPerSlot
			);
			if (capacityLimitedCopies != targetCopiesPerSlot) {
				targetCopiesPerSlot = capacityLimitedCopies;
				plannedResult = IngredientPlanning.plan(
					ingredientSummary,
					planningInventoryCounts,
					planningGridStacks,
					totalAvailable,
					effectivePreferenceTotals,
					targetCopiesPerSlot,
					effectivePlanningPolicy
				);
			}
		}
		plannedTargets = plannedResult.slotTargets();
		remainingItemIds.clear();
		remainingItemIds.addAll(redistributeReservedGrid ? computeRedistributedFetchItemIds(plannedTargets) : computeFetchItemIds(plannedTargets));

		if (!planningPolicy.redistributeToCraftWhenNeeded() && committedReservedGrid) {
			Map<String, Integer> finalMissing = subtractAvailableCounts(computeReservedGridNeededCounts(), inventoryAndNearby);
			if (!finalMissing.isEmpty()) {
				String blockedSummary = AvailableItemSnapshot.formatCounts(finalMissing);
				ReachCraftingMod.LOGGER.debug(
					"[nearby_plan] idx={} committed_layout_blocked missing={}",
					recipeIndex,
					blockedSummary
				);
				return new SearchPlanDecision(
					recipeId,
					outputLabel,
					ingredientSummary,
					redistributeReservedGrid,
					targetCopiesPerSlot,
					plannedTargets,
					List.of(),
					List.of(),
					plannedResult.hasMissingIngredients(),
					true,
					false,
					blockedSummary,
					plannedResult.compactMissingSummary(),
					AvailableItemSnapshot.formatCounts(totalAvailable)
				);
			}
		}

		ReachCraftingMod.LOGGER.debug(
			"[nearby_plan] idx={} total_available={} target_copies={} planned={}",
			recipeIndex,
			AvailableItemSnapshot.formatCounts(totalAvailable),
			targetCopiesPerSlot,
			summarizeRemainingItems(remainingItemIds)
		);

		boolean missingEssential = targetCopiesPerSlot <= 0 || plannedResult.hasMissingIngredients();
		boolean underServed = !craftAll && targetCopiesPerSlot < desiredTargetCopies;
		boolean alreadyScannedInBulk = BulkAutoCraftController.isActive() && BulkAutoCraftController.hasPerformedDiscovery();
		boolean startFallbackDiscovery = (missingEssential || underServed || hasUnscanned) 
			&& useCachedSearch 
			&& !discoveryFallbackStarted
			&& !alreadyScannedInBulk;
		boolean resumeOriginalContext = !startFallbackDiscovery && (remainingItemIds.isEmpty() || missingEssential);
		List<BlockPos> withdrawCandidates = resumeOriginalContext || startFallbackDiscovery ? List.of() : buildWithdrawCandidates();
		if (!resumeOriginalContext
			&& !startFallbackDiscovery
			&& allowNearby
			&& ReachCraftingConfig.get().cacheContainersForFasterSearch()
			&& withdrawCandidates.isEmpty()) {
			startFallbackDiscovery = shouldStartFallbackDiscovery();
			resumeOriginalContext = !startFallbackDiscovery;
		}

		return new SearchPlanDecision(
			recipeId,
			outputLabel,
			ingredientSummary,
			redistributeReservedGrid,
			targetCopiesPerSlot,
			plannedTargets,
			List.copyOf(remainingItemIds),
			withdrawCandidates,
			plannedResult.hasMissingIngredients(),
			resumeOriginalContext,
			startFallbackDiscovery,
			null,
			plannedResult.compactMissingSummary(),
			AvailableItemSnapshot.formatCounts(totalAvailable)
		);
	}

	private void applySearchPlanDecision(SearchPlanDecision decision) {
		ReachCraftingMod.LOGGER.info(
			"[nearby_plan] idx={} phase={} requested_recipe={} chosen_recipe={} chosen_output={} target_copies={} fetch={} withdraw_candidates={} resume={} fallback={} total_available={}",
			recipeIndex,
			phase.name().toLowerCase(),
			initialRequestedRecipeId,
			decision.resolvedRecipeId(),
			decision.resolvedOutputLabel(),
			decision.targetCopiesPerSlot(),
			summarizeRemainingItems(decision.fetchItemIds()),
			decision.withdrawCandidates().size(),
			decision.resumeOriginalContext(),
			decision.startFallbackDiscovery(),
			decision.totalAvailableSummary()
		);
		recipeId = decision.resolvedRecipeId();
		outputLabel = decision.resolvedOutputLabel();
		ingredientSummary = decision.resolvedIngredientSummary();
		redistributeThisRun = decision.redistributeReservedGrid();
		targetCopiesPerSlot = decision.targetCopiesPerSlot();
		plannedTargets = decision.plannedTargets();
		remainingItemIds.clear();
		remainingItemIds.addAll(decision.fetchItemIds());
		blockedCommittedLayoutMissingSummary = decision.blockedCommittedLayoutMissingSummary();

		if (decision.resumeOriginalContext()) {
			if (decision.targetCopiesPerSlot() <= 0 && decision.hasMissingIngredients()) {
				String missing = decision.blockedCommittedLayoutMissingSummary() != null 
					? decision.blockedCommittedLayoutMissingSummary() 
					: decision.compactMissingSummary();
				sendMissingIngredientsChat("Missing: " + missing);
			}
			beginResume();
			return;
		}
		if (decision.startFallbackDiscovery()) {
			beginFallbackDiscovery();
			return;
		}

		visited.clear();
		nextCandidateIndex = 0;
		pendingContainerPos = null;
		phase = SearchPhase.WITHDRAW;
		activeCandidates = decision.withdrawCandidates();
		state = SearchState.OPEN_NEXT;
		sendDebugChat("Fetching: " + summarizeRemainingItems(remainingItemIds));
	}

	private List<String> computeFetchItemIds(List<IngredientPlanning.SlotTarget> slotTargets) {
		if (originalContext.hasReservedGrid() && originalContext.reservedGridMatches(ingredientSummary)) {
			return computeReservedGridFetchItemIds();
		}

		Map<String, Integer> desiredCounts = new LinkedHashMap<>();
		Map<String, Integer> alreadyCovered = new LinkedHashMap<>(currentInventoryCounts());

		for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
			if (slotTarget.itemId() == null || slotTarget.targetCount() <= 0) {
				continue;
			}
			desiredCounts.merge(slotTarget.itemId(), slotTarget.targetCount(), Integer::sum);
		}

		List<ItemStack> reservedGrid = originalContext.gridStacks();
		for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
			int slotIndex = slotTarget.slotIndex() - 1;
			if (slotIndex < 0 || slotIndex >= reservedGrid.size()) {
				continue;
			}
			ItemStack stack = reservedGrid.get(slotIndex);
			if (stack.isEmpty() || slotTarget.itemId() == null) {
				continue;
			}
			String stackItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if (slotTarget.itemId().equals(stackItemId)) {
				alreadyCovered.merge(stackItemId, stack.getCount(), Integer::sum);
			}
		}

		appendBulkFutureStagingCounts(slotTargets, desiredCounts, alreadyCovered);
		return buildMissingFetchItems(desiredCounts, alreadyCovered);
	}


	private List<String> computeReservedGridFetchItemIds() {
		Map<String, Integer> neededCounts = computeReservedGridNeededCounts();

		Map<String, Integer> availableInventory = new LinkedHashMap<>(currentInventoryCounts());
		appendBulkFutureStagingCounts(plannedTargets, neededCounts, availableInventory);
		return buildMissingFetchItems(neededCounts, availableInventory);
	}

	private List<String> computeRedistributedFetchItemIds(List<IngredientPlanning.SlotTarget> slotTargets) {
		Map<String, Integer> desiredCounts = new LinkedHashMap<>();
		for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
			if (slotTarget.itemId() == null || slotTarget.targetCount() <= 0) {
				continue;
			}
			desiredCounts.merge(slotTarget.itemId(), slotTarget.targetCount(), Integer::sum);
		}

		Map<String, Integer> movableCounts = AvailableItemSnapshot.mergeCounts(currentInventoryCounts(), countStacks(originalContext.gridStacks()));
		appendBulkFutureStagingCounts(slotTargets, desiredCounts, movableCounts);
		return buildMissingFetchItems(desiredCounts, movableCounts);
	}

	private void appendBulkFutureStagingCounts(
		List<IngredientPlanning.SlotTarget> slotTargets,
		Map<String, Integer> desiredCounts,
		Map<String, Integer> alreadyCovered
	) {
		if (!AutoCraftController.isBulkModeEnabled() || !allowNearby || craftAll) {
			return;
		}

		int remainingCopies = BulkAutoCraftController.remainingRequestedRecipeCopies();
		if (remainingCopies <= 0) {
			return;
		}

		int extraFutureCopies = Math.max(0, remainingCopies - Math.max(targetCopiesPerSlot, 1));
		if (extraFutureCopies <= 0) {
			return;
		}

		Map<String, Integer> perCraftCounts = perCraftIngredientCounts(slotTargets);
		if (perCraftCounts.isEmpty()) {
			return;
		}

		int reachableFutureCopies = computeReachableFutureCopies(desiredCounts, perCraftCounts);
		if (reachableFutureCopies <= 0) {
			return;
		}

		int stageableFutureCopies = computeStageableFutureCopies(
			desiredCounts,
			alreadyCovered,
			perCraftCounts,
			Math.min(extraFutureCopies, reachableFutureCopies)
		);
		if (stageableFutureCopies <= 0) {
			return;
		}

		for (Map.Entry<String, Integer> entry : perCraftCounts.entrySet()) {
			desiredCounts.merge(entry.getKey(), entry.getValue() * stageableFutureCopies, Integer::sum);
		}
	}

	private Map<String, Integer> perCraftIngredientCounts(List<IngredientPlanning.SlotTarget> slotTargets) {
		Map<String, Integer> perCraftCounts = new LinkedHashMap<>();
		for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
			if (slotTarget.itemId() == null || slotTarget.targetCount() <= 0) {
				continue;
			}
			perCraftCounts.merge(slotTarget.itemId(), 1, Integer::sum);
		}
		return perCraftCounts;
	}

	private int computeStageableFutureCopies(
		Map<String, Integer> currentDesiredCounts,
		Map<String, Integer> alreadyCovered,
		Map<String, Integer> perCraftCounts,
		int maxExtraFutureCopies
	) {
		List<ItemStack> currentPlanInventory = snapshotPlayerInventorySlots();
		Map<String, ItemStack> prototypes = buildItemPrototypes(currentDesiredCounts, perCraftCounts);
		for (Map.Entry<String, Integer> entry : currentDesiredCounts.entrySet()) {
			int toStageNow = Math.max(0, entry.getValue() - alreadyCovered.getOrDefault(entry.getKey(), 0));
			if (toStageNow <= 0) {
				continue;
			}
			if (!placeIntoVirtualInventory(currentPlanInventory, prototypeForItem(entry.getKey(), prototypes), toStageNow)) {
				return 0;
			}
		}

		Map<String, Integer> futureLocalReserve = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : alreadyCovered.entrySet()) {
			int remaining = entry.getValue() - currentDesiredCounts.getOrDefault(entry.getKey(), 0);
			if (remaining > 0) {
				futureLocalReserve.put(entry.getKey(), remaining);
			}
		}

		int stageableCopies = 0;
		List<ItemStack> simulatedInventory = copyStacks(currentPlanInventory);
		Map<String, Integer> simulatedReserve = new LinkedHashMap<>(futureLocalReserve);
		for (int copyIndex = 0; copyIndex < maxExtraFutureCopies; copyIndex++) {
			List<ItemStack> nextInventory = copyStacks(simulatedInventory);
			Map<String, Integer> nextReserve = new LinkedHashMap<>(simulatedReserve);
			boolean fits = true;
			for (Map.Entry<String, Integer> entry : perCraftCounts.entrySet()) {
				String itemId = entry.getKey();
				int needed = entry.getValue();
				int reserved = nextReserve.getOrDefault(itemId, 0);
				int consumedFromReserve = Math.min(reserved, needed);
				if (consumedFromReserve > 0) {
					int remainingReserve = reserved - consumedFromReserve;
					if (remainingReserve > 0) {
						nextReserve.put(itemId, remainingReserve);
					} else {
						nextReserve.remove(itemId);
					}
				}

				int toStage = needed - consumedFromReserve;
				if (toStage > 0 && !placeIntoVirtualInventory(nextInventory, prototypeForItem(itemId, prototypes), toStage)) {
					fits = false;
					break;
				}
			}

			if (!fits) {
				break;
			}

			simulatedInventory = nextInventory;
			simulatedReserve = nextReserve;
			stageableCopies++;
		}

		return stageableCopies;
	}

	private int computeReachableFutureCopies(Map<String, Integer> currentDesiredCounts, Map<String, Integer> perCraftCounts) {
		Map<String, Integer> totalAvailable = AvailableItemSnapshot.mergeCounts(currentInventoryCounts(), discoveredNearby);
		int maxFutureCopies = Integer.MAX_VALUE;
		for (Map.Entry<String, Integer> entry : perCraftCounts.entrySet()) {
			int perCraft = entry.getValue();
			if (perCraft <= 0) {
				continue;
			}

			int available = totalAvailable.getOrDefault(entry.getKey(), 0) - currentDesiredCounts.getOrDefault(entry.getKey(), 0);
			int reachableCopies = Math.max(0, available / perCraft);
			maxFutureCopies = Math.min(maxFutureCopies, reachableCopies);
		}
		return maxFutureCopies == Integer.MAX_VALUE ? 0 : maxFutureCopies;
	}

	private int clampTargetCopiesToInventoryCapacity(
		Map<String, Integer> planningInventoryCounts,
		List<ItemStack> planningGridStacks,
		Map<String, Integer> totalAvailable,
		Map<String, Integer> effectivePreferenceTotals,
		IngredientPlanning.Policy effectivePlanningPolicy,
		int requestedCopies
	) {
		int low = 0;
		int high = requestedCopies;
		while (low < high) {
			int mid = (low + high + 1) / 2;
			IngredientPlanning.PlanResult midPlan = IngredientPlanning.plan(
				ingredientSummary,
				planningInventoryCounts,
				planningGridStacks,
				totalAvailable,
				effectivePreferenceTotals,
				mid,
				effectivePlanningPolicy
			);
			if (midPlan.hasMissingIngredients() || !canStageCurrentPlan(midPlan.slotTargets(), planningInventoryCounts)) {
				high = mid - 1;
			} else {
				low = mid;
			}
		}
		return low;
	}

	private boolean canStageCurrentPlan(List<IngredientPlanning.SlotTarget> slotTargets, Map<String, Integer> alreadyCovered) {
		Map<String, Integer> desiredCounts = new LinkedHashMap<>();
		for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
			if (slotTarget.itemId() == null || slotTarget.targetCount() <= 0) {
				continue;
			}
			desiredCounts.merge(slotTarget.itemId(), slotTarget.targetCount(), Integer::sum);
		}

		List<ItemStack> simulatedInventory = snapshotPlayerInventorySlots();
		Map<String, ItemStack> prototypes = buildItemPrototypes(desiredCounts, Map.of());
		for (Map.Entry<String, Integer> entry : desiredCounts.entrySet()) {
			int toStageNow = Math.max(0, entry.getValue() - alreadyCovered.getOrDefault(entry.getKey(), 0));
			if (toStageNow <= 0) {
				continue;
			}
			if (!placeIntoVirtualInventory(simulatedInventory, prototypeForItem(entry.getKey(), prototypes), toStageNow)) {
				return false;
			}
		}
		return true;
	}

	private List<ItemStack> snapshotPlayerInventorySlots() {
		List<ItemStack> slots = new ArrayList<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			slots.add(stack.copy());
		}
		return slots;
	}

	private Map<String, ItemStack> buildItemPrototypes(Map<String, Integer> desiredCounts, Map<String, Integer> perCraftCounts) {
		Map<String, ItemStack> prototypes = new LinkedHashMap<>();
		for (String itemId : desiredCounts.keySet()) {
			prototypes.put(itemId, itemPrototype(itemId));
		}
		for (String itemId : perCraftCounts.keySet()) {
			prototypes.putIfAbsent(itemId, itemPrototype(itemId));
		}
		return prototypes;
	}

	private ItemStack prototypeForItem(String itemId, Map<String, ItemStack> prototypes) {
		return prototypes.getOrDefault(itemId, itemPrototype(itemId));
	}

	private ItemStack itemPrototype(String itemId) {
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

	private List<ItemStack> copyStacks(List<ItemStack> stacks) {
		List<ItemStack> copies = new ArrayList<>(stacks.size());
		for (ItemStack stack : stacks) {
			copies.add(stack.copy());
		}
		return copies;
	}

	private boolean placeIntoVirtualInventory(List<ItemStack> inventorySlots, ItemStack prototype, int count) {
		if (count <= 0) {
			return true;
		}
		if (prototype == null || prototype.isEmpty()) {
			return false;
		}

		int remaining = count;
		int maxStackSize = Math.max(prototype.getMaxStackSize(), 1);
		for (ItemStack slot : inventorySlots) {
			if (remaining <= 0) {
				break;
			}
			if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, prototype)) {
				continue;
			}

			int room = maxStackSize - slot.getCount();
			if (room <= 0) {
				continue;
			}
			int placed = Math.min(room, remaining);
			slot.grow(placed);
			remaining -= placed;
		}

		for (int i = 0; i < inventorySlots.size() && remaining > 0; i++) {
			if (!inventorySlots.get(i).isEmpty()) {
				continue;
			}

			int placed = Math.min(maxStackSize, remaining);
			ItemStack newStack = prototype.copy();
			newStack.setCount(placed);
			inventorySlots.set(i, newStack);
			remaining -= placed;
		}

		return remaining <= 0;
	}

	private List<String> buildMissingFetchItems(Map<String, Integer> desiredCounts, Map<String, Integer> availableCounts) {
		List<String> fetchItems = new ArrayList<>();
		Map<String, Integer> mutableAvailable = new LinkedHashMap<>(availableCounts);
		for (Map.Entry<String, Integer> entry : desiredCounts.entrySet()) {
			String itemId = entry.getKey();
			int stillNeeded = entry.getValue();
			int available = mutableAvailable.getOrDefault(itemId, 0);
			int covered = Math.min(available, stillNeeded);
			stillNeeded -= covered;
			if (covered > 0) {
				if (available == covered) {
					mutableAvailable.remove(itemId);
				} else {
					mutableAvailable.put(itemId, available - covered);
				}
			}

			for (int i = 0; i < stillNeeded; i++) {
				fetchItems.add(itemId);
			}
		}
		return fetchItems;
	}

	private Map<String, Integer> computeReservedGridNeededCounts() {
		Map<String, Integer> neededCounts = new LinkedHashMap<>();
		for (ItemStack stack : originalContext.gridStacks()) {
			if (stack.isEmpty()) {
				continue;
			}

			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			int currentCount = stack.getCount();
			int desiredCount = Math.min(stack.getMaxStackSize(), Math.max(targetCopiesPerSlot, currentCount));
			int missing = Math.max(desiredCount - currentCount, 0);
			if (missing > 0) {
				neededCounts.merge(itemId, missing, Integer::sum);
			}
		}
		return neededCounts;
	}

	private static Map<String, Integer> subtractAvailableCounts(Map<String, Integer> neededCounts, Map<String, Integer> availableCounts) {
		Map<String, Integer> remaining = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : neededCounts.entrySet()) {
			int missing = entry.getValue() - availableCounts.getOrDefault(entry.getKey(), 0);
			if (missing > 0) {
				remaining.put(entry.getKey(), missing);
			}
		}
		return remaining;
	}

	private Map<String, Integer> currentInventoryCounts() {
		return AvailableItemSnapshot.mergeCounts(localItems.inventoryCounts(), withdrawnItems);
	}

	private void refreshReachableView() {
		reachableView = NearbyContainerCache.getReachableView(level, cameraEntity, player.blockInteractionRange());
	}

	private boolean shouldStartFallbackDiscovery() {
		int desiredTargetCopies = originalContext.hasReservedGrid()
			? currentReservedCraftCopies() + requestedSingleClicks
			: requestedSingleClicks;
		boolean underServed = !craftAll && targetCopiesPerSlot < desiredTargetCopies;
		boolean hasUnscanned = reachableView.snapshotsByKey().size() < reachableView.nearestAccessByKey().size();
		boolean alreadyScannedInBulk = BulkAutoCraftController.isActive() && BulkAutoCraftController.hasPerformedDiscovery();

		return useCachedSearch
			&& allowNearby
			&& (phase == SearchPhase.WITHDRAW || phase == SearchPhase.DISCOVERY)
			&& (!remainingItemIds.isEmpty() || targetCopiesPerSlot <= 0 || underServed || hasUnscanned)
			&& !discoveryFallbackStarted
			&& !alreadyScannedInBulk;
	}

	private void beginFallbackDiscovery() {
		discoveryFallbackStarted = true;
		if (BulkAutoCraftController.isActive()) {
			BulkAutoCraftController.noteDiscoveryPerformed();
		}
		refreshReachableView();
		ReachCraftingMod.LOGGER.info(
			"[nearby_cache] idx={} fallback_discovery requested_recipe={} current_recipe={} current_output={} discovered={} remaining={} snapshots={} reachable_containers={}",
			recipeIndex,
			initialRequestedRecipeId,
			recipeId,
			outputLabel,
			AvailableItemSnapshot.formatCounts(discoveredNearby),
			summarizeRemainingItems(remainingItemIds),
			reachableView.snapshotsByKey().size(),
			reachableView.nearestAccessByKey().size()
		);
		phase = SearchPhase.DISCOVERY;
		activeCandidates = remainingDiscoveryCandidates(candidates);
		nextCandidateIndex = 0;
		pendingContainerPos = null;
		state = SearchState.OPEN_NEXT;
		ReachCraftingMod.LOGGER.debug(
			"[nearby_cache] idx={} fallback_discovery remaining={}",
			recipeIndex,
			summarizeRemainingItems(remainingItemIds)
		);
		sendDebugChat("Cache missed some items, scanning the rest...");
	}

	private List<BlockPos> buildWithdrawCandidates() {
		if (!allowNearby) {
			return List.of();
		}
		if (!ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			return candidates;
		}

		refreshReachableView();
		Map<String, Integer> neededCounts = countNeededItems(remainingItemIds);
		if (neededCounts.isEmpty()) {
			return List.of();
		}

		List<BlockPos> prioritized = new ArrayList<>();
		for (BlockPos pos : candidates) {
			if (cachedMatchUnitsAt(pos, neededCounts) > 0) {
				prioritized.add(pos);
			}
		}

		Map<BlockPos, Integer> originalOrder = new HashMap<>();
		for (int i = 0; i < prioritized.size(); i++) {
			originalOrder.put(prioritized.get(i), i);
		}

		Comparator<BlockPos> comparator = Comparator
			.comparingInt((BlockPos pos) -> cachedDistinctMatchesAt(pos, neededCounts)).reversed()
			.thenComparingInt((BlockPos pos) -> cachedMatchUnitsAt(pos, neededCounts)).reversed();
		if (ReachCraftingConfig.get().countPreference() == IngredientPlanning.CountPreference.HIGHEST_TOTAL) {
			comparator = comparator.thenComparing(Comparator.comparingInt((BlockPos pos) -> cachedPreferenceTotalAt(pos, neededCounts)).reversed());
		} else {
			comparator = comparator.thenComparingInt(pos -> cachedPreferenceTotalAt(pos, neededCounts));
		}
		comparator = comparator.thenComparingInt(pos -> originalOrder.getOrDefault(pos, Integer.MAX_VALUE));

		prioritized.sort(comparator);
		return List.copyOf(prioritized);
	}

	private List<BlockPos> remainingUnvisitedCandidates(List<BlockPos> source) {
		List<BlockPos> remaining = new ArrayList<>();
		for (BlockPos pos : source) {
			if (!visited.contains(pos)) {
				remaining.add(pos);
			}
		}
		return List.copyOf(remaining);
	}

	private List<BlockPos> remainingDiscoveryCandidates(List<BlockPos> source) {
		List<BlockPos> unvisited = remainingUnvisitedCandidates(source);
		if (unvisited.isEmpty()) {
			return unvisited;
		}

		List<BlockPos> uncached = new ArrayList<>();
		for (BlockPos pos : unvisited) {
			var key = reachableView.accessKeyByPos().get(pos);
			if (key == null || !reachableView.snapshotsByKey().containsKey(key)) {
				uncached.add(pos);
			}
		}

		return uncached.isEmpty() ? unvisited : List.copyOf(uncached);
	}

	private int cachedDistinctMatchesAt(BlockPos pos, Map<String, Integer> neededCounts) {
		int matches = 0;
		for (String itemId : neededCounts.keySet()) {
			if (reachableView.itemCountsAt(pos).getOrDefault(itemId, 0) > 0) {
				matches++;
			}
		}
		return matches;
	}

	private int cachedMatchUnitsAt(BlockPos pos, Map<String, Integer> neededCounts) {
		int matches = 0;
		Map<String, Integer> itemCounts = reachableView.itemCountsAt(pos);
		for (Map.Entry<String, Integer> entry : neededCounts.entrySet()) {
			matches += Math.min(itemCounts.getOrDefault(entry.getKey(), 0), entry.getValue());
		}
		return matches;
	}

	private int cachedPreferenceTotalAt(BlockPos pos, Map<String, Integer> neededCounts) {
		int total = 0;
		Map<String, Integer> itemCounts = reachableView.itemCountsAt(pos);
		for (String itemId : neededCounts.keySet()) {
			total += itemCounts.getOrDefault(itemId, 0);
		}
		return total;
	}

	private static Map<String, Integer> countNeededItems(List<String> itemIds) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String itemId : itemIds) {
			counts.merge(itemId, 1, Integer::sum);
		}
		return counts;
	}

	private static void subtractCounts(Map<String, Integer> counts, Map<String, Integer> subtract) {
		for (Map.Entry<String, Integer> entry : subtract.entrySet()) {
			int remaining = counts.getOrDefault(entry.getKey(), 0) - entry.getValue();
			if (remaining > 0) {
				counts.put(entry.getKey(), remaining);
			} else {
				counts.remove(entry.getKey());
			}
		}
	}


	private static Map<String, Integer> countStacks(List<ItemStack> stacks) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (ItemStack stack : stacks) {
			if (stack.isEmpty()) {
				continue;
			}
			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			counts.merge(itemId, stack.getCount(), Integer::sum);
		}
		return counts;
	}

	private int currentReservedCraftCopies() {
		return ContainerUtils.currentReservedCraftCopies(originalContext.gridStacks());
	}

	private IngredientPlanning.Policy planningPolicyForSelection(RecipeVariantResolver.Selection selection) {
		if (selection == null) {
			return planningPolicy;
		}

		Set<String> preferredVariants = preferredIngredientVariantsFor(selection);
		if (preferredVariants.isEmpty()) {
			return planningPolicy;
		}

		return new IngredientPlanning.Policy(
			planningPolicy.countPreference(),
			planningPolicy.redistributeToCraftWhenNeeded(),
			planningPolicy.preferInventory(),
			preferredVariants
		);
	}

	private Map<String, Integer> preferenceTotalsForSelection(Map<String, Integer> baseTotals, RecipeVariantResolver.Selection selection) {
		Set<String> preferredVariants = preferredIngredientVariantsFor(selection);
		if (preferredVariants.isEmpty()) {
			return baseTotals;
		}

		Map<String, Integer> boostedTotals = new LinkedHashMap<>(baseTotals);
		for (String itemId : preferredVariants) {
			boostedTotals.merge(itemId, 1_000_000, Integer::sum);
		}
		return Map.copyOf(boostedTotals);
	}

	private Set<String> preferredIngredientVariantsFor(RecipeVariantResolver.Selection selection) {
		if (selection == null || recipeCollection == null || recipeCollection.getRecipes().size() <= 1) {
			return Set.of();
		}
		if (ReachCraftingConfig.get().revolvingCraftHandling() != ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK) {
			return Set.of();
		}

		int bestScore = 0;
		LinkedHashSet<String> preferred = new LinkedHashSet<>();
		for (String itemId : selection.ingredientSummary().acceptedItemIds()) {
			int score = variantMatchScore(selection.outputItemId(), itemId);
			if (score <= 0) {
				continue;
			}
			if (score > bestScore) {
				bestScore = score;
				preferred.clear();
			}
			if (score == bestScore) {
				preferred.add(itemId);
			}
		}
		return preferred.isEmpty() ? Set.of() : Set.copyOf(preferred);
	}

	private int variantMatchScore(String outputItemId, String ingredientItemId) {
		String outputPath = outputItemId.substring(outputItemId.indexOf(':') + 1);
		String ingredientPath = ingredientItemId.substring(ingredientItemId.indexOf(':') + 1);
		String[] outputTokens = outputPath.split("_");
		String[] ingredientTokens = ingredientPath.split("_");
		int max = Math.min(outputTokens.length, ingredientTokens.length);
		int matched = 0;
		while (matched < max && outputTokens[matched].equals(ingredientTokens[matched])) {
			matched++;
		}
		return matched;
	}

	private BulkAutoCraftController.VariantContinuationMode resolveBulkVariantContinuationMode(RecipeVariantResolver.Selection resolvedSelection) {
		BulkAutoCraftController.VariantContinuationMode currentMode = BulkAutoCraftController.currentVariantContinuationMode();
		if (currentMode == BulkAutoCraftController.VariantContinuationMode.FAMILY_FALLBACK) {
			return currentMode;
		}
		if (currentMode == BulkAutoCraftController.VariantContinuationMode.STRICT_CURRENT_VARIANT) {
			return currentMode;
		}
		if (explicitVariantSelection
			|| ReachCraftingConfig.get().revolvingCraftHandling() != ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK) {
			return BulkAutoCraftController.determineVariantContinuationMode(initialRequestedRecipeId, recipeId, explicitVariantSelection);
		}
		if (resolvedSelection == null) {
			return BulkAutoCraftController.VariantContinuationMode.UNDECIDED;
		}
		return resolvedSelection.recipeId().equals(initialRequestedRecipeId)
			? BulkAutoCraftController.VariantContinuationMode.STRICT_CURRENT_VARIANT
			: BulkAutoCraftController.VariantContinuationMode.FAMILY_FALLBACK;
	}


	private void resumeOriginalContext() {
		super.resumeOriginalContext(originalContext);
	}

	private boolean isOriginalContextReady() {
		return super.isOriginalContextReady(originalContext);
	}

	private boolean tryFinishAfterResume() {
		if (!isOriginalContextReady()) {
			lastRestoreFailure = "context_not_ready";
			return false;
		}

		boolean gridRestored = false;
		restoreRecipeBookSnapshot();
		restoreMousePosition();
		gridRestored = redistributeThisRun || restoreReservedGrid();
		
		if (!gridRestored && originalContext.hasReservedGrid() && restoreTicksRemaining > 0) {
			restoreTicksRemaining--;
			ReachCraftingMod.LOGGER.debug(
				"[nearby_restore] idx={} waiting_for_grid_restore remaining_ticks={}",
				recipeIndex,
				restoreTicksRemaining
			);
			return false;
		}

		boolean reservedGridMatchesRecipe = redistributeThisRun
			? originalContext.hasReservedGrid() && originalContext.reservedGridMatchesCollection(client.level, recipeCollection)
			: originalContext.reservedGridMatches(ingredientSummary);

		boolean reservedGridExpanded = false;
		if (gridRestored && reservedGridMatchesRecipe && targetCopiesPerSlot > 0) {
			reservedGridExpanded = redistributeThisRun
				? applyPlannedTargetsToGrid(originalOccupiedGridSlotIndices())
				: expandReservedGrid();
		}

		boolean placedPlannedGrid = false;
		if (!originalContext.hasReservedGrid()
			&& (craftAll || requestedSingleClicks > 1)
			&& targetCopiesPerSlot > 0
			&& !plannedTargets.isEmpty()) {
			PlacementAttempt seededPlacement = placePlannedGridWithVanillaShape();
			if (seededPlacement == PlacementAttempt.WAITING_FOR_SEED) {
				if (seedWaitTicksRemaining > 0) {
					seedWaitTicksRemaining--;
					ReachCraftingMod.LOGGER.debug(
						"[nearby_restore] idx={} waiting_for_seeded_shape remaining_ticks={}",
						recipeIndex,
						seedWaitTicksRemaining
					);
					return false;
				}
				lastRestoreFailure = "vanilla_shape_seed_timeout";
			} else {
				placedPlannedGrid = seededPlacement == PlacementAttempt.SUCCESS;
			}
		}

		Map<String, Integer> updatedCounts = AvailableItemSnapshot.mergeCounts(localItems.inventoryCounts(), withdrawnItems);
		int reportCopies = Math.max(targetCopiesPerSlot, craftAll ? 1 : requestedSingleClicks);
		RecipeDeficitReport updatedDeficit = RecipeDeficitReport.from(
			ingredientSummary,
			updatedCounts,
			originalContext.gridStacks(),
			reportCopies
		);
		if (originalContext.hasReservedGrid()
			&& gridRestored
			&& reservedGridMatchesRecipe
			&& reservedGridExpanded
			&& remainingItemIds.isEmpty()
			&& !inventorySpaceBlocked) {
			updatedDeficit = new RecipeDeficitReport(
				Map.of(),
				Map.of(),
				List.of(),
				plannedTargets,
				"<none>",
				false
			);
		} else if (!originalContext.hasReservedGrid()
			&& placedPlannedGrid
			&& remainingItemIds.isEmpty()
			&& !inventorySpaceBlocked) {
			updatedDeficit = new RecipeDeficitReport(
				Map.of(),
				Map.of(),
				List.of(),
				plannedTargets,
				"<none>",
				false
			);
		}
		ReachCraftingMod.LOGGER.debug(
			"[nearby_scan] idx={} scanned={} discovered={} withdrawn={} remaining={}",
			recipeIndex,
			scannedContainers,
			AvailableItemSnapshot.formatCounts(discoveredNearby),
			AvailableItemSnapshot.formatCounts(withdrawnItems),
			blockedCommittedLayoutMissingSummary != null ? blockedCommittedLayoutMissingSummary : updatedDeficit.compactMissingSummary()
		);

		if (originalContext.hasReservedGrid()) {
			if (blockedCommittedLayoutMissingSummary != null) {
				sendMissingIngredientsChat("Missing (committed layout): " + blockedCommittedLayoutMissingSummary);
			} else
			if (!gridRestored) {
				ReachCraftingMod.LOGGER.warn(
					"[nearby_restore] idx={} final_failure reason={}",
					recipeIndex,
					lastRestoreFailure
				);
				sendDebugChat("Fetched items, but couldn't fully restore the crafting grid.");
			} else if (targetCopiesPerSlot > 0 && !updatedDeficit.hasMissingIngredients() && reservedGridMatchesRecipe && reservedGridExpanded) {
				sendDebugChat("Updated grid: " + outputLabel);
			} else if (updatedDeficit.hasMissingIngredients()) {
				if (!BulkAutoCraftController.isActive() || BulkAutoCraftController.getCompletedRecipeCopies() == 0) {
					sendMissingIngredientsChat("Missing: " + updatedDeficit.compactMissingSummary());
				}
			} else {
				sendDebugChat("Fetched ingredients for the next craft.");
			}
		} else if (placedPlannedGrid) {
			sendDebugChat("Updated grid: " + outputLabel);
		} else if (targetCopiesPerSlot > 0 && !updatedDeficit.hasMissingIngredients() && player.containerMenu != null) {
			ReachCraftingMod.LOGGER.info(
				"[recipe_place] from=SearchSession.tryFinishAfterResume shift={} recipe_id={} output={} target_copies={} remaining={} updated_missing={}",
				craftAll,
				recipeId,
				outputLabel,
				targetCopiesPerSlot,
				summarizeRemainingItems(remainingItemIds),
				updatedDeficit.compactMissingSummary()
			);
			gameMode.handlePlaceRecipe(player.containerMenu.containerId, recipeId, craftAll);
			sendDebugChat("Placed recipe: " + outputLabel);
		} else if (!remainingItemIds.isEmpty() || inventorySpaceBlocked) {
			if (!BulkAutoCraftController.isActive() || BulkAutoCraftController.getCompletedRecipeCopies() == 0) {
				sendMissingIngredientsChat("Missing: " + updatedDeficit.compactMissingSummary());
			}
		} else if (updatedDeficit.hasMissingIngredients()) {
			if (!BulkAutoCraftController.isActive() || BulkAutoCraftController.getCompletedRecipeCopies() == 0) {
				sendMissingIngredientsChat("Missing: " + updatedDeficit.compactMissingSummary());
			}
		} else {
			sendDebugChat("Ready to place: " + outputLabel);
		}

		coordinator.armInteractionBlock();
		if (explicitVariantSelection) {
			RecipeBookClickCapture.tryCloseOverlayAfterRelease();
		}
		if (AutoCraftController.isEnabled()) {
			ItemStack expectedStack = ItemStack.EMPTY;
			var knownRecipes = ((com.reachcrafting.client.mixin.ClientRecipeBookAccessor) player.getRecipeBook()).getKnown();
			var entry = knownRecipes.get(recipeId);
			if (entry != null) {
				expectedStack = RecipeVariantResolver.resolveDisplayStack(entry.display(), net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(level));
			}
			if (AutoCraftController.isBulkModeEnabled() && !craftAll && requestedSingleClicks > 1) {
				BulkAutoCraftController.startOrUpdate(
					new RecipeBookClickCapture.HeldRecipeAction(
						recipeId,
						recipeCollection,
						ItemStack.EMPTY,
						org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
						explicitVariantSelection
					),
					requestedSingleClicks,
					allowNearby,
					resolveBulkVariantContinuationMode(null),
					expectedStack,
					ingredientSummary
				);
			}
			ContainerUtils.scheduleAutoMove(expectedStack);
		}
		finishSession(false);
		return true;
	}

	private void restoreRecipeBookSnapshot() {
		if (!(client.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return;
		}
		originalContext.recipeBookState().restore(recipeBookScreen);
	}

	private void restoreMousePosition() {
		if (client.mouseHandler.isMouseGrabbed()) {
			return;
		}

		double clampedX = Mth.clamp(originalContext.mouseX(), 0.0D, Math.max(0.0D, client.getWindow().getScreenWidth() - 1.0D));
		double clampedY = Mth.clamp(originalContext.mouseY(), 0.0D, Math.max(0.0D, client.getWindow().getScreenHeight() - 1.0D));
		client.mouseHandler.setIgnoreFirstMove();
		GLFW.glfwSetCursorPos(client.getWindow().handle(), clampedX, clampedY);
	}

	private boolean restoreReservedGrid() {
		if (!originalContext.hasReservedGrid()) {
			return true;
		}
		if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
			lastRestoreFailure = "screen_not_container";
			return false;
		}

		AbstractContainerMenu menu = containerScreen.getMenu();
		List<ItemStack> desiredGridStacks = originalContext.gridStacks();
		lastRestoreFailure = "<none>";
		for (int slotIndex = 1; slotIndex <= desiredGridStacks.size(); slotIndex++) {
			ItemStack desiredStack = desiredGridStacks.get(slotIndex - 1);
			if (desiredStack.isEmpty()) {
				continue;
			}
			if (!restoreGridSlot(menu, slotIndex, desiredStack)) {
				ReachCraftingMod.LOGGER.warn(
					"[nearby_restore] idx={} failed_to_restore grid_slot={} desired={} actual={} reason={}",
					recipeIndex,
					slotIndex,
					ContainerUtils.formatStack(desiredStack),
					ContainerUtils.formatStack(menu.getSlot(slotIndex).getItem()),
					lastRestoreFailure
				);
				return false;
			}
		}

		ReachCraftingMod.LOGGER.debug("[nearby_restore] idx={} restored_grid={}", recipeIndex, originalContext.gridSummary());
		return true;
	}

	private boolean expandReservedGrid() {
		if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
			return false;
		}

		AbstractContainerMenu menu = containerScreen.getMenu();
		List<ItemStack> originalGridStacks = originalContext.gridStacks();
		for (int slotIndex = 1; slotIndex <= originalGridStacks.size(); slotIndex++) {
			ItemStack originalStack = originalGridStacks.get(slotIndex - 1);
			if (originalStack.isEmpty()) {
				continue;
			}

			Slot targetSlot = menu.getSlot(slotIndex);
			ItemStack currentStack = targetSlot.getItem();
			if (currentStack.isEmpty() || !ItemStack.isSameItemSameComponents(currentStack, originalStack)) {
				return false;
			}

			int targetCount = Math.min(currentStack.getMaxStackSize(), Math.max(targetCopiesPerSlot, currentStack.getCount()));
			if (currentStack.getCount() >= targetCount) {
				continue;
			}

			ItemStack desiredStack = currentStack.copy();
			desiredStack.setCount(targetCount);
			if (!restoreGridSlot(menu, slotIndex, desiredStack)) {
				ReachCraftingMod.LOGGER.warn(
					"[nearby_restore] idx={} failed_to_expand grid_slot={} from={} to={} actual={} reason={}",
					recipeIndex,
					slotIndex,
					ContainerUtils.formatStack(currentStack),
					ContainerUtils.formatStack(desiredStack),
					ContainerUtils.formatStack(menu.getSlot(slotIndex).getItem()),
					lastRestoreFailure
				);
				return false;
			}
		}

		ReachCraftingMod.LOGGER.debug("[nearby_restore] idx={} expanded_grid={}", recipeIndex, summarizeGrid(menu, originalGridStacks.size()));
		return true;
	}

	private PlacementAttempt placePlannedGridWithVanillaShape() {
		if (targetCopiesPerSlot <= 0 || recipeId == null) {
			return PlacementAttempt.SUCCESS;
		}

		int queueLimit = RecipeClickExecutor.resolveRecipeQueueLimit(client, recipeId, recipeCollection);
		if (targetCopiesPerSlot >= queueLimit) {
			ReachCraftingMod.LOGGER.info("[recipe_place] handlePlaceRecipe(shift=true) from SearchSession.placePlannedGrid target={}", targetCopiesPerSlot);
			gameMode.handlePlaceRecipe(player.containerMenu.containerId, recipeId, true);
		} else {
			ReachCraftingMod.LOGGER.info("[recipe_place] handlePlaceRecipe(shift=false) x{} from SearchSession.placePlannedGrid", targetCopiesPerSlot);
			for (int i = 0; i < targetCopiesPerSlot; i++) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, recipeId, false);
			}
		}

		ReachCraftingMod.LOGGER.debug("[nearby_restore] idx={} placed_via_vanilla_calls target={}", recipeIndex, targetCopiesPerSlot);
		return PlacementAttempt.SUCCESS;
	}



	private boolean applyPlannedTargetsToGrid(List<Integer> occupiedAnchorSlots) {
		if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
			return false;
		}

		AbstractContainerMenu menu = containerScreen.getMenu();
		// Clear the entire grid to ensure a clean slate for vanilla placement
		if (!clearGridForRedistribute(menu, Map.of())) {
			return false;
		}

		// Ensure cursor is empty before starting vanilla placement
		if (!player.containerMenu.getCarried().isEmpty()) {
			// Try to drop the item into the inventory
			for (Slot slot : menu.slots) {
				if (slot.container instanceof Inventory && !slot.hasItem()) {
					pickup(menu, slot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
					break;
				}
			}
			// If still not empty, we might need to wait or fail
			if (!player.containerMenu.getCarried().isEmpty()) {
				lastRestoreFailure = "cursor_not_empty_after_clear item=" + BuiltInRegistries.ITEM.getKey(player.containerMenu.getCarried().getItem());
				return false;
			}
		}

		return placePlannedGridWithVanillaShape() == PlacementAttempt.SUCCESS;
	}

	private List<Integer> originalOccupiedGridSlotIndices() {
		List<Integer> occupiedSlots = new ArrayList<>();
		List<ItemStack> gridStacks = originalContext.gridStacks();
		for (int index = 0; index < gridStacks.size(); index++) {
			if (!gridStacks.get(index).isEmpty()) {
				occupiedSlots.add(index + 1);
			}
		}
		return occupiedSlots;
	}

	private boolean clearGridForRedistribute(AbstractContainerMenu menu, Map<Integer, IngredientPlanning.SlotTarget> targetsBySlot) {
		for (int slotIndex = 1; slotIndex <= originalContext.gridStacks().size(); slotIndex++) {
			Slot slot = menu.getSlot(slotIndex);
			ItemStack current = slot.getItem();
			if (current.isEmpty()) {
				continue;
			}
			IngredientPlanning.SlotTarget desiredTarget = targetsBySlot.get(slotIndex);
			boolean mustClear = craftAll || desiredTarget == null || desiredTarget.itemId() == null;
			if (!mustClear) {
				String desiredItemId = desiredTarget.itemId();
				String currentItemId = BuiltInRegistries.ITEM.getKey(current.getItem()).toString();
				mustClear = !desiredItemId.equals(currentItemId) || current.getCount() > desiredTarget.targetCount();
			}
			if (mustClear && !moveGridSlotBackToInventory(menu, slot)) {
				lastRestoreFailure = "failed_to_clear_for_redistribute slot=" + slotIndex;
				return false;
			}
		}
		return true;
	}

	private enum PlacementAttempt {
		SUCCESS,
		WAITING_FOR_SEED,
		FAILURE
	}

	@Override
	public void stop(boolean closeContainer) {
		if (closeContainer && player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}


	private void pickup(AbstractContainerMenu menu, Slot slot, int mouseButton) {
		MenuTransferHelper.pickup(gameMode, player, menu, slot, mouseButton);
	}

	private boolean restoreGridSlot(AbstractContainerMenu menu, int targetSlotIndex, ItemStack desiredStack) {
		if (!player.containerMenu.getCarried().isEmpty()) {
			lastRestoreFailure = "carried_stack=" + ContainerUtils.formatStack(player.containerMenu.getCarried());
			return false;
		}

		Slot targetSlot = menu.getSlot(targetSlotIndex);
		ItemStack currentStack = targetSlot.getItem();
		if (!currentStack.isEmpty() && !ItemStack.isSameItemSameComponents(currentStack, desiredStack)) {
			if (!moveGridSlotBackToInventory(menu, targetSlot)) {
				lastRestoreFailure = "wrong_item_in_grid actual=" + ContainerUtils.formatStack(currentStack) + " desired=" + ContainerUtils.formatStack(desiredStack);
				return false;
			}
			currentStack = targetSlot.getItem();
		}

		int remaining = desiredStack.getCount() - currentStack.getCount();
		while (remaining > 0) {
			Slot sourceSlot = findMatchingInventorySourceSlot(menu, desiredStack);
			if (sourceSlot == null) {
				lastRestoreFailure = "missing_source_for=" + ContainerUtils.formatStack(desiredStack) + " remaining=" + remaining;
				return false;
			}

			int moved = moveExactCount(menu, sourceSlot, targetSlot, remaining);
			if (moved <= 0) {
				lastRestoreFailure = "move_failed source=" + ContainerUtils.formatStack(sourceSlot.getItem()) + " target=" + ContainerUtils.formatStack(targetSlot.getItem()) + " remaining=" + remaining;
				return false;
			}
			InventoryGridRestoreTracker.recordModMove(sourceSlot.index, targetSlot.index);
			remaining -= moved;
		}

		ItemStack restoredStack = targetSlot.getItem();
		boolean restored = ItemStack.isSameItemSameComponents(restoredStack, desiredStack) && restoredStack.getCount() >= desiredStack.getCount();
		if (!restored) {
			lastRestoreFailure = "post_check actual=" + ContainerUtils.formatStack(restoredStack) + " desired=" + ContainerUtils.formatStack(desiredStack);
		}
		return restored;
	}

	private boolean moveGridSlotBackToInventory(AbstractContainerMenu menu, Slot sourceGridSlot) {
		boolean moved = MenuTransferHelper.moveGridSlotBackToInventory(menu, sourceGridSlot, player, gameMode);
		if (!moved) {
			lastRestoreFailure = "grid_clear_failed slot=" + sourceGridSlot.index;
		}
		return moved;
	}

	private String summarizeGrid(AbstractContainerMenu menu, int gridSlotCount) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int slotIndex = 1; slotIndex <= gridSlotCount; slotIndex++) {
			ItemStack stack = menu.getSlot(slotIndex).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			counts.merge(itemId, stack.getCount(), Integer::sum);
		}
		return AvailableItemSnapshot.formatCounts(counts);
	}

	private Slot findMatchingInventorySourceSlot(AbstractContainerMenu menu, ItemStack desiredStack) {
		return MenuTransferHelper.findMatchingInventorySourceSlot(menu, desiredStack, player);
	}

	private int moveExactCount(AbstractContainerMenu menu, Slot sourceSlot, Slot targetSlot, int remaining) {
		return MenuTransferHelper.moveExactCount(menu, sourceSlot, targetSlot, remaining, player, gameMode);
	}

	private static List<BlockPos> findCandidates(Level level, Entity cameraEntity, double reachDistance) {
		Vec3 eyePos = cameraEntity.getEyePosition(0);
		int radius = Mth.ceil(reachDistance);
		BlockPos center = BlockPos.containing(eyePos);
		List<BlockPos> candidates = new ArrayList<>();

		for (BlockPos pos : BlockPos.betweenClosed(
			center.offset(-radius, -radius, -radius),
			center.offset(radius, radius, radius)
		)) {
			BlockState state = level.getBlockState(pos);
			if (InWorldFilterManager.isContainerActive(level, pos, state)) {
				candidates.add(pos.immutable());
			}
		}

		candidates.sort(Comparator.comparingDouble(pos -> ContainerUtils.squaredDistanceToBlock(eyePos, pos)));
		return candidates;
	}

	private static Map<String, Integer> collectUsefulItems(AbstractContainerMenu menu, Set<String> acceptedItemIds) {
		Map<String, Integer> usefulItems = new LinkedHashMap<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory || !slot.hasItem()) {
				continue;
			}

			String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
			if (acceptedItemIds.contains(itemId)) {
				usefulItems.merge(itemId, slot.getItem().getCount(), Integer::sum);
			}
		}
		return usefulItems;
	}

	private static String summarizeRemainingItems(List<String> remainingItemIds) {
		if (remainingItemIds.isEmpty()) {
			return "<none>";
		}

		Map<String, Integer> aggregate = new LinkedHashMap<>();
		for (String itemId : remainingItemIds) {
			aggregate.merge(itemId, 1, Integer::sum);
		}
		return AvailableItemSnapshot.formatCounts(aggregate);
	}

	private WithdrawalPlan buildWithdrawalPlan(AbstractContainerMenu menu) {
		Map<String, Integer> remainingNeeds = countNeededItems(remainingItemIds);
		Map<String, Integer> plannedWithdrawals = new LinkedHashMap<>();
		List<WithdrawalPlan.PlannedMove> moves = new ArrayList<>();
		Map<Integer, Integer> virtualPlayerCounts = capturePlayerOccupancy(menu);
		Map<Integer, String> virtualPlayerItemIds = capturePlayerItemIds(menu);
		boolean blockedByInventory = false;

		for (Map.Entry<String, Integer> needEntry : remainingNeeds.entrySet()) {
			String itemId = needEntry.getKey();
			int stillNeeded = needEntry.getValue();
			List<Slot> matchingSources = sortedMatchingContainerSources(menu, itemId);
			if (matchingSources.isEmpty()) {
				continue;
			}

			for (Slot sourceSlot : matchingSources) {
				if (stillNeeded <= 0) {
					break;
				}

				int sourceRemaining = sourceSlot.getItem().getCount();
				while (sourceRemaining > 0 && stillNeeded > 0) {
					Slot targetSlot = findPlannedDestinationSlot(menu, itemId, virtualPlayerCounts, virtualPlayerItemIds);
					if (targetSlot == null) {
						blockedByInventory = true;
						break;
					}

					int currentTargetCount = virtualPlayerCounts.getOrDefault(targetSlot.index, targetSlot.hasItem() ? targetSlot.getItem().getCount() : 0);
					int maxTargetCount = targetSlot.hasItem()
						? Math.min(targetSlot.getMaxStackSize(), targetSlot.getItem().getMaxStackSize())
						: Math.min(targetSlot.getMaxStackSize(), sourceSlot.getItem().getMaxStackSize());
					int roomInTarget = maxTargetCount - currentTargetCount;
					int moveCount = Math.min(stillNeeded, Math.min(sourceRemaining, roomInTarget));
					if (moveCount <= 0) {
						break;
					}

					moves.add(new WithdrawalPlan.PlannedMove(sourceSlot, targetSlot, itemId, moveCount));
					virtualPlayerCounts.put(targetSlot.index, currentTargetCount + moveCount);
					virtualPlayerItemIds.put(targetSlot.index, itemId);
					plannedWithdrawals.merge(itemId, moveCount, Integer::sum);
					sourceRemaining -= moveCount;
					stillNeeded -= moveCount;
				}

				if (blockedByInventory) {
					break;
				}
			}

			remainingNeeds.put(itemId, stillNeeded);
			if (blockedByInventory) {
				break;
			}
		}

		Map<String, Integer> unresolvedNeeds = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : remainingNeeds.entrySet()) {
			if (entry.getValue() > 0) {
				unresolvedNeeds.put(entry.getKey(), entry.getValue());
			}
		}

		if (!moves.isEmpty()) {
			ReachCraftingMod.LOGGER.debug(
				"[nearby_withdraw_plan] idx={} pos={} moves={} planned={} unresolved={}",
				recipeIndex,
				ContainerUtils.formatPos(pendingContainerPos),
				moves.size(),
				AvailableItemSnapshot.formatCounts(plannedWithdrawals),
				AvailableItemSnapshot.formatCounts(unresolvedNeeds)
			);
		}

		return new WithdrawalPlan(moves, plannedWithdrawals, unresolvedNeeds, blockedByInventory);
	}

	private Map<String, Integer> executeWithdrawalPlan(AbstractContainerMenu menu, WithdrawalPlan plan) {
		Map<String, Integer> executedWithdrawals = new LinkedHashMap<>();
		for (WithdrawalPlan.PlannedMove move : plan.moves()) {
			if (!move.source().hasItem() || !move.source().mayPickup(player)) {
				ReachCraftingMod.LOGGER.warn(
					"[nearby_withdraw_plan] idx={} source_unavailable slot={} item={}",
					recipeIndex,
					move.source().index,
					move.itemId()
				);
				break;
			}

			ItemStack withdrawnStack = move.source().getItem().copy();
			withdrawnStack.setCount(Math.min(move.count(), withdrawnStack.getCount()));
			MenuTransferHelper.WithdrawalMoveResult result = MenuTransferHelper.moveExactCountFromContainerToInventory(
				menu,
				move.source(),
				move.target(),
				move.count(),
				player,
				gameMode
			);
			if (result.moved() <= 0) {
				ReachCraftingMod.LOGGER.warn(
					"[nearby_withdraw_plan] idx={} move_failed source={} target={} item={} requested={}",
					recipeIndex,
					move.source().index,
					move.target().index,
					move.itemId(),
					move.count()
				);
				break;
			}

			ReachCraftingMod.LOGGER.debug(
				"[nearby_withdraw_move] idx={} source={} target={} item={} count={} mode={}",
				recipeIndex,
				move.source().index,
				move.target().index,
				move.itemId(),
				result.moved(),
				result.mode().name().toLowerCase()
			);

			withdrawnStack.setCount(result.moved());
			PulledResourcesTracker.recordWithdrawal(pendingContainerPos, move.source().index, withdrawnStack);
			executedWithdrawals.merge(move.itemId(), result.moved(), Integer::sum);
		}
		return executedWithdrawals;
	}

	private void applyWithdrawalResults(Map<String, Integer> executedWithdrawals, boolean blockedByInventory) {
		if (blockedByInventory) {
			inventorySpaceBlocked = true;
		}
		if (executedWithdrawals.isEmpty()) {
			if (inventorySpaceBlocked) {
				sendDebugChat("No inventory space for fetched items");
			}
			return;
		}

		for (Map.Entry<String, Integer> entry : executedWithdrawals.entrySet()) {
			removeRemainingItems(entry.getKey(), entry.getValue());
			withdrawnItems.merge(entry.getKey(), entry.getValue(), Integer::sum);
		}

		subtractCounts(discoveredNearby, executedWithdrawals);
		NearbyContainerCache.applyWithdrawals(level, pendingContainerPos, executedWithdrawals);
		ReachCraftingMod.LOGGER.debug(
			"[nearby_withdraw] idx={} pos={} total_withdrawn={} remaining={}",
			recipeIndex,
			ContainerUtils.formatPos(pendingContainerPos),
			AvailableItemSnapshot.formatCounts(withdrawnItems),
			summarizeRemainingItems(remainingItemIds)
		);
	}

	private static Map<Integer, Integer> capturePlayerOccupancy(AbstractContainerMenu menu) {
		Map<Integer, Integer> occupancy = new HashMap<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory) {
				occupancy.put(slot.index, slot.hasItem() ? slot.getItem().getCount() : 0);
			}
		}
		return occupancy;
	}

	private static Map<Integer, String> capturePlayerItemIds(AbstractContainerMenu menu) {
		Map<Integer, String> itemIds = new HashMap<>();
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
				continue;
			}
			itemIds.put(slot.index, BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());
		}
		return itemIds;
	}

	private List<Slot> sortedMatchingContainerSources(AbstractContainerMenu menu, String itemId) {
		List<Slot> matchingSources = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory || !slot.hasItem() || !slot.mayPickup(player)) {
				continue;
			}
			String slotItemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
			if (itemId.equals(slotItemId)) {
				matchingSources.add(slot);
			}
		}

		matchingSources.sort(
			Comparator.comparingInt((Slot slot) -> slot.getItem().getCount())
				.thenComparingInt(slot -> slot.index)
		);
		return matchingSources;
	}

	private Slot findPlannedDestinationSlot(
		AbstractContainerMenu menu,
		String itemId,
		Map<Integer, Integer> virtualPlayerCounts,
		Map<Integer, String> virtualPlayerItemIds
	) {
		Slot bestPartial = null;
		int bestPartialCount = -1;
		Slot bestEmpty = null;

		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory)) {
				continue;
			}

			int virtualCount = virtualPlayerCounts.getOrDefault(slot.index, slot.hasItem() ? slot.getItem().getCount() : 0);
			if (slot.hasItem()) {
				String slotItemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				if (!itemId.equals(slotItemId)) {
					continue;
				}

				int max = Math.min(slot.getMaxStackSize(), slot.getItem().getMaxStackSize());
				if (virtualCount >= max) {
					continue;
				}
				if (bestPartial == null || virtualCount > bestPartialCount || (virtualCount == bestPartialCount && slot.index < bestPartial.index)) {
					bestPartial = slot;
					bestPartialCount = virtualCount;
				}
			} else {
				String virtualItemId = virtualPlayerItemIds.get(slot.index);
				if (virtualCount > 0) {
					if (!itemId.equals(virtualItemId)) {
						continue;
					}
					ItemStack prototype = itemPrototype(itemId);
					int max = prototype.isEmpty() ? slot.getMaxStackSize() : Math.min(slot.getMaxStackSize(), prototype.getMaxStackSize());
					if (virtualCount >= max) {
						continue;
					}
					if (bestPartial == null || virtualCount > bestPartialCount || (virtualCount == bestPartialCount && slot.index < bestPartial.index)) {
						bestPartial = slot;
						bestPartialCount = virtualCount;
					}
				} else if (bestEmpty == null) {
					bestEmpty = slot;
				}
			}
		}

		return bestPartial != null ? bestPartial : bestEmpty;
	}

	private void removeRemainingItems(String itemId, int count) {
		int toRemove = count;
		for (int i = 0; i < remainingItemIds.size() && toRemove > 0; ) {
			if (itemId.equals(remainingItemIds.get(i))) {
				remainingItemIds.remove(i);
				toRemove--;
				continue;
			}
			i++;
		}
	}


}
