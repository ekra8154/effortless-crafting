package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.lwjgl.glfw.GLFW;

public final class RecipeBookClickCapture {
	private static PendingHeldRecipe pendingHeldRecipe;
	private static ReplayBatch replayBatch;
	private static boolean wasControlDown;

	private RecipeBookClickCapture() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean controlDown = isControlKeyDown(client);
			if (wasControlDown && !controlDown) {
				releasePendingHeldRecipe();
			}
			wasControlDown = controlDown;
			processReplayBatch(client);
			if (!controlDown && !ReachCraftingConfig.get().reachCraftHoldAndRelease()) {
				pendingHeldRecipe = null;
			}
		});
	}

	public static void onRecipeButtonClicked(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean shiftModifierDown,
		boolean ctrlModifierDown,
		boolean explicitVariantSelection
	) {
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return;
		}
		if (replayBatch != null) {
			return;
		}

		boolean craftAll = shiftModifierDown || isShiftKeyDown(minecraft);
		boolean allowNearbyFallback = ctrlModifierDown || isControlKeyDown(minecraft);
		if (shouldQueueHeldRecipe(minecraft, allowNearbyFallback, craftAll) && replayBatch == null) {
			queueHeldRecipe(recipeId, collection, displayStack, mouseButton, explicitVariantSelection);
			return;
		}

		executeRecipeButtonClick(
			minecraft,
			player,
			screen,
			recipeId,
			collection,
			displayStack,
			mouseButton,
			craftAll,
			allowNearbyFallback,
			explicitVariantSelection
		);
	}

	private static void executeRecipeButtonClick(
		Minecraft minecraft,
		LocalPlayer player,
		Screen screen,
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean craftAll,
		boolean allowNearbyFallback,
		boolean explicitVariantSelection
	) {
		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		boolean allowReservedGridVariantSwitch = false;
		int desiredVariantCopies = availableItems.hasReservedGrid() && !craftAll
			? currentReservedCraftCopies(availableItems) + 1
			: 1;

		String screenKind = screen instanceof InventoryScreen ? "inventory_2x2" : "crafting_table_3x3";
		RecipeVariantResolver.Selection selectedRecipe = RecipeVariantResolver.resolve(
			minecraft,
			player,
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			explicitVariantSelection,
			allowNearbyFallback,
			availableItems,
			availableItems.inventoryCounts(),
			availableItems.inventoryCounts(),
			craftAll,
			allowReservedGridVariantSwitch,
			desiredVariantCopies
		);
		if (selectedRecipe == null) {
			ReachCraftingMod.LOGGER.warn("[recipe_click] missing RecipeDisplayEntry for recipe_index={}", recipeId.index());
			return;
		}
		boolean craftable = collection.isCraftable(recipeId);
		int recipeIndex = selectedRecipe.recipeId().index();
		if (!selectedRecipe.recipeId().equals(recipeId)) {
			ReachCraftingMod.LOGGER.info(
				"[recipe_variant] clicked_idx={} selected_idx={} mode={} output={}",
				recipeId.index(),
				selectedRecipe.recipeId().index(),
				ReachCraftingConfig.get().revolvingCraftHandling().name().toLowerCase(),
				selectedRecipe.outputLabel()
			);
		}

		ItemStack resolvedDisplayStack = selectedRecipe.displayStack().copy();
		RecipeIngredientSummary ingredientSummary = selectedRecipe.ingredientSummary();
		RecipeDeficitReport deficitReport = RecipeDeficitReport.from(
			ingredientSummary,
			availableItems.inventoryCounts(),
			availableItems.gridStacks(),
			craftAll
		);
		String resolvedItemId = BuiltInRegistries.ITEM.getKey(resolvedDisplayStack.getItem()).toString();
		String outputLabel = resolvedItemId + " x" + resolvedDisplayStack.getCount();
		String chatMessage = deficitReport.hasMissingIngredients()
			? "Missing: " + deficitReport.compactMissingSummary()
			: "Ready: " + outputLabel;

		ReachCraftingMod.LOGGER.info(
			"[recipe_click] screen={} button={} idx={} craftable={} shift={} ctrl={} output={}",
			screenKind,
			mouseButton,
			recipeIndex,
			craftable,
			craftAll,
			allowNearbyFallback,
			outputLabel
		);
		ReachCraftingMod.LOGGER.info(
			"[recipe_needs] idx={} summary={} slots={}",
			recipeIndex,
			ingredientSummary.compactSummary(),
			ingredientSummary.rawSlots()
		);
		ReachCraftingMod.LOGGER.info(
			"[recipe_missing] idx={} inventory={} grid={} missing={}",
			recipeIndex,
			availableItems.inventorySummary(),
			availableItems.gridSummary(),
			deficitReport.compactMissingSummary()
		);

		player.displayClientMessage(
			Component.literal("[Reach Crafting] " + chatMessage)
				.withStyle(ChatFormatting.YELLOW),
			false
		);

		if (allowNearbyFallback && availableItems.hasReservedGrid() && !deficitReport.hasMissingIngredients()) {
			if (NearbyContainerDryRun.tryExpandReservedGrid(
				selectedRecipe.recipeId(),
				collection,
				explicitVariantSelection,
				recipeIndex,
				outputLabel,
				ingredientSummary,
				availableItems,
				craftAll
			)) {
				return;
			}
			NearbyContainerDryRun.start(
				selectedRecipe.recipeId(),
				collection,
				explicitVariantSelection,
				recipeIndex,
				outputLabel,
				ingredientSummary,
				availableItems,
				craftAll
			);
			return;
		}

		if (allowNearbyFallback && !deficitReport.hasMissingIngredients() && !availableItems.hasReservedGrid()) {
			MultiPlayerGameMode gameMode = minecraft.gameMode;
			if (gameMode != null) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipeId(), craftAll);
				player.displayClientMessage(
					Component.literal("[Reach Crafting] Placed recipe: " + outputLabel)
						.withStyle(ChatFormatting.YELLOW),
					false
				);
				return;
			}
		}

		if (deficitReport.hasMissingIngredients() && allowNearbyFallback) {
			NearbyContainerDryRun.start(
				selectedRecipe.recipeId(),
				collection,
				explicitVariantSelection,
				recipeIndex,
				outputLabel,
				ingredientSummary,
				availableItems,
				craftAll
			);
		} else {
			NearbyContainerDryRun.cancelCurrent();
		}
	}

	private static boolean shouldQueueHeldRecipe(Minecraft minecraft, boolean allowNearbyFallback, boolean craftAll) {
		return ReachCraftingConfig.get().reachCraftHoldAndRelease()
			&& allowNearbyFallback
			&& !craftAll
			&& isControlKeyDown(minecraft);
	}

	private static void queueHeldRecipe(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean explicitVariantSelection
	) {
		HeldRecipeAction action = new HeldRecipeAction(
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			mouseButton,
			explicitVariantSelection
		);
		if (pendingHeldRecipe == null) {
			pendingHeldRecipe = new PendingHeldRecipe(action, 1, false);
			return;
		}

		if (pendingHeldRecipe.action().sameRecipe(action)) {
			int updatedCount = pendingHeldRecipe.clickCount() + 1;
			pendingHeldRecipe = new PendingHeldRecipe(action, updatedCount, updatedCount >= 2);
			return;
		}

		if (!pendingHeldRecipe.locked()) {
			pendingHeldRecipe = new PendingHeldRecipe(action, 1, false);
		}
	}

	private static void releasePendingHeldRecipe() {
		if (pendingHeldRecipe == null) {
			return;
		}
		replayBatch = new ReplayBatch(pendingHeldRecipe.action(), pendingHeldRecipe.clickCount());
		pendingHeldRecipe = null;
	}

	private static void processReplayBatch(Minecraft minecraft) {
		if (replayBatch == null) {
			return;
		}
		if (NearbyContainerDryRun.isActiveSessionRunning()) {
			return;
		}

		Screen screen = minecraft.screen;
		LocalPlayer player = minecraft.player;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			replayBatch = null;
			return;
		}
		if (player == null || minecraft.level == null) {
			replayBatch = null;
			return;
		}

		executeRecipeButtonClick(
			minecraft,
			player,
			screen,
			replayBatch.action().recipeId(),
			replayBatch.action().collection(),
			replayBatch.action().displayStack().copy(),
			replayBatch.action().mouseButton(),
			false,
			true,
			replayBatch.action().explicitVariantSelection()
		);

		int remaining = replayBatch.remainingClicks() - 1;
		replayBatch = remaining > 0 ? new ReplayBatch(replayBatch.action(), remaining) : null;
	}

	private static boolean isShiftKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private static boolean isControlKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	private static int currentReservedCraftCopies(AvailableItemSnapshot availableItems) {
		int minCount = Integer.MAX_VALUE;
		for (ItemStack stack : availableItems.gridStacks()) {
			if (stack.isEmpty()) {
				continue;
			}
			minCount = Math.min(minCount, stack.getCount());
		}
		return minCount == Integer.MAX_VALUE ? 0 : minCount;
	}

	private record HeldRecipeAction(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean explicitVariantSelection
	) {
		private boolean sameRecipe(HeldRecipeAction other) {
			return other != null
				&& recipeId.equals(other.recipeId)
				&& explicitVariantSelection == other.explicitVariantSelection;
		}
	}

	private record PendingHeldRecipe(HeldRecipeAction action, int clickCount, boolean locked) {
	}

	private record ReplayBatch(HeldRecipeAction action, int remainingClicks) {
	}
}
