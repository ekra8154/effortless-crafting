package com.reachcrafting.client;

import java.util.List;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.Recipe;

public final class NearbyContainerDryRun {
	private static final NearbyCraftCoordinator COORDINATOR = NearbyCraftCoordinator.getInstance();

	private NearbyContainerDryRun() {
	}

	public static void init() {
		COORDINATOR.init();
	}

	public static void start(
		ResourceLocation recipeId,
		Recipe<?> recipe,
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
			recipe,
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
		ResourceLocation recipeId,
		Recipe<?> recipe,
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
			recipe,
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
