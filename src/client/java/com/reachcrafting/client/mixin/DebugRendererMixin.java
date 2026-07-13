package com.reachcrafting.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric API for 1.21.9 shipped no world-render events (the hooks removed by
 * the 1.21.6 render-pipeline rewrite only returned in fabric-api 0.138 for
 * 1.21.10, and 0.134.1 is the final 1.21.9 build), so the container filter
 * outlines piggyback on vanilla's per-frame debug render pass instead: it
 * runs every frame with the pose stack, a line-capable buffer source, and
 * the camera position — everything the outlines need.
 */
@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDDZ)V", at = @At("TAIL"))
	private void reachcrafting$renderFilterOutlines(PoseStack poseStack, Frustum frustum, MultiBufferSource.BufferSource bufferSource, double camX, double camY, double camZ, boolean bl, CallbackInfo ci) {
		com.reachcrafting.client.ContainerFilterRenderer.renderOutlines(poseStack, bufferSource, camX, camY, camZ);
	}
}
