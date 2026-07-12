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
	private static BulkChainRequest pendingBulkChainStart;
	private static boolean openingConfirmPopup;

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

		showConfirmPopup(messageFor(plan, requestedRecipeCopies), new PendingPopup(plan, deferredMissingMessage, null));
	}

	static void handleBulkChainPlan(
		ChainCraftPlan plan,
		RecipeVariantResolver.Selection selection,
		boolean allowNearby,
		int requestedRecipeCopies,
		boolean maxRequest,
		String deferredMissingMessage
	) {
		ReachCraftingConfig.ChainCraftingMode mode = ReachCraftingConfig.get().chainCraftingMode();
		if (mode == ReachCraftingConfig.ChainCraftingMode.DISABLED || plan == null || selection == null) {
			return;
		}
		boolean downgraded = !maxRequest && requestedRecipeCopies > plan.finalRecipeCopies();
		// A single-step plan is pure direct crafting — the case the yellow
		// craftable indicator promises needs no chaining. Flat bulk max never
		// prompts for that, so neither does bulk chain; the per-iteration
		// replans still add conversion steps later if directs run dry.
		if (mode == ReachCraftingConfig.ChainCraftingMode.ALWAYS || plan.steps().size() <= 1) {
			if (downgraded) {
				ReachCraftingModClient.sendChainCraftChat(bulkAlwaysPartialMessage(plan, requestedRecipeCopies).getString());
			}
			BulkChainCraftController.start(selection, allowNearby, plan.finalRecipeCopies());
			return;
		}

		showConfirmPopup(
			bulkMessageFor(plan, requestedRecipeCopies, maxRequest),
			new PendingPopup(null, deferredMissingMessage, new BulkChainRequest(selection, allowNearby, plan.finalRecipeCopies()))
		);
	}

	private static void showConfirmPopup(Component message, PendingPopup pending) {
		Minecraft client = Minecraft.getInstance();
		Screen background = client.screen;
		if (!(background instanceof CraftingScreen) && !(background instanceof InventoryScreen)) {
			return;
		}

		java.util.concurrent.atomic.AtomicBoolean resolved = new java.util.concurrent.atomic.AtomicBoolean(false);
		it.unimi.dsi.fastutil.booleans.BooleanConsumer callback = accepted -> {
			if (!resolved.compareAndSet(false, true)) {
				return;
			}
			if (accepted) {
				if (pending.bulkChain() != null) {
					pendingBulkChainStart = pending.bulkChain();
				} else {
					pendingStartPlan = pending.plan();
				}
			} else {
				sendDeferredMissing(pending);
			}
			// Defer the screen swap to the next tick: when the popup is confirmed with Space/Enter,
			// GLFW still delivers the trailing char event this frame, and a synchronous swap would
			// route that character into the restored screen's focused search box (replacing its
			// selected text). Queued via tell(), the char lands harmlessly on the closing popup.
			client.tell(() -> client.setScreen(background));
		};
		ConfirmScreen popup = new ConfirmScreen(
			callback,
			Component.translatable("popup.reachcrafting.chain_crafting.title"),
			message,
			Component.translatable("popup.reachcrafting.chain_crafting.yes"),
			Component.translatable("popup.reachcrafting.chain_crafting.no")
		) {
			@Override
			public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
				// Match the modern PopupScreen behavior: Enter / numpad-Enter / Space confirms.
				if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
					|| keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
					|| keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
					callback.accept(true);
					return true;
				}
				return super.keyPressed(keyCode, scanCode, modifiers);
			}

			@Override
			public void onClose() {
				// ESC = cancel; restore the crafting screen instead of vanilla's setScreen(null).
				callback.accept(false);
			}
		};
		// Swapping in the popup fires the crafting screen's removed(), which
		// the close mixin must not mistake for the container closing: that
		// would wipe the bulk latch and pulled-resource tracking mid-flow.
		openingConfirmPopup = true;
		try {
			client.setScreen(popup);
		} finally {
			openingConfirmPopup = false;
		}
	}

	/** No popup-screen click hooks on this version; present for init-call parity. */
	static void init() {
	}

	public static boolean isOpeningConfirmPopup() {
		return openingConfirmPopup;
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

	private static Component bulkMessageFor(ChainCraftPlan plan, int requestedRecipeCopies, boolean maxRequest) {
		int outputPerCraft = Math.max(plan.finalOutput().getCount(), 1);
		String itemName = plan.finalOutput().getHoverName().getString();
		int achievableItems = plan.finalRecipeCopies() * outputPerCraft;
		if (maxRequest) {
			return Component.translatable(
				"popup.reachcrafting.chain_crafting.bulk_max_message",
				achievableItems,
				itemName
			);
		}
		if (requestedRecipeCopies <= plan.finalRecipeCopies()) {
			return Component.translatable(
				"popup.reachcrafting.chain_crafting.bulk_message",
				achievableItems,
				itemName
			);
		}
		return Component.translatable(
			"popup.reachcrafting.chain_crafting.bulk_partial_message",
			requestedRecipeCopies * outputPerCraft,
			itemName,
			achievableItems
		);
	}

	private static Component bulkAlwaysPartialMessage(ChainCraftPlan plan, int requestedRecipeCopies) {
		int outputPerCraft = Math.max(plan.finalOutput().getCount(), 1);
		String itemName = plan.finalOutput().getHoverName().getString();
		return Component.translatable(
			"message.reachcrafting.chain_crafting.bulk_partial_always",
			requestedRecipeCopies * outputPerCraft,
			itemName,
			plan.finalRecipeCopies() * outputPerCraft
		);
	}

	static void tick(Minecraft client) {
		if (pendingStartPlan == null && pendingBulkChainStart == null) {
			return;
		}
		if (client.player == null || (!(client.screen instanceof CraftingScreen) && !(client.screen instanceof InventoryScreen))) {
			pendingStartPlan = null;
			pendingBulkChainStart = null;
			ReachCraftingModClient.sendChat(Component.translatable("message.reachcrafting.chain_crafting.context_lost").getString());
			return;
		}
		if (pendingBulkChainStart != null) {
			BulkChainRequest request = pendingBulkChainStart;
			pendingBulkChainStart = null;
			BulkChainCraftController.start(request.selection(), request.allowNearby(), request.targetCopies());
			return;
		}
		ChainCraftPlan plan = pendingStartPlan;
		pendingStartPlan = null;
		ChainCraftController.start(plan);
	}

	private static void sendDeferredMissing(PendingPopup pending) {
		if (pending.deferredMissingMessage() != null && !pending.deferredMissingMessage().isBlank()) {
			ReachCraftingModClient.sendMissingIngredientsChat(pending.deferredMissingMessage());
		}
	}

	private record PendingPopup(ChainCraftPlan plan, String deferredMissingMessage, BulkChainRequest bulkChain) {
	}

	private record BulkChainRequest(RecipeVariantResolver.Selection selection, boolean allowNearby, int targetCopies) {
	}
>>>>>>> 9f7bf3e (Add bulk chain crafting)
}
