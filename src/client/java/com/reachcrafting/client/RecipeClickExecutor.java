package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import java.util.Map;
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
		HeldRecipeQueueState state
	) {
		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		boolean vanillaShiftClick = craftAll && !forceDryRun && !allowNearbyChests;
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
		int desiredVariantCopies = availableItems.hasReservedGrid() && !craftAll
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
			craftAll,
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
		if (allowNearbyChests && ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(minecraft.level, minecraft.getCameraEntity(), reachDistance(minecraft, player));
			availableCounts = AvailableItemSnapshot.mergeCounts(availableCounts, reachableView.countsFor(ingredientSummary.acceptedItemIds()));
		}
		RecipeDeficitReport deficitReport = craftAll
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
			: craftAll
				? deficitReport.possibleCopies()
				: requestedClicks;
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
				craftAll,
				effectiveRequestedClicks,
				refillableBulkMaxMode,
				selectedRecipe.displayStack(),
				ingredientSummary
			);
			if (allowNearbyChests
				&& AutoCraftController.isBulkModeEnabled()
				&& !craftAll
				&& !immediateLocalCraftDeficit.hasMissingIngredients()
				&& minecraft.gameMode != null) {
				// Always use a single handlePlaceRecipe(shift=true) to fill the grid.
				// Calling handlePlaceRecipe(false) in a loop triggers N server-side
				// grid clears, each of which consumes the previous result and injects
				// byproducts into inventory via Inventory.add(), causing fragmentation.
				ReachCraftingMod.LOGGER.info("[recipe_place] handlePlaceRecipe(shift=true) from RecipeClickExecutor NEARBY path");
				minecraft.gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipe(), true);
				AvailableItemSnapshot postPlaceSnapshot = AvailableItemSnapshot.capture(player, screen);
				ReachCraftingMod.LOGGER.info(
					"[recipe_place] post_place nearby result={} staged_copies={} requestedClicks={} queueLimit={} grid_reserved={}",
					ContainerUtils.formatStack(player.containerMenu.getSlot(0).getItem()),
					ContainerUtils.currentReservedCraftCopies(postPlaceSnapshot.gridStacks()),
					requestedClicks,
					resolveRecipeQueueLimit(minecraft, selectedRecipe.recipe(), collection),
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
				&& !craftAll
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
			boolean useBulkPlace = craftAll || (AutoCraftController.isBulkModeEnabled() && requestedClicks >= queueLimit);

			if (useBulkPlace) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipe(), true);
			} else {
				int iterations = AutoCraftController.isBulkModeEnabled() ? Math.max(effectiveRequestedClicks, 1) : 1;
				for (int i = 0; i < iterations; i++) {
					gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipe(), false);
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
					recipe,
					recipe.getId(),
					selectedRecipe.recipe(),
					selectedRecipe.recipeId(),
					collection,
					displayStack,
					mouseButton,
					explicitVariantSelection,
					allowNearbyChests,
					craftAll,
					requestedClicks,
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
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
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
			refillableBulkMaxMode,
			continuationMode,
			expectedOutput,
			ingredientSummary
		);
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

	private static double reachDistance(Minecraft minecraft, LocalPlayer player) {
		if (minecraft.gameMode != null) {
			return minecraft.gameMode.getPickRange();
		}
		return 4.5D;
	}
}
