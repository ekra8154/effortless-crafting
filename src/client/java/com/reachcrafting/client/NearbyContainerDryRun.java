package com.reachcrafting.client;

import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

public final class NearbyContainerDryRun {
	private static final NearbyCraftCoordinator COORDINATOR = NearbyCraftCoordinator.getInstance();

	private NearbyContainerDryRun() {
	}

	public static void init() {
		COORDINATOR.init();
	}

	public static void start(
		RecipeDisplayId recipeId,
		RecipeCollection recipeCollection,
		boolean explicitVariantSelection,
		int recipeIndex,
		String outputLabel,
		RecipeIngredientSummary ingredientSummary,
		AvailableItemSnapshot localItems,
		boolean craftAll,
		int requestedSingleClicks,
		boolean allowNearby
	) {
		COORDINATOR.start(new SearchRequest(
			recipeId,
			recipeCollection,
			explicitVariantSelection,
			recipeIndex,
			outputLabel,
			ingredientSummary,
			localItems,
			craftAll,
			requestedSingleClicks,
			allowNearby
		));
	}

	public static boolean tryExpandReservedGrid(
		RecipeDisplayId recipeId,
		RecipeCollection recipeCollection,
		boolean explicitVariantSelection,
		int recipeIndex,
		String outputLabel,
		RecipeIngredientSummary ingredientSummary,
		AvailableItemSnapshot localItems,
		boolean craftAll,
		int requestedSingleClicks,
		boolean allowNearby
	) {
		return COORDINATOR.tryExpandReservedGrid(new SearchRequest(
			recipeId,
			recipeCollection,
			explicitVariantSelection,
			recipeIndex,
			outputLabel,
			ingredientSummary,
			localItems,
			craftAll,
			requestedSingleClicks,
			allowNearby
		));
	}

	public static void startReturn(AbstractContainerMenu closingMenu, List<PulledResourcesTracker.WithdrawnItem> items, boolean reopenScreen) {
		COORDINATOR.startReturn(closingMenu, items, reopenScreen);
	}

	public static void startCountStaging(Map<String, Integer> desiredCounts, String reason) {
		COORDINATOR.startCountStaging(new CountStagingRequest(desiredCounts, reason));
	}

	public static void cancelCurrent() {
		COORDINATOR.cancelCurrent();
	}

	public static void abortActiveSession() {
		COORDINATOR.abortActiveSession();
	}

	public static boolean isActiveSessionRunning() {
		return COORDINATOR.isActiveSessionRunning();
	}

	public static boolean shouldBlockWorldInteraction() {
		return COORDINATOR.shouldBlockWorldInteraction();
	}

	public static boolean shouldSuppressSecondaryUse() {
		return COORDINATOR.shouldSuppressSecondaryUse();
	}

	public static void runPendingPostReturnCompaction(Minecraft client) {
		COORDINATOR.runPendingPostReturnCompaction(client);
	}

	public static void onContainerContentsInitialized(AbstractContainerMenu menu) {
		COORDINATOR.onContainerContentsInitialized(menu);
	}
}
