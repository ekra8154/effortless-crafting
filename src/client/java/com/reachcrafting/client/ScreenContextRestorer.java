package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

final class ScreenContextRestorer {
	private ScreenContextRestorer() {
	}

	static void resumeOriginalContext(
		ScreenContextSnapshot context,
		Minecraft client,
		LocalPlayer player,
		Level level,
		MultiPlayerGameMode gameMode,
		Entity cameraEntity,
		NearbyCraftCoordinator coordinator
	) {
		java.util.Map<String, Integer> reservedGridCounts = new java.util.LinkedHashMap<>();
		for (net.minecraft.world.item.ItemStack stack : context.gridStacks()) {
			if (stack.isEmpty()) {
				continue;
			}
			String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			reservedGridCounts.merge(itemId, stack.getCount(), Integer::sum);
		}
		ReachCraftingMod.LOGGER.info(
			"[nearby_resume] kind={} grid={} mouse=({}, {})",
			context.kind(),
			AvailableItemSnapshot.formatCounts(reservedGridCounts),
			String.format(java.util.Locale.ROOT, "%.1f", context.mouseX()),
			String.format(java.util.Locale.ROOT, "%.1f", context.mouseY())
		);
		if (context.kind() == ScreenKind.INVENTORY_2X2) {
			client.setScreen(new InventoryScreen(player));
			return;
		}
		if (context.kind() == ScreenKind.CRAFTING_TABLE_3X3 && context.craftingTablePos() != null) {
			Vec3 eyePos = cameraEntity.getEyePosition(0);
			BlockPos pos = context.craftingTablePos();
			if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE) && ContainerUtils.squaredDistanceToBlock(eyePos, pos) <= Mth.square(player.blockInteractionRange())) {
				Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, pos);
				Direction face = Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
				BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);
				boolean wasSneaking = player.isShiftKeyDown() || (player.input != null && player.input.keyPresses != null && player.input.keyPresses.shift());
				coordinator.withSuppressedSecondaryUse(() -> {
					if (wasSneaking) {
						coordinator.sendShiftOverride(client, player, false);
						player.setShiftKeyDown(false);
					}
					gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
					if (wasSneaking) {
						player.setShiftKeyDown(true);
						coordinator.sendShiftOverride(client, player, true);
					}
				});
			}
		}
	}

	static boolean isOriginalContextReady(ScreenContextSnapshot context, Screen screen) {
		if (context.kind() == ScreenKind.NONE) {
			return true;
		}
		if (context.kind() == ScreenKind.INVENTORY_2X2) {
			return screen instanceof InventoryScreen;
		}
		return screen instanceof CraftingScreen;
	}

	static void restoreRecipeBookState(ScreenContextSnapshot context, Screen screen) {
		if (screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen) {
			context.recipeBookState().restore(recipeBookScreen);
		}
	}

	static void restoreMousePosition(ScreenContextSnapshot context, Minecraft client) {
		if (client.mouseHandler.isMouseGrabbed()) {
			return;
		}

		double clampedX = Mth.clamp(context.mouseX(), 0.0D, Math.max(0.0D, client.getWindow().getScreenWidth() - 1.0D));
		double clampedY = Mth.clamp(context.mouseY(), 0.0D, Math.max(0.0D, client.getWindow().getScreenHeight() - 1.0D));
		client.mouseHandler.setIgnoreFirstMove();
		GLFW.glfwSetCursorPos(client.getWindow().handle(), clampedX, clampedY);
	}
}
