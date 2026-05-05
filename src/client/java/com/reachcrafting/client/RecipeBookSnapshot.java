package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.ExtendedRecipeBookCategory;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

record RecipeBookSnapshot(
	boolean visible,
	boolean filtering,
	String searchText,
	boolean focused,
	ExtendedRecipeBookCategory selectedCategory,
	int currentPage,
	boolean overlayVisible,
	net.minecraft.client.gui.screens.recipebook.RecipeCollection overlayCollection
) {
	static RecipeBookSnapshot capture(AbstractRecipeBookScreen<?> screen) {
		RecipeBookComponent<?> component = ((AbstractRecipeBookScreenAccessor) screen).getRecipeBookComponent();
		RecipeBookComponentAccessor accessor = (RecipeBookComponentAccessor) component;
		RecipeBookTabButton selectedTab = accessor.getSelectedTab();
		CycleButton<Boolean> filterButton = accessor.getFilterButton();
		EditBox searchBox = accessor.getSearchBox();
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) accessor.getRecipeBookPage();
		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		String text = searchBox != null ? searchBox.getValue() : "";
		if (searchBox != null) {
			ReachCraftingConfig.setLastSearchText(text);
		}
		ReachCraftingMod.LOGGER.debug("[nearby_capture] Captured search text: '{}' (focused={})", text, searchBox != null && searchBox.isFocused());
		return new RecipeBookSnapshot(
			component.isVisible(),
			filterButton != null && Boolean.TRUE.equals(filterButton.getValue()),
			text,
			searchBox != null && searchBox.isFocused(),
			selectedTab != null ? selectedTab.getCategory() : null,
			pageAccessor.getCurrentPage(),
			overlay != null && overlay.isVisible(),
			overlay != null ? overlay.getRecipeCollection() : null
		);
	}

	static RecipeBookSnapshot empty() {
		return new RecipeBookSnapshot(false, false, "", false, null, 0, false, null);
	}

	void restore(AbstractRecipeBookScreen<?> screen) {
		RecipeBookComponent<?> component = ((AbstractRecipeBookScreenAccessor) screen).getRecipeBookComponent();
		RecipeBookComponentAccessor accessor = (RecipeBookComponentAccessor) component;
		if (component.isVisible() != visible) {
			component.toggleVisibility();
		}

		CycleButton<Boolean> filterButton = accessor.getFilterButton();
		if (filterButton != null && Boolean.TRUE.equals(filterButton.getValue()) != filtering) {
			filterButton.setValue(filtering);
		}

		EditBox searchBox = accessor.getSearchBox();
		if (searchBox != null) {
			if (!searchText.equals(searchBox.getValue())) {
				ReachCraftingMod.LOGGER.debug("[nearby_restore] Restoring search text: '{}' (was: '{}')", searchText, searchBox.getValue());
				searchBox.setValue(searchText);
			}
			ReachCraftingConfig.setLastSearchText(searchBox.getValue());
			if (focused) {
				searchBox.setFocused(true);
				searchBox.setCursorPosition(searchBox.getValue().length());
				searchBox.setHighlightPos(0);
			}
		}

		if (selectedCategory != null) {
			for (RecipeBookTabButton tabButton : accessor.getTabButtons()) {
				if (selectedCategory.equals(tabButton.getCategory())) {
					accessor.invokeReplaceSelected(tabButton);
					break;
				}
			}
		}

		component.recipesUpdated();

		RecipeBookPage page = accessor.getRecipeBookPage();
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) page;
		int totalPages = Math.max(1, pageAccessor.getTotalPages());
		pageAccessor.setCurrentPage(Mth.clamp(currentPage, 0, totalPages - 1));
		if (overlayVisible && overlayCollection != null) {
			restoreOverlay(accessor, pageAccessor);
		}
	}

	private void restoreOverlay(RecipeBookComponentAccessor accessor, RecipeBookPageAccessor pageAccessor) {
		RecipeButton matchingButton = null;
		for (RecipeButton button : pageAccessor.getButtons()) {
			if (button.getCollection() == overlayCollection || button.getCollection().equals(overlayCollection)) {
				matchingButton = button;
				break;
			}
		}
		if (matchingButton == null) {
			return;
		}

		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		Minecraft minecraft = accessor.getMinecraft();
		if (overlay == null || minecraft == null || minecraft.level == null) {
			return;
		}

		ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
		int left = accessor.invokeGetXOrigin();
		int top = accessor.invokeGetYOrigin();
		int width = accessor.getWidth();
		int height = accessor.getHeight();
		overlay.init(
			overlayCollection,
			context,
			pageAccessor.getIsFiltering(),
			matchingButton.getX(),
			matchingButton.getY(),
			left + width / 2,
			top + 13 + height / 2,
			matchingButton.getWidth()
		);
	}
}
