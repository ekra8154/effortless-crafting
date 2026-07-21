package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReproHarness;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev-only: while the repro client's free-mouse mode is active, don't let
 * Minecraft grab/confine the cursor for camera control, so the developer's
 * mouse stays usable across the rest of the desktop. Fully inert unless
 * ReproHarness.freeMouseActive() (gated on the repro launch flag / F6 toggle
 * and only ever true in a development environment).
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$keepCursorFree(CallbackInfo ci) {
		if (ReproHarness.freeMouseActive()) {
			ci.cancel();
		}
	}
}
