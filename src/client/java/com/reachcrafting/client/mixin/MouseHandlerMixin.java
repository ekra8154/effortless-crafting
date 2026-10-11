package com.reachcrafting.client.mixin;

import com.reachcrafting.client.CursorRescueWatchdog;
import com.reachcrafting.client.ReproHarness;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
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
	/**
	 * Every physical mouse button event, before any screen or other mod
	 * (Mouse Tweaks, Tweakeroo) can consume it: the cursor watchdog uses it
	 * to tell a stack the player picked up from one automation left behind.
	 */
	@Inject(method = "onButton", at = @At("HEAD"))
	private void reachcrafting$notePlayerInput(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		CursorRescueWatchdog.notePlayerInput();
	}

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$keepCursorFree(CallbackInfo ci) {
		if (ReproHarness.freeMouseActive()) {
			ci.cancel();
		}
	}
}
