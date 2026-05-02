package com.reachcrafting.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
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
		// Use AFTER_ENTITIES as AFTER_TRANSLUCENT doesn't provide vertex consumers in modern Fabric/MC
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
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
			Vec3 cameraPos = client.gameRenderer.getMainCamera().position();

			renderList(context, level, cameraPos, InWorldFilterManager.getBlacklistedKeys(), 0.0f, 0.0f, 0.0f); // Black
			renderList(context, level, cameraPos, InWorldFilterManager.getWhitelistedKeys(), 1.0f, 1.0f, 1.0f); // White
		});
	}

	private static void renderList(WorldRenderContext context, Level level, Vec3 cameraPos, Set<String> keys, float r, float g, float b) {
		if (keys.isEmpty()) {
			return;
		}

		PoseStack poseStack = context.matrices();
		VertexConsumer consumer = context.consumers().getBuffer(RenderTypes.lines());

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
		float minX = (float) box.minX;
		float minY = (float) box.minY;
		float minZ = (float) box.minZ;
		float maxX = (float) box.maxX;
		float maxY = (float) box.maxY;
		float maxZ = (float) box.maxZ;

		PoseStack.Pose pose = poseStack.last();

		// Bottom
		drawEdge(pose, consumer, minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
		drawEdge(pose, consumer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
		drawEdge(pose, consumer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
		drawEdge(pose, consumer, minX, minY, maxZ, minX, minY, minZ, r, g, b, a);

		// Top
		drawEdge(pose, consumer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
		drawEdge(pose, consumer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
		drawEdge(pose, consumer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
		drawEdge(pose, consumer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);

		// Pillars
		drawEdge(pose, consumer, minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
		drawEdge(pose, consumer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
		drawEdge(pose, consumer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);
		drawEdge(pose, consumer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);
	}

	private static void drawEdge(PoseStack.Pose pose, VertexConsumer consumer, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		float dz = z2 - z1;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len > 0) {
			dx /= len;
			dy /= len;
			dz /= len;
		}
		consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(dx, dy, dz).setLineWidth(2.5f);
		consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(dx, dy, dz).setLineWidth(2.5f);
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
