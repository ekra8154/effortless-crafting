package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ReachCraftingModClient implements ClientModInitializer {
	private static KeyMapping debugPingKey;

	@Override
	public void onInitializeClient() {
		ReachCraftingConfig.load();
		NearbyContainerCache.init();
		RecipeBookClickCapture.init();
		NearbyContainerDryRun.init();

		KeyMapping.Category reachCraftingCategory = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(ReachCraftingMod.MOD_ID, "debug")
		);

		debugPingKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.reachcrafting.debug_ping",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_F8,
			reachCraftingCategory
		));

		int[] tickCounter = {0};
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			tickCounter[0]++;
			if (tickCounter[0] >= 200) {
				tickCounter[0] = 0;
				InWorldFilterManager.validateFilters(client.level);
				InWorldFilterManager.saveIfDirty();
			}

			while (debugPingKey.consumeClick()) {
				ReachCraftingMod.LOGGER.info("[debug_ping] manual debug ping triggered");
				if (client.player != null) {
					client.player.displayClientMessage(
						Component.literal("Reach Crafting debug ping logged.")
							.withStyle(ChatFormatting.AQUA),
						true
					);
				}
			}
		});
	}
}
