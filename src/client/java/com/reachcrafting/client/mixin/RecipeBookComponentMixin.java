package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeBookSmartSorter;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.lwjgl.glfw.GLFW;
import com.reachcrafting.client.ContainerUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
	@Shadow
	protected EditBox searchBox;

	private long lastScreenOpenTime = 0;
	private int reachcrafting$searchHistoryIndex = -1;
	private String reachcrafting$searchHistoryDraft = "";
	private boolean reachcrafting$lastKeyPressedWasToggle = false;

	@Shadow
	private String lastSearch;

	@Shadow
	protected RecipeBookMenu menu;

	@Shadow
	public abstract boolean isVisible();

	@Shadow
	protected Minecraft minecraft;

	@Shadow
	protected abstract void updateCollections(boolean resetPage, boolean filtering);

	@Shadow
	protected abstract void checkSearchStringUpdate();

	@Inject(method = "init", at = @At("TAIL"))
	private void reachcrafting$onInit(int width, int height, Minecraft client, boolean isFiltering, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		lastScreenOpenTime = System.currentTimeMillis();
		reachcrafting$applyAutoFocus();
		com.reachcrafting.client.ReachCraftingModClient.forceNextInventorySearchFocus = false;
	}

	@Inject(method = "setVisible", at = @At("TAIL"))
	private void reachcrafting$onSetVisible(boolean visible, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (visible) {
			lastScreenOpenTime = System.currentTimeMillis();
			reachcrafting$resetSearchHistoryNavigation();
			reachcrafting$applyAutoFocus();
		} else {
			reachcrafting$commitCurrentSearchToHistory();
			reachcrafting$resetSearchHistoryNavigation();
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"))
	private void reachcrafting$onMouseClicked(MouseButtonEvent click, boolean filtering, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
			reachcrafting$commitCurrentSearchToHistory();
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
		}
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onKeyPressedHead(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		reachcrafting$lastKeyPressedWasToggle = false;
		if (event.key() == GLFW.GLFW_KEY_SPACE
			&& reachcrafting$isToggleBoundToSpace()
			&& reachcrafting$isSearchReadyToReplace()
			&& reachcrafting$toggleCraftabilityAndClearSearch()) {
			reachcrafting$lastKeyPressedWasToggle = true;
			cir.setReturnValue(true);
			return;
		}
		if (this.searchBox != null && this.searchBox.isFocused()) {
			int key = event.key();
			if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
				if (reachcrafting$navigateSearchHistory(key == GLFW.GLFW_KEY_UP)) {
					cir.setReturnValue(true);
					return;
				}
			}

			// If focused, let number keys through to the screen for hotbar switching
			if ((key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) ||
				(key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9)) {
				cir.setReturnValue(false);
				return;
			}
		}

		if (event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT) {
			ContainerUtils.handleAutoCraftKeyPress();
			cir.setReturnValue(true);
		} else if (com.reachcrafting.client.ReachCraftingModClient.toggleCraftableFilterKey.matches(event)) {
			boolean isSpace = event.key() == GLFW.GLFW_KEY_SPACE;
			boolean requestModifierHeld = com.reachcrafting.client.RecipeBookFocusManager.isControlKeyDown(this.minecraft)
				|| com.reachcrafting.client.RecipeBookFocusManager.isShiftKeyDown(this.minecraft)
				|| (ReachCraftingConfig.get().altAsRequestKey() && com.reachcrafting.client.RecipeBookFocusManager.isAltKeyDown(this.minecraft));
			boolean isSearchBoxFocused = this.searchBox != null && this.searchBox.isFocused();
			boolean canToggle = !isSearchBoxFocused
				|| (isSpace && (this.searchBox.getValue().isEmpty() || reachcrafting$isSearchReadyToReplace()));

			if (this.isVisible() && canToggle && !(isSpace && requestModifierHeld)) {
				if (reachcrafting$toggleCraftabilityAndClearSearch()) {
					reachcrafting$lastKeyPressedWasToggle = true;
					cir.setReturnValue(true);
					return;
				}
			}
		} else if (ContainerUtils.isAutoCraftTogglePending()) {
			ContainerUtils.cancelAutoCraftToggle();
		}

		if (ReachCraftingConfig.get().typeToFocusSearch() && this.searchBox != null && this.isVisible() && !this.searchBox.isFocused()) {
			if (reachcrafting$isEligibleKey(event)) {
				this.searchBox.setFocused(true);
				this.searchBox.setCursorPosition(this.searchBox.getValue().length());
				this.searchBox.setHighlightPos(0);
			}
		}
	}

	@Inject(method = "keyReleased", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onKeyReleased(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT) {
			ContainerUtils.handleAutoCraftKeyReleased();
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "keyPressed", at = @At("TAIL"))
	private void reachcrafting$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
			if (this.searchBox.isFocused()) {
				int key = event.key();
				if (key != GLFW.GLFW_KEY_UP && key != GLFW.GLFW_KEY_DOWN) {
					reachcrafting$resetSearchHistoryNavigation();
				}
			}
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
			this.checkSearchStringUpdate();
		}
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onCharTypedHead(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (reachcrafting$lastKeyPressedWasToggle) {
			reachcrafting$lastKeyPressedWasToggle = false;
			cir.setReturnValue(true);
			return;
		}
		if (event.codepoint() == ' ' && reachcrafting$isToggleBoundToSpace() && reachcrafting$isSearchReadyToReplace()) {
			if (reachcrafting$toggleCraftabilityAndClearSearch()) {
				reachcrafting$lastKeyPressedWasToggle = true;
				cir.setReturnValue(true);
				return;
			}
		}
		if (this.searchBox != null && this.searchBox.isFocused()) {
			if (Character.isDigit(event.codepoint())) {
				cir.setReturnValue(false);
			}
		}
	}

	@Inject(method = "charTyped", at = @At("TAIL"))
	private void reachcrafting$onCharTyped(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
			if (this.searchBox.isFocused()) {
				reachcrafting$resetSearchHistoryNavigation();
			}
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
			this.checkSearchStringUpdate();
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void reachcrafting$onRender(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled() || !this.isVisible()) {
			return;
		}

		// Draw indicators for the buttons on the current page.
		// Since we're at the TAIL of RecipeBookComponent.render, all buttons are already drawn.
		net.minecraft.client.gui.screens.recipebook.RecipeBookPage page = ((RecipeBookComponentAccessor) this).getRecipeBookPage();
		if (page != null) {
			java.util.List<net.minecraft.client.gui.screens.recipebook.RecipeButton> buttons = ((RecipeBookPageAccessor) page).getButtons();
			for (net.minecraft.client.gui.screens.recipebook.RecipeButton button : buttons) {
				if (button.visible) {
					com.reachcrafting.client.RecipeButtonQueuedCountIndicator.render(guiGraphics, button);
				}
			}
		}
	}

	@ModifyArg(
		method = "updateCollections",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookPage;updateCollections(Ljava/util/List;ZZ)V"
		),
		index = 0
	)
	private List<RecipeCollection> reachcrafting$sortRecipeBookCollections(List<RecipeCollection> collections) {
		if (!ReachCraftingConfig.get().enabled()) {
			return collections;
		}
		return RecipeBookSmartSorter.sorted(collections);
	}

	private void reachcrafting$applyAutoFocus() {
		if (this.minecraft == null || this.minecraft.player == null) {
			return;
		}

		if (this.searchBox == null || !this.isVisible() || this.menu == null) {
			return;
		}

		if (!reachcrafting$isSupportedScreen()) {
			return;
		}

		if (ReachCraftingConfig.get().shouldRestoreLastSearch()) {
			String lastSearch = ReachCraftingConfig.getLastSearchText();
			if (!lastSearch.isEmpty()) {
				ReachCraftingConfig.pushSearchHistory(lastSearch);
				this.searchBox.setValue(lastSearch);
				this.lastSearch = lastSearch;
				boolean isFiltering = this.minecraft.player != null && this.minecraft.player.getRecipeBook().isFiltering(this.menu.getRecipeBookType());
				this.updateCollections(true, isFiltering);
				this.searchBox.setHighlightPos(0);
				this.searchBox.setCursorPosition(lastSearch.length());
			}
		}
		
		if (reachcrafting$shouldAutoFocusSearch()) {
			this.searchBox.setFocused(true);
			this.searchBox.setCursorPosition(this.searchBox.getValue().length());
			this.searchBox.setHighlightPos(0);
			com.reachcrafting.client.ReachCraftingModClient.forceNextInventorySearchFocus = false;
		}
	}

	private boolean reachcrafting$isSupportedScreen() {
		if (this.minecraft == null || this.minecraft.screen == null) {
			return false;
		}
		return this.minecraft.screen instanceof CraftingScreen || this.minecraft.screen instanceof InventoryScreen;
	}

	private boolean reachcrafting$isEligibleKey(KeyEvent event) {
		if (this.minecraft == null || this.minecraft.options == null) {
			return false;
		}

		int key = event.key();

		// Exclude movement keys during 'coyote time' after opening the screen (prevents "wwww" searches)
		if (System.currentTimeMillis() - lastScreenOpenTime < 500) {
			if (this.minecraft.options.keyUp.matches(event) ||
				this.minecraft.options.keyDown.matches(event) ||
				this.minecraft.options.keyLeft.matches(event) ||
				this.minecraft.options.keyRight.matches(event)) {
				return false;
			}
		}

		// Exclude modifiers and space
		if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT ||
			key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL ||
			key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT ||
			key == GLFW.GLFW_KEY_LEFT_SUPER || key == GLFW.GLFW_KEY_RIGHT_SUPER ||
			key == GLFW.GLFW_KEY_SPACE ||
			com.reachcrafting.client.ReachCraftingModClient.toggleCraftableFilterKey.matches(event)) {
			return false;
		}

		// Exclude numbers
		if ((key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) ||
			(key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9)) {
			return false;
		}

		if (this.minecraft.options.keyDrop.matches(event) && reachcrafting$hasHoveredStack()) {
			return false;
		}

		if (this.minecraft.options.keySwapOffhand.matches(event)
			&& (reachcrafting$hasHoveredStack() || reachcrafting$hasOffhandStack())) {
			return false;
		}

		// Exclude system keys that don't produce characters
		if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_TAB || 
			key == GLFW.GLFW_KEY_ENTER || 
			key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_INSERT ||
			key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN ||
			key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END ||
			(key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25)) {
			return false;
		}

		return true;
	}

	private boolean reachcrafting$navigateSearchHistory(boolean moveUp) {
		if (!ReachCraftingConfig.get().isSearchHistoryEnabled()) {
			return false;
		}
		int historySize = ReachCraftingConfig.getSearchHistorySize();
		if (moveUp) {
			if (historySize <= 0) {
				return false;
			}
			if (reachcrafting$searchHistoryIndex < 0) {
				reachcrafting$searchHistoryDraft = this.searchBox.getValue();
				reachcrafting$searchHistoryIndex = 0;
			} else if (reachcrafting$searchHistoryIndex < historySize - 1) {
				reachcrafting$searchHistoryIndex++;
			}
			reachcrafting$applySearchText(ReachCraftingConfig.getSearchHistoryEntry(reachcrafting$searchHistoryIndex));
			return true;
		}

		if (reachcrafting$searchHistoryIndex < 0) {
			reachcrafting$applySearchText("");
			return true;
		}
		reachcrafting$searchHistoryIndex--;
		if (reachcrafting$searchHistoryIndex < 0) {
			reachcrafting$applySearchText(reachcrafting$searchHistoryDraft);
			reachcrafting$searchHistoryDraft = "";
			return true;
		}
		reachcrafting$applySearchText(ReachCraftingConfig.getSearchHistoryEntry(reachcrafting$searchHistoryIndex));
		return true;
	}

	private void reachcrafting$applySearchText(String text) {
		if (this.searchBox == null) {
			return;
		}
		String updated = text != null ? text : "";
		this.searchBox.setValue(updated);
		this.lastSearch = updated;
		ReachCraftingConfig.setLastSearchText(updated);
		boolean isFiltering = this.minecraft != null
			&& this.minecraft.player != null
			&& this.menu != null
			&& this.minecraft.player.getRecipeBook().isFiltering(this.menu.getRecipeBookType());
		this.updateCollections(true, isFiltering);
		this.searchBox.setCursorPosition(updated.length());
		this.searchBox.setHighlightPos(0);
	}

	private void reachcrafting$commitCurrentSearchToHistory() {
		if (this.searchBox == null) {
			return;
		}
		ReachCraftingConfig.pushSearchHistory(this.searchBox.getValue());
	}

	private boolean reachcrafting$isSearchReadyToReplace() {
		if (this.searchBox == null || !this.searchBox.isFocused()) {
			return false;
		}
		String value = this.searchBox.getValue();
		if (value.isEmpty()) {
			return true;
		}
		int cursor = this.searchBox.getCursorPosition();
		int highlight = ((EditBoxAccessor) this.searchBox).getHighlightPos();
		int selectionStart = Math.min(cursor, highlight);
		int selectionEnd = Math.max(cursor, highlight);
		return selectionStart == 0 && selectionEnd == value.length();
	}

	private void reachcrafting$clearSearchForCraftabilityToggle() {
		if (this.searchBox == null || !reachcrafting$isSupportedScreen()) {
			return;
		}
		reachcrafting$commitCurrentSearchToHistory();
		reachcrafting$resetSearchHistoryNavigation();
		reachcrafting$applySearchText("");
	}

	private boolean reachcrafting$toggleCraftabilityAndClearSearch() {
		if (!this.isVisible()) {
			return false;
		}
		net.minecraft.client.gui.components.CycleButton<Boolean> filterButton = ((RecipeBookComponentAccessor) this).getFilterButton();
		if (filterButton == null) {
			return false;
		}
		boolean newValue = !filterButton.getValue();
		reachcrafting$clearSearchForCraftabilityToggle();
		filterButton.setValue(newValue);
		if (this.minecraft.player != null) {
			this.minecraft.player.getRecipeBook().setFiltering(this.menu.getRecipeBookType(), newValue);
			((RecipeBookComponentAccessor) this).invokeSendUpdateSettings();
		}
		this.updateCollections(true, newValue);
		return true;
	}

	private boolean reachcrafting$shouldAutoFocusSearch() {
		if (com.reachcrafting.client.ReachCraftingModClient.forceNextInventorySearchFocus) {
			return true;
		}
		return switch (ReachCraftingConfig.get().autoFocusSearchMode()) {
			case DISABLED -> false;
			case CRAFTING_3X3 -> this.minecraft.screen instanceof CraftingScreen;
			case INVENTORY_2X2_AND_3X3 -> this.minecraft.screen instanceof CraftingScreen
				|| this.minecraft.screen instanceof InventoryScreen;
		};
	}

	private boolean reachcrafting$hasHoveredStack() {
		if (!(this.minecraft.screen instanceof AbstractContainerScreen<?> containerScreen)) {
			return false;
		}
		Slot hoveredSlot = ((AbstractContainerScreenAccessor) containerScreen).getHoveredSlot();
		return hoveredSlot != null && hoveredSlot.hasItem();
	}

	private boolean reachcrafting$hasOffhandStack() {
		return this.minecraft.player != null && !this.minecraft.player.getOffhandItem().isEmpty();
	}

	private boolean reachcrafting$isToggleBoundToSpace() {
		InputConstants.Key key = ((KeyMappingAccessor) com.reachcrafting.client.ReachCraftingModClient.toggleCraftableFilterKey).getKey();
		return key.getType() == InputConstants.Type.KEYSYM && key.getValue() == GLFW.GLFW_KEY_SPACE;
	}

	private void reachcrafting$resetSearchHistoryNavigation() {
		reachcrafting$searchHistoryIndex = -1;
		reachcrafting$searchHistoryDraft = "";
	}
}
