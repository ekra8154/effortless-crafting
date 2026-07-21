package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

public class ReachCraftingModClient implements ClientModInitializer {
	public static KeyMapping showFilterOutlinesKey;
	public static KeyMapping quickCraftKey;
	public static KeyMapping toggleCraftableFilterKey;
	public static boolean forceNextInventorySearchFocus = false;
	
	public static void sendChat(String message) {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Effortless Crafting] " + message).withStyle(net.minecraft.ChatFormatting.GOLD));
		}
	}

	public static void sendDebugChat(String message) {
		if (ReachCraftingConfig.get().debugMessagesEnabled()) {
			sendChat(message);
		}
	}

	public static void sendAbortedChat(String message) {
		if (ReachCraftingConfig.get().showCraftAbortedMessage()) {
			sendChat(message);
		}
	}

	public static void sendBulkSummaryChat(String message) {
		// Degraded support on 1.20.1: summaries are sent again (honoring the
		// config toggle), but the compose sites never state item counts —
		// output accounting has never been reliable on this version (see
		// "failed attempts to make chat output accurate"). Elapsed time and
		// item names are fine.
		if (ReachCraftingConfig.get().showBulkCraftSummaryMessage()) {
			sendChat(message);
		}
	}

	public static void sendMissingIngredientsChat(String message) {
		if (ReachCraftingConfig.get().showMissingIngredientsMessage()) {
			sendChat(message);
		}
	}

	public static void sendChainCraftChat(String message) {
		if (ReachCraftingConfig.get().showChainCraftMessages()) {
			sendChat(message);
		}
	}

	@Override
	public void onInitializeClient() {
		ReachCraftingConfig.load();
		NearbyContainerCache.init();
		RecipeBookClickCapture.init();
		NearbyContainerDryRun.init();
		RecipeBookChunkedScheduler.init();
		ChainCraftabilityCache.init();
		ChainCraftPopupController.init();
		ChainCraftController.init();
		BulkChainCraftController.init();
		GridExtractor.init();
		CursorRescueWatchdog.init();
		TickStallWatchdog.init();
		ContainerFilterRenderer.init();
		ChainCraftabilityCache.init();
		RecipeBookChunkedScheduler.init();
		ChainCraftController.init();
		RecipeBookScrollController.init();
		PlaceRecipeBudget.init();
		ReproHarness.init();
		String reachCraftingCategory = "key.categories." + ReachCraftingMod.MOD_ID;

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

		toggleCraftableFilterKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.reachcrafting.toggle_craftable_filter",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_SPACE,
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

			if (!ReachCraftingConfig.get().enabled()) {
				// Flush any pending clicks/keys just in case
				while (quickCraftKey.consumeClick());
				while (toggleCraftableFilterKey.consumeClick());
				return;
			}

			while (quickCraftKey.consumeClick()) {
				attemptQuickCraft(client);
			}

			// toggleCraftableFilterKey is handled in RecipeBookComponentMixin for screens
			// but we consume it here to prevent it from piling up if pressed outside screens
			while (toggleCraftableFilterKey.consumeClick());
		});
	}

	private void attemptQuickCraft(net.minecraft.client.Minecraft client) {
		if (client.player == null || client.level == null) return;

		net.minecraft.world.phys.Vec3 eyePos = client.player.getEyePosition(0);
		double reach = client.player != null ? client.player.blockInteractionRange() : 4.5D;
		net.minecraft.core.BlockPos tablePos = ContainerUtils.findNearestCraftingTable(client.level, eyePos, reach);

		if (tablePos != null) {
			net.minecraft.world.phys.Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, tablePos);
			net.minecraft.world.phys.Vec3 delta = hitPos.subtract(eyePos);
			net.minecraft.core.Direction face = net.minecraft.core.Direction.getNearest(delta.x, delta.y, delta.z).getOpposite();
			net.minecraft.world.phys.BlockHitResult hitResult = new net.minecraft.world.phys.BlockHitResult(hitPos, face, tablePos, false);
			client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.MAIN_HAND, hitResult);
		} else {
			forceNextInventorySearchFocus = true;
			client.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
		}
	}
}
