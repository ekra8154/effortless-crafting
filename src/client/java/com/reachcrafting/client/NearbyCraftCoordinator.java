package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;

final class NearbyCraftCoordinator {
	private static final NearbyCraftCoordinator INSTANCE = new NearbyCraftCoordinator();

	private int interactionBlockTicks;
	private boolean suppressSecondaryUse;
	private Set<String> pendingPostReturnCompactionItemIds = Set.of();
	private CraftSession activeSession;

	private NearbyCraftCoordinator() {
	}

	static NearbyCraftCoordinator getInstance() {
		return INSTANCE;
	}

	void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ReachCraftingConfig.get().enabled()) {
				if (activeSession != null) {
					abortActiveSession();
				}
				return;
			}
			if (interactionBlockTicks > 0) {
				interactionBlockTicks--;
			}
			if (client.screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
				ContainerUtils.abortAllSessions();
			}
			if (activeSession != null) {
				activeSession.tick();
			}
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (activeSession == null) {
				return;
			}
			if (message.getContents() instanceof TranslatableContents translatable
				&& "container.isLocked".equals(translatable.getKey())) {
				activeSession.onOpenFailed("locked");
			}
		});
	}

	void start(SearchRequest request) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		Level level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;
		Entity cameraEntity = client.getCameraEntity();
		if (player == null || level == null || gameMode == null || cameraEntity == null) {
			return;
		}

		cancelCurrent();
		SearchSession session = new SearchSession(this, client, player, level, gameMode, cameraEntity, request);
		if (!session.canStart()) {
			return;
		}

		activeSession = session;
		session.start();
	}

	boolean tryExpandReservedGrid(SearchRequest request) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		Level level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;
		Entity cameraEntity = client.getCameraEntity();
		if (player == null || level == null || gameMode == null || cameraEntity == null) {
			return false;
		}

		cancelCurrent();
		SearchSession session = new SearchSession(this, client, player, level, gameMode, cameraEntity, request);
		return session.expandReservedGridInPlace();
	}

	void startReturn(AbstractContainerMenu closingMenu, List<PulledResourcesTracker.WithdrawnItem> items, boolean reopenScreen) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		Level level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;
		Entity cameraEntity = client.getCameraEntity();

		if (client == null || player == null || level == null || gameMode == null || cameraEntity == null || items.isEmpty()) {
			return;
		}

		cancelCurrent();
		AvailableItemSnapshot localItems = AvailableItemSnapshot.capture(player, client.screen);
		ScreenContextSnapshot context = ScreenContextSnapshot.capture(client, cameraEntity, player.blockInteractionRange(), localItems);
		if (reopenScreen) {
			context = context.withClearedGrid();
		}
		activeSession = new ReturnSession(this, client, player, level, gameMode, cameraEntity, new ReturnRequest(closingMenu, items, context, reopenScreen));
		activeSession.start();
	}

	void cancelCurrent() {
		if (activeSession != null) {
			activeSession.stop(true);
			activeSession = null;
			RecipeBookClickCapture.refocusRecipeBookSearch(Minecraft.getInstance());
		}
	}

	void abortActiveSession() {
		if (activeSession != null) {
			activeSession.stop(false);
			activeSession = null;
			RecipeBookClickCapture.refocusRecipeBookSearch(Minecraft.getInstance());
		}
	}

	boolean isActiveSessionRunning() {
		return activeSession != null;
	}

	boolean shouldBlockWorldInteraction() {
		return activeSession != null || interactionBlockTicks > 0;
	}

	boolean shouldSuppressSecondaryUse() {
		return suppressSecondaryUse;
	}

	void runPendingPostReturnCompaction(Minecraft client) {
		if (pendingPostReturnCompactionItemIds.isEmpty() || client == null || client.player == null || client.gameMode == null) {
			return;
		}
		InventoryGridRestoreTracker.compactTrackedInventoryStacks(client.player.containerMenu, client.gameMode, pendingPostReturnCompactionItemIds);
		ReachCraftingMod.LOGGER.debug(
			"[nearby_return] Deferred compaction applied: {}",
			AvailableItemSnapshot.formatInventorySlots(client.player, pendingPostReturnCompactionItemIds)
		);
		pendingPostReturnCompactionItemIds = Set.of();
	}

	void armInteractionBlock() {
		interactionBlockTicks = Math.max(interactionBlockTicks, 4);
	}

	void withSuppressedSecondaryUse(Runnable action) {
		boolean previous = suppressSecondaryUse;
		suppressSecondaryUse = true;
		try {
			action.run();
		} finally {
			suppressSecondaryUse = previous;
		}
	}

	void sendShiftOverride(Minecraft client, LocalPlayer player, boolean shiftDown) {
		if (client.getConnection() == null || player.input == null) {
			return;
		}
		Input keyPresses = player.input.keyPresses;
		if (keyPresses == null) {
			return;
		}
		client.getConnection().send(new ServerboundPlayerInputPacket(new Input(
			keyPresses.forward(),
			keyPresses.backward(),
			keyPresses.left(),
			keyPresses.right(),
			keyPresses.jump(),
			shiftDown,
			keyPresses.sprint()
		)));
	}

	void onContainerContentsInitialized(AbstractContainerMenu menu) {
		if (activeSession != null) {
			activeSession.onContainerContentsInitialized(menu);
		}
	}

	void clearActiveSession(CraftSession session) {
		if (activeSession == session) {
			activeSession = null;
		}
	}

	void onSessionFinished(CraftSession session) {
		clearActiveSession(session);
		RecipeBookClickCapture.refocusRecipeBookSearch(Minecraft.getInstance());
	}

	void queuePostReturnCompaction(Set<String> itemIds) {
		pendingPostReturnCompactionItemIds = Set.copyOf(itemIds);
	}
}
