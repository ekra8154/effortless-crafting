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

	private static void toggleAutoCraftMode() {
		long now = System.currentTimeMillis();
		if (now - lastAutoCraftToggleTime < 50) {
			return;
		}
		lastAutoCraftToggleTime = now;

		boolean current = ReachCraftingConfig.get().autoCraftMode();
		boolean next = !current;
		ReachCraftingConfig.get().setAutoCraftMode(next);
		if (next) {
			AutoMoveController.scheduleAutoMove(net.minecraft.world.item.ItemStack.EMPTY);
		}
		ReachCraftingConfig.save();
	}
}
