package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ContainerUtils;
import com.reachcrafting.client.ReachCraftingConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StateSwitchingButton;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {
	@Shadow
	protected EditBox searchBox;

	@Shadow
	private String lastSearch;

	@Shadow
	protected RecipeBookMenu menu;

	@Shadow
	protected Minecraft minecraft;

	@Shadow
	public abstract boolean isVisible();

	@Shadow
	protected abstract void updateCollections(boolean resetPage);

	@Shadow
	protected abstract void checkSearchStringUpdate();

	private long lastScreenOpenTime = 0L;
	private int reachcrafting$searchHistoryIndex = -1;
	private String reachcrafting$searchHistoryDraft = "";
	private boolean reachcrafting$lastKeyPressedWasToggle = false;

	@Inject(method = "init", at = @At("TAIL"))
	private void reachcrafting$onInit(int width, int height, Minecraft client, boolean widthTooNarrow, RecipeBookMenu menu, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		lastScreenOpenTime = System.currentTimeMillis();
		reachcrafting$applyAutoFocus();
		com.reachcrafting.client.ReachCraftingModClient.forceNextInventorySearchFocus = false;
	}

	@Inject(method = "setVisible", at = @At("TAIL"))
	private void reachcrafting$onSetVisible(boolean visible, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
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
	private void reachcrafting$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
			reachcrafting$commitCurrentSearchToHistory();
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
		}
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onKeyPressedHead(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		reachcrafting$lastKeyPressedWasToggle = false;
		if (this.searchBox != null && this.searchBox.isFocused()) {
			if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
				if (reachcrafting$navigateSearchHistory(keyCode == GLFW.GLFW_KEY_UP)) {
					cir.setReturnValue(true);
					return;
				}
			}

			if ((keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9)
				|| (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9)) {
				cir.setReturnValue(false);
				return;
			}
		}

		if (keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT) {
			ContainerUtils.handleAutoCraftKeyPress();
			cir.setReturnValue(true);
		} else if (com.reachcrafting.client.ReachCraftingModClient.toggleCraftableFilterKey.matches(keyCode, scanCode)) {
			boolean isSpace = keyCode == GLFW.GLFW_KEY_SPACE;
			boolean requestModifierHeld = com.reachcrafting.client.RecipeBookFocusManager.isControlKeyDown(this.minecraft)
				|| com.reachcrafting.client.RecipeBookFocusManager.isShiftKeyDown(this.minecraft)
				|| (ReachCraftingConfig.get().altAsRequestKey() && com.reachcrafting.client.RecipeBookFocusManager.isAltKeyDown(this.minecraft));
			boolean isSearchBoxFocused = this.searchBox != null && this.searchBox.isFocused();
			boolean canToggle = !isSearchBoxFocused || (isSpace && this.searchBox.getValue().isEmpty());

			if (this.isVisible() && canToggle && !(isSpace && requestModifierHeld)) {
				StateSwitchingButton filterButton = ((RecipeBookComponentAccessor) this).getFilterButton();
				if (filterButton != null) {
					boolean newValue = !filterButton.isStateTriggered();
					filterButton.setStateTriggered(newValue);
					if (this.minecraft.player != null) {
						this.minecraft.player.getRecipeBook().setFiltering(this.menu.getRecipeBookType(), newValue);
						((RecipeBookComponentAccessor) this).invokeSendUpdateSettings();
					}
					this.updateCollections(true);
					reachcrafting$lastKeyPressedWasToggle = true;
					cir.setReturnValue(true);
					return;
				}
			}
		} else if (ContainerUtils.isAutoCraftTogglePending()) {
			ContainerUtils.cancelAutoCraftToggle();
		}

		if (ReachCraftingConfig.get().typeToFocusSearch()
			&& this.searchBox != null
			&& this.isVisible()
			&& !this.searchBox.isFocused()
			&& reachcrafting$isEligibleKey(keyCode, scanCode)) {
			this.searchBox.setFocused(true);
			this.searchBox.setCursorPosition(this.searchBox.getValue().length());
			this.searchBox.setHighlightPos(0);
			if (this.minecraft != null && this.minecraft.screen instanceof net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener recipeBookScreen) {
				com.reachcrafting.client.RecipeBookFocusManager.focusRecipeBookComponent(recipeBookScreen);
			}
		}
	}

	@Inject(method = "keyReleased", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onKeyReleased(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT) {
			ContainerUtils.handleAutoCraftKeyReleased();
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "keyPressed", at = @At("TAIL"))
	private void reachcrafting$onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
			if (this.searchBox.isFocused() && keyCode != GLFW.GLFW_KEY_UP && keyCode != GLFW.GLFW_KEY_DOWN) {
				reachcrafting$resetSearchHistoryNavigation();
			}
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
			this.checkSearchStringUpdate();
		}
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onCharTypedHead(char codePoint, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (reachcrafting$lastKeyPressedWasToggle) {
			reachcrafting$lastKeyPressedWasToggle = false;
			cir.setReturnValue(true);
			return;
		}
		if (this.searchBox != null && this.searchBox.isFocused() && Character.isDigit(codePoint)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "charTyped", at = @At("TAIL"))
	private void reachcrafting$onCharTyped(char codePoint, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
			if (this.searchBox.isFocused()) {
				reachcrafting$resetSearchHistoryNavigation();
			}
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
			this.checkSearchStringUpdate();
		}
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
		if (ReachCraftingConfig.get().rememberPreviousSearch()) {
			String lastSearchText = ReachCraftingConfig.getLastSearchText();
			if (!lastSearchText.isEmpty()) {
				ReachCraftingConfig.pushSearchHistory(lastSearchText);
				this.searchBox.setValue(lastSearchText);
				this.lastSearch = lastSearchText;
				this.updateCollections(true);
				this.searchBox.setHighlightPos(0);
				this.searchBox.setCursorPosition(lastSearchText.length());
			}
		}

		if (this.minecraft.screen instanceof CraftingScreen || com.reachcrafting.client.ReachCraftingModClient.forceNextInventorySearchFocus) {
			this.searchBox.setFocused(true);
			this.searchBox.setCursorPosition(this.searchBox.getValue().length());
			this.searchBox.setHighlightPos(0);
			if (this.minecraft.screen instanceof net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener recipeBookScreen) {
				com.reachcrafting.client.RecipeBookFocusManager.focusRecipeBookComponent(recipeBookScreen);
			}
			com.reachcrafting.client.ReachCraftingModClient.forceNextInventorySearchFocus = false;
		}
	}

	private boolean reachcrafting$isSupportedScreen() {
		return this.minecraft != null
			&& this.minecraft.screen != null
			&& (this.minecraft.screen instanceof CraftingScreen || this.minecraft.screen instanceof InventoryScreen);
	}

	private boolean reachcrafting$isEligibleKey(int keyCode, int scanCode) {
		if (this.minecraft == null || this.minecraft.options == null) {
			return false;
		}

		if (System.currentTimeMillis() - lastScreenOpenTime < 500) {
			if (this.minecraft.options.keyUp.matches(keyCode, scanCode)
				|| this.minecraft.options.keyDown.matches(keyCode, scanCode)
				|| this.minecraft.options.keyLeft.matches(keyCode, scanCode)
				|| this.minecraft.options.keyRight.matches(keyCode, scanCode)) {
				return false;
			}
		}

		if (keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT
			|| keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL
			|| keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT
			|| keyCode == GLFW.GLFW_KEY_LEFT_SUPER || keyCode == GLFW.GLFW_KEY_RIGHT_SUPER
			|| keyCode == GLFW.GLFW_KEY_SPACE
			|| com.reachcrafting.client.ReachCraftingModClient.toggleCraftableFilterKey.matches(keyCode, scanCode)) {
			return false;
		}

		if ((keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9)
			|| (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9)) {
			return false;
		}

		if (this.minecraft.options.keyDrop.matches(keyCode, scanCode)
			|| this.minecraft.options.keySwapOffhand.matches(keyCode, scanCode)) {
			return false;
		}

		if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_TAB
			|| keyCode == GLFW.GLFW_KEY_ENTER
			|| keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_INSERT
			|| keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_PAGE_DOWN
			|| keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END
			|| (keyCode >= GLFW.GLFW_KEY_F1 && keyCode <= GLFW.GLFW_KEY_F25)) {
			return false;
		}

		return true;
	}

	private boolean reachcrafting$navigateSearchHistory(boolean moveUp) {
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
		this.updateCollections(true);
		this.searchBox.setCursorPosition(updated.length());
		this.searchBox.setHighlightPos(0);
	}

	private void reachcrafting$commitCurrentSearchToHistory() {
		if (this.searchBox != null) {
			ReachCraftingConfig.pushSearchHistory(this.searchBox.getValue());
		}
	}

	private void reachcrafting$resetSearchHistoryNavigation() {
		reachcrafting$searchHistoryIndex = -1;
		reachcrafting$searchHistoryDraft = "";
	}
}
