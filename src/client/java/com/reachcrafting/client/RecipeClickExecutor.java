package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import java.util.Map;
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
		if (allowNearbyChests && ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(minecraft.level, minecraft.getCameraEntity(), player.blockInteractionRange());
			availableCounts = AvailableItemSnapshot.mergeCounts(availableCounts, reachableView.countsFor(ingredientSummary.acceptedItemIds()));
		}
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

		boolean useDryRun = forceDryRun || allowNearbyChests;
		if (deficitReport.hasMissingIngredients()) {
			ReachCraftingModClient.sendDebugChat("Missing from inventory: " + deficitReport.compactMissingSummary());
			if (!useDryRun) {
				ReachCraftingModClient.sendMissingIngredientsChat("Missing: " + deficitReport.compactMissingSummary());
			}
		} else {
			ReachCraftingModClient.sendDebugChat("Ready: " + outputLabel);
		}

		int effectiveRequestedClicks = refillableBulkMaxMode
			? Math.max(requestedClicks, 1)
			: effectiveCraftAll
				? deficitReport.possibleCopies()
				: requestedClicks;
		boolean nearbyResourcesRequired = allowNearbyChests
			&& areNearbyResourcesRequired(craftAll, effectiveRequestedClicks, localDeficitReport.possibleCopies());
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
				&& !effectiveCraftAll
				&& !immediateLocalCraftDeficit.hasMissingIngredients()
				&& minecraft.gameMode != null) {
				// Always use a single handlePlaceRecipe(shift=true) to fill the grid.
				// Calling handlePlaceRecipe(false) in a loop triggers N server-side
				// grid clears, each of which consumes the previous result and injects
				// byproducts into inventory via Inventory.add(), causing fragmentation.
				ReachCraftingMod.LOGGER.info("[recipe_place] handlePlaceRecipe(shift=true) from RecipeClickExecutor NEARBY path");
				minecraft.gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipeId(), true);
				AvailableItemSnapshot postPlaceSnapshot = AvailableItemSnapshot.capture(player, screen);
				ReachCraftingMod.LOGGER.info(
					"[recipe_place] post_place nearby result={} staged_copies={} requestedClicks={} queueLimit={} grid_reserved={}",
					ContainerUtils.formatStack(player.containerMenu.getSlot(0).getItem()),
					ContainerUtils.currentReservedCraftCopies(postPlaceSnapshot.gridStacks()),
					requestedClicks,
					resolveRecipeQueueLimit(minecraft, selectedRecipe.recipeId(), collection),
					postPlaceSnapshot.hasReservedGrid()
				);
				ContainerUtils.scheduleAutoMove(selectedRecipe.displayStack());
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
			boolean useBulkPlace = effectiveCraftAll || (AutoCraftController.isBulkModeEnabled() && requestedClicks >= queueLimit);

			if (useBulkPlace) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipeId(), true);
			} else {
				int iterations = AutoCraftController.isBulkModeEnabled() ? Math.max(effectiveRequestedClicks, 1) : 1;
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
				ContainerUtils.scheduleAutoMove(selectedRecipe.displayStack());
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

		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, minecraft.screen);
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
			"[bulk_arm] clicked_recipe={} resolved_recipe={} requestedClicks={} craftAll={} allowNearby={} bulk_mode={} explicit_variant={} refillable={} expected_output={}",
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
		if (!(minecraft.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
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
