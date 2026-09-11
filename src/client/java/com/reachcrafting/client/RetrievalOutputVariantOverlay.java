package com.reachcrafting.client;

import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

/**
 * The expanded variant menu for Retrieval Mode.
 *
 * <p>On 1.21.2 and newer this groups the family by OUTPUT ITEM, one synthetic
 * entry per colour, so the menu reads as "which of these do you want to pull".
 * That needs recipe display entries, which this version does not have: the
 * recipe book here holds real {@link RecipeHolder} objects and there is no way
 * to mint an entry for something the book has no recipe for. So the menu shows
 * the collection's REAL recipe variants instead. For a family like stained
 * glass panes the two come to the same thing, one button per colour, because
 * each colour is its own recipe; the difference only shows on recipes that
 * share an output, where you see the recipes rather than one merged button.
 */
public final class RetrievalOutputVariantOverlay {

	private RetrievalOutputVariantOverlay() {
	}

	/**
	 * Open the variant menu for this button, if its collection holds more than
	 * one recipe. False when there is nothing to choose between, so the caller
	 * can fall through to an ordinary click.
	 */
	public static boolean openForButton(RecipeButton button) {
		if (button == null || button.getCollection() == null || button.getRecipe() == null) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (!(minecraft.screen instanceof RecipeUpdateListener recipeBookScreen) || minecraft.level == null) {
			return false;
		}
		if (minecraft.player == null) {
			return false;
		}

		RecipeCollection collection = button.getCollection();
		if (collection.getRecipes().size() <= 1) {
			return false;
		}

		RecipeBookComponent component = recipeBookScreen.getRecipeBookComponent();
		if (component == null) {
			return false;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) component;
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) componentAccessor.getRecipeBookPage();
		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		if (overlay == null) {
			return false;
		}

		int width = componentAccessor.getWidth();
		int height = componentAccessor.getHeight();
		overlay.init(
			minecraft,
			collection,
			button.getX(),
			button.getY(),
			width / 2,
			(height / 2) + 13,
			button.getWidth()
		);
		return true;
	}

	/**
	 * Dev harness only: the variant menu the book would open for this item,
	 * i.e. the collection that owns a recipe producing it. Null when no recipe
	 * makes the item, which on this version also means it can never appear in
	 * Retrieval Mode at all.
	 */
	static RecipeCollection harnessGroupedCollection(String itemId) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return null;
		}
		for (RecipeCollection collection : minecraft.player.getRecipeBook().getCollections()) {
			for (Recipe<?> entry : collection.getRecipes()) {
				ItemStack stack = RecipeVariantResolver.resolveDisplayStack(entry, minecraft);
				if (stack.isEmpty()) {
					continue;
				}
				String outputId = net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getKey(stack.getItem()).toString();
				if (itemId.equals(outputId)) {
					return collection;
				}
			}
		}
		return null;
	}
}
