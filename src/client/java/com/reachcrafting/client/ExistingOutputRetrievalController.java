package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

public final class ExistingOutputRetrievalController {
	private static final long DOUBLE_TAP_WINDOW_MS = 250L;

	private static boolean enabled;
	private static boolean lastCtrlDown;
	private static long lastCtrlPressAt;

	private ExistingOutputRetrievalController() {
	}

	public static boolean isEnabled() {
		return enabled && ReachCraftingConfig.get().enableExistingOutputRetrieval();
	}

	static void setEnabled(boolean enabled) {
		boolean next = enabled && ReachCraftingConfig.get().enableExistingOutputRetrieval();
		if (ExistingOutputRetrievalController.enabled == next) {
			ReachCraftingMod.LOGGER.info("[retrieval_mode] setEnabled no-op next={} config_enabled={}", next, ReachCraftingConfig.get().enableExistingOutputRetrieval());
			return;
		}
		ExistingOutputRetrievalController.enabled = next;
		ReachCraftingMod.LOGGER.info("[retrieval_mode] setEnabled next={} screen={}", next, Minecraft.getInstance().screen != null ? Minecraft.getInstance().screen.getClass().getSimpleName() : "null");
		RecipeButtonNearbyIndicator.clearCaches();
		RecipeBookChunkedScheduler.markForceEagerNextSort();
		RecipeBookChunkedScheduler.clear();
		RecipeBookChunkedScheduler.resetFrozenPageState("retrieval_mode_toggled");
		RecipeBookChunkedScheduler.forceVisibleRecipeBookRefresh();
		if (next) {
			triggerCacheWarmupIfNeeded();
		}
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

	private static void triggerCacheWarmupIfNeeded() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null || client.level == null || client.getCameraEntity() == null) {
			return;
		}
		NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(
			client.level,
			client.getCameraEntity(),
			client.player.blockInteractionRange()
		);
		if (reachableView.nearestAccessByKey().isEmpty()) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] warmup skipped reason=no_reachable_containers");
			return;
		}
		if (reachableView.snapshotsByKey().size() >= reachableView.nearestAccessByKey().size()) {
			ReachCraftingMod.LOGGER.info(
				"[retrieval_virtual] warmup skipped reason=all_reachable_cached snapshots={} reachable={} cached_items={}",
				reachableView.snapshotsByKey().size(),
				reachableView.nearestAccessByKey().size(),
				reachableView.aggregateCounts().size()
			);
			return;
		}
		ReachCraftingMod.LOGGER.info(
			"[retrieval_virtual] warmup requested snapshots={} reachable={} uncached={}",
			reachableView.snapshotsByKey().size(),
			reachableView.nearestAccessByKey().size(),
			reachableView.nearestAccessByKey().size() - reachableView.snapshotsByKey().size()
		);
		NearbyContainerDryRun.startCacheWarmup("retrieval_virtual_entries");
	}
}
