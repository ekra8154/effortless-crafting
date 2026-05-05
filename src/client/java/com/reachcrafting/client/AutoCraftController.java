package com.reachcrafting.client;

final class AutoCraftController {
	private static boolean autoCraftKeyHeld = false;
	private static long lastAutoCraftToggleTime = 0;

	private AutoCraftController() {
	}

	static void handleKeyPress() {
		autoCraftKeyHeld = true;
	}

	static void handleKeyReleased() {
		if (autoCraftKeyHeld) {
			autoCraftKeyHeld = false;
			toggleAutoCraftMode();
		}
	}

	static void cancelToggle() {
		autoCraftKeyHeld = false;
	}

	static boolean isTogglePending() {
		return autoCraftKeyHeld;
	}

	static boolean isEnabled() {
		return ReachCraftingConfig.get().autoCraftEnabled();
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
		if (!config.autoCraftEnabled()) {
			config.setAutoCraftEnabled(true);
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.BULK);
		} else if (config.autoCraftEnabledMode() == ReachCraftingConfig.AutoCraftMode.BULK) {
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
			BulkAutoCraftController.clear();
		} else {
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.BULK);
		}
		ReachCraftingConfig.save();
	}

	private static void toggleAutoCraftMode() {
		long now = System.currentTimeMillis();
		if (now - lastAutoCraftToggleTime < 50) {
			return;
		}
		lastAutoCraftToggleTime = now;

		boolean current = ReachCraftingConfig.get().autoCraftEnabled();
		boolean next = !current;
		ReachCraftingConfig.get().setAutoCraftEnabled(next);
		if (!next) {
			BulkAutoCraftController.clear();
		}
		if (next) {
			AutoMoveController.scheduleAutoMove(net.minecraft.world.item.ItemStack.EMPTY);
		}
		ReachCraftingConfig.save();
	}
}
