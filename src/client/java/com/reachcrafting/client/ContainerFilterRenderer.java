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
	private static boolean outlinesToggledOn;

	private ContainerFilterRenderer() {
	}

	public static void init() {
		// No world-render event API available on this MC version; outlines are
		// unavailable, but the keybind toggle state and the sneak-click filter
		// cycling latch still tick so behavior matches the other versions.
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
}
