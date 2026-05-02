package com.reachcrafting.client.mixin;

import com.reachcrafting.client.NearbyContainerCache;
import com.reachcrafting.client.ReachCraftingConfig;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import com.reachcrafting.client.InWorldFilterManager;
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
		if (!com.reachcrafting.client.NearbyContainerDryRun.isActiveSessionRunning()) {
			com.reachcrafting.client.InventoryGridRestoreTracker.clear();
			com.reachcrafting.client.PulledResourcesTracker.clear();

			if ((ReachCraftingConfig.get().putPulledResourcesBack() || ReachCraftingConfig.get().restoreInventoryItemPositions())
				&& ((Object) this instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen || (Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
				com.reachcrafting.ReachCraftingMod.LOGGER.info("[grid_restore] Capturing fresh inventory snapshot...");
				com.reachcrafting.client.PulledResourcesTracker.captureInventorySnapshot(net.minecraft.client.Minecraft.getInstance().player);
			}
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void reachcrafting$onClose(CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		com.reachcrafting.ReachCraftingMod.LOGGER.info("[grid_restore] Screen onClose triggered for {}", this.getClass().getName());

		if (com.reachcrafting.client.NearbyContainerDryRun.isActiveSessionRunning()) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[grid_restore] Aborting active session.");
			com.reachcrafting.client.NearbyContainerDryRun.abortActiveSession();
		}

		if ((Object) this instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen || (Object) this instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
			com.reachcrafting.client.ContainerUtils.flushCraftingGrid(net.minecraft.client.Minecraft.getInstance(), true, false);
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void reachcrafting$cacheContainerOnClose(CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		NearbyContainerCache.onContainerScreenRemoved(this.menu);
		
		if (!com.reachcrafting.client.NearbyContainerDryRun.isActiveSessionRunning()) {
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
			&& NearbyContainerCache.isCurrentContainerSupported()) {
			
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
		if (ReachCraftingConfig.get().inWorldFilterMode() == ReachCraftingConfig.InWorldFilterMode.NONE) return;
		if (click.button() != 0) return; // Only left click
		
		Minecraft client = Minecraft.getInstance();
		if (!NearbyContainerCache.isCurrentContainerSupported()) return;
		
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
			NearbyContainerCache.toggleCurrentContainerInclusion();
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "renderSlot", at = @At("HEAD"))
	private void reachcrafting$renderResultArrow(GuiGraphics guiGraphics, Slot slot, int i, int j, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (ReachCraftingConfig.get().autoCraftMode() && slot instanceof ResultSlot) {
			if ((Object) this instanceof CraftingScreen || (Object) this instanceof InventoryScreen) {
				RecipeButtonNearbyIndicator.renderGrayArrow(guiGraphics, slot.x + 6, slot.y + 6);
			}
		}
	}


	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (event.key() == GLFW.GLFW_KEY_LEFT_ALT || event.key() == GLFW.GLFW_KEY_RIGHT_ALT) {
			com.reachcrafting.client.ContainerUtils.handleAutoCraftKeyPress();
			cir.setReturnValue(true);
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
		
		if (!Minecraft.getInstance().isWindowActive() && com.reachcrafting.client.ContainerUtils.isAutoCraftTogglePending()) {
			com.reachcrafting.client.ContainerUtils.cancelAutoCraftToggle();
		}

		if (!altDown && com.reachcrafting.client.ContainerUtils.isAutoCraftTogglePending()) {
			com.reachcrafting.client.ContainerUtils.handleAutoCraftKeyReleased();
		}

		if (com.reachcrafting.client.ContainerUtils.isAutoMovePending()) {
			com.reachcrafting.client.ContainerUtils.autoMoveResult(Minecraft.getInstance());
		}
	}

	@Inject(method = "renderSlot", at = @At("TAIL"))
	private void reachcrafting$renderOutputCounter(GuiGraphics guiGraphics, Slot slot, int i, int j, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		com.reachcrafting.client.RecipeOutputCounter.render(guiGraphics, (AbstractContainerScreen<?>) (Object) this, slot);
	}
}
