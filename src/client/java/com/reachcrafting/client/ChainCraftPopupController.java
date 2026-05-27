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
		ReachCraftingConfig.ChainCraftingMode mode = ReachCraftingConfig.get().chainCraftingMode();
		if (mode == ReachCraftingConfig.ChainCraftingMode.DISABLED || plan == null) {
			return;
		}
		if (mode == ReachCraftingConfig.ChainCraftingMode.ALWAYS) {
			ChainCraftController.start(plan);
			return;
		}

		Minecraft client = Minecraft.getInstance();
		Screen background = client.screen;
		if (!(background instanceof CraftingScreen) && !(background instanceof InventoryScreen)) {
			return;
		}

		PopupScreen popup = new PopupScreen.Builder(
			background,
			Component.translatable("popup.reachcrafting.chain_crafting.title")
		)
			.setWidth(260)
			.addMessage(Component.translatable("popup.reachcrafting.chain_crafting.message"))
			.addButton(Component.translatable("popup.reachcrafting.chain_crafting.yes"), ChainCraftPopupController::confirm)
			.addButton(Component.translatable("popup.reachcrafting.chain_crafting.no"), ChainCraftPopupController::cancel)
			.onClose(() -> {
			})
			.build();

		PENDING_POPUPS.put(popup, new PendingPopup(plan));
		client.setScreen(popup);
	}

	public static boolean confirm(PopupScreen popup) {
		PendingPopup pending = PENDING_POPUPS.remove(popup);
		if (pending == null) {
			return false;
		}
		pendingStartPlan = pending.plan();
		popup.onClose();
		return true;
	}

	public static boolean cancel(PopupScreen popup) {
		if (!PENDING_POPUPS.containsKey(popup)) {
			return false;
		}
		PENDING_POPUPS.remove(popup);
		popup.onClose();
		return true;
	}

	public static boolean isChainCraftPopup(PopupScreen popup) {
		return PENDING_POPUPS.containsKey(popup);
	}

	public static void closed(PopupScreen popup) {
		PENDING_POPUPS.remove(popup);
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

	private record PendingPopup(ChainCraftPlan plan) {
	}
}
