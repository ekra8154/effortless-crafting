package com.reachcrafting.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

/**
 * In-world container-filter outline rendering.
 *
 * <p>Fabric API for this Minecraft version ships no world-render event hooks
 * (removed by the 1.21.6 render-pipeline rewrite; reintroduced only in
 * fabric-api 0.138 for 1.21.10, while 0.134.1 is the final 1.21.9 build), so
 * instead of an event this version draws through {@code DebugRendererMixin},
 * which calls {@link #renderOutlines} from vanilla's per-frame debug render
 * pass with a line-capable buffer source.</p>
 */
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

	/** Called by DebugRendererMixin every frame from vanilla's debug render pass. */
	public static void renderOutlines(PoseStack poseStack, MultiBufferSource bufferSource, double camX, double camY, double camZ) {
		if (!areOutlinesVisible()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}

		Level level = client.level;
		Vec3 cameraPos = new Vec3(camX, camY, camZ);
		VertexConsumer consumer = bufferSource.getBuffer(net.minecraft.client.renderer.RenderType.lines());

		renderList(poseStack, consumer, level, cameraPos, InWorldFilterManager.getBlacklistedKeys(), 0.0f, 0.0f, 0.0f); // Black
		renderList(poseStack, consumer, level, cameraPos, InWorldFilterManager.getWhitelistedKeys(), 1.0f, 1.0f, 1.0f); // White
	}

	private static void renderList(PoseStack poseStack, VertexConsumer consumer, Level level, Vec3 cameraPos, Set<String> keys, float r, float g, float b) {
		if (keys.isEmpty()) {
			return;
		}

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

			ShapeRenderer.renderLineBox(poseStack.last(), consumer, box, r, g, b, 0.8f);

			poseStack.popPose();
		}
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
