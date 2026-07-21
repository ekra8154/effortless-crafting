package com.reachcrafting.client.mixin;

import com.reachcrafting.client.NearbyContainerCache;
import com.reachcrafting.client.ReachCraftingConfig;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import com.reachcrafting.client.InWorldFilterManager;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import com.reachcrafting.client.RecipeButtonNearbyIndicator;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin<T extends AbstractContainerMenu> {
	@Shadow
	protected T menu;

	@Shadow
	protected int titleLabelX;

	@Shadow
	protected int titleLabelY;
	
	@Shadow
	protected int leftPos;
	
	@Shadow
	protected int topPos;



	@Inject(method = "init", at = @At("HEAD"))
	private void reachcrafting$captureInventoryOnOpen(CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		// ONLY clear and snapshot if we aren't in the middle of an automated session!
		if (!com.reachcrafting.client.ContainerUtils.isAutomatedInteractionRunning()) {
			com.reachcrafting.client.InventoryGridRestoreTracker.clear();
			com.reachcrafting.client.PulledResourcesTracker.clear();

			if ((ReachCraftingConfig.get().putPulledResourcesBack() || ReachCraftingConfig.get().restoreInventoryItemPositions())
				&& ((Object) this instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen || (Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
				com.reachcrafting.ReachCraftingMod.LOGGER.debug("[grid_restore] Capturing fresh inventory snapshot...");
				com.reachcrafting.client.PulledResourcesTracker.captureInventorySnapshot(net.minecraft.client.Minecraft.getInstance().player);
			}
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void reachcrafting$onClose(CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		com.reachcrafting.ReachCraftingMod.LOGGER.debug("[grid_restore] Screen onClose triggered for {}", this.getClass().getName());
		com.reachcrafting.client.ContainerUtils.abortAllSessions();

		if ((Object) this instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen || (Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
			com.reachcrafting.client.ContainerUtils.flushCraftingGrid(net.minecraft.client.Minecraft.getInstance(), true, false);
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void reachcrafting$cacheContainerOnClose(CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		
		// Final menu contents are authoritative for cache refresh, including automated open/close flows.
		NearbyContainerCache.onContainerScreenRemoved(this.menu);
		
		// The chain craft confirm popup replaces this screen without closing
		// the container; wiping hold state there kills the bulk latch (and
		// pulled-resource tracking) before the confirmed session can start.
		if (!com.reachcrafting.client.ContainerUtils.isAnySessionActive()
			&& !com.reachcrafting.client.ChainCraftPopupController.isOpeningConfirmPopup()) {
			com.reachcrafting.client.ContainerUtils.clearHoldSession();
			com.reachcrafting.client.PulledResourcesTracker.clear();
			com.reachcrafting.client.InventoryGridRestoreTracker.clear();
		}
	}

	@Inject(method = "renderLabels", at = @At("TAIL"))
	private void reachcrafting$renderControlDot(GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (ReachCraftingConfig.get().inWorldFilterMode() == ReachCraftingConfig.InWorldFilterMode.NONE) return;

		Minecraft client = Minecraft.getInstance();
		if ((InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL))
			&& NearbyContainerCache.isTrackedContainerEligibleForFilterUi()) {
			
			Component titleComponent = ((Screen) (Object) this).getTitle();
			int titleWidth = client.font.width(titleComponent);
			int dotX = this.titleLabelX + titleWidth + 4;
			int dotY = this.titleLabelY + 2;
			
			BlockPos containerPos = NearbyContainerCache.getCurrentContainerPos();
			InWorldFilterManager.InclusionState manual = InWorldFilterManager.getManualState(client.level, containerPos);
			if (manual == InWorldFilterManager.InclusionState.UNSET) {
				RecipeButtonNearbyIndicator.renderGrayDot(guiGraphics, dotX, dotY);
			} else if (manual == InWorldFilterManager.InclusionState.MANUAL_WHITELIST) {
				RecipeButtonNearbyIndicator.renderWhiteDot(guiGraphics, dotX, dotY);
			} else {
				RecipeButtonNearbyIndicator.renderBlackDot(guiGraphics, dotX, dotY);
			}
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onMouseClicked(MouseButtonEvent click, boolean filtering, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (click.button() == 0
			&& (click.modifiers() & GLFW.GLFW_MOD_ALT) != 0
			&& com.reachcrafting.client.ContainerUtils.isAutoCraftEnabled()
			&& ((Object) this instanceof CraftingScreen || (Object) this instanceof InventoryScreen)) {
			Slot hoveredSlot = ((AbstractContainerScreenAccessor) this).getHoveredSlot();
			if (hoveredSlot instanceof ResultSlot && reachcrafting$isArrowClickTarget(hoveredSlot, click.x(), click.y())) {
				if (com.reachcrafting.client.ContainerUtils.isExistingOutputRetrievalEnabled()) {
					com.reachcrafting.client.ContainerUtils.disableExistingOutputRetrieval();
				}
				com.reachcrafting.client.ContainerUtils.consumeAutoCraftToggle();
				com.reachcrafting.client.ContainerUtils.toggleAutoCraftEnabledModeViaArrow();
				cir.setReturnValue(true);
				return;
			}
		}
		if (click.button() == 0
			&& ReachCraftingConfig.get().enableExistingOutputRetrieval()
			&& (click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0
			&& ((Object) this instanceof CraftingScreen || (Object) this instanceof InventoryScreen)) {
			Slot hoveredSlot = ((AbstractContainerScreenAccessor) this).getHoveredSlot();
			if (hoveredSlot instanceof ResultSlot && reachcrafting$isArrowClickTarget(hoveredSlot, click.x(), click.y())) {
				com.reachcrafting.client.ContainerUtils.toggleExistingOutputRetrievalViaResultSlot();
				cir.setReturnValue(true);
				return;
			}
		}
		if (ReachCraftingConfig.get().inWorldFilterMode() == ReachCraftingConfig.InWorldFilterMode.NONE) return;
		if (click.button() != 0) return; // Only left click
		
		Minecraft client = Minecraft.getInstance();
		if (!NearbyContainerCache.isTrackedContainerEligibleForFilterUi()) return;
		
		boolean ctrlDown = (click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
		if (!ctrlDown) return;

		Component titleComponent = ((Screen) (Object) this).getTitle();
		int titleWidth = client.font.width(titleComponent);
		
		// The dot is at (leftPos + titleLabelX + titleWidth + 4, topPos + titleLabelY + 2)
		// and is 5x5 pixels (from RecipeButtonNearbyIndicator.renderDot)
		double dotStartX = this.leftPos + this.titleLabelX + titleWidth + 4;
		double dotStartY = this.topPos + this.titleLabelY + 2;
		double dotEndX = dotStartX + 5;
		double dotEndY = dotStartY + 5;

		if (click.x() >= dotStartX && click.x() <= dotEndX && click.y() >= dotStartY && click.y() <= dotEndY) {
			NearbyContainerCache.toggleTrackedContainerInclusion();
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (!ReachCraftingConfig.get().recipeBookPageNavigation()) return;
		if (scrollY == 0.0D) return;
		if (!((Object) this instanceof CraftingScreen || (Object) this instanceof InventoryScreen)) return;
		if (!((Object) this instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) return;
		Minecraft client = Minecraft.getInstance();
		if (client == null) return;
		var window = client.getWindow();
		if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)) {
			return;
		}

		RecipeBookComponent<?> recipeBookComponent = ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (recipeBookComponent == null || !recipeBookComponent.isVisible()) return;
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) recipeBookComponent;

		RecipeBookPage page = componentAccessor.getRecipeBookPage();
		if (page == null) return;

		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) page;
		int bookX = componentAccessor.invokeGetXOrigin();
		int bookY = componentAccessor.invokeGetYOrigin();
		int bookMinX = bookX - 30;
		int bookMaxX = bookX + 147;
		int bookMinY = bookY;
		int bookMaxY = bookY + 166;
		boolean hoveringRecipeBookUi = mouseX >= bookMinX && mouseX <= bookMaxX && mouseY >= bookMinY && mouseY <= bookMaxY;
		if (!hoveringRecipeBookUi) return;

		int currentPage = pageAccessor.getCurrentPage();
		int totalPages = Math.max(1, pageAccessor.getTotalPages());
		int targetPage = currentPage;
		if (scrollY < 0.0D) {
			targetPage = Math.min(currentPage + 1, totalPages - 1);
		} else if (scrollY > 0.0D) {
			targetPage = Math.max(currentPage - 1, 0);
		}

		if (targetPage == currentPage) {
			return;
		}

		pageAccessor.setCurrentPage(targetPage);
		pageAccessor.invokeUpdateButtonsForPage();
		com.reachcrafting.client.RecipeBookChunkedScheduler.noteVisiblePageIndex(targetPage);
		cir.setReturnValue(true);
	}

	@Inject(method = "renderSlot", at = @At("HEAD"))
	private void reachcrafting$renderResultArrow(GuiGraphics guiGraphics, Slot slot, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (slot instanceof ResultSlot
			&& ((Object) this instanceof CraftingScreen || (Object) this instanceof InventoryScreen)
			&& com.reachcrafting.client.ContainerUtils.isExistingOutputRetrievalEnabled()) {
			RecipeButtonNearbyIndicator.renderRetrievalX(guiGraphics, slot.x + 8, slot.y + 8);
			return;
		}
		if (com.reachcrafting.client.ContainerUtils.isAutoCraftEnabled() && slot instanceof ResultSlot) {
			if ((Object) this instanceof CraftingScreen || (Object) this instanceof InventoryScreen) {
				if (com.reachcrafting.client.ContainerUtils.isBulkAutoCraftModeEnabled()) {
					RecipeButtonNearbyIndicator.renderOrangeArrowOutline(guiGraphics, slot.x + 6, slot.y + 6);
				}
				RecipeButtonNearbyIndicator.renderGrayArrow(guiGraphics, slot.x + 6, slot.y + 6);
			}
		}
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT) {
			if (com.reachcrafting.client.ContainerUtils.isExistingOutputRetrievalEnabled()) {
				com.reachcrafting.client.ContainerUtils.disableExistingOutputRetrieval();
			}
			com.reachcrafting.client.ContainerUtils.handleAutoCraftKeyPress();
			cir.setReturnValue(true);
		} else if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (com.reachcrafting.client.ContainerUtils.isAnySessionActive() || com.reachcrafting.client.ContainerUtils.isAutomatedInteractionRunning()) {
				com.reachcrafting.client.ContainerUtils.abortAllSessions();
			}
		} else if (com.reachcrafting.client.ContainerUtils.isAutoCraftTogglePending()) {
			com.reachcrafting.client.ContainerUtils.cancelAutoCraftToggle();
		}
	}

	@Inject(method = "containerTick", at = @At("TAIL"))
	private void reachcrafting$onContainerTick(CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		// Detect Alt release via polling to avoid Mixin remapping issues with inherited methods
		boolean altDown = InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_ALT) 
					   || InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
		com.reachcrafting.client.ContainerUtils.tickExistingOutputRetrievalController(Minecraft.getInstance());
		com.reachcrafting.client.ContainerUtils.tickAutoCraftController();
		
		if (!Minecraft.getInstance().isWindowActive() && !com.reachcrafting.client.ReproHarness.suppressFocusGuard()) {
			if (com.reachcrafting.client.ContainerUtils.isAutoCraftTogglePending()) {
				com.reachcrafting.client.ContainerUtils.cancelAutoCraftToggle();
			}
			if (com.reachcrafting.client.ContainerUtils.isInputQueueActive()) {
				com.reachcrafting.client.ContainerUtils.clearInputQueue();
			}
		}

		if (!altDown && com.reachcrafting.client.ContainerUtils.isAutoCraftTogglePending()) {
			com.reachcrafting.client.ContainerUtils.handleAutoCraftKeyReleased();
		}

		if (com.reachcrafting.client.ContainerUtils.isAutoMovePending()) {
			com.reachcrafting.client.ContainerUtils.autoMoveResult(Minecraft.getInstance());
		}

		if (com.reachcrafting.client.BulkAutoCraftController.isActive()) {
			com.reachcrafting.client.BulkAutoCraftController.tick(Minecraft.getInstance());
		}

		com.reachcrafting.client.OffhandConsolidationController.tick(Minecraft.getInstance());
	}

	@Inject(method = "renderSlot", at = @At("TAIL"))
	private void reachcrafting$renderOutputCounter(GuiGraphics guiGraphics, Slot slot, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		com.reachcrafting.client.RecipeOutputCounter.render(guiGraphics, (AbstractContainerScreen<?>) (Object) this, slot);
	}

	private boolean reachcrafting$isArrowClickTarget(Slot slot, double mouseX, double mouseY) {
		double startX = this.leftPos + slot.x;
		double endX = startX + 16;
		double startY = this.topPos + slot.y;
		double endY = startY + 16;
		return mouseX >= startX && mouseX <= endX && mouseY >= startY && mouseY <= endY;
	}
}
