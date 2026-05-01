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
	public abstract boolean isVisible();

	@Shadow
	protected Minecraft minecraft;

	@Shadow
	protected abstract void updateCollections(boolean resetPage, boolean filtering);

	@Shadow
	protected abstract void checkSearchStringUpdate();

	@Inject(method = "init", at = @At("TAIL"))
	private void reachcrafting$onInit(int width, int height, Minecraft client, boolean isFiltering, CallbackInfo ci) {
		reachcrafting$applyAutoFocus();
	}

	@Inject(method = "setVisible", at = @At("TAIL"))
	private void reachcrafting$onSetVisible(boolean visible, CallbackInfo ci) {
		if (visible) {
			reachcrafting$applyAutoFocus();
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"))
	private void reachcrafting$onMouseClicked(MouseButtonEvent click, boolean filtering, CallbackInfoReturnable<Boolean> cir) {
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
		}
	}

	@Inject(method = "keyPressed", at = @At("TAIL"))
	private void reachcrafting$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (this.searchBox != null && this.isVisible() && reachcrafting$isSupportedScreen()) {
			ReachCraftingConfig.setLastSearchText(this.searchBox.getValue());
			this.checkSearchStringUpdate();
		}
	}

	@Inject(method = "charTyped", at = @At("TAIL"))
	private void reachcrafting$onCharTyped(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
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
			
			// Only focus automatically in the 3x3 crafting grid.
			// This allows 'Q' and number keys to work normally in the player inventory.
			if (this.minecraft.screen instanceof CraftingScreen) {
				this.searchBox.setFocused(true);
			}
		}
	}

	private boolean reachcrafting$isSupportedScreen() {
		if (this.minecraft == null || this.minecraft.screen == null) {
			return false;
		}
		return this.minecraft.screen instanceof CraftingScreen || this.minecraft.screen instanceof InventoryScreen;
	}
}
