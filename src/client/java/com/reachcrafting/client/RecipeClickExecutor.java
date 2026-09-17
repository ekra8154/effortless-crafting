package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
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
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

final class RecipeClickExecutor {
	private static final int BULK_QUEUE_LIMIT = 10_000;

	private RecipeClickExecutor() {
	}

	static void executeRecipeButtonClick(
		Minecraft minecraft,
		LocalPlayer player,
		Screen screen,
		Recipe<?> recipe,
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
		boolean retrievalDone,
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
			state.pendingHeldRecipe() != null ? state.pendingHeldRecipe().action().recipeId() + "x" + state.pendingHeldRecipe().clickCount() : "<none>",
			state.replayBatch() != null ? state.replayBatch().action().recipeId() + "x" + state.replayBatch().remainingClicks() : "<none>"
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
			recipe,
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
			ReachCraftingMod.LOGGER.warn("[recipe_click] missing recipe for id={}", recipe.getId());
			return;
		}
		boolean craftable = collection != null && collection.isCraftable(selectedRecipe.recipe());
		int recipeIndex = collection != null ? collection.getRecipes().indexOf(selectedRecipe.recipe()) : -1;
		if (!selectedRecipe.recipeId().equals(recipe.getId())) {
			ReachCraftingMod.LOGGER.debug(
				"[recipe_variant] clicked_id={} selected_id={} mode={} output={}",
				recipe.getId(),
				selectedRecipe.recipeId(),
				ReachCraftingConfig.get().revolvingCraftHandling().name().toLowerCase(),
				selectedRecipe.outputLabel()
			);
		}

		ItemStack resolvedDisplayStack = selectedRecipe.displayStack().copy();
		RecipeIngredientSummary ingredientSummary = selectedRecipe.ingredientSummary();
		Map<String, Integer> localAvailableCounts = availableItems.totalCounts();
		Map<String, Integer> availableCounts = availableItems.totalCounts();
		// Chain planning must see ALL nearby items (intermediates' base materials), not just the
		// final recipe's accepted ingredients.
		Map<String, Integer> chainAvailableCounts = availableCounts;
		boolean nearbyCacheIncomplete = false;
		if (allowNearbyChests && ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(minecraft.level, minecraft.getCameraEntity(), reachDistance(minecraft, player));
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
		String resolvedItemId = BuiltInRegistries.ITEM.getKey(resolvedDisplayStack.getItem()).toString();
		String outputLabel = resolvedItemId + " x" + resolvedDisplayStack.getCount();
		if (ExistingOutputRetrievalController.isEnabled()) {
			// Retrieval Mode: the click pulls the resolved output out of nearby
			// storage instead of crafting it. This version has no synthetic
			// recipe-book entries, so only outputs that some recipe makes can
			// ever be clicked here.
			if (!ReachCraftingConfig.get().enableNearbyContainerUsage()) {
				ReachCraftingModClient.sendChat("Nearby container usage is disabled.");
				return;
			}
			NearbyContainerDryRun.startExistingOutputRetrieval(new ExistingOutputRetrievalRequest(
				recipe,
				selectedRecipe.recipe(),
				collection,
				explicitVariantSelection,
				resolvedItemId,
				outputLabel,
				resolvedDisplayStack,
				craftAll ? Math.max(desiredVariantCopies, 1) : Math.max(requestedClicks, 1)
			));
			if (explicitVariantSelection) {
				tryCloseOverlayAfterRelease();
			}
			return;
		}

		// Retrieve-first step (existingOutputHandling). Only on a click that
		// already allows nearby containers, never on the replay it schedules
		// for the remainder, and never underneath a running session.
		ReachCraftingConfig.ExistingOutputHandling outputHandling = ReachCraftingConfig.get().existingOutputHandling();
		if (outputHandling != ReachCraftingConfig.ExistingOutputHandling.CRAFT_ONLY
			&& !retrievalDone
			&& allowNearbyChests
			&& ReachCraftingConfig.get().enableNearbyContainerUsage()
			&& ReachCraftingConfig.get().cacheContainersForFasterSearch()
			&& !ChainCraftController.isActive()
			&& !BulkAutoCraftController.isActive()
			&& !BulkChainCraftController.isActive()
			&& !RetrieveThenCraftController.isActive()) {
			NearbyContainerCache.ReachableView outputView = NearbyContainerCache.getReachableView(minecraft.level, minecraft.getCameraEntity(), reachDistance(minecraft, player));
			Map<String, Integer> nearbyTotals = outputView.aggregateCounts();
			// The variant to RETRIEVE is chosen by which output is nearby (per
			// the revolving-variant setting), not by which ingredients the
			// craft resolver found: dark oak stairs requested with oak stairs
			// in the chest retrieves oak stairs when fallback is allowed.
			RecipeVariantResolver.Selection retrievalSelection = RecipeVariantResolver.resolveRetrievalVariant(
				minecraft,
				player,
				recipe,
				collection,
				displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
				explicitVariantSelection,
				true,
				AvailableItemSnapshot.empty(),
				nearbyTotals,
				nearbyTotals,
				craftAll,
				false,
				desiredVariantCopies
			);
			if (retrievalSelection == null || retrievalSelection.displayStack().isEmpty()) {
				retrievalSelection = selectedRecipe;
			}
			ItemStack retrieveStack = retrievalSelection.displayStack().copy();
			String retrieveItemId = BuiltInRegistries.ITEM.getKey(retrieveStack.getItem()).toString();
			int outputPerCraft = Math.max(retrieveStack.getCount(), 1);
			int targetItems = craftAll ? bulkRecipeQueueLimit() : Math.max(requestedClicks, 1) * outputPerCraft;
			int nearbyOutput = nearbyTotals.getOrDefault(retrieveItemId, 0);
			boolean cacheComplete = outputView.snapshotsByKey().size() >= outputView.nearestAccessByKey().size();
			if (nearbyOutput <= 0 && cacheComplete) {
				RetrieveThenCraftController.logNoneNearby(retrieveItemId, targetItems, outputHandling);
			} else {
				// What the materials can DIRECTLY make, so the prompt never
				// offers a remainder it will not deliver. Deliberately not a
				// chain estimate: chain crafting asks its own question with its
				// own number afterwards, and it is not even eligible without an
				// auto craft request, so counting it here would promise crafts
				// this click can never perform.
				int craftableCopies = deficitReport.possibleCopies();
				RetrieveThenCraftController.start(
					new RetrieveThenCraftController.FollowUp(
						new RecipeBookClickCapture.HeldRecipeAction(
							recipe,
							recipe.getId(),
							collection,
							displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
							mouseButton,
							explicitVariantSelection
						),
						requestedClicks,
						allowNearbyChests,
						craftAll,
						refillableBulkMaxMode,
						autoCraftRequested,
						outputPerCraft,
						targetItems,
						craftableCopies,
						retrieveItemId,
						retrieveStack,
						outputHandling,
						retrievalSelection.recipe(),
						!AutoCraftController.isBulkModeEnabled(),
						false
					)
				);
				if (explicitVariantSelection) {
					tryCloseOverlayAfterRelease();
				}
				return;
			}
		} else {
			ReachCraftingMod.diag(
				"[retrieve_then_craft] rtc_skipped reason={} item={} clicks={}",
				outputHandling == ReachCraftingConfig.ExistingOutputHandling.CRAFT_ONLY ? "craft_only"
					: retrievalDone ? "remainder_replay"
					: !allowNearbyChests ? "no_ctrl"
					: "session_active",
				resolvedItemId,
				requestedClicks
			);
		}

		ReachCraftingMod.LOGGER.debug(
			"[recipe_click] screen={} button={} idx={} craftable={} shift={} ctrl={} output={}",
			screenKind,
			mouseButton,
			recipeIndex,
			craftable,
			craftAll,
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

		RecipeDeficitReport localDeficitReport = effectiveCraftAll
			? RecipeDeficitReport.from(ingredientSummary, localAvailableCounts, availableItems.gridStacks(), true)
			: RecipeDeficitReport.from(ingredientSummary, localAvailableCounts, availableItems.gridStacks(), desiredVariantCopies);

		int effectiveRequestedClicks = refillableBulkMaxMode
			? Math.max(requestedClicks, 1)
			: effectiveCraftAll
				? deficitReport.possibleCopies()
				: requestedClicks;

		// Output variant switching for a plain (non-bulk) auto craft. When the
		// resolved variant cannot cover the request, craft what it can and let
		// OutputVariantContinuationController replay the remainder; that pass
		// resolves the next variant. A continuation pass that finds no
		// craftable variant ends the run quietly (the player got the max).
		boolean continuationPass = OutputVariantContinuationController.consumeFiring();
		boolean variantContinuationEligible = !AutoCraftController.isBulkModeEnabled()
			&& !ChainCraftController.isActive()
			&& !BulkChainCraftController.isActive()
			&& !BulkAutoCraftController.isActive()
			&& (autoCraftRequested || AutoCraftController.isEnabled())
			&& ReachCraftingConfig.get().outputVariantSwitching()
			&& !explicitVariantSelection
			&& collection != null
			&& collection.getRecipes().size() > 1
			&& BulkAutoCraftController.determineVariantContinuationMode(recipe.getId(), selectedRecipe.recipeId(), explicitVariantSelection)
				== BulkAutoCraftController.VariantContinuationMode.FAMILY_FALLBACK;
		RecipeBookClickCapture.HeldRecipeAction continuationAction = new RecipeBookClickCapture.HeldRecipeAction(
			recipe,
			recipe.getId(),
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			mouseButton,
			explicitVariantSelection
		);
		// A continuation pass with nothing directly craftable may still chain
		// craft the next variant (logs -> planks -> stairs); the run only ends
		// once the chain offer below comes up empty too.
		boolean continuationExhausted = false;
		if (variantContinuationEligible) {
			int possibleCopies = deficitReport.possibleCopies();
			if (possibleCopies <= 0) {
				continuationExhausted = continuationPass;
			} else if (effectiveCraftAll) {
				OutputVariantContinuationController.arm(continuationAction, -1, true, allowNearbyChests, autoCraftRequested, continuationPass, resolvedItemId, possibleCopies);
			} else if (possibleCopies < effectiveRequestedClicks) {
				OutputVariantContinuationController.arm(continuationAction, effectiveRequestedClicks - possibleCopies, false, allowNearbyChests, autoCraftRequested, continuationPass, resolvedItemId, possibleCopies);
				// Re-enter with the count this variant can actually make, so
				// the craft completes cleanly instead of reporting a shortfall.
				executeRecipeButtonClick(
					minecraft,
					player,
					screen,
					recipe,
					collection,
					displayStack,
					mouseButton,
					craftAll,
					allowNearbyChests,
					forceDryRun,
					explicitVariantSelection,
					possibleCopies,
					refillableBulkMaxMode,
					autoCraftRequested,
					true,
					state
				);
				return;
			} else if (continuationPass) {
				// Last step: this variant covers the rest.
				OutputVariantContinuationController.end("satisfied", resolvedItemId);
			}
		}

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
		// Brought up to date first: the cache refreshes asynchronously off the
		// tick, and the first click after opening the table (or after a chest
		// scan moved the nearby counts) otherwise reads the previous state's
		// sets, which hid every chain sibling from the variant fallback below.
		ChainCraftabilityCache.refreshNow(minecraft);
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
				recipe,
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
			ReachCraftingMod.diag(
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
				ReachCraftingMod.diag(
					"[chain_plan] available clicked_recipe={} recipe={} requested={} planned={} steps={} allow_nearby={} bulk_mode={}",
					selectedRecipe.recipeId(),
					chainSelection.recipeId(),
					chainOffer.get().requestedRecipeCopies(),
					chainPlan.finalRecipeCopies(),
					chainPlan.steps().size(),
					allowNearbyChests,
					AutoCraftController.isBulkModeEnabled()
				);
				// Output variant switching across chain crafts: the gate is
				// bulk's rule applied to the variant the CHAIN chose (the
				// direct resolver kept the clicked one because nothing was
				// directly craftable). The prompt reports what every family
				// variant can cover together; after this variant's chain
				// finishes, the continuation replays the click and the next
				// variant chains without asking again.
				boolean chainVariantSwitching = ReachCraftingConfig.get().outputVariantSwitching()
					&& !explicitVariantSelection
					&& collection != null
					&& collection.getRecipes().size() > 1
					&& BulkAutoCraftController.determineVariantContinuationMode(recipe.getId(), chainSelection.recipeId(), explicitVariantSelection)
						== BulkAutoCraftController.VariantContinuationMode.FAMILY_FALLBACK;
				if (AutoCraftController.isBulkModeEnabled()) {
					if (!ReachCraftingConfig.get().enableBulkChainCrafting()) {
						ReachCraftingModClient.sendChat(net.minecraft.network.chat.Component.translatable("message.reachcrafting.chain_crafting.bulk_unsupported").getString());
						return;
					}
					BulkChainCraftController.VariantFamily bulkFamily = null;
					int bulkVariantTotal = chainPlan.finalRecipeCopies();
					if (chainVariantSwitching) {
						boolean bulkMax = chainOffer.get().maxRequest();
						int wanted = bulkMax
							? bulkRecipeQueueLimit()
							: chainOffer.get().requestedRecipeCopies() - chainPlan.finalRecipeCopies();
						if (wanted > 0) {
							bulkVariantTotal += otherVariantChainCopies(
								minecraft,
								player,
								chainVariantCandidates,
								chainSelection,
								chainAvailableCounts,
								allowNearbyChests,
								wanted
							);
						}
						bulkFamily = new BulkChainCraftController.VariantFamily(
							recipe,
							collection,
							displayStack != null ? displayStack.copy() : ItemStack.EMPTY
						);
					}
					ChainCraftPopupController.handleBulkChainPlan(
						chainPlan,
						chainSelection,
						allowNearbyChests,
						chainOffer.get().requestedRecipeCopies(),
						chainOffer.get().maxRequest(),
						chainMissingMessage,
						bulkFamily,
						bulkVariantTotal
					);
					return;
				}
				int popupRequestedCopies = chainOffer.get().maxRequest()
					? chainPlan.finalRecipeCopies()
					: chainOffer.get().requestedRecipeCopies();
				if (chainVariantSwitching) {
					boolean chainMaxRequest = chainOffer.get().maxRequest();
					int planCopies = chainPlan.finalRecipeCopies();
					int requestedCopies = chainOffer.get().requestedRecipeCopies();
					int remainingAfterPlan = chainMaxRequest ? -1 : requestedCopies - planCopies;
					boolean moreWanted = chainMaxRequest || remainingAfterPlan > 0;
					String chainItemId = BuiltInRegistries.ITEM.getKey(chainPlan.finalOutput().getItem()).toString();
					boolean thisPassIsContinuation = continuationPass;
					Runnable armContinuation = () -> {
						if (moreWanted) {
							OutputVariantContinuationController.arm(continuationAction, remainingAfterPlan, chainMaxRequest, allowNearbyChests, autoCraftRequested, thisPassIsContinuation, chainItemId, planCopies);
						} else if (thisPassIsContinuation) {
							OutputVariantContinuationController.end("satisfied", chainItemId);
						}
					};
					if (continuationPass) {
						armContinuation.run();
						ReachCraftingModClient.sendChat("Output variant switching: chain crafting "
							+ (planCopies * Math.max(chainPlan.finalOutput().getCount(), 1)) + " "
							+ chainPlan.finalOutput().getHoverName().getString() + ".");
						ChainCraftController.start(chainPlan);
						return;
					}
					int variantTotalCopies = planCopies;
					if (moreWanted) {
						// chainAvailableCounts, not availableCounts: the latter only
						// holds the CLICKED recipe's ingredients, so every other
						// variant planned against it came up empty and the prompt
						// fell back to the single-variant wording.
						variantTotalCopies += otherVariantChainCopies(
							minecraft,
							player,
							chainVariantCandidates,
							chainSelection,
							chainAvailableCounts,
							allowNearbyChests,
							chainMaxRequest ? bulkRecipeQueueLimit() : remainingAfterPlan
						);
						if (!chainMaxRequest) {
							variantTotalCopies = Math.min(variantTotalCopies, requestedCopies);
						}
					}
					ChainCraftPopupController.handlePlanWithVariantSwitching(chainPlan, popupRequestedCopies, chainMissingMessage, variantTotalCopies, armContinuation);
					return;
				}
				ChainCraftPopupController.handlePlan(chainPlan, popupRequestedCopies, false, chainMissingMessage);
				return;
			}
			if (continuationExhausted) {
				OutputVariantContinuationController.end("no_viable_variant", resolvedItemId);
				ReachCraftingModClient.sendDebugChat("Output variant switching: no craftable variant left.");
				return;
			}
			ReachCraftingMod.diag(
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
						selectedRecipe.recipe(),
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
			ReachCraftingMod.diag(
				"[bulk_warmup] cold_cache_full_scan_first recipe={} clicks={} craft_all={} refillable={}",
				selectedRecipe.recipeId(),
				effectiveRequestedClicks,
				effectiveCraftAll,
				refillableBulkMaxMode
			);
			ChainCraftController.armRetryAfterNearbyWarmup(
				new RecipeBookClickCapture.HeldRecipeAction(
					selectedRecipe.recipe(),
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
				recipe,
				recipe.getId(),
				selectedRecipe.recipe(),
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
							ReachCraftingMod.diag(
								"[recipe_place] chain_t2 key-cycle batch copies={} recipe={} (nearby path)",
								effectiveRequestedClicks,
								selectedRecipe.recipeId()
							);
						}
					} else {
						ReachCraftingMod.diag("[recipe_place] handlePlaceRecipe(shift=true) from RecipeClickExecutor NEARBY path");
						minecraft.gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipe(), true);
					}
				}
				AvailableItemSnapshot postPlaceSnapshot = AvailableItemSnapshot.capture(player, screen);
				ReachCraftingMod.diag(
					"[recipe_place] post_place nearby result={} staged_copies={} requestedClicks={} queueLimit={} grid_reserved={}",
					ContainerUtils.formatStack(player.containerMenu.getSlot(0).getItem()),
					ContainerUtils.currentReservedCraftCopies(postPlaceSnapshot.gridStacks()),
					requestedClicks,
					resolveRecipeQueueLimit(minecraft, selectedRecipe.recipe(), collection),
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
				ReachCraftingMod.diag(
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
				ReachCraftingMod.diag("[recipe_place] grid_topup ring cycle, dry-run bypassed");
				if (AutoCraftController.isEnabled()
					&& !tryArmFlatBulkKeyCycle(player, selectedRecipe, effectiveRequestedClicks, ingredientSummary)) {
					ContainerUtils.scheduleAutoMove(selectedRecipe.displayStack());
				}
				return;
			}

			if (!deficitReport.hasMissingIngredients() && availableItems.hasReservedGrid()) {
				if (NearbyContainerDryRun.tryExpandReservedGrid(
					selectedRecipe.recipeId(),
					selectedRecipe.recipe(),
					collection,
					explicitVariantSelection,
					recipeIndex,
					outputLabel,
					ingredientSummary,
					availableItems,
					craftAll,
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
				selectedRecipe.recipe(),
				collection,
				explicitVariantSelection,
				recipeIndex,
				outputLabel,
				ingredientSummary,
				availableItems,
				craftAll,
				effectiveRequestedClicks,
				allowNearbyChests
			);
			return;
		}

		MultiPlayerGameMode gameMode = minecraft.gameMode;
		if (gameMode != null) {
			int queueLimit = resolveRecipeQueueLimit(minecraft, selectedRecipe.recipe(), collection);
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
			} else if (ChainCraftController.tryManualLastResortPlacement(minecraft, resolvedItemId)) {
				// Mixed slot (any oak log): inputs placed client-side so the
				// server cannot substitute stripped logs for the planned ones.
			} else if (chainCountedT1) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipe(), true);
				GridExtractor.begin(selectedRecipe.displayStack(), effectiveRequestedClicks, ingredientSummary);
				ReachCraftingMod.diag(
					"[recipe_place] chain_t1 single max place + counted extraction copies={} recipe={} final_step={}",
					effectiveRequestedClicks,
					selectedRecipe.recipeId(),
					ChainCraftController.isRunningFinalStep()
				);
			} else if (chainFinalT2 && GridTopUp.tryStageInsteadOfPlace(minecraft, player, ingredientSummary)) {
				GridExtractor.begin(selectedRecipe.displayStack(), effectiveRequestedClicks, ingredientSummary, true);
				ReachCraftingMod.diag(
					"[recipe_place] chain_t2 key-cycle batch copies={} recipe={}",
					effectiveRequestedClicks,
					selectedRecipe.recipeId()
				);
			} else if (GridTopUp.tryStageInsteadOfPlace(minecraft, player, ingredientSummary)) {
				// Unstackable-ingredient bulk: the ingredient ring was built or
				// maintained with ordinary clicks, saving the rationed place
				// packet (see PlaceRecipeBudget / GridTopUp).
				tryArmFlatBulkKeyCycle(player, selectedRecipe, effectiveRequestedClicks, ingredientSummary);
			} else if (useBulkPlace) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipe(), true);
			} else {
				int iterations = repeatDirectPlacement ? Math.max(effectiveRequestedClicks, 1) : 1;
				if (iterations > 1) {
					// The one placement branch with no fast-path win: N single
					// placements, which a server processes asynchronously and
					// can race -- that is how a sticky piston batch landed 14
					// of 22 and then gave up. It was also the only SILENT
					// branch, so a recipe falling in here was detectable just
					// by feeling the stutter. Name the disqualifier instead,
					// so the next one reports itself.
					ReachCraftingMod.diag(
						"[recipe_place] slow_repeat_placement copies={} recipe={} reason={} chain={} final_step={} bulk={} summary={}",
						iterations,
						selectedRecipe.recipeId(),
						slowPlacementReason(minecraft, ingredientSummary, effectiveRequestedClicks),
						ChainCraftController.isActive(),
						ChainCraftController.isRunningFinalStep(),
						AutoCraftController.isBulkModeEnabled(),
						ingredientSummary.compactSummary()
					);
				}
				for (int i = 0; i < iterations; i++) {
					gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipe(), false);
				}
			}
			AvailableItemSnapshot postPlaceSnapshot = AvailableItemSnapshot.capture(player, screen);
			ReachCraftingMod.diag(
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
					recipe,
					recipe.getId(),
					selectedRecipe.recipe(),
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
		Recipe<?> recipe,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null || recipe == null || collection == null) {
			return 0;
		}
		Screen screen = minecraft.screen;
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

		ItemStack displayStack = resolveExpectedOutputStack(
			minecraft,
			minecraft.player,
			recipe,
			collection,
			ItemStack.EMPTY,
			explicitVariantSelection
		);
		if (displayStack.isEmpty()) {
			return 0;
		}

		return ItemStack.isSameItemSameTags(currentResult, displayStack) ? gridCount : 0;
	}

	static ItemStack resolveExpectedOutputStack(
		Minecraft minecraft,
		LocalPlayer player,
		Recipe<?> recipe,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection
	) {
		if (displayStack != null && !displayStack.isEmpty()) {
			return displayStack.copy();
		}
		if (minecraft == null || player == null || minecraft.level == null || recipe == null || collection == null) {
			return ItemStack.EMPTY;
		}

		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, minecraft.screen);
		Map<String, Integer> availableCounts = availableItems.totalCounts();
		if (ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(minecraft.level, minecraft.getCameraEntity(), reachDistance(minecraft, player));
			availableCounts = AvailableItemSnapshot.mergeCounts(availableCounts, reachableView.aggregateCounts());
		}
		RecipeVariantResolver.Selection selection = RecipeVariantResolver.resolve(
			minecraft,
			player,
			recipe,
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

	/**
	 * Flat bulk on a staged ring used to craft one copy per cycle: re-seat
	 * the key item, wait to see the result, shift-click it, organize, settle,
	 * replay - eight to ten ticks a dispenser. The chain finals already run
	 * such a batch as a GridExtractor key-cycle (insert next key, take
	 * result, repeat, several clicks a tick); this gives flat bulk the same
	 * loop. Outputs are banked, so the batch needs somewhere to put them;
	 * with no room the auto-move path keeps its eject-when-full handling.
	 */
	private static boolean tryArmFlatBulkKeyCycle(
		LocalPlayer player,
		RecipeVariantResolver.Selection selectedRecipe,
		int copies,
		RecipeIngredientSummary ingredientSummary
	) {
		if (copies <= 1
			|| GridExtractor.isActive()
			|| !BulkAutoCraftController.isActive()
			|| ChainCraftController.isActive()
			|| BulkChainCraftController.isActive()
			|| !GridTopUp.isKeyCycleEligible(ingredientSummary)
			|| player == null
			|| player.containerMenu == null
			|| !player.containerMenu.getCarried().isEmpty()) {
			return false;
		}
		ItemStack output = selectedRecipe.displayStack();
		String outputId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
		if (MenuTransferHelper.findPlayerDestinationSlot(player, player.containerMenu, outputId) == null) {
			return false;
		}
		GridExtractor.begin(output, copies, ingredientSummary, true);
		ReachCraftingMod.diag(
			"[recipe_place] flat_bulk key-cycle batch copies={} recipe={}",
			copies,
			selectedRecipe.recipeId()
		);
		return true;
	}

	static int resolveRecipeQueueLimit(
		Minecraft minecraft,
		Recipe<?> recipe,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection
	) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null || recipe == null) {
			return 64;
		}

		int craftingGridSlotCount = minecraft.screen instanceof InventoryScreen ? 4 : 9;
		RecipeIngredientSummary ingredientSummary = RecipeIngredientSummary.fromRecipe(recipe, craftingGridSlotCount);
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
		Recipe<?> clickedRecipe,
		net.minecraft.resources.ResourceLocation clickedRecipeId,
		Recipe<?> recipe,
		net.minecraft.resources.ResourceLocation recipeId,
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
		com.reachcrafting.ReachCraftingMod.diag(
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
			com.reachcrafting.ReachCraftingMod.diag(
				"[bulk_arm] clear reason={} bulk_mode={} requestedClicks={}",
				!AutoCraftController.isBulkModeEnabled() ? "bulk_mode_disabled" : "requested_clicks_too_small",
				AutoCraftController.isBulkModeEnabled(),
				requestedClicks
			);
			BulkAutoCraftController.clear();
			return;
		}

		boolean keepFamilyContinuation = ReachCraftingConfig.get().outputVariantSwitching();
		net.minecraft.resources.ResourceLocation continuationRecipeId = keepFamilyContinuation ? clickedRecipeId : recipeId;
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
		com.reachcrafting.ReachCraftingMod.diag(
			"[bulk_arm] start continuation_recipe={} continuation_mode={} keep_family={}",
			continuationRecipeId,
			continuationMode,
			keepFamilyContinuation
		);

		BulkAutoCraftController.startOrUpdate(
			new RecipeBookClickCapture.HeldRecipeAction(
				clickedRecipe,
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

	private static java.util.Optional<ChainCraftPlan> planChainCraft(
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
	private static java.util.Optional<ChainCraftOffer> planChainCraftOffer(
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
		java.util.Optional<ChainCraftPlan> exactOrMax = planChainCraft(
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

	/**
	 * Which gate sent this batch to repeated single placements, checked in the
	 * order chainCountedT1 evaluates them so the slug names the FIRST reason
	 * rather than an incidental one.
	 *
	 * <p>"unlimited_budget" is singleplayer and benign -- the integrated server
	 * applies the placements synchronously, so the repeat cannot race itself.
	 * The rest are worth acting on.</p>
	 */
	private static String slowPlacementReason(
		Minecraft minecraft,
		RecipeIngredientSummary ingredientSummary,
		int effectiveRequestedClicks
	) {
		if (PlaceRecipeBudget.isUnlimited(minecraft)) {
			return "unlimited_budget";
		}
		String ineligible = GridExtractor.describeIneligibility(ingredientSummary);
		if (ineligible != null) {
			return ineligible;
		}
		if (!ChainCraftController.isActive()) {
			return "no_chain_session";
		}
		if (effectiveRequestedClicks <= 1) {
			return "single_copy";
		}
		return "unclassified";
	}

	private static final int CHAIN_TIER_LOCAL = 0;
	private static final int CHAIN_TIER_NEARBY = 1;
	private static final int CHAIN_TIER_UNREACHABLE = 2;

	/**
	 * Chain reachability of one variant, ranked so inventory-only chains beat
	 * ones needing a chest withdrawal -- the smart sort's tiering. Costs two
	 * cache queries, so callers snapshot it rather than asking per comparison.
	 */
	private static int chainTier(net.minecraft.resources.ResourceLocation recipeId, boolean allowNearbyChests) {
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
		Recipe<?> clickedRecipe,
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
			clickedRecipe,
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
		Map<net.minecraft.resources.ResourceLocation, Integer> chainTiers = new HashMap<>();
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
		Map<net.minecraft.resources.ResourceLocation, Integer> variantScores =
			ChainVariantRanking.scoreVariants(variants, chainAvailableCounts);
		// "Prefer Non-Stripped Logs" spends stripped logs last within a recipe;
		// the variant ranking must agree, or a family tie (576 stripped spruce
		// vs 576 oak logs) breaks toward whichever variant sorted first and
		// the stripped logs get burned while ordinary ones sit there. Score
		// each variant on ordinary material too, and rank on that first; a
		// variant whose only material is last-resort sorts behind the rest.
		java.util.Set<String> lastResort = LastResortIngredients.activeCategories(ReachCraftingConfig.get());
		Map<net.minecraft.resources.ResourceLocation, Integer> ordinaryScores = lastResort.isEmpty()
			? variantScores
			: ChainVariantRanking.scoreVariants(variants, withoutLastResort(chainAvailableCounts, lastResort));
		boolean lowestFirst = ReachCraftingConfig.get().countPreference()
			== IngredientPlanning.CountPreference.LOWEST_TOTAL;
		Comparator<RecipeVariantResolver.Selection> byScore =
			Comparator.comparingInt((RecipeVariantResolver.Selection candidate) ->
				variantScores.getOrDefault(candidate.recipeId(), 0));
		Comparator<RecipeVariantResolver.Selection> byOrdinaryScore =
			Comparator.comparingInt((RecipeVariantResolver.Selection candidate) ->
				ordinaryScores.getOrDefault(candidate.recipeId(), 0));
		Comparator<RecipeVariantResolver.Selection> chainOrder = Comparator
			.comparingInt((RecipeVariantResolver.Selection candidate) ->
				chainTiers.getOrDefault(candidate.recipeId(), CHAIN_TIER_UNREACHABLE))
			.thenComparingInt(candidate -> variantScores.containsKey(candidate.recipeId()) ? 0 : 1)
			.thenComparingInt(candidate -> ordinaryScores.getOrDefault(candidate.recipeId(), 0) > 0
				|| variantScores.getOrDefault(candidate.recipeId(), 0) <= 0 ? 0 : 1)
			.thenComparing(lowestFirst ? byOrdinaryScore : byOrdinaryScore.reversed())
			.thenComparing(lowestFirst ? byScore : byScore.reversed())
			.thenComparing(RecipeVariantResolver.preferenceOrder());

		// A sibling whose only material is last-resort (stripped logs) is not
		// an automatic fallback at all: those get spent only when the player
		// asked for that variant and it has nothing else. The clicked/selected
		// variant is kept regardless, since that IS the explicit request.
		List<RecipeVariantResolver.Selection> siblings = variants.stream()
			.filter(candidate -> !candidate.recipeId().equals(selectedRecipe.recipeId()))
			.filter(candidate -> chainTiers.getOrDefault(candidate.recipeId(), CHAIN_TIER_UNREACHABLE)
				!= CHAIN_TIER_UNREACHABLE)
			.filter(candidate -> !onlyLastResortMaterial(candidate.recipeId(), variantScores, ordinaryScores, lastResort))
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
		ReachCraftingMod.diag(
			"[chain_variant_fallback] clicked_recipe={} selected_recipe={} handling={} preference={} prefer_clicked={} order={}",
			clickedRecipe.getId(),
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
					+ (lastResort.isEmpty() ? "" : " ordinary=" + ordinaryScores.getOrDefault(candidate.recipeId(), 0))
					+ ")")
				.toList()
		);
		return List.copyOf(candidates);
	}

	/** Scored on material, but none of it ordinary: every input it could use is one the player wants spent last. */
	static boolean onlyLastResortMaterial(
		net.minecraft.resources.ResourceLocation recipeId,
		Map<net.minecraft.resources.ResourceLocation, Integer> totalScores,
		Map<net.minecraft.resources.ResourceLocation, Integer> ordinaryScores,
		java.util.Set<String> lastResort
	) {
		return !lastResort.isEmpty()
			&& totalScores.getOrDefault(recipeId, 0) > 0
			&& ordinaryScores.getOrDefault(recipeId, 0) <= 0;
	}

	static Map<String, Integer> withoutLastResort(Map<String, Integer> counts, java.util.Set<String> lastResort) {
		Map<String, Integer> filtered = new HashMap<>();
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (!LastResortIngredients.isLastResort(entry.getKey(), lastResort)) {
				filtered.put(entry.getKey(), entry.getValue());
			}
		}
		return filtered;
	}

	/**
	 * How many more copies the OTHER family variants could chain craft, each
	 * planned on its own against the same materials (an upper bound when
	 * variants share inputs; the prompt says "up to"). Capped at the copies
	 * still wanted so a huge family does not plan more than it needs.
	 */
	private static int otherVariantChainCopies(
		Minecraft minecraft,
		LocalPlayer player,
		List<RecipeVariantResolver.Selection> candidates,
		RecipeVariantResolver.Selection chosen,
		Map<String, Integer> availableCounts,
		boolean allowNearbyChests,
		int copiesWanted
	) {
		int total = 0;
		for (RecipeVariantResolver.Selection candidate : candidates) {
			if (candidate == null || candidate.recipeId().equals(chosen.recipeId()) || total >= copiesWanted) {
				continue;
			}
			Optional<ChainCraftOffer> offer = planChainCraftOfferFor(
				minecraft,
				player,
				candidate,
				availableCounts,
				allowNearbyChests,
				true,
				Math.max(copiesWanted - total, 1),
				Math.max(copiesWanted - total, 1)
			);
			if (offer.isPresent()) {
				total += offer.get().plan().finalRecipeCopies();
			}
		}
		return Math.min(total, copiesWanted);
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

	static void tryCloseOverlayAfterRelease() {
		if (!ReachCraftingConfig.get().reachCraftCloseOverlayAfterRelease()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!(minecraft.screen instanceof RecipeUpdateListener recipeUpdateListener)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) recipeUpdateListener.getRecipeBookComponent();
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) componentAccessor.getRecipeBookPage();
		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		if (overlay != null && overlay.isVisible()) {
			((com.reachcrafting.client.mixin.OverlayRecipeComponentAccessor) overlay).setIsVisible(false);
		}
	}

	static double reachDistance(Minecraft minecraft, LocalPlayer player) {
		if (minecraft.gameMode != null) {
			return minecraft.gameMode.getPickRange();
		}
		return 4.5D;
	}
}
