package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReproHarness;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLHints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev-only: when the repro client launches in "quiet" mode, tell SDL not to
 * activate the window when it is shown. That stops the from-source test client
 * from stealing foreground / flashing the taskbar orange on launch. The hint is
 * set just before Minecraft creates its window (26.3 moved from GLFW to SDL3,
 * and SDL shows the window as part of creating it). Inert unless
 * ReproHarness.suppressWindowFocus() (the repro launch flag, only ever set in a
 * development environment).
 */
@Mixin(Window.class)
public class WindowMixin {
	@Inject(method = "createWindow", at = @At("HEAD"), require = 1)
	private void reachcrafting$createWithoutStealingFocus(CallbackInfoReturnable<Long> cir) {
		if (ReproHarness.suppressWindowFocus()) {
			SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
		}
	}
}
