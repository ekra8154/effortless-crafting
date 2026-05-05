package com.reachcrafting.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

abstract class BaseCraftSession implements CraftSession {
	protected final NearbyCraftCoordinator coordinator;
	protected final Minecraft client;
	protected final LocalPlayer player;
	protected final Level level;
	protected final MultiPlayerGameMode gameMode;
	protected final Entity cameraEntity;

	protected BaseCraftSession(
		NearbyCraftCoordinator coordinator,
		Minecraft client,
		LocalPlayer player,
		Level level,
		MultiPlayerGameMode gameMode,
		Entity cameraEntity
	) {
		this.coordinator = coordinator;
		this.client = client;
		this.player = player;
		this.level = level;
		this.gameMode = gameMode;
		this.cameraEntity = cameraEntity;
	}

	protected void sendChat(String message) {
		player.displayClientMessage(Component.literal("[Effortless Crafting] " + message).withStyle(ChatFormatting.GOLD), false);
	}

	protected void resumeOriginalContext(ScreenContextSnapshot context) {
		ScreenContextRestorer.resumeOriginalContext(context, client, player, level, gameMode, cameraEntity, coordinator);
	}

	protected void withSuppressedSecondaryUse(Runnable action) {
		coordinator.withSuppressedSecondaryUse(action);
	}

	protected void sendShiftOverride(Minecraft client, LocalPlayer player, boolean shiftDown) {
		coordinator.sendShiftOverride(client, player, shiftDown);
	}

	protected boolean isOriginalContextReady(ScreenContextSnapshot context) {
		return ScreenContextRestorer.isOriginalContextReady(context, client.screen);
	}

	protected void restoreRecipeBookState(ScreenContextSnapshot context) {
		ScreenContextRestorer.restoreRecipeBookState(context, client.screen);
	}

	protected void restoreMousePosition(ScreenContextSnapshot context) {
		ScreenContextRestorer.restoreMousePosition(context, client);
	}

	protected static BlockPos findNearestCraftingTable(Level level, Entity cameraEntity, double reachDistance) {
		if (level == null) {
			return null;
		}
		return ContainerUtils.findNearestCraftingTable(level, cameraEntity.getEyePosition(0), reachDistance);
	}

	protected final void finishSession(boolean closeContainer) {
		stop(closeContainer);
		coordinator.onSessionFinished(this);
	}
}
