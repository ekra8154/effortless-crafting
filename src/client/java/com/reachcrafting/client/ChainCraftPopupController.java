package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

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
	// The confirm here is a ConfirmScreen, which REPLACES the crafting screen
	// rather than overlaying it the way 1.21.2+'s PopupScreen does, and the
	// restore is queued with client.tell() so the trailing char event lands on
	// the closing popup. That leaves a window where the plan is pending but
	// client.screen is still the popup -- and tick() read that as the player
	// having navigated away, so a confirmed chain died with "crafting screen
	// changed" instead of starting. Wait for the queued restore instead.
	private static int awaitingBackgroundRestoreTicks;
	private static final int BACKGROUND_RESTORE_WAIT_TICKS = 20;

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

		showConfirmPopup(messageFor(plan, requestedRecipeCopies), new PendingPopup(plan, deferredMissingMessage, null, null, null));
	}

	/**
	 * Chain offer for a click that output variant switching may continue on
	 * other family variants. {@code onConfirm} arms that continuation and
	 * runs on Yes (or immediately in ALWAYS mode); a decline arms nothing.
	 */
	static void handlePlanWithVariantSwitching(ChainCraftPlan plan, int requestedRecipeCopies, String deferredMissingMessage, int variantTotalCopies, Runnable onConfirm) {
		ReachCraftingConfig.ChainCraftingMode mode = ReachCraftingConfig.get().chainCraftingMode();
		if (mode == ReachCraftingConfig.ChainCraftingMode.DISABLED || plan == null) {
			return;
		}
		if (mode == ReachCraftingConfig.ChainCraftingMode.ALWAYS) {
			if (requestedRecipeCopies > plan.finalRecipeCopies()) {
				ReachCraftingModClient.sendChainCraftChat(alwaysPartialMessage(plan, requestedRecipeCopies).getString());
			}
			onConfirm.run();
			ChainCraftController.start(plan);
			return;
		}
		Component message = variantTotalCopies > plan.finalRecipeCopies()
			? variantMessageFor(plan, requestedRecipeCopies, variantTotalCopies)
			: messageFor(plan, requestedRecipeCopies);
		showConfirmPopup(message, new PendingPopup(plan, deferredMissingMessage, null, onConfirm, null));
	}

	private static Component variantMessageFor(ChainCraftPlan plan, int requestedRecipeCopies, int variantTotalCopies) {
		int outputPerCraft = Math.max(plan.finalOutput().getCount(), 1);
		return Component.translatable(
			"popup.reachcrafting.chain_crafting.variant_message",
			variantTotalCopies * outputPerCraft,
			requestedRecipeCopies * outputPerCraft
		);
	}

	static void handleBulkChainPlan(
		ChainCraftPlan plan,
		RecipeVariantResolver.Selection selection,
		boolean allowNearby,
		int requestedRecipeCopies,
		boolean maxRequest,
		String deferredMissingMessage
	) {
		handleBulkChainPlan(plan, selection, allowNearby, requestedRecipeCopies, maxRequest, deferredMissingMessage, null, plan != null ? plan.finalRecipeCopies() : 0);
	}

	/**
	 * {@code family} non-null means Output Variant Switching may carry the
	 * session onto other variants; {@code variantTotalCopies} is what the
	 * whole family can reach (each variant planned on its own, an upper
	 * bound), used for the prompt and to size a count request's target.
	 */
	static void handleBulkChainPlan(
		ChainCraftPlan plan,
		RecipeVariantResolver.Selection selection,
		boolean allowNearby,
		int requestedRecipeCopies,
		boolean maxRequest,
		String deferredMissingMessage,
		BulkChainCraftController.VariantFamily family,
		int variantTotalCopies
	) {
		ReachCraftingConfig.ChainCraftingMode mode = ReachCraftingConfig.get().chainCraftingMode();
		if (mode == ReachCraftingConfig.ChainCraftingMode.DISABLED || plan == null || selection == null) {
			return;
		}
		boolean switching = family != null && variantTotalCopies > plan.finalRecipeCopies();
		// With switching, a max request keeps going until no variant is
		// left (uncapped target); a count request aims for the count, capped
		// at what the family can reach.
		int targetCopies = !switching
			? plan.finalRecipeCopies()
			: maxRequest
				? RecipeClickExecutor.bulkRecipeQueueLimit()
				: Math.min(requestedRecipeCopies, variantTotalCopies);
		boolean downgraded = !maxRequest && requestedRecipeCopies > (switching ? variantTotalCopies : plan.finalRecipeCopies());
		// A single-step plan is pure direct crafting, the case the yellow
		// craftable indicator promises needs no chaining. Flat bulk max never
		// prompts for that, so neither does bulk chain; the per-iteration
		// replans still add conversion steps later if directs run dry.
		if (mode == ReachCraftingConfig.ChainCraftingMode.ALWAYS || plan.steps().size() <= 1) {
			if (downgraded) {
				ReachCraftingModClient.sendChainCraftChat(bulkAlwaysPartialMessage(plan, requestedRecipeCopies).getString());
			}
			BulkChainCraftController.start(selection, allowNearby, targetCopies, family);
			return;
		}

		Component message = switching
			? bulkVariantMessageFor(plan, requestedRecipeCopies, maxRequest, variantTotalCopies)
			: bulkMessageFor(plan, requestedRecipeCopies, maxRequest);
		showConfirmPopup(
			message,
			new PendingPopup(null, deferredMissingMessage, new BulkChainRequest(selection, allowNearby, targetCopies, family), null, null)
		);
	}

	private static Component bulkVariantMessageFor(ChainCraftPlan plan, int requestedRecipeCopies, boolean maxRequest, int variantTotalCopies) {
		int outputPerCraft = Math.max(plan.finalOutput().getCount(), 1);
		if (maxRequest) {
			return Component.translatable(
				"popup.reachcrafting.chain_crafting.bulk_variant_max_message",
				variantTotalCopies * outputPerCraft
			);
		}
		return Component.translatable(
			"popup.reachcrafting.chain_crafting.bulk_variant_message",
			variantTotalCopies * outputPerCraft,
			requestedRecipeCopies * outputPerCraft
		);
	}

	/**
	 * A Yes/No prompt for another feature (retrieve-then-craft) that wants the
	 * same keyboard and harness handling as the chain prompt. Exactly one of
	 * the two callbacks runs: confirm on Yes/Enter/Space, cancel on No/Esc.
	 */
	static void showConfirm(Component title, Component message, Runnable onConfirm, Runnable onCancel) {
		showConfirmPopup(title, message, new PendingPopup(null, null, null, onConfirm, onCancel));
	}

	private static void showConfirmPopup(Component message, PendingPopup pending) {
		showConfirmPopup(Component.translatable("popup.reachcrafting.chain_crafting.title"), message, pending);
	}

	private static void showConfirmPopup(Component title, Component message, PendingPopup pending) {
		Minecraft client = Minecraft.getInstance();
		Screen background = client.screen;
		if (!(background instanceof CraftingScreen) && !(background instanceof InventoryScreen)) {
			if (pending.onCancel() != null) {
				pending.onCancel().run();
			}
			return;
		}
		ReachCraftingMod.diag("[chain_popup] title={} message={}", title.getString(), message.getString());

		java.util.concurrent.atomic.AtomicBoolean resolved = new java.util.concurrent.atomic.AtomicBoolean(false);
		it.unimi.dsi.fastutil.booleans.BooleanConsumer callback = accepted -> {
			if (!resolved.compareAndSet(false, true)) {
				return;
			}
			if (accepted) {
				if (pending.onConfirm() != null) {
					pending.onConfirm().run();
				}
				if (pending.bulkChain() != null) {
					pendingBulkChainStart = pending.bulkChain();
				} else if (pending.plan() != null) {
					pendingStartPlan = pending.plan();
				}
			} else {
				sendDeferredMissing(pending);
			}
			awaitingBackgroundRestoreTicks = BACKGROUND_RESTORE_WAIT_TICKS;
			// Defer the screen swap to the next tick: when the popup is confirmed with Space/Enter,
			// GLFW still delivers the trailing char event this frame, and a synchronous swap would
			// route that character into the restored screen's focused search box (replacing its
			// selected text). Queued via tell(), the char lands harmlessly on the closing popup.
			client.tell(() -> client.setScreen(background));
		};
		ConfirmScreen popup = new ConfirmScreen(
			callback,
			title,
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

	/**
	 * Names the raw material that ran out, when the planner identified one.
	 * "Not enough Leather for 57 Lectern" is the same length as "Not enough
	 * resources for 57 Lectern" and tells the player what to go and get.
	 * Falls back to the generic wording when nothing identifiable ran short.
	 */
	private static Component partial(String baseKey, ChainCraftPlan plan, int requested, int achievable) {
		String itemName = plan.finalOutput().getHoverName().getString();
		String limiting = limitingItemName(plan);
		if (limiting == null) {
			return Component.translatable(baseKey, requested, itemName, achievable);
		}
		return Component.translatable(baseKey + "_limited", limiting, requested, itemName, achievable);
	}

	private static String limitingItemName(ChainCraftPlan plan) {
		if (plan.limitingItemId() == null) {
			return null;
		}
		ResourceLocation id = ResourceLocation.tryParse(plan.limitingItemId());
		if (id == null) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.get(id);
		// An unregistered id would render as "air"; better to say nothing.
		return item == null || item == Items.AIR ? null : new ItemStack(item).getHoverName().getString();
	}

	private static Component messageFor(ChainCraftPlan plan, int requestedRecipeCopies) {
		if (requestedRecipeCopies <= plan.finalRecipeCopies()) {
			return Component.translatable("popup.reachcrafting.chain_crafting.message");
		}
		int outputPerCraft = Math.max(plan.finalOutput().getCount(), 1);
		return partial(
			"popup.reachcrafting.chain_crafting.partial_message",
			plan,
			requestedRecipeCopies * outputPerCraft,
			plan.finalRecipeCopies() * outputPerCraft
		);
	}

	private static Component alwaysPartialMessage(ChainCraftPlan plan, int requestedRecipeCopies) {
		int outputPerCraft = Math.max(plan.finalOutput().getCount(), 1);
		return partial(
			"message.reachcrafting.chain_crafting.partial_always",
			plan,
			requestedRecipeCopies * outputPerCraft,
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
		return partial(
			"popup.reachcrafting.chain_crafting.bulk_partial_message",
			plan,
			requestedRecipeCopies * outputPerCraft,
			achievableItems
		);
	}

	private static Component bulkAlwaysPartialMessage(ChainCraftPlan plan, int requestedRecipeCopies) {
		int outputPerCraft = Math.max(plan.finalOutput().getCount(), 1);
		return partial(
			"message.reachcrafting.chain_crafting.bulk_partial_always",
			plan,
			requestedRecipeCopies * outputPerCraft,
			plan.finalRecipeCopies() * outputPerCraft
		);
	}

	static void tick(Minecraft client) {
		if (pendingStartPlan == null && pendingBulkChainStart == null) {
			return;
		}
		boolean onCraftingScreen = client.screen instanceof CraftingScreen || client.screen instanceof InventoryScreen;
		if (client.player != null && !onCraftingScreen && awaitingBackgroundRestoreTicks > 0) {
			// The queued setScreen(background) has not run yet; this is the
			// popup on its way out, not the player leaving.
			awaitingBackgroundRestoreTicks--;
			return;
		}
		if (client.player == null || !onCraftingScreen) {
			pendingStartPlan = null;
			pendingBulkChainStart = null;
			awaitingBackgroundRestoreTicks = 0;
			ReachCraftingModClient.sendChat(Component.translatable("message.reachcrafting.chain_crafting.context_lost").getString());
			return;
		}
		awaitingBackgroundRestoreTicks = 0;
		if (pendingBulkChainStart != null) {
			BulkChainRequest request = pendingBulkChainStart;
			pendingBulkChainStart = null;
			BulkChainCraftController.start(request.selection(), request.allowNearby(), request.targetCopies(), request.family());
			return;
		}
		ChainCraftPlan plan = pendingStartPlan;
		pendingStartPlan = null;
		ChainCraftController.start(plan);
	}

	private static void sendDeferredMissing(PendingPopup pending) {
		if (pending.onCancel() != null) {
			pending.onCancel().run();
		}
		if (pending.deferredMissingMessage() != null && !pending.deferredMissingMessage().isBlank()) {
			ReachCraftingModClient.sendMissingIngredientsChat(pending.deferredMissingMessage());
		}
	}

	private record PendingPopup(ChainCraftPlan plan, String deferredMissingMessage, BulkChainRequest bulkChain, Runnable onConfirm, Runnable onCancel) {
	}

	private record BulkChainRequest(RecipeVariantResolver.Selection selection, boolean allowNearby, int targetCopies, BulkChainCraftController.VariantFamily family) {
	}
}
