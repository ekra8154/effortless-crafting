package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.lwjgl.glfw.GLFW;
import com.reachcrafting.client.ContainerUtils;
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

	private long lastScreenOpenTime = 0;

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
			reachcrafting$applyAutoFocus();
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"))
	private void reachcrafting$onMouseClicked(MouseButtonEvent click, boolean filtering, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
		}
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onKeyPressedHead(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT) {
			ContainerUtils.handleAutoCraftKeyPress();
			cir.setReturnValue(true);
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
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
			this.checkSearchStringUpdate();
		}
	}

	@Inject(method = "charTyped", at = @At("TAIL"))
	private void reachcrafting$onCharTyped(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
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

		if (ReachCraftingConfig.get().rememberPreviousSearch() && reachcrafting$isSupportedScreen()) {
			String lastSearch = ReachCraftingConfig.getLastSearchText();
			if (!lastSearch.isEmpty()) {
				this.searchBox.setValue(lastSearch);
				this.lastSearch = lastSearch;
				boolean isFiltering = this.minecraft.player != null && this.minecraft.player.getRecipeBook().isFiltering(this.menu.getRecipeBookType());
				this.updateCollections(true, isFiltering);
				this.searchBox.setHighlightPos(0);
				this.searchBox.setCursorPosition(lastSearch.length());
			}
			
			// Only focus automatically in the 3x3 crafting grid or if forced by a Quick Craft fallback.
			// This allows 'Q' and number keys to work normally in the player inventory usually.
			if (this.minecraft.screen instanceof CraftingScreen || com.reachcrafting.client.ReachCraftingModClient.forceNextInventorySearchFocus) {
				this.searchBox.setFocused(true);
				this.searchBox.setCursorPosition(this.searchBox.getValue().length());
				this.searchBox.setHighlightPos(0);
				com.reachcrafting.client.ReachCraftingModClient.forceNextInventorySearchFocus = false;
			}
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
			key == GLFW.GLFW_KEY_SPACE) {
			return false;
		}

		// Exclude numbers
		if ((key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) ||
			(key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9)) {
			return false;
		}

		// Exclude drop key
		if (this.minecraft.options.keyDrop.matches(event)) {
			return false;
		}

		// Exclude system keys that don't produce characters
		if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_TAB || 
			key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_BACKSPACE || 
			key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_INSERT ||
			key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN ||
			key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END ||
			(key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25)) {
			return false;
		}

		return true;
	}
}
