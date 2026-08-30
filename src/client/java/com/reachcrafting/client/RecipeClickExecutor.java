package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

final class RecipeClickExecutor {
	private static final int BULK_QUEUE_LIMIT = 10_000;

	private RecipeClickExecutor() {
	}

	static void executeRecipeButtonClick(
		Minecraft minecraft,
		LocalPlayer player,
		Screen screen,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean craftAll,
		boolean allowNearbyChests,
		boolean forceDryRun,
		boolean explicitVariantSelection,
		int requestedClicks,
		boolean refillableBulkMaxMode,
		boolean autoCraftRequested,
		HeldRecipeQueueState state
	) {
		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		boolean nearbyBulkMaxMode = allowNearbyChests && refillableBulkMaxMode;
		boolean effectiveCraftAll = craftAll && !nearbyBulkMaxMode;
		boolean vanillaShiftClick = effectiveCraftAll && !forceDryRun && !allowNearbyChests;
		ReachCraftingMod.LOGGER.debug(
			"[recipe_capture] screen={} inventory={} grid={} slots={} pending={} replay={}",
			screen.getClass().getSimpleName(),
			availableItems.inventorySummary(),
			availableItems.gridSummary(),
			AvailableItemSnapshot.formatInventorySlots(player),
			state.pendingHeldRecipe() != null ? state.pendingHeldRecipe().action().recipeId().index() + "x" + state.pendingHeldRecipe().clickCount() : "<none>",
			state.replayBatch() != null ? state.replayBatch().action().recipeId().index() + "x" + state.replayBatch().remainingClicks() : "<none>"
		);
		boolean allowReservedGridVariantSwitch = false;
		int desiredVariantCopies = availableItems.hasReservedGrid() && !effectiveCraftAll
			? ContainerUtils.currentReservedCraftCopies(availableItems.gridStacks()) + requestedClicks
			: Math.max(requestedClicks, 1);

		boolean allowVariantSwitching = !vanillaShiftClick && (allowNearbyChests || forceDryRun);
		String screenKind = screen instanceof InventoryScreen ? "inventory_2x2" : "crafting_table_3x3";
		RecipeVariantResolver.Selection selectedRecipe = RecipeVariantResolver.resolve(
			minecraft,
			player,
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			explicitVariantSelection,
			allowVariantSwitching,
			availableItems,
			availableItems.inventoryCounts(),
			availableItems.inventoryCounts(),
			effectiveCraftAll,
			allowReservedGridVariantSwitch,
			desiredVariantCopies
		);
		if (selectedRecipe == null) {
			ReachCraftingMod.LOGGER.warn("[recipe_click] missing RecipeDisplayEntry for recipe_index={}", recipeId.index());
			return;
		}
		ReachCraftingMod.LOGGER.info(
			"[recipe_click] resolved clicked_recipe={} selected_recipe={} explicit_variant={} requested_clicks={} allow_nearby={} craft_all={} display_stack={}",
			recipeId,
			selectedRecipe.recipeId(),
			explicitVariantSelection,
			requestedClicks,
			allowNearbyChests,
			craftAll,
			ContainerUtils.formatStack(selectedRecipe.displayStack())
		);
		boolean craftable = collection.isCraftable(recipeId);
		int recipeIndex = selectedRecipe.recipeId().index();
		if (!selectedRecipe.recipeId().equals(recipeId)) {
			ReachCraftingMod.LOGGER.debug(
				"[recipe_variant] clicked_idx={} selected_idx={} mode={} output={}",
				recipeId.index(),
				selectedRecipe.recipeId().index(),
				ReachCraftingConfig.get().revolvingCraftHandling().name().toLowerCase(),
				selectedRecipe.outputLabel()
			);
		}

		ItemStack resolvedDisplayStack = selectedRecipe.displayStack().copy();
		RecipeIngredientSummary ingredientSummary = selectedRecipe.ingredientSummary();
		Map<String, Integer> localAvailableCounts = availableItems.totalCounts();
		Map<String, Integer> availableCounts = availableItems.totalCounts();
		Map<String, Integer> chainAvailableCounts = availableCounts;
		boolean nearbyCacheIncomplete = false;
		if (allowNearbyChests && ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(minecraft.level, minecraft.getCameraEntity(), player.blockInteractionRange());
			availableCounts = AvailableItemSnapshot.mergeCounts(availableCounts, reachableView.countsFor(ingredientSummary.acceptedItemIds()));
			// Lockstep with BulkChainCraftController.collectAvailableCounts.
			chainAvailableCounts = AvailableItemSnapshot.mergeCounts(localAvailableCounts, reachableView.aggregateCounts());
			nearbyCacheIncomplete = reachableView.snapshotsByKey().size() < reachableView.nearestAccessByKey().size();
		}
		// Lockstep with BulkChainCraftController.collectAvailableCounts:
		// non-pristine local stacks are unusable as self-referential inputs.
		chainAvailableCounts = ContainerUtils.subtractNonPristineLocalStacks(minecraft, chainAvailableCounts);
		RecipeDeficitReport deficitReport = effectiveCraftAll
			? RecipeDeficitReport.from(ingredientSummary, availableCounts, availableItems.gridStacks(), true)
			: RecipeDeficitReport.from(ingredientSummary, availableCounts, availableItems.gridStacks(), desiredVariantCopies);
		RecipeDeficitReport immediateCraftDeficit = RecipeDeficitReport.from(
			ingredientSummary,
			availableCounts,
			availableItems.gridStacks(),
			1
		);
		RecipeDeficitReport immediateLocalCraftDeficit = RecipeDeficitReport.from(
			ingredientSummary,
			localAvailableCounts,
			availableItems.gridStacks(),
			1
		);
		RecipeDeficitReport localDeficitReport = effectiveCraftAll
			? RecipeDeficitReport.from(ingredientSummary, localAvailableCounts, availableItems.gridStacks(), true)
			: RecipeDeficitReport.from(ingredientSummary, localAvailableCounts, availableItems.gridStacks(), desiredVariantCopies);
		String resolvedItemId = BuiltInRegistries.ITEM.getKey(resolvedDisplayStack.getItem()).toString();
		String outputLabel = resolvedItemId + " x" + resolvedDisplayStack.getCount();
		if (ExistingOutputRetrievalController.isEnabled()) {
			if (!ReachCraftingConfig.get().enableNearbyContainerUsage()) {
				ReachCraftingModClient.sendChat("Nearby container usage is disabled.");
				return;
			}
			NearbyContainerDryRun.startExistingOutputRetrieval(
				recipeId,
				selectedRecipe.recipeId(),
				collection,
				explicitVariantSelection,
				resolvedItemId,
				outputLabel,
				resolvedDisplayStack,
				Math.max(effectiveRequestedClicks(craftAll, requestedClicks, desiredVariantCopies), 1)
			);
			if (explicitVariantSelection) {
				tryCloseOverlayAfterRelease();
			}
			return;
		}

		ReachCraftingMod.LOGGER.debug(
			"[recipe_click] screen={} button={} idx={} craftable={} shift={} ctrl={} output={}",
			screenKind,
			mouseButton,
			recipeIndex,
			craftable,
			effectiveCraftAll,
			allowNearbyChests,
			outputLabel
		);
		ReachCraftingMod.LOGGER.debug(
			"[recipe_needs] idx={} summary={} slots={}",
			recipeIndex,
			ingredientSummary.compactSummary(),
			ingredientSummary.rawSlots()
		);
		ReachCraftingMod.LOGGER.debug(
			"[recipe_missing] idx={} inventory={} grid={} missing={}",
			recipeIndex,
			availableItems.inventorySummary(),
			availableItems.gridSummary(),
			deficitReport.compactMissingSummary()
		);

		int effectiveRequestedClicks = refillableBulkMaxMode
			? Math.max(requestedClicks, 1)
			: effectiveCraftAll
				? deficitReport.possibleCopies()
				: requestedClicks;

		// Chain already resolved local-only intermediate dependencies up front, so
		// these replayed steps can use the faster direct placement path.
		boolean directChainReplay = ChainCraftController.isActive()
			&& (!allowNearbyChests || ChainCraftController.isUsingPreStagedNearbyResources());
		boolean useDryRun = (allowNearbyChests && !directChainReplay) || (forceDryRun && !directChainReplay);
		ReachCraftingConfig.ChainCraftingMode chainMode = ReachCraftingConfig.get().chainCraftingMode();
		// Bulk mode implies autocraft semantics even when Alt is not physically
		// held at click time, so bulk requests also qualify for chain offers.
		// Active sessions are excluded so their replayed clicks never re-offer.
		// Self-referential recipes (a dyed bundle's slot accepts all bundles,
		// including the output) are ALWAYS routed through the chain path in
		// bulk mode, even with nothing missing: flat bulk's server-side
		// placement would happily re-dye its own output or the player's
		// colored variants, while the chain executor places the planned base
		// inputs manually. The single-step plan starts silently, so the UX
		// matches flat bulk.
		boolean selfReferentialRecipe = ingredientSummary.acceptedItemIds().contains(resolvedItemId);
		// Yellow-icon contract: yellow means "this click crafts directly", so a
		// max bulk request on a recipe that is directly craftable right now
		// (counting nearby stock) drains that direct supply with flat bulk
		// first — no chain offer. Only once direct crafting is exhausted does
		// the indicator turn orange and a chain offer become legitimate.
		// Exact-count requests still chain (the user asked for a number that
		// direct crafting alone may not reach), and self-referential recipes
		// always route through the chain path in bulk regardless.
		// Yellow-indicator contract: if the recipe is directly craftable right
		// now (counting nearby stock -- exactly what the yellow icon means),
		// there is no reason to look for a chain alternative until that direct
		// supply is exhausted. The immediate deficit is computed from
		// availableCounts, which lags behind the (nearby-aware) craftability
		// cache on a cold nearby scan -- the first click after opening a table
		// then wrongly concluded "direct unsatisfiable -> chain", launching an
		// expensive cyclic planner search AND dropping into tiny eject batches.
		// Trust the same signal the indicator shows.
		boolean directlyCraftableNow = !immediateCraftDeficit.hasMissingIngredients()
			|| ChainCraftabilityCache.isReachable(selectedRecipe.recipeId());
		boolean directBulkTakesPriority = (refillableBulkMaxMode
				|| (effectiveCraftAll && AutoCraftController.isBulkModeEnabled()))
			&& !selfReferentialRecipe
			&& directlyCraftableNow;
		boolean canOfferChainCraft = (deficitReport.hasMissingIngredients()
				|| (selfReferentialRecipe && AutoCraftController.isBulkModeEnabled()))
			&& !directBulkTakesPriority
			&& (autoCraftRequested || AutoCraftController.isBulkModeEnabled())
			&& chainMode != ReachCraftingConfig.ChainCraftingMode.DISABLED
			&& !ChainCraftController.isActive()
			&& !BulkAutoCraftController.isActive()
			&& !BulkChainCraftController.isActive();
		// Variant fallback for the chain path. The collection's chain
		// indicator lights up when ANY variant is chain-craftable (spruce
		// logs on a door entry rotating through bamboo), but the click only
		// ever planned the ONE recipe the resolver returned -- and when no
		// variant is directly craftable that is always the clicked one. So
		// the request died as "missing bamboo planks" on a collection the
		// icon had just promised was reachable. Gate on the availability
		// setting rather than on allowVariantSwitching: the resolver's flag
		// only opens up on the nearby/dry-run paths, while the setting is
		// what the user actually told us about swapping variants.
		List<RecipeVariantResolver.Selection> chainVariantCandidates = canOfferChainCraft
			? chainVariantFallbackCandidates(
				minecraft,
				player,
				recipeId,
				collection,
				displayStack,
				availableItems,
				chainAvailableCounts,
				selectedRecipe,
				explicitVariantSelection,
				vanillaShiftClick,
				effectiveCraftAll,
				desiredVariantCopies,
				allowNearbyChests
			)
			: List.of(selectedRecipe);
		String missingMessage = deficitReport.hasMissingIngredients()
			? "Missing: " + deficitReport.compactMissingSummary()
			: "";
		if (deficitReport.hasMissingIngredients()) {
			ReachCraftingModClient.sendDebugChat("Missing from inventory: " + deficitReport.compactMissingSummary());
			if (!useDryRun && !canOfferChainCraft) {
				ReachCraftingModClient.sendMissingIngredientsChat(missingMessage);
			}
		} else {
			ReachCraftingModClient.sendDebugChat("Ready: " + outputLabel);
		}

		if (deficitReport.hasMissingIngredients()) {
			ReachCraftingMod.LOGGER.debug("[chain_gate_hold_state] {}", AutoCraftController.describeHoldState());
			ReachCraftingMod.LOGGER.info(
				"[chain_gate] recipe={} missing={} direct_priority={} auto_requested={} mode={} use_dry_run={} force_dry_run={} allow_nearby={} bulk_mode={} craft_all={} effective_craft_all={} requested_clicks={} desired_copies={} available={} chain_available={} local_available={}",
				selectedRecipe.recipeId(),
				deficitReport.compactMissingSummary(),
				directBulkTakesPriority,
				autoCraftRequested,
				chainMode,
				useDryRun,
				forceDryRun,
				allowNearbyChests,
				AutoCraftController.isBulkModeEnabled(),
				craftAll,
				effectiveCraftAll,
				requestedClicks,
				desiredVariantCopies,
				AvailableItemSnapshot.formatCounts(availableCounts),
				AvailableItemSnapshot.formatCounts(chainAvailableCounts),
				AvailableItemSnapshot.formatCounts(localAvailableCounts)
			);
		}

		if (canOfferChainCraft) {
			long chainOfferStartNanos = PerformanceProfiler.start();
			// A refillable bulk max request is a craft-all for planning purposes:
			// plan the largest achievable count instead of one exact huge count.
			boolean chainCraftAll = effectiveCraftAll || refillableBulkMaxMode;
			Optional<ChainCraftOffer> chainOffer = planChainCraftOffer(
				minecraft,
				player,
				chainVariantCandidates,
				chainAvailableCounts,
				allowNearbyChests,
				chainCraftAll,
				requestedClicks,
				desiredVariantCopies
			);
			PerformanceProfiler.record(
				"recipe_click.chain_offer",
				chainOfferStartNanos,
				"present=" + chainOffer.isPresent() + " allow_nearby=" + allowNearbyChests + " craft_all=" + effectiveCraftAll
			);
			if (chainOffer.isPresent()) {
				ChainCraftPlan chainPlan = chainOffer.get().plan();
				RecipeVariantResolver.Selection chainSelection = chainOffer.get().selection();
				// A fallback variant has its own shortfall, so the deferred
				// "Missing: ..." shown when the popup is declined must name
				// the variant we offered, not the one that was rotating.
				String chainMissingMessage = chainSelection.recipeId().equals(selectedRecipe.recipeId())
					? missingMessage
					: missingMessageFor(chainSelection, availableCounts, availableItems, effectiveCraftAll, desiredVariantCopies);
				ReachCraftingMod.LOGGER.info(
					"[chain_plan] available clicked_recipe={} recipe={} requested={} planned={} steps={} allow_nearby={} bulk_mode={}",
					selectedRecipe.recipeId(),
					chainSelection.recipeId(),
					chainOffer.get().requestedRecipeCopies(),
					chainPlan.finalRecipeCopies(),
					chainPlan.steps().size(),
					allowNearbyChests,
					AutoCraftController.isBulkModeEnabled()
				);
				if (AutoCraftController.isBulkModeEnabled()) {
					if (!ReachCraftingConfig.get().enableBulkChainCrafting()) {
						ReachCraftingModClient.sendChat(net.minecraft.network.chat.Component.translatable("message.reachcrafting.chain_crafting.bulk_unsupported").getString());
						return;
					}
					ChainCraftPopupController.handleBulkChainPlan(
						chainPlan,
						chainSelection,
						allowNearbyChests,
						chainOffer.get().requestedRecipeCopies(),
						chainOffer.get().maxRequest(),
						chainMissingMessage
					);
					return;
				}
				int popupRequestedCopies = chainOffer.get().maxRequest()
					? chainPlan.finalRecipeCopies()
					: chainOffer.get().requestedRecipeCopies();
				ChainCraftPopupController.handlePlan(chainPlan, popupRequestedCopies, false, chainMissingMessage);
				return;
			}
			ReachCraftingMod.LOGGER.info(
				"[chain_plan] unavailable recipe={} requested={} allow_nearby={} missing={} nearby_cache_incomplete={}",
				selectedRecipe.recipeId(),
				effectiveCraftAll ? requestedClicks : desiredVariantCopies,
				allowNearbyChests,
				deficitReport.compactMissingSummary(),
				nearbyCacheIncomplete
			);
			if (allowNearbyChests && nearbyCacheIncomplete && useDryRun) {
				ChainCraftController.armRetryAfterNearbyWarmup(
					new RecipeBookClickCapture.HeldRecipeAction(
						selectedRecipe.recipeId(),
						collection,
						selectedRecipe.displayStack().copy(),
						mouseButton,
						explicitVariantSelection
					),
					effectiveRequestedClicks,
					allowNearbyChests,
					effectiveCraftAll,
					refillableBulkMaxMode,
					selectedRecipe.displayStack()
				);
			}
			if (!useDryRun) {
				ReachCraftingModClient.sendMissingIngredientsChat(missingMessage);
			}
		}

		boolean nearbyResourcesRequired = allowNearbyChests
			&& areNearbyResourcesRequired(craftAll, effectiveRequestedClicks, localDeficitReport.possibleCopies());
		// Bulk on a cold cache: don't interleave crafting with need-targeted
		// mini-discoveries (screen-flashing chest visits every few batches).
		// Run ONE full discovery scan of the uncached containers, then the
		// armed retry replays this exact request against the warm cache,
		// where the session withdraws in big lumps and crafts continuously.
		// Gated to the initial user click (never replays) so an unreachable
		// container that keeps the cache incomplete cannot loop the warmup.
		if (useDryRun
			&& !autoCraftRequested
			&& allowNearbyChests
			&& nearbyCacheIncomplete
			&& AutoCraftController.isBulkModeEnabled()
			&& (refillableBulkMaxMode || effectiveCraftAll)) {
			ReachCraftingMod.LOGGER.info(
				"[bulk_warmup] cold_cache_full_scan_first recipe={} clicks={} craft_all={} refillable={}",
				selectedRecipe.recipeId(),
				effectiveRequestedClicks,
				effectiveCraftAll,
				refillableBulkMaxMode
			);
			ChainCraftController.armRetryAfterNearbyWarmup(
				new RecipeBookClickCapture.HeldRecipeAction(
					selectedRecipe.recipeId(),
					collection,
					selectedRecipe.displayStack().copy(),
					mouseButton,
					explicitVariantSelection
				),
				effectiveRequestedClicks,
				allowNearbyChests,
				effectiveCraftAll,
				refillableBulkMaxMode,
				selectedRecipe.displayStack()
			);
			NearbyContainerDryRun.startCacheWarmup("bulk_cold_cache");
			return;
		}
		if (useDryRun) {
			armBulkAutoCraft(
				recipeId,
				selectedRecipe.recipeId(),
				collection,
				displayStack,
				mouseButton,
				explicitVariantSelection,
				allowNearbyChests,
				effectiveCraftAll,
				effectiveRequestedClicks,
				nearbyResourcesRequired,
				refillableBulkMaxMode,
				selectedRecipe.displayStack(),
				ingredientSummary
			);
			if (allowNearbyChests
				&& AutoCraftController.isBulkModeEnabled()
				&& (!ChainCraftController.isActive() || ChainCraftController.isRunningFinalStep())
				&& !effectiveCraftAll
				&& !immediateLocalCraftDeficit.hasMissingIngredients()
				&& minecraft.gameMode != null) {
				// Always use a single handlePlaceRecipe(shift=true) to fill the grid.
				// Calling handlePlaceRecipe(false) in a loop triggers N server-side
				// grid clears, each of which consumes the previous result and injects
				// byproducts into inventory via Inventory.add(), causing fragmentation.
				if (!ChainCraftController.tryManualSelfReferentialPlacement(minecraft, resolvedItemId)) {
					// M3 (T2): bulk-chain finals with a single unstackable
					// ingredient run the whole batch as a GridExtractor
					// key-cycle over the ring — zero place packets and no
					// per-copy settlement rounds (see the direct-path twin).
					boolean nearbyChainFinalT2 = ChainCraftController.isRunningFinalStep()
						&& BulkChainCraftController.isActive()
						&& !PlaceRecipeBudget.isUnlimited(minecraft)
						&& GridTopUp.isKeyCycleEligible(ingredientSummary);
					if (GridTopUp.tryStageInsteadOfPlace(minecraft, player, ingredientSummary)) {
						// Ring built/maintained via clicks, rationed place
						// packet skipped.
						if (nearbyChainFinalT2) {
							GridExtractor.begin(selectedRecipe.displayStack(), effectiveRequestedClicks, ingredientSummary, true);
							ReachCraftingMod.LOGGER.info(
								"[recipe_place] chain_t2 key-cycle batch copies={} recipe={} (nearby path)",
								effectiveRequestedClicks,
								selectedRecipe.recipeId()
							);
						}
					} else {
						ReachCraftingMod.LOGGER.info("[recipe_place] handlePlaceRecipe(shift=true) from RecipeClickExecutor NEARBY path");
						minecraft.gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipeId(), true);
					}
				}
				AvailableItemSnapshot postPlaceSnapshot = AvailableItemSnapshot.capture(player, screen);
				ReachCraftingMod.LOGGER.info(
					"[recipe_place] post_place nearby result={} staged_copies={} requestedClicks={} queueLimit={} grid_reserved={}",
					ContainerUtils.formatStack(player.containerMenu.getSlot(0).getItem()),
					ContainerUtils.currentReservedCraftCopies(postPlaceSnapshot.gridStacks()),
					requestedClicks,
					resolveRecipeQueueLimit(minecraft, selectedRecipe.recipeId(), collection),
					postPlaceSnapshot.hasReservedGrid()
				);
				if (!GridExtractor.isActive()) {
					ContainerUtils.scheduleAutoMove(selectedRecipe.displayStack());
				}
				if (!ChainCraftController.isActive()) {
					ReachCraftingConfig.get().noteRecentRecipe(selectedRecipe.recipeId());
					RecipeBookChunkedScheduler.onRecentRecipesChanged();
				}
				ReachCraftingModClient.sendDebugChat("Placed recipe: " + outputLabel);
				if (explicitVariantSelection) {
					tryCloseOverlayAfterRelease();
				}
				return;
			}
			if (allowNearbyChests
				&& AutoCraftController.isBulkModeEnabled()
				&& !effectiveCraftAll
				&& !immediateCraftDeficit.hasMissingIngredients()
				&& immediateLocalCraftDeficit.hasMissingIngredients()) {
				ReachCraftingMod.LOGGER.info(
					"[recipe_place] skip_direct_nearby_bulk reason=nearby_only_first_craft local_missing={} total_missing={}",
					immediateLocalCraftDeficit.compactMissingSummary(),
					immediateCraftDeficit.compactMissingSummary()
				);
			}

			if (GridTopUp.tryStageInsteadOfPlace(minecraft, player, ingredientSummary)) {
				// Ring cycle: unstackable slot(s) refilled via clicks. Bypass
				// the dry-run/search-session machinery entirely — its restore
				// bookkeeping treats a persistent ring as foreign grid content
				// and aborts the session (observed as skip_schedule
				// no_craft_staged). The bulk session is already armed here;
				// scheduling the auto-move is all that remains.
				ReachCraftingMod.LOGGER.info("[recipe_place] grid_topup ring cycle, dry-run bypassed");
				if (AutoCraftController.isEnabled()) {
					ContainerUtils.scheduleAutoMove(selectedRecipe.displayStack());
				}
				return;
			}

			if (!deficitReport.hasMissingIngredients() && availableItems.hasReservedGrid()) {
				if (NearbyContainerDryRun.tryExpandReservedGrid(
					selectedRecipe.recipeId(),
					collection,
					explicitVariantSelection,
					recipeIndex,
					outputLabel,
					ingredientSummary,
					availableItems,
					effectiveCraftAll,
					effectiveRequestedClicks,
					allowNearbyChests
				)) {
					if (AutoCraftController.isEnabled()) {
						ContainerUtils.scheduleAutoMove(selectedRecipe.displayStack());
					}
					return;
				}
			}
			NearbyContainerDryRun.start(
				selectedRecipe.recipeId(),
				collection,
				explicitVariantSelection,
				recipeIndex,
				outputLabel,
				ingredientSummary,
				availableItems,
				effectiveCraftAll,
				effectiveRequestedClicks,
				allowNearbyChests
			);
			return;
		}

		MultiPlayerGameMode gameMode = minecraft.gameMode;
		if (gameMode != null) {
			int queueLimit = resolveRecipeQueueLimit(minecraft, selectedRecipe.recipeId(), collection);
			// Intermediate chain steps must keep exact per-copy CONSUMPTION; a
			// shift place + QUICK_MOVE would craft-all, and with shared
			// ingredients (lectern: planks feed slabs AND bookshelves) that
			// overcraft starves later steps. On budgeted servers the T1 path
			// below still gets the packet win: ONE shift place stages maximal
			// stacks (staging is not consumption) and GridExtractor caps the
			// crafts via counted result clicks. The FINAL chain step is a flat
			// bulk craft though — one shift place crafts the whole batch at
			// flat bulk speed instead of one copy per settlement round.
			boolean chainFinalBulkPlace = AutoCraftController.isBulkModeEnabled() && ChainCraftController.isRunningFinalStep();
			// The flat-bulk final place above needs bulk mode, so on a PLAIN
			// chain craft the final step matched none of the fast branches and
			// dropped to the per-copy loop below: one balanced copy staged,
			// one craft, a whole settlement round, repeat. Pistons flew and
			// the sticky pistons that consumed them trickled out one at a
			// time. T1 is the right path for it -- one shift place stages
			// maximal stacks (staging is not consumption) and GridExtractor
			// caps the crafts by counted result clicks, so the exact-count
			// contract a non-bulk chain depends on is still kept.
			boolean chainCountedT1 = !chainFinalBulkPlace
				&& ChainCraftController.isActive()
				&& !PlaceRecipeBudget.isUnlimited(minecraft)
				&& effectiveRequestedClicks > 1
				&& GridExtractor.isEligibleSummary(ingredientSummary);
			// M3 (T2): a bulk-chain FINAL step with a single unstackable
			// ingredient (dispenser's bow) used to pay one rationed packet per
			// copy — schedule, shift-place one balanced copy, settle, repeat.
			// Instead run the whole batch as a GridExtractor key-cycle: the
			// ring stages the stackable slots, each craft is "insert next key
			// + pick result" in ordinary clicks, zero place packets.
			boolean chainFinalT2 = chainFinalBulkPlace
				&& BulkChainCraftController.isActive()
				&& !PlaceRecipeBudget.isUnlimited(minecraft)
				&& GridTopUp.isKeyCycleEligible(ingredientSummary);
			boolean useBulkPlace = effectiveCraftAll
				|| chainFinalBulkPlace
				|| (AutoCraftController.isBulkModeEnabled() && !ChainCraftController.isActive() && requestedClicks >= queueLimit);
			boolean repeatDirectPlacement = AutoCraftController.isBulkModeEnabled() || directChainReplay;

			if (ChainCraftController.tryManualSelfReferentialPlacement(minecraft, resolvedItemId)) {
				// Self-referential chain step: inputs were placed client-side so
				// the server cannot pick the step's own output as an ingredient.
			} else if (chainCountedT1) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipeId(), true);
				GridExtractor.begin(selectedRecipe.displayStack(), effectiveRequestedClicks, ingredientSummary);
				ReachCraftingMod.LOGGER.info(
					"[recipe_place] chain_t1 single max place + counted extraction copies={} recipe={} final_step={}",
					effectiveRequestedClicks,
					selectedRecipe.recipeId(),
					ChainCraftController.isRunningFinalStep()
				);
			} else if (chainFinalT2 && GridTopUp.tryStageInsteadOfPlace(minecraft, player, ingredientSummary)) {
				GridExtractor.begin(selectedRecipe.displayStack(), effectiveRequestedClicks, ingredientSummary, true);
				ReachCraftingMod.LOGGER.info(
					"[recipe_place] chain_t2 key-cycle batch copies={} recipe={}",
					effectiveRequestedClicks,
					selectedRecipe.recipeId()
				);
			} else if (GridTopUp.tryStageInsteadOfPlace(minecraft, player, ingredientSummary)) {
				// Unstackable-ingredient bulk: the ingredient ring was built or
				// maintained with ordinary clicks, saving the rationed place
				// packet (see PlaceRecipeBudget / GridTopUp).
			} else if (useBulkPlace) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipeId(), true);
			} else {
				int iterations = repeatDirectPlacement ? Math.max(effectiveRequestedClicks, 1) : 1;
				for (int i = 0; i < iterations; i++) {
					gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipeId(), false);
				}
			}
			AvailableItemSnapshot postPlaceSnapshot = AvailableItemSnapshot.capture(player, screen);
			ReachCraftingMod.LOGGER.info(
				"[recipe_place] post_place direct useBulkPlace={} requestedClicks={} queueLimit={} result={} staged_copies={} grid_reserved={}",
				useBulkPlace,
				requestedClicks,
				queueLimit,
				ContainerUtils.formatStack(player.containerMenu.getSlot(0).getItem()),
				ContainerUtils.currentReservedCraftCopies(postPlaceSnapshot.gridStacks()),
				postPlaceSnapshot.hasReservedGrid()
			);

			if (AutoCraftController.isEnabled()) {
				armBulkAutoCraft(
					recipeId,
					selectedRecipe.recipeId(),
					collection,
					displayStack,
					mouseButton,
					explicitVariantSelection,
					allowNearbyChests,
					effectiveCraftAll,
					requestedClicks,
					nearbyResourcesRequired,
					refillableBulkMaxMode,
					selectedRecipe.displayStack(),
					ingredientSummary
				);
				// A T1 batch owns its result slot: GridExtractor performs the
				// counted extraction and reports through onAutoMoveFinished
				// itself; the blanket auto-move would QUICK_MOVE-craft the
				// whole staged grid past the scheduled copy count.
				if (!GridExtractor.isActive()) {
					ContainerUtils.scheduleAutoMove(selectedRecipe.displayStack());
				}
			}
			if (!ChainCraftController.isActive()) {
				ReachCraftingConfig.get().noteRecentRecipe(selectedRecipe.recipeId());
				RecipeBookChunkedScheduler.onRecentRecipesChanged();
			}
			ReachCraftingModClient.sendDebugChat("Placed recipe: " + outputLabel);
			if (explicitVariantSelection) {
				tryCloseOverlayAfterRelease();
			}
		}
	}

	static int resolveGridMatchedCount(
		Minecraft minecraft,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null || recipeId == null || collection == null) {
			return 0;
		}
		Screen screen = minecraft.gui.screen();
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return 0;
		}

		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(minecraft.player, screen);
		if (!availableItems.hasReservedGrid()) {
			return 0;
		}

		int gridCount = ContainerUtils.currentReservedCraftCopies(availableItems.gridStacks());
		if (gridCount <= 0) {
			return 0;
		}
		if (minecraft.player.containerMenu == null || minecraft.player.containerMenu.slots.isEmpty()) {
			return 0;
		}
		ItemStack currentResult = minecraft.player.containerMenu.getSlot(0).getItem();
		if (currentResult.isEmpty()) {
			return 0;
		}

		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) minecraft.player.getRecipeBook()).getKnown();
		RecipeDisplayEntry entry = findRecipeEntry(collection, knownRecipes, recipeId);
		if (entry == null) {
			return 0;
		}

		ItemStack displayStack = RecipeVariantResolver.resolveDisplayStack(entry.display(), SlotDisplayContext.fromLevel(minecraft.level));
		if (displayStack.isEmpty()) {
			return 0;
		}

		return ItemStack.isSameItemSameComponents(currentResult, displayStack) ? gridCount : 0;
	}

	static ItemStack resolveExpectedOutputStack(
		Minecraft minecraft,
		LocalPlayer player,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection
	) {
		if (displayStack != null && !displayStack.isEmpty()) {
			return displayStack.copy();
		}
		if (minecraft == null || player == null || minecraft.level == null || recipeId == null || collection == null) {
			return ItemStack.EMPTY;
		}

		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, minecraft.gui.screen());
		Map<String, Integer> availableCounts = availableItems.totalCounts();
		if (ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(minecraft.level, minecraft.getCameraEntity(), player.blockInteractionRange());
			availableCounts = AvailableItemSnapshot.mergeCounts(availableCounts, reachableView.aggregateCounts());
		}
		RecipeVariantResolver.Selection selection = RecipeVariantResolver.resolve(
			minecraft,
			player,
			recipeId,
			collection,
			ItemStack.EMPTY,
			explicitVariantSelection,
			false,
			availableItems,
			availableCounts,
			availableCounts,
			false,
			false,
			1
		);
		return selection != null ? selection.displayStack().copy() : ItemStack.EMPTY;
	}

	static int resolveRecipeQueueLimit(
		Minecraft minecraft,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection
	) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null || recipeId == null) {
			return 64;
		}

		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) minecraft.player.getRecipeBook()).getKnown();
		RecipeDisplayEntry entry = findRecipeEntry(collection, knownRecipes, recipeId);
		if (entry == null) {
			return 64;
		}

		ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
		RecipeIngredientSummary ingredientSummary = RecipeIngredientSummary.fromDisplay(entry.display(), context);
		return ingredientSummary.slots().stream()
			.filter(slot -> !slot.isEmpty())
			.mapToInt(RecipeIngredientSummary.IngredientSlot::maxStackSize)
			.min()
			.orElse(64);
	}

	static int bulkRecipeQueueLimit() {
		return BULK_QUEUE_LIMIT;
	}

	private static void armBulkAutoCraft(
		RecipeDisplayId clickedRecipeId,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean explicitVariantSelection,
		boolean allowNearbyChests,
		boolean craftAll,
		int requestedClicks,
		boolean nearbyResourcesRequired,
		boolean refillableBulkMaxMode,
		ItemStack expectedOutput,
		RecipeIngredientSummary ingredientSummary
	) {
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[bulk_arm] clicked_recipe={} resolved_recipe={} requestedClicks={} craftAll={} allowNearby={} nearby_required={} bulk_mode={} explicit_variant={} refillable={} expected_output={}",
			clickedRecipeId,
			recipeId,
			requestedClicks,
			craftAll,
			allowNearbyChests,
			nearbyResourcesRequired,
			AutoCraftController.isBulkModeEnabled(),
			explicitVariantSelection,
			refillableBulkMaxMode,
			ContainerUtils.formatStack(expectedOutput)
		);
		if (!AutoCraftController.isBulkModeEnabled() || requestedClicks <= 1) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[bulk_arm] clear reason={} bulk_mode={} requestedClicks={}",
				!AutoCraftController.isBulkModeEnabled() ? "bulk_mode_disabled" : "requested_clicks_too_small",
				AutoCraftController.isBulkModeEnabled(),
				requestedClicks
			);
			BulkAutoCraftController.clear();
			return;
		}

		boolean keepFamilyContinuation = ReachCraftingConfig.get().bulkVariantSwitching();
		RecipeDisplayId continuationRecipeId = keepFamilyContinuation ? clickedRecipeId : recipeId;
		BulkAutoCraftController.VariantContinuationMode continuationMode;
		if (!keepFamilyContinuation) {
			continuationMode = BulkAutoCraftController.VariantContinuationMode.STRICT_CURRENT_VARIANT;
		} else if (allowNearbyChests
			&& requestedClicks > 1
			&& !explicitVariantSelection
			&& ReachCraftingConfig.get().revolvingCraftHandling() == ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK) {
			continuationMode = BulkAutoCraftController.VariantContinuationMode.UNDECIDED;
		} else {
			continuationMode = BulkAutoCraftController.determineVariantContinuationMode(clickedRecipeId, recipeId, explicitVariantSelection);
		}
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[bulk_arm] start continuation_recipe={} continuation_mode={} keep_family={}",
			continuationRecipeId,
			continuationMode,
			keepFamilyContinuation
		);

		BulkAutoCraftController.startOrUpdate(
			new RecipeBookClickCapture.HeldRecipeAction(
				continuationRecipeId,
				collection,
				displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
				mouseButton,
				explicitVariantSelection
			),
			requestedClicks,
			allowNearbyChests,
			nearbyResourcesRequired,
			refillableBulkMaxMode,
			continuationMode,
			expectedOutput,
			ingredientSummary
		);
	}

	private static Optional<ChainCraftPlan> planChainCraft(
		Minecraft minecraft,
		LocalPlayer player,
		RecipeVariantResolver.Selection selectedRecipe,
		Map<String, Integer> availableCounts,
		boolean allowNearbyChests,
		boolean effectiveCraftAll,
		int requestedClicks,
		int desiredVariantCopies
	) {
		if (effectiveCraftAll) {
			int upperBound = Math.max(requestedClicks, 1);
			// Bulk accepts single-step plans: leftovers can make the final
			// directly craftable for small counts, which would otherwise
			// break the max search's monotonicity (see planMax).
			return ChainCraftPlanner.planMax(
				minecraft,
				player,
				selectedRecipe,
				availableCounts,
				allowNearbyChests,
				upperBound,
				AutoCraftController.isBulkModeEnabled()
			);
		}
		return ChainCraftPlanner.plan(
			minecraft,
			player,
			selectedRecipe,
			availableCounts,
			allowNearbyChests,
			Math.max(desiredVariantCopies, 1)
		);
	}

	/**
	 * Walks the candidate variants in preference order and offers the first one
	 * the planner can actually build. The clicked variant always leads, so a
	 * collection that chains on its own recipe never gets swapped away from.
	 */
	private static Optional<ChainCraftOffer> planChainCraftOffer(
		Minecraft minecraft,
		LocalPlayer player,
		List<RecipeVariantResolver.Selection> candidates,
		Map<String, Integer> availableCounts,
		boolean allowNearbyChests,
		boolean effectiveCraftAll,
		int requestedClicks,
		int desiredVariantCopies
	) {
		for (RecipeVariantResolver.Selection candidate : candidates) {
			Optional<ChainCraftOffer> offer = planChainCraftOfferFor(
				minecraft,
				player,
				candidate,
				availableCounts,
				allowNearbyChests,
				effectiveCraftAll,
				requestedClicks,
				desiredVariantCopies
			);
			if (offer.isPresent()) {
				return offer;
			}
		}
		return Optional.empty();
	}

	private static Optional<ChainCraftOffer> planChainCraftOfferFor(
		Minecraft minecraft,
		LocalPlayer player,
		RecipeVariantResolver.Selection selectedRecipe,
		Map<String, Integer> availableCounts,
		boolean allowNearbyChests,
		boolean effectiveCraftAll,
		int requestedClicks,
		int desiredVariantCopies
	) {
		int requestedRecipeCopies = effectiveCraftAll
			? Math.max(requestedClicks, 1)
			: Math.max(desiredVariantCopies, 1);
		Optional<ChainCraftPlan> exactOrMax = planChainCraft(
			minecraft,
			player,
			selectedRecipe,
			availableCounts,
			allowNearbyChests,
			effectiveCraftAll,
			requestedClicks,
			desiredVariantCopies
		);
		if (exactOrMax.isPresent()) {
			return Optional.of(new ChainCraftOffer(exactOrMax.get(), selectedRecipe, requestedRecipeCopies, effectiveCraftAll));
		}
		// Craft-all already ran planMax, so an empty result is final; only exact
		// count requests benefit from the smaller-count fallback search.
		if (requestedRecipeCopies <= 1 || effectiveCraftAll) {
			return Optional.empty();
		}
		return ChainCraftPlanner.planMax(
			minecraft,
			player,
			selectedRecipe,
			availableCounts,
			allowNearbyChests,
			requestedRecipeCopies - 1,
			AutoCraftController.isBulkModeEnabled()
		).map(plan -> new ChainCraftOffer(plan, selectedRecipe, requestedRecipeCopies, false));
	}

	private static final int CHAIN_TIER_LOCAL = 0;
	private static final int CHAIN_TIER_NEARBY = 1;
	private static final int CHAIN_TIER_UNREACHABLE = 2;

	/**
	 * Chain reachability of one variant, ranked so inventory-only chains beat
	 * ones needing a chest withdrawal -- the smart sort's tiering. Costs two
	 * cache queries, so callers snapshot it rather than asking per comparison.
	 */
	private static int chainTier(RecipeDisplayId recipeId, boolean allowNearbyChests) {
		if (ChainCraftabilityCache.isChainCraftableLocally(recipeId)) {
			return CHAIN_TIER_LOCAL;
		}
		if (allowNearbyChests && ChainCraftabilityCache.isChainCraftable(recipeId)) {
			return CHAIN_TIER_NEARBY;
		}
		return CHAIN_TIER_UNREACHABLE;
	}

	/**
	 * The variants worth asking the chain planner about, best first.
	 *
	 * <p>Siblings are filtered by the same per-recipe chain cache the
	 * collection's indicator is drawn from, so a click can only offer what the
	 * icon shows, and ones chain-craftable from the inventory alone rank above
	 * ones needing a chest withdrawal (the smart sort's tiering). Where the
	 * rotating variant lands in that order depends on the availability
	 * setting -- see the ordering comment below.</p>
	 */
	private static List<RecipeVariantResolver.Selection> chainVariantFallbackCandidates(
		Minecraft minecraft,
		LocalPlayer player,
		RecipeDisplayId clickedRecipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		AvailableItemSnapshot availableItems,
		Map<String, Integer> chainAvailableCounts,
		RecipeVariantResolver.Selection selectedRecipe,
		boolean explicitVariantSelection,
		boolean vanillaShiftClick,
		boolean effectiveCraftAll,
		int desiredVariantCopies,
		boolean allowNearbyChests
	) {
		if (explicitVariantSelection
			|| vanillaShiftClick
			|| collection == null
			|| collection.getRecipes().size() <= 1
			|| availableItems.hasReservedGrid()
			|| ReachCraftingConfig.get().revolvingCraftHandling() == ReachCraftingConfig.RevolvingCraftHandling.SPECIFIC_VARIANT_ONLY) {
			return List.of(selectedRecipe);
		}

		List<RecipeVariantResolver.Selection> variants = RecipeVariantResolver.collectionCandidates(
			minecraft,
			player,
			clickedRecipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			availableItems,
			availableItems.inventoryCounts(),
			availableItems.inventoryCounts(),
			effectiveCraftAll,
			desiredVariantCopies
		);

		// Snapshot each variant's tier ONCE. Every ChainCraftabilityCache
		// query re-walks the whole recipe book and re-hashes the inventory
		// before it reaches its own staleness check (refreshIfNeeded skips the
		// tick cooldown for off-tick callers), so asking it from inside a
		// comparator would pay that O(recipe book) cost O(n log n) times.
		Map<RecipeDisplayId, Integer> chainTiers = new HashMap<>();
		for (RecipeVariantResolver.Selection candidate : variants) {
			chainTiers.put(candidate.recipeId(), chainTier(candidate.recipeId(), allowNearbyChests));
		}
		// Rank on the material that actually decides the variant. The direct
		// count preference cannot: a chain candidate holds none of its own
		// direct ingredient by definition, so preferredTotalCount reads zero
		// for every one of them and the order collapses to a stable-but-
		// arbitrary tiebreak. Variants the ranking has no signal for sort
		// behind the scored ones in BOTH directions -- under Lowest Total an
		// unscored variant would otherwise win on a zero it never earned.
		Map<RecipeDisplayId, Integer> variantScores =
			ChainVariantRanking.scoreVariants(variants, chainAvailableCounts);
		boolean lowestFirst = ReachCraftingConfig.get().countPreference()
			== IngredientPlanning.CountPreference.LOWEST_TOTAL;
		Comparator<RecipeVariantResolver.Selection> byScore =
			Comparator.comparingInt((RecipeVariantResolver.Selection candidate) ->
				variantScores.getOrDefault(candidate.recipeId(), 0));
		Comparator<RecipeVariantResolver.Selection> chainOrder = Comparator
			.comparingInt((RecipeVariantResolver.Selection candidate) ->
				chainTiers.getOrDefault(candidate.recipeId(), CHAIN_TIER_UNREACHABLE))
			.thenComparingInt(candidate -> variantScores.containsKey(candidate.recipeId()) ? 0 : 1)
			.thenComparing(lowestFirst ? byScore : byScore.reversed())
			.thenComparing(RecipeVariantResolver.preferenceOrder());

		List<RecipeVariantResolver.Selection> siblings = variants.stream()
			.filter(candidate -> !candidate.recipeId().equals(selectedRecipe.recipeId()))
			.filter(candidate -> chainTiers.getOrDefault(candidate.recipeId(), CHAIN_TIER_UNREACHABLE)
				!= CHAIN_TIER_UNREACHABLE)
			.sorted(chainOrder)
			.toList();
		if (siblings.isEmpty()) {
			return List.of(selectedRecipe);
		}

		// The two fallback settings disagree about the rotating variant, so
		// the chain path has to disagree with them too. "Current Variant with
		// Availability Fallback" gives it first refusal -- chain it if it can
		// be chained, siblings only after that. "Always Based On Available"
		// gives it no privilege at all, so it gets ranked alongside them.
		// Either way a variant the resolver already found direct stock for
		// keeps the lead: resolve() picked it under this same count
		// preference, and a chain-only sibling should not displace a variant
		// that is already partly craftable outright.
		boolean preferClickedVariant = selectedRecipe.copiesAvailable() > 0
			|| ReachCraftingConfig.get().revolvingCraftHandling()
				== ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK;

		List<RecipeVariantResolver.Selection> candidates = new ArrayList<>();
		candidates.add(selectedRecipe);
		candidates.addAll(siblings);
		if (!preferClickedVariant) {
			candidates.sort(chainOrder);
		}
		ReachCraftingMod.LOGGER.info(
			"[chain_variant_fallback] clicked_recipe={} selected_recipe={} handling={} preference={} prefer_clicked={} order={}",
			clickedRecipeId,
			selectedRecipe.recipeId(),
			ReachCraftingConfig.get().revolvingCraftHandling(),
			ReachCraftingConfig.get().countPreference(),
			preferClickedVariant,
			candidates.stream()
				.map(candidate -> candidate.outputItemId()
					+ "(tier=" + chainTiers.getOrDefault(candidate.recipeId(), CHAIN_TIER_UNREACHABLE)
					+ " score=" + (variantScores.containsKey(candidate.recipeId())
						? String.valueOf(variantScores.get(candidate.recipeId()))
						: "none")
					+ ")")
				.toList()
		);
		return List.copyOf(candidates);
	}

	private static String missingMessageFor(
		RecipeVariantResolver.Selection selection,
		Map<String, Integer> availableCounts,
		AvailableItemSnapshot availableItems,
		boolean effectiveCraftAll,
		int desiredVariantCopies
	) {
		RecipeDeficitReport report = effectiveCraftAll
			? RecipeDeficitReport.from(selection.ingredientSummary(), availableCounts, availableItems.gridStacks(), true)
			: RecipeDeficitReport.from(selection.ingredientSummary(), availableCounts, availableItems.gridStacks(), desiredVariantCopies);
		return report.hasMissingIngredients() ? "Missing: " + report.compactMissingSummary() : "";
	}

	private record ChainCraftOffer(
		ChainCraftPlan plan,
		RecipeVariantResolver.Selection selection,
		int requestedRecipeCopies,
		boolean maxRequest
	) {
	}

	private static boolean areNearbyResourcesRequired(
		boolean craftAll,
		int requestedClicks,
		int localPossibleCopies
	) {
		// Ctrl+Shift bulk should always stay on the nearby/staging path so it can
		// immediately opt into direct eject and continue pulling more resources.
		if (craftAll) {
			return true;
		}

		// For finite Ctrl requests, only use the conservative local-output path
		// when the current inventory can already satisfy the whole request.
		return localPossibleCopies < Math.max(requestedClicks, 1);
	}

	private static int effectiveRequestedClicks(boolean craftAll, int requestedClicks, int desiredVariantCopies) {
		return craftAll ? Math.max(desiredVariantCopies, 1) : Math.max(requestedClicks, 1);
	}

	private static RecipeDisplayEntry findRecipeEntry(
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes,
		RecipeDisplayId recipeId
	) {
		if (collection != null) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				if (entry.id().equals(recipeId)) {
					return entry;
				}
			}
		}
		return knownRecipes.get(recipeId);
	}

	static void tryCloseOverlayAfterRelease() {
		if (!ReachCraftingConfig.get().reachCraftCloseOverlayAfterRelease()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!(minecraft.gui.screen() instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) componentAccessor.getRecipeBookPage();
		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		if (overlay != null && overlay.isVisible()) {
			((com.reachcrafting.client.mixin.OverlayRecipeComponentAccessor) overlay).setIsVisible(false);
		}
	}
}
