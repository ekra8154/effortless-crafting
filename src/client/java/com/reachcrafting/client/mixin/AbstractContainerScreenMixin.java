package com.reachcrafting.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.client.InWorldFilterManager;
import com.reachcrafting.client.NearbyContainerCache;
import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.RecipeButtonNearbyIndicator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (!com.reachcrafting.client.ContainerUtils.isAutomatedInteractionRunning()) {
			com.reachcrafting.client.InventoryGridRestoreTracker.clear();
			com.reachcrafting.client.PulledResourcesTracker.clear();

			if ((ReachCraftingConfig.get().putPulledResourcesBack() || ReachCraftingConfig.get().restoreInventoryItemPositions())
				&& ((Object) this instanceof CraftingScreen || (Object) this instanceof InventoryScreen)) {
				com.reachcrafting.ReachCraftingMod.LOGGER.debug("[grid_restore] Capturing fresh inventory snapshot...");
				com.reachcrafting.client.PulledResourcesTracker.captureInventorySnapshot(Minecraft.getInstance().player);
			}
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void reachcrafting$onClose(CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		com.reachcrafting.ReachCraftingMod.LOGGER.debug("[grid_restore] Screen onClose triggered for {}", this.getClass().getName());
		com.reachcrafting.client.ContainerUtils.abortAllSessions();

		if ((Object) this instanceof CraftingScreen || (Object) this instanceof InventoryScreen) {
			com.reachcrafting.client.ContainerUtils.flushCraftingGrid(Minecraft.getInstance(), true, false);
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void reachcrafting$cacheContainerOnClose(CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}

		NearbyContainerCache.onContainerScreenRemoved(this.menu);

		if (!com.reachcrafting.client.ContainerUtils.isAnySessionActive()) {
			com.reachcrafting.client.ContainerUtils.clearHoldSession();
			com.reachcrafting.client.PulledResourcesTracker.clear();
			com.reachcrafting.client.InventoryGridRestoreTracker.clear();
		}
	}

	@Inject(method = "renderLabels", at = @At("TAIL"))
	private void reachcrafting$renderControlDot(GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (ReachCraftingConfig.get().inWorldFilterMode() == ReachCraftingConfig.InWorldFilterMode.NONE) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		long window = client.getWindow().getWindow();
		if ((InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL))
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
	private void reachcrafting$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
			&& Screen.hasAltDown()
			&& com.reachcrafting.client.ContainerUtils.isAutoCraftEnabled()
			&& ((Object) this instanceof CraftingScreen || (Object) this instanceof InventoryScreen)) {
			Slot hoveredSlot = ((AbstractContainerScreenAccessor) this).getHoveredSlot();
			if (hoveredSlot instanceof ResultSlot && reachcrafting$isArrowClickTarget(hoveredSlot, mouseX, mouseY)) {
				com.reachcrafting.client.ContainerUtils.consumeAutoCraftToggle();
				com.reachcrafting.client.ContainerUtils.toggleAutoCraftEnabledModeViaArrow();
				cir.setReturnValue(true);
				return;
			}
		}
		if (ReachCraftingConfig.get().inWorldFilterMode() == ReachCraftingConfig.InWorldFilterMode.NONE) {
			return;
		}
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (!NearbyContainerCache.isTrackedContainerEligibleForFilterUi() || !Screen.hasControlDown()) {
			return;
		}

		Component titleComponent = ((Screen) (Object) this).getTitle();
		int titleWidth = client.font.width(titleComponent);
		double dotStartX = this.leftPos + this.titleLabelX + titleWidth + 4;
		double dotStartY = this.topPos + this.titleLabelY + 2;
		double dotEndX = dotStartX + 5;
		double dotEndY = dotStartY + 5;

		if (mouseX >= dotStartX && mouseX <= dotEndX && mouseY >= dotStartY && mouseY <= dotEndY) {
			NearbyContainerCache.toggleTrackedContainerInclusion();
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "renderSlot", at = @At("HEAD"))
	private void reachcrafting$renderResultArrow(GuiGraphics guiGraphics, Slot slot, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) {
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
	private void reachcrafting$onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT) {
			com.reachcrafting.client.ContainerUtils.handleAutoCraftKeyPress();
			cir.setReturnValue(true);
		} else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			if (com.reachcrafting.client.ContainerUtils.isInputQueueActive()
				|| com.reachcrafting.client.ContainerUtils.isAutomatedInteractionRunning()) {
				com.reachcrafting.client.ContainerUtils.abortAllSessions();
			}
		} else if (com.reachcrafting.client.ContainerUtils.isAutoCraftTogglePending()) {
			com.reachcrafting.client.ContainerUtils.cancelAutoCraftToggle();
		}
	}

	@Inject(method = "containerTick", at = @At("TAIL"))
	private void reachcrafting$onContainerTick(CallbackInfo ci) {
		com.reachcrafting.client.ContainerUtils.tickContainerScreen();
	}

	@Inject(method = "renderSlot", at = @At("TAIL"))
	private void reachcrafting$renderOutputCounter(GuiGraphics guiGraphics, Slot slot, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
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
