package com.reachcrafting.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
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
	private static boolean outlinesToggledOn;

	private ContainerFilterRenderer() {
	}

	public static void init() {
		// The keybind toggles the outlines rather than showing them only
		// while held: each press flips the state until pressed again.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (ReachCraftingModClient.showFilterOutlinesKey.consumeClick()) {
				outlinesToggledOn = !outlinesToggledOn;
			}
			if (client.level == null) {
				outlinesToggledOn = false;
			}
			InWorldFilterManager.tickSneakCycleLatch(client);
		});
		// Submit line geometry during the collect phase so it flows through the 26.2 deferred render pipeline.
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			ReachCraftingConfig config = ReachCraftingConfig.get();
			if (!config.enabled()) {
				return;
			}
			if (!areOutlinesVisible()) {
				return;
			}

			Minecraft client = Minecraft.getInstance();
			if (client.level == null || client.player == null) {
				return;
			}

			Level level = client.level;
			Vec3 cameraPos = client.gameRenderer.mainCamera().position();

			renderList(context, level, cameraPos, InWorldFilterManager.getBlacklistedKeys(), 0.0f, 0.0f, 0.0f); // Black
			renderList(context, level, cameraPos, InWorldFilterManager.getWhitelistedKeys(), 1.0f, 1.0f, 1.0f); // White
		});
	}

	/** Whether the filter outlines are currently drawn (ON mode, or KEYBIND mode with the toggle latched on). */
	static boolean areOutlinesVisible() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (!config.enabled()) {
			return false;
		}
		return switch (config.showFilterOutlines()) {
			case ON -> true;
			case KEYBIND -> outlinesToggledOn;
			default -> false;
		};
	}

	private static void renderList(LevelRenderContext context, Level level, Vec3 cameraPos, Set<String> keys, float r, float g, float b) {
		if (keys.isEmpty()) {
			return;
		}

		PoseStack poseStack = context.poseStack();

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

			AABB outlineBox = box;

			poseStack.pushPose();
			poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

			// 26.2: submit line geometry to the deferred collector instead of drawing into a buffer source directly.
			context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lines(),
				(pose, consumer) -> drawBox(pose, consumer, outlineBox, r, g, b, 0.8f));

			poseStack.popPose();
		}
	}

	private static void drawBox(PoseStack.Pose pose, VertexConsumer consumer, AABB box, float r, float g, float b, float a) {
		float minX = (float) box.minX;
		float minY = (float) box.minY;
		float minZ = (float) box.minZ;
		float maxX = (float) box.maxX;
		float maxY = (float) box.maxY;
		float maxZ = (float) box.maxZ;

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
