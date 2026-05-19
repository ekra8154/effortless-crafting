package com.reachcrafting.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

public final class ContainerFilterRenderer {
	private ContainerFilterRenderer() {
	}

	public static void init() {
		// Render after translucent world features so the outlines sit on top of normal world geometry.
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
			ReachCraftingConfig config = ReachCraftingConfig.get();
			if (!config.enabled()) {
				return;
			}
			ReachCraftingConfig.OutlineDisplayMode mode = config.showFilterOutlines();
			
			if (mode == ReachCraftingConfig.OutlineDisplayMode.OFF) {
				return;
			}
			
			if (mode == ReachCraftingConfig.OutlineDisplayMode.KEYBIND && !ReachCraftingModClient.showFilterOutlinesKey.isDown()) {
				return;
			}

			Minecraft client = Minecraft.getInstance();
			if (client.level == null || client.player == null) {
				return;
			}

			Level level = client.level;
			Vec3 cameraPos = client.gameRenderer.getMainCamera().getPosition();

			renderList(context, level, cameraPos, InWorldFilterManager.getBlacklistedKeys(), 0.0f, 0.0f, 0.0f); // Black
			renderList(context, level, cameraPos, InWorldFilterManager.getWhitelistedKeys(), 1.0f, 1.0f, 1.0f); // White
		});
	}

	private static void renderList(WorldRenderContext context, Level level, Vec3 cameraPos, Set<String> keys, float r, float g, float b) {
		if (keys.isEmpty()) {
			return;
		}

		PoseStack poseStack = context.matrixStack();
		if (poseStack == null || context.consumers() == null) {
			return;
		}
		VertexConsumer consumer = context.consumers().getBuffer(RenderType.lines());

		for (String key : keys) {
			BlockPos pos = parsePos(level, key);
			if (pos == null) {
				continue;
			}

			// Use squared distance for performance, 16 blocks range as requested.
			if (pos.distSqr(BlockPos.containing(cameraPos)) > 16 * 16) {
				continue;
			}
			if (!level.isLoaded(pos)) {
				continue;
			}

			BlockState state = level.getBlockState(pos);
			if (!ContainerUtils.isPotentiallySupportedContainer(state)) {
				continue;
			}

			AABB box = new AABB(pos);

			// Double chest handling
			Optional<BlockPos> otherHalf = ContainerUtils.getOtherHalfOfLargeChest(level, pos);
			if (otherHalf.isPresent()) {
				box = box.minmax(new AABB(otherHalf.get()));
			}

			poseStack.pushPose();
			poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
			
			drawBox(poseStack, consumer, box, r, g, b, 0.8f);
			
			poseStack.popPose();
		}
	}

	private static void drawBox(PoseStack poseStack, VertexConsumer consumer, AABB box, float r, float g, float b, float a) {
		LevelRenderer.renderLineBox(poseStack, consumer, box, r, g, b, a);
	}

	private static BlockPos parsePos(Level level, String key) {
		try {
			int lastColon = key.lastIndexOf(':');
			if (lastColon == -1) {
				return null;
			}
			String dim = key.substring(0, lastColon);
			if (!level.dimension().toString().equals(dim)) {
				return null;
			}

			String coords = key.substring(lastColon + 1);
			String[] split = coords.split(",");
			if (split.length != 3) {
				return null;
			}
			return new BlockPos(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]));
		} catch (Exception e) {
			return null;
		}
	}
}
