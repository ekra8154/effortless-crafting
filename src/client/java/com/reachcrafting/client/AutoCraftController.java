package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

final class AutoCraftController {
	private static final int HOLD_RELEASE_GRACE_TICKS = 3;
	private static boolean autoCraftKeyHeld = false;
	private static boolean autoCraftTogglePending = false;
	private static long lastAutoCraftToggleTime = 0;
	private static boolean holdSessionActive = false;
	private static boolean holdStickyNormalLatched = false;
	private static boolean holdStickyBulkLatched = false;
	private static boolean holdStickyNormalAltOverride = false;
	private static boolean holdStickyBulkAltOverride = false;
	private static boolean holdQuickCraftCancelled = false;
	private static boolean holdQuickCraftConsumed = false;
	private static int holdReleaseGraceTicks = 0;

	private AutoCraftController() {
	}

	static void handleKeyPress() {
		autoCraftKeyHeld = true;
		holdQuickCraftCancelled = false;
		holdQuickCraftConsumed = false;
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			autoCraftTogglePending = true;
			return;
		}
		if (holdStickyBulkLatched) {
			holdStickyBulkAltOverride = true;
		} else if (holdStickyNormalLatched) {
			holdStickyNormalAltOverride = true;
		}
	}

	static void handleKeyReleased() {
		if (!autoCraftKeyHeld && !autoCraftTogglePending) {
			return;
		}

		autoCraftKeyHeld = false;
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			if (autoCraftTogglePending) {
				autoCraftTogglePending = false;
				toggleAutoCraftMode();
			}
			return;
		}

		if ((holdStickyBulkLatched && holdStickyBulkAltOverride) || (holdStickyNormalLatched && holdStickyNormalAltOverride)) {
			BulkAutoCraftController.stop(true, "alt_reheld_release");
			return;
		}

		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (!holdQuickCraftCancelled && !holdQuickCraftConsumed) {
				Minecraft client = Minecraft.getInstance();
				if (client.player != null && client.player.containerMenu != null && (client.screen instanceof CraftingScreen || client.screen instanceof InventoryScreen)) {
					Slot resultSlot = client.player.containerMenu.getSlot(0);
					if (resultSlot != null && resultSlot.hasItem()) {
						AutoMoveController.scheduleAutoMove(ItemStack.EMPTY);
					}
				}
			}
			holdQuickCraftCancelled = false;
			holdQuickCraftConsumed = false;
		}

		autoCraftTogglePending = false;
		holdReleaseGraceTicks = HOLD_RELEASE_GRACE_TICKS;
	}

	static void cancelToggle() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			autoCraftTogglePending = false;
		} else if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (!holdQuickCraftCancelled) {
				holdQuickCraftCancelled = true;
				BulkAutoCraftController.stop(true, "hold_cancel_on_key");
			}
		}
	}

	static void consumeQuickCraft() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			autoCraftTogglePending = false;
		} else if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			holdQuickCraftConsumed = true;
		}
	}

	static boolean isTogglePending() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			return autoCraftTogglePending;
		}
		return autoCraftKeyHeld;
	}

	static boolean isEnabled() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			return isHoldRuntimeEnabled();
		}
		return config.autoCraftEnabled() || holdSessionActive || RecipeBookInputController.getInstance().isAltRequestActive();
	}

	static boolean isHoldRuntimeEnabled() {
		return !holdQuickCraftCancelled && (isPhysicalAltHeld() || holdSessionActive || holdStickyNormalLatched || holdStickyBulkLatched);
	}

	static ReachCraftingConfig.AutoCraftMode enabledMode() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			return holdStickyBulkLatched ? ReachCraftingConfig.AutoCraftMode.BULK : ReachCraftingConfig.AutoCraftMode.NORMAL;
		}
		return ReachCraftingConfig.get().autoCraftEnabledMode();
	}

	static boolean isBulkModeEnabled() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			return isEnabled()
				&& holdStickyBulkLatched
				&& ReachCraftingConfig.get().autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.BULK;
		}
		return ReachCraftingConfig.get().isBulkAutoCraftMode();
	}

	static void setEnabled(boolean enabled) {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (!enabled) {
				clearHoldRuntimeState();
			} else if (ReachCraftingConfig.get().autoCraftCapability() != ReachCraftingConfig.AutoCraftCapability.NONE) {
				holdSessionActive = true;
			}
			return;
		}
		ReachCraftingConfig.get().setAutoCraftEnabled(enabled);
		if (!enabled) {
			BulkAutoCraftController.clear();
		} else {
			AutoMoveController.scheduleAutoMove(net.minecraft.world.item.ItemStack.EMPTY);
		}
		ReachCraftingConfig.save();
	}

	static void setEnabledMode(ReachCraftingConfig.AutoCraftMode mode) {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (mode != ReachCraftingConfig.AutoCraftMode.BULK) {
				holdStickyBulkLatched = false;
				holdStickyBulkAltOverride = false;
				holdStickyNormalLatched = false;
				BulkAutoCraftController.clear();
			} else if (ReachCraftingConfig.get().autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.BULK) {
				holdStickyBulkLatched = true;
				holdStickyNormalLatched = false;
				holdSessionActive = true;
			}
			return;
		}
		ReachCraftingConfig.get().setAutoCraftEnabledMode(mode);
		if (!ReachCraftingConfig.get().autoCraftEnabled()) {
			ReachCraftingConfig.save();
			return;
		}
		if (mode != ReachCraftingConfig.AutoCraftMode.BULK) {
			BulkAutoCraftController.clear();
		}
		ReachCraftingConfig.save();
	}

	static void toggleEnabledModeViaArrow() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftCapability() != ReachCraftingConfig.AutoCraftCapability.BULK) {
			return;
		}
		if (config.autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (!isHoldRuntimeEnabled()) {
				return;
			}
			if (holdStickyBulkLatched) {
				demoteBulkToPhysicalHoldMode();
			} else {
				holdStickyBulkLatched = true;
				holdStickyNormalLatched = false;
				holdStickyBulkAltOverride = false;
				holdSessionActive = true;
			}
			return;
		}
		if (!config.autoCraftEnabled()) {
			return;
		}
		if (config.autoCraftEnabledMode() == ReachCraftingConfig.AutoCraftMode.BULK) {
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
			BulkAutoCraftController.clear();
		} else {
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.BULK);
		}
		ReachCraftingConfig.save();
	}

	static void resetBulkModeAfterSession(boolean preserveAutoCraft) {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (!preserveAutoCraft || !isPhysicalAltHeld()) {
				clearHoldRuntimeState();
				return;
			}
			demoteBulkToPhysicalHoldMode();
			return;
		}
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftEnabledMode() == ReachCraftingConfig.AutoCraftMode.BULK) {
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
			ReachCraftingConfig.save();
		}
		BulkAutoCraftController.clear();
	}

	static void armHoldSessionForCurrentRequest(boolean altTriggered) {
		if (ReachCraftingConfig.get().autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.NONE) {
			return;
		}
		if (altTriggered || holdStickyNormalLatched || holdStickyBulkLatched || isPhysicalAltHeld() || holdReleaseGraceTicks > 0) {
			holdSessionActive = true;
		}
	}

	static void clearHoldSession() {
		clearHoldRuntimeState();
	}

	static void tick() {
		if (ReachCraftingConfig.get().autoCraftHandling() != ReachCraftingConfig.AutoCraftHandling.HOLD && !holdSessionActive) {
			return;
		}

		if (!isPhysicalAltHeld() && holdReleaseGraceTicks > 0) {
			holdReleaseGraceTicks--;
		}

		if (!Minecraft.getInstance().isWindowActive()) {
			holdReleaseGraceTicks = 0;
			holdStickyNormalLatched = false;
			holdStickyBulkLatched = false;
			holdStickyNormalAltOverride = false;
			holdStickyBulkAltOverride = false;
			holdSessionActive = false;
		}

		if (holdSessionActive
			&& !holdStickyNormalLatched
			&& !holdStickyBulkLatched
			&& !isPhysicalAltHeld()
			&& !BulkAutoCraftController.isActive()
			&& !AutoMoveController.isAutomatedInteractionRunning()
			&& !RecipeBookInputController.getInstance().isInputQueueActive()) {
			holdSessionActive = false;
		}

		if (!holdStickyBulkLatched) {
			holdStickyBulkAltOverride = false;
		}
		if (!holdStickyNormalLatched) {
			holdStickyNormalAltOverride = false;
		}
	}

	private static void toggleAutoCraftMode() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.NONE) {
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastAutoCraftToggleTime < 50) {
			return;
		}
		lastAutoCraftToggleTime = now;

		boolean current = config.autoCraftEnabled();
		boolean next = !current;
		config.setAutoCraftEnabled(next);
		if (!next) {
			BulkAutoCraftController.clear();
		}
		if (next) {
			AutoMoveController.scheduleAutoMove(net.minecraft.world.item.ItemStack.EMPTY);
		}
		ReachCraftingConfig.save();
	}

	private static boolean isPhysicalAltHeld() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null && RecipeBookFocusManager.isAltKeyDown(minecraft);
	}

	private static void clearHoldRuntimeState() {
		autoCraftKeyHeld = false;
		autoCraftTogglePending = false;
		holdSessionActive = false;
		holdStickyNormalLatched = false;
		holdStickyBulkLatched = false;
		holdStickyNormalAltOverride = false;
		holdStickyBulkAltOverride = false;
		holdQuickCraftCancelled = false;
		holdQuickCraftConsumed = false;
		holdReleaseGraceTicks = 0;
		BulkAutoCraftController.clear();
	}

	private static void demoteBulkToNormalHoldMode() {
		holdStickyBulkLatched = false;
		holdStickyBulkAltOverride = false;
		holdStickyNormalLatched = true;
		holdStickyNormalAltOverride = false;
		holdSessionActive = true;
		BulkAutoCraftController.clear();
	}

	private static void demoteBulkToPhysicalHoldMode() {
		holdStickyBulkLatched = false;
		holdStickyBulkAltOverride = false;
		holdStickyNormalLatched = false;
		holdStickyNormalAltOverride = false;
		holdSessionActive = isPhysicalAltHeld() || holdReleaseGraceTicks > 0;
		BulkAutoCraftController.clear();
	}
}
