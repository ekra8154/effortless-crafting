package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class BulkAutoCraftController {
	private static BulkCraftSession activeSession;

	private BulkAutoCraftController() {
	}

	static void startOrUpdate(
		RecipeBookClickCapture.HeldRecipeAction action,
		int requestedRecipeCopies,
		boolean allowNearby,
		ItemStack expectedOutput
	) {
		if (!AutoCraftController.isBulkModeEnabled() || action == null || requestedRecipeCopies <= 1 || expectedOutput == null || expectedOutput.isEmpty()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		ItemStack expectedCopy = expectedOutput.copy();
		if (activeSession == null || !activeSession.action().sameRecipe(action)) {
			int baselineOutputCount = countOutputInInventory(client, expectedCopy);
			activeSession = new BulkCraftSession(
				action,
				requestedRecipeCopies,
				0,
				expectedCopy,
				allowNearby,
				baselineOutputCount
			);
			return;
		}

		if (!ItemStack.isSameItemSameComponents(activeSession.expectedOutput(), expectedCopy)) {
			clear();
			return;
		}

		activeSession = activeSession.withUpdatedCycle(allowNearby, requestedRecipeCopies);
	}

	static void clear() {
		activeSession = null;
	}

	static int remainingRequestedRecipeCopies() {
		if (activeSession == null) {
			return 0;
		}
		return Math.max(0, activeSession.requestedRecipeCopies() - activeSession.completedRecipeCopies());
	}

	static boolean shouldRetainPulledResourcesForNextBulkCraft() {
		return activeSession != null && activeSession.allowNearby();
	}

	static void onAutoMoveFinished(Minecraft client, boolean success) {
		if (activeSession == null) {
			return;
		}
		if (!success || !AutoCraftController.isBulkModeEnabled() || !isSupportedScreen(client.screen) || client.player == null) {
			clear();
			return;
		}

		int outputPerCraft = Math.max(activeSession.expectedOutput().getCount(), 1);
		int currentOutputCount = countOutputInInventory(client, activeSession.expectedOutput());
		int gainedOutputCount = currentOutputCount - activeSession.lastObservedOutputCount();
		int craftedCopies = gainedOutputCount / outputPerCraft;
		if (craftedCopies <= 0) {
			clear();
			return;
		}

		int completedRecipeCopies = activeSession.completedRecipeCopies() + craftedCopies;
		if (completedRecipeCopies >= activeSession.requestedRecipeCopies()) {
			clear();
			return;
		}

		activeSession = activeSession.withProgress(completedRecipeCopies, currentOutputCount);
		RecipeBookClickCapture.scheduleReplay(
			activeSession.action(),
			activeSession.requestedRecipeCopies() - activeSession.completedRecipeCopies(),
			activeSession.allowNearby(),
			false
		);
	}

	private static boolean isSupportedScreen(Screen screen) {
		return screen instanceof CraftingScreen || screen instanceof InventoryScreen;
	}

	private static int countOutputInInventory(Minecraft client, ItemStack expectedOutput) {
		if (client.player == null || expectedOutput == null || expectedOutput.isEmpty()) {
			return 0;
		}

		int count = 0;
		for (Slot slot : client.player.containerMenu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
				continue;
			}
			if (ItemStack.isSameItemSameComponents(slot.getItem(), expectedOutput)) {
				count += slot.getItem().getCount();
			}
		}
		return count;
	}

	private record BulkCraftSession(
		RecipeBookClickCapture.HeldRecipeAction action,
		int requestedRecipeCopies,
		int completedRecipeCopies,
		ItemStack expectedOutput,
		boolean allowNearby,
		int lastObservedOutputCount
	) {
		private BulkCraftSession withUpdatedCycle(boolean updatedAllowNearby, int requestedCopiesForCycle) {
			return new BulkCraftSession(action, Math.max(requestedRecipeCopies, requestedCopiesForCycle), completedRecipeCopies, expectedOutput.copy(), updatedAllowNearby, lastObservedOutputCount);
		}

		private BulkCraftSession withProgress(int updatedCompletedRecipeCopies, int updatedLastObservedOutputCount) {
			return new BulkCraftSession(action, requestedRecipeCopies, updatedCompletedRecipeCopies, expectedOutput.copy(), allowNearby, updatedLastObservedOutputCount);
		}
	}
}
