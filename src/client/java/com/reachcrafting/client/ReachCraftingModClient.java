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
// import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.lwjgl.glfw.GLFW;

public class ReachCraftingModClient implements ClientModInitializer {
	private static KeyMapping debugPingKey;
	public static KeyMapping showFilterOutlinesKey;
	public static KeyMapping quickCraftKey;
	public static boolean forceNextInventorySearchFocus = false;

	@Override
	public void onInitializeClient() {
		ReachCraftingConfig.load();
		NearbyContainerCache.init();
		RecipeBookClickCapture.init();
		NearbyContainerDryRun.init();
		ContainerFilterRenderer.init();
		


		KeyMapping.Category reachCraftingCategory = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(ReachCraftingMod.MOD_ID, "debug")
		);

		debugPingKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.reachcrafting.debug_ping",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_F8,
			reachCraftingCategory
		));

		showFilterOutlinesKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.reachcrafting.show_filter_outlines",
			InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.getValue(),
			reachCraftingCategory
		));

		quickCraftKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.reachcrafting.quick_craft",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_B,
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

			while (quickCraftKey.consumeClick()) {
				attemptQuickCraft(client);
			}
		});
	}

	private void attemptQuickCraft(net.minecraft.client.Minecraft client) {
		if (client.player == null || client.level == null) return;

		net.minecraft.world.phys.Vec3 eyePos = client.player.getEyePosition(0);
		double reach = client.player.blockInteractionRange();
		net.minecraft.core.BlockPos tablePos = ContainerUtils.findNearestCraftingTable(client.level, eyePos, reach);

		if (tablePos != null) {
			net.minecraft.world.phys.Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, tablePos);
			net.minecraft.core.Direction face = net.minecraft.core.Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
			net.minecraft.world.phys.BlockHitResult hitResult = new net.minecraft.world.phys.BlockHitResult(hitPos, face, tablePos, false);
			client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.MAIN_HAND, hitResult);
		} else {
			forceNextInventorySearchFocus = true;
			client.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
		}
	}
}
