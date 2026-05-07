package com.reachcrafting.client;

import net.minecraft.client.Minecraft;

final class AutoCraftController {
	private static boolean autoCraftKeyHeld = false;
	private static long lastAutoCraftToggleTime = 0;
	private static boolean holdModeActive = false;

	private AutoCraftController() {
	}

	static void handleKeyPress() {
		autoCraftKeyHeld = true;
	}

	static void handleKeyReleased() {
		if (autoCraftKeyHeld) {
			autoCraftKeyHeld = false;
			if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
				toggleAutoCraftMode();
			}
		}
	}

	static void cancelToggle() {
		autoCraftKeyHeld = false;
	}

	static boolean isTogglePending() {
		return autoCraftKeyHeld;
	}

	static boolean isEnabled() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			return isHoldModeActive();
		}
		return config.autoCraftEnabled();
	}

	static boolean isHoldModeActive() {
		Minecraft minecraft = Minecraft.getInstance();
		boolean alt = RecipeBookFocusManager.isAltKeyDown(minecraft);
		boolean shift = RecipeBookFocusManager.isShiftKeyDown(minecraft);
		boolean ctrl = RecipeBookFocusManager.isControlKeyDown(minecraft);

		if (alt) {
			holdModeActive = true;
		}

		if (holdModeActive && (alt || shift || ctrl)) {
			return true;
		}

		if (BulkAutoCraftController.isActive() || AutoMoveController.isAutomatedInteractionRunning() || RecipeBookInputController.getInstance().isInputQueueActive()) {
			return true;
		}

		if (holdModeActive) {
			holdModeActive = false;
			ReachCraftingConfig.get().setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
		}

		return false;
	}

	static ReachCraftingConfig.AutoCraftMode enabledMode() {
		return ReachCraftingConfig.get().autoCraftEnabledMode();
	}

	static boolean isBulkModeEnabled() {
		return ReachCraftingConfig.get().isBulkAutoCraftMode();
	}

	static void setEnabled(boolean enabled) {
		ReachCraftingConfig.get().setAutoCraftEnabled(enabled);
		if (!enabled) {
			BulkAutoCraftController.clear();
		} else {
			AutoMoveController.scheduleAutoMove(net.minecraft.world.item.ItemStack.EMPTY);
		}
		ReachCraftingConfig.save();
	}

	static void setEnabledMode(ReachCraftingConfig.AutoCraftMode mode) {
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

	static void enableBulkViaArrow() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		config.setAutoCraftEnabled(true);
		config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.BULK);
		ReachCraftingConfig.save();
	}

	static void toggleEnabledModeViaArrow() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.NONE) {
			return;
		}
		boolean bulkAllowed = config.autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.BULK;
		if (!config.autoCraftEnabled()) {
			config.setAutoCraftEnabled(true);
			config.setAutoCraftEnabledMode(bulkAllowed ? ReachCraftingConfig.AutoCraftMode.BULK : ReachCraftingConfig.AutoCraftMode.NORMAL);
		} else if (config.autoCraftEnabledMode() == ReachCraftingConfig.AutoCraftMode.BULK) {
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
			BulkAutoCraftController.clear();
		} else {
			if (bulkAllowed) {
				config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.BULK);
			} else {
				config.setAutoCraftEnabled(false);
				BulkAutoCraftController.clear();
			}
		}
		ReachCraftingConfig.save();
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
}
