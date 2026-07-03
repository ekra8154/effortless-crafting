package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * Handles the chain-craft confirmation flow.
 *
 * <p>1.20.1 has no {@code net.minecraft.client.gui.components.PopupScreen} (introduced in a later
 * version), so the CONFIRM prompt is presented with the vanilla {@link ConfirmScreen} yes/no dialog.
 * On confirm we stash the plan and re-launch it via {@link #tick} once the crafting/inventory screen
 * is showing again.</p>
 */
public final class ChainCraftPopupController {
	private static ChainCraftPlan pendingStartPlan;

	private ChainCraftPopupController() {
	}

	static void handlePlan(ChainCraftPlan plan) {
		handlePlan(plan, plan != null ? plan.finalRecipeCopies() : 0);
	}

	static void handlePlan(ChainCraftPlan plan, int requestedRecipeCopies) {
		handlePlan(plan, requestedRecipeCopies, false, null);
	}

	static void handlePlan(ChainCraftPlan plan, int requestedRecipeCopies, boolean allowDowngradedAlwaysMode) {
		handlePlan(plan, requestedRecipeCopies, allowDowngradedAlwaysMode, null);
	}

	static void handlePlan(ChainCraftPlan plan, int requestedRecipeCopies, boolean allowDowngradedAlwaysMode, String deferredMissingMessage) {
		ReachCraftingConfig.ChainCraftingMode mode = ReachCraftingConfig.get().chainCraftingMode();
		if (mode == ReachCraftingConfig.ChainCraftingMode.DISABLED || plan == null) {
			return;
		}
		boolean downgraded = requestedRecipeCopies > plan.finalRecipeCopies();
		if (mode == ReachCraftingConfig.ChainCraftingMode.ALWAYS) {
			if (downgraded) {
				ReachCraftingModClient.sendChainCraftChat(alwaysPartialMessage(plan, requestedRecipeCopies).getString());
			}
			ChainCraftController.start(plan);
			return;
		}

		Minecraft client = Minecraft.getInstance();
		Screen background = client.screen;
		if (!(background instanceof CraftingScreen) && !(background instanceof InventoryScreen)) {
			return;
		}

		final ChainCraftPlan confirmedPlan = plan;
		final String deferred = deferredMissingMessage;
		ConfirmScreen popup = new ConfirmScreen(
			accepted -> {
				if (accepted) {
					pendingStartPlan = confirmedPlan;
				} else if (deferred != null && !deferred.isBlank()) {
					ReachCraftingModClient.sendMissingIngredientsChat(deferred);
				}
				client.setScreen(background);
			},
			Component.translatable("popup.reachcrafting.chain_crafting.title"),
			messageFor(plan, requestedRecipeCopies),
			Component.translatable("popup.reachcrafting.chain_crafting.yes"),
			Component.translatable("popup.reachcrafting.chain_crafting.no")
		);
		client.setScreen(popup);
	}

	private static Component messageFor(ChainCraftPlan plan, int requestedRecipeCopies) {
		if (requestedRecipeCopies <= plan.finalRecipeCopies()) {
			return Component.translatable("popup.reachcrafting.chain_crafting.message");
		}
		int outputPerCraft = Math.max(plan.finalOutput().getCount(), 1);
		String itemName = plan.finalOutput().getHoverName().getString();
		return Component.translatable(
			"popup.reachcrafting.chain_crafting.partial_message",
			requestedRecipeCopies * outputPerCraft,
			itemName,
			plan.finalRecipeCopies() * outputPerCraft
		);
	}

	private static Component alwaysPartialMessage(ChainCraftPlan plan, int requestedRecipeCopies) {
		int outputPerCraft = Math.max(plan.finalOutput().getCount(), 1);
		String itemName = plan.finalOutput().getHoverName().getString();
		return Component.translatable(
			"message.reachcrafting.chain_crafting.partial_always",
			requestedRecipeCopies * outputPerCraft,
			itemName,
			plan.finalRecipeCopies() * outputPerCraft
		);
	}

	static void tick(Minecraft client) {
		if (pendingStartPlan == null) {
			return;
		}
		if (client.player == null || (!(client.screen instanceof CraftingScreen) && !(client.screen instanceof InventoryScreen))) {
			pendingStartPlan = null;
			ReachCraftingModClient.sendChat(Component.translatable("message.reachcrafting.chain_crafting.context_lost").getString());
			return;
		}
		ChainCraftPlan plan = pendingStartPlan;
		pendingStartPlan = null;
		ChainCraftController.start(plan);
	}
}
