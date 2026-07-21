package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReproHarness;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Dev-only: when the repro client launches in "quiet" mode, tell GLFW not to
 * focus the window on show. That stops the from-source test client from
 * stealing foreground / flashing the taskbar orange on launch. Redirects the
 * glfwCreateWindow call so the hints are set immediately before window
 * creation; depends only on the stable LWJGL signature, not Minecraft's
 * window-creation internals. Inert unless ReproHarness.suppressWindowFocus()
 * (the repro launch flag, only ever set in a development environment).
 */
@Mixin(Window.class)
public class WindowMixin {
	@Redirect(
		method = "createGlfwWindow",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"
		)
	)
	private static long reachcrafting$createWithoutStealingFocus(int width, int height, CharSequence title, long monitor, long share) {
		if (ReproHarness.suppressWindowFocus()) {
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
			GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
		}
		return GLFW.glfwCreateWindow(width, height, title, monitor, share);
	}
}
