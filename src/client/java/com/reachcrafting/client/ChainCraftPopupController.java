package com.reachcrafting.client;

import java.util.Map;
import java.util.WeakHashMap;
import com.reachcrafting.client.mixin.PopupScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PopupScreen;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class ChainCraftPopupController {
	private static final Map<PopupScreen, PendingPopup> PENDING_POPUPS = new WeakHashMap<>();
	private static ChainCraftPlan pendingStartPlan;
	private static BulkChainRequest pendingBulkChainStart;
	private static boolean openingConfirmPopup;

	private ChainCraftPopupController() {
	}

	static void init() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof PopupScreen popup) || !isChainCraftPopup(popup)) {
				return;
			}
			ScreenMouseEvents.allowMouseClick(screen).register((currentScreen, click) -> {
				if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !isChainCraftPopup(popup)) {
					return true;
				}
				LinearLayout layout = ((PopupScreenAccessor) popup).reachcrafting$getLayout();
				int left = layout.getX() - 18;
				int top = layout.getY() - 18;
				int right = layout.getX() + layout.getWidth() + 18;
				int bottom = layout.getY() + layout.getHeight() + 18;
				if (click.x() < left || click.x() > right || click.y() < top || click.y() > bottom) {
					cancel(popup);
					return false;
				}
				return true;
			});
		});
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
		if (mode == ReachCraftingConfig.ChainCraftingMode.ALWAYS) {
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
		Screen background = client.gui.screen();
		if (!(background instanceof CraftingScreen) && !(background instanceof InventoryScreen)) {
			return;
		}

		PopupScreen popup = new PopupScreen.Builder(
			background,
			Component.translatable("popup.reachcrafting.chain_crafting.title")
		)
			.setWidth(260)
			.addMessage(message)
			.addButton(Component.translatable("popup.reachcrafting.chain_crafting.yes"), ChainCraftPopupController::confirm)
			.addButton(Component.translatable("popup.reachcrafting.chain_crafting.no"), ChainCraftPopupController::cancel)
			.onClose(() -> {
			})
			.build();

		PENDING_POPUPS.put(popup, pending);
		// Swapping in the popup fires the crafting screen's removed(), which
		// the close mixin must not mistake for the container closing: that
		// would wipe the bulk latch and pulled-resource tracking mid-flow.
		openingConfirmPopup = true;
		try {
			client.setScreenAndShow(popup);
		} finally {
			openingConfirmPopup = false;
		}
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

	public static boolean confirm(PopupScreen popup) {
		PendingPopup pending = PENDING_POPUPS.remove(popup);
		if (pending == null) {
			return false;
		}
		if (pending.bulkChain() != null) {
			pendingBulkChainStart = pending.bulkChain();
		} else {
			pendingStartPlan = pending.plan();
		}
		popup.onClose();
		return true;
	}

	public static boolean cancel(PopupScreen popup) {
		PendingPopup pending = PENDING_POPUPS.remove(popup);
		if (pending == null) {
			return false;
		}
		sendDeferredMissing(pending);
		popup.onClose();
		return true;
	}

	public static boolean isChainCraftPopup(PopupScreen popup) {
		return PENDING_POPUPS.containsKey(popup);
	}

	public static void closed(PopupScreen popup) {
		PendingPopup pending = PENDING_POPUPS.remove(popup);
		if (pending != null) {
			sendDeferredMissing(pending);
		}
	}

	static void tick(Minecraft client) {
		if (pendingStartPlan == null && pendingBulkChainStart == null) {
			return;
		}
		if (client.player == null || (!(client.gui.screen() instanceof CraftingScreen) && !(client.gui.screen() instanceof InventoryScreen))) {
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
}
