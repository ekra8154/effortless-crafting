package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

public final class RecipeBookClickCapture {
	private static final RecipeBookInputController CONTROLLER = RecipeBookInputController.getInstance();
	private static boolean suppressNextVanillaRecipeClick = false;

	private RecipeBookClickCapture() {
	}

	public static void init() {
		CONTROLLER.init();
	}

	public static void onRecipeButtonClicked(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean shiftModifierDown,
		boolean ctrlModifierDown,
		boolean altModifierDown,
		boolean explicitVariantSelection
	) {
		CONTROLLER.onRecipeButtonClicked(
			recipeId,
			collection,
			displayStack,
			mouseButton,
			shiftModifierDown,
			ctrlModifierDown,
			altModifierDown,
			explicitVariantSelection
		);
	}

	public static void onVanillaRecipeButtonClicked(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection,
		boolean altModifierDown
	) {
		CONTROLLER.onVanillaRecipeButtonClicked(recipeId, collection, displayStack, explicitVariantSelection, altModifierDown);
	}

	public static void suppressNextVanillaRecipeClick() {
		suppressNextVanillaRecipeClick = true;
	}

	public static boolean consumeSuppressedVanillaRecipeClick() {
		if (!suppressNextVanillaRecipeClick) {
			return false;
		}
		suppressNextVanillaRecipeClick = false;
		return true;
	}

	public static boolean onRecipeButtonRightClicked(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection
	) {
		return CONTROLLER.onRecipeButtonRightClicked(recipeId, collection, displayStack, explicitVariantSelection);
	}

	public static int getHeldQueuedCount(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		return CONTROLLER.getHeldQueuedCount(recipeId, collection, explicitVariantSelection);
	}

	public static void tryCloseOverlayAfterRelease() {
		CONTROLLER.tryCloseOverlayAfterRelease();
	}

	public static int getHeldQueuedCount(RecipeButton button) {
		return CONTROLLER.getHeldQueuedCount(button);
	}

	public static void refocusRecipeBookSearch(Minecraft minecraft) {
		CONTROLLER.refocusRecipeBookSearch(minecraft);
	}

	public static void defocusRecipeBookSearch(Minecraft minecraft) {
		CONTROLLER.defocusRecipeBookSearch(minecraft);
	}

	public static PendingHeldRecipe getPendingHeldRecipe() {
		return CONTROLLER.getPendingHeldRecipe();
	}

	public static void scheduleReplay(HeldRecipeAction action, int remainingClicks, boolean allowNearby, boolean craftAll, boolean refillableBulkMaxMode) {
		CONTROLLER.scheduleReplay(action, remainingClicks, allowNearby, craftAll, refillableBulkMaxMode);
	}

	public static boolean isBulkModeEnabled() {
		return AutoCraftController.isBulkModeEnabled();
	}

	public static QueuedRecipeCountState getQueuedCountState(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		return CONTROLLER.getQueuedCountState(recipeId, collection, explicitVariantSelection);
	}

	public static QueuedRecipeCountState getQueuedCountState(RecipeButton button) {
		return CONTROLLER.getQueuedCountState(button);
	}

	public static boolean hasPendingHeldRecipe(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		return CONTROLLER.hasPendingHeldRecipe(recipeId, collection, explicitVariantSelection);
	}

	public static ItemStack resolvePendingOutputStack(Minecraft minecraft) {
		return CONTROLLER.resolvePendingOutputStack(minecraft);
	}

	public record HeldRecipeAction(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean explicitVariantSelection
	) {
		boolean sameRecipe(HeldRecipeAction other) {
			if (other == null || explicitVariantSelection != other.explicitVariantSelection) {
				return false;
			}
			if (explicitVariantSelection) {
				return recipeId.equals(other.recipeId);
			}
			if (collection != null && other.collection != null) {
				if (collection == other.collection || collection.equals(other.collection)) {
					return true;
				}
				boolean thisCollectionContainsOther = collection.getRecipes().stream()
					.anyMatch(entry -> entry.id().equals(other.recipeId));
				boolean otherCollectionContainsThis = other.collection.getRecipes().stream()
					.anyMatch(entry -> entry.id().equals(recipeId));
				if (thisCollectionContainsOther && otherCollectionContainsThis) {
					return true;
				}
			}
			return recipeId.equals(other.recipeId);
		}
	}

	public record PendingHeldRecipe(HeldRecipeAction action, int clickCount, boolean locked) {
	}

	public record ReplayBatch(HeldRecipeAction action, int remainingClicks, boolean allowNearby, boolean craftAll, boolean refillableBulkMaxMode) {
	}
}
