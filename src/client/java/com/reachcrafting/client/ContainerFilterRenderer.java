package com.reachcrafting.client;

/**
 * In-world container-filter outline rendering.
 *
 * <p>DISABLED on this Minecraft version. The 1.21.6–1.21.9 render-pipeline rewrite removed the
 * world-render event hooks from Fabric API: fabric-api 0.134.1 (the build for 1.21.9) ships no
 * {@code net.fabricmc.fabric.api.client.rendering.v1[.world].WorldRenderEvents}/{@code WorldRenderContext}.
 * The event was (re)introduced under {@code rendering.v1.world} in fabric-api 0.138 (1.21.10), and the
 * older {@code rendering.v1} location is used on 1.21.5 and below. With no available world-render hook on
 * this version, the outline overlay is a no-op here; the rest of the mod is unaffected.
 */
public final class ContainerFilterRenderer {
	private ContainerFilterRenderer() {
	}

	public static void init() {
		// No world-render event API available on this MC version; outlines are unavailable.
	}
}
