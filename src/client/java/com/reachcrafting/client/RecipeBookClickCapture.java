package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

public final class RecipeBookClickCapture {
	private static final RecipeBookInputController CONTROLLER = RecipeBookInputController.getInstance();

	private RecipeBookClickCapture() {
	}

	public static void init() {
		CONTROLLER.init();
	}

	public static void onRecipeButtonClicked(
		Recipe<?> recipe,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean shiftModifierDown,
		boolean ctrlModifierDown,
		boolean altModifierDown,
		boolean explicitVariantSelection
	) {
		CONTROLLER.onRecipeButtonClicked(
			recipe,
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
		Recipe<?> recipe,
		RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection,
		boolean altModifierDown
	) {
		CONTROLLER.onVanillaRecipeButtonClicked(recipe, collection, displayStack, explicitVariantSelection, altModifierDown);
	}

	public static boolean onRecipeButtonRightClicked(
		Recipe<?> recipe,
		RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection
	) {
		return CONTROLLER.onRecipeButtonRightClicked(recipe, collection, displayStack, explicitVariantSelection);
	}

	public static int getHeldQueuedCount(
		Recipe<?> recipe,
		RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		return CONTROLLER.getHeldQueuedCount(recipe, collection, explicitVariantSelection);
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

	public static QueuedRecipeCountState getQueuedCountState(
		Recipe<?> recipe,
		RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		return CONTROLLER.getQueuedCountState(recipe, collection, explicitVariantSelection);
	}

	public static QueuedRecipeCountState getQueuedCountState(RecipeButton button) {
		return CONTROLLER.getQueuedCountState(button);
	}

	public static boolean hasPendingHeldRecipe(
		Recipe<?> recipe,
		RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		return CONTROLLER.hasPendingHeldRecipe(recipe, collection, explicitVariantSelection);
	}

	public static ItemStack resolvePendingOutputStack(Minecraft minecraft) {
		return CONTROLLER.resolvePendingOutputStack(minecraft);
	}

	public record HeldRecipeAction(
		Recipe<?> recipe,
		ResourceLocation recipeId,
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
					.anyMatch(candidate -> candidate.getId().equals(other.recipeId));
				boolean otherCollectionContainsThis = other.collection.getRecipes().stream()
					.anyMatch(candidate -> candidate.getId().equals(recipeId));
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
