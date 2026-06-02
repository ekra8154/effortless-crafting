package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

final class ExistingOutputRetrievalController {
	private static final long DOUBLE_TAP_WINDOW_MS = 250L;

	private static boolean enabled;
	private static boolean lastCtrlDown;
	private static long lastCtrlPressAt;

	private ExistingOutputRetrievalController() {
	}

	static boolean isEnabled() {
		return enabled && ReachCraftingConfig.get().enableExistingOutputRetrieval();
	}

	static void setEnabled(boolean enabled) {
		boolean next = enabled && ReachCraftingConfig.get().enableExistingOutputRetrieval();
		if (ExistingOutputRetrievalController.enabled == next) {
			return;
		}
		ExistingOutputRetrievalController.enabled = next;
		RecipeButtonNearbyIndicator.clearCaches();
	}

	static void toggleViaResultSlot() {
		if (!ReachCraftingConfig.get().enableExistingOutputRetrieval()) {
			return;
		}
		if (!enabled) {
			ContainerUtils.abortAllSessions();
			setEnabled(true);
			return;
		}

		NearbyContainerDryRun.abortActiveSession();
		ContainerUtils.clearInputQueue();
		setEnabled(false);
	}

	static void tick(Minecraft client) {
		if (client == null || !ReachCraftingConfig.get().enabled() || !ReachCraftingConfig.get().enableExistingOutputRetrieval()) {
			lastCtrlDown = false;
			if (enabled) {
				setEnabled(false);
			}
			return;
		}

		Screen screen = client.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			lastCtrlDown = false;
			return;
		}

		boolean ctrlDown = RecipeBookFocusManager.isControlKeyDown(client);
		if (ctrlDown && !lastCtrlDown) {
			long now = System.currentTimeMillis();
			if (now - lastCtrlPressAt <= DOUBLE_TAP_WINDOW_MS) {
				if (!enabled) {
					ContainerUtils.abortAllSessions();
				} else {
					NearbyContainerDryRun.abortActiveSession();
					ContainerUtils.clearInputQueue();
				}
				setEnabled(!enabled);
				lastCtrlPressAt = 0L;
			} else {
				lastCtrlPressAt = now;
			}
		}

		lastCtrlDown = ctrlDown;
	}
}

