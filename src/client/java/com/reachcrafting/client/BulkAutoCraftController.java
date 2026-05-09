package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class BulkAutoCraftController {
	private static BulkCraftSession activeSession;
	// private static int tickCounter = 0;
	private static boolean performedDiscoveryThisSession = false;

	private BulkAutoCraftController() {
	}

	static void startOrUpdate(
		RecipeBookClickCapture.HeldRecipeAction action,
		int requestedRecipeCopies,
		boolean allowNearby,
		VariantContinuationMode variantContinuationMode,
		ItemStack expectedOutput,
		RecipeIngredientSummary ingredientSummary
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
				expectedOutput.copy(),
				allowNearby,
				variantContinuationMode,
				baselineOutputCount,
				0,
				ingredientSummary,
				AvailableItemSnapshot.capture(client.player, client.screen).totalCounts()
			);
			performedDiscoveryThisSession = false;

			// Trigger offhand swap immediately for 3x3 grids if needed
			int slotsNeeded = estimatedRequiredSlotsForNextBatch();
			OffhandConsolidationController.prepareSwapIfNeeded(client, expectedCopy, slotsNeeded);
			return;
		}

		if (!ItemStack.isSameItemSameComponents(activeSession.expectedOutput(), expectedCopy)) {
			clear();
			return;
		}

		activeSession = activeSession.withUpdatedCycle(allowNearby, requestedRecipeCopies, variantContinuationMode);
	}

	static void clear() {
		activeSession = null;
		// tickCounter = 0;
		performedDiscoveryThisSession = false;
	}

	public static boolean isActive() {
		return activeSession != null;
	}

	public static boolean hasPerformedDiscovery() {
		return performedDiscoveryThisSession;
	}

	public static void noteDiscoveryPerformed() {
		performedDiscoveryThisSession = true;
	}

	public static int getCompletedRecipeCopies() {
		return activeSession != null ? activeSession.completedRecipeCopies() : 0;
	}

	static java.util.Set<String> getAcceptedItemIds() {
		if (activeSession != null && activeSession.ingredientSummary() != null) {
			return activeSession.ingredientSummary().acceptedItemIds();
		}
		return null;
	}

	static java.util.Map<String, Integer> getInitialInventoryCounts() {
		if (activeSession != null) {
			return activeSession.initialInventoryCounts();
		}
		return null;
	}

	static ItemStack getExpectedOutput() {
		if (activeSession != null) {
			return activeSession.expectedOutput();
		}
		return ItemStack.EMPTY;
	}

	static int estimatedRequiredSlotsForNextBatch() {
		if (activeSession == null || activeSession.ingredientSummary() == null) {
			return 0;
		}
		int remaining = remainingRequestedRecipeCopies();
		// In bulk mode, we might craft up to 64 at once if shift-clicking.
		// Budgeting for at least 64 (if remaining allows) ensures we don't 
		// accidentally overflow the inventory with unstackables.
		int batchTarget = Math.min(remaining, 64);
		int ingredientSlots = activeSession.ingredientSummary().estimateRequiredInventorySlots(batchTarget);
		
		ItemStack output = activeSession.expectedOutput();
		int outputPerCraft = Math.max(output.getCount(), 1);
		long totalOutputCount = (long) outputPerCraft * batchTarget;
		int maxStack = output.getMaxStackSize();
		int outputSlots = (int) ((totalOutputCount + maxStack - 1) / maxStack);
		
		int total = ingredientSlots + outputSlots;
		com.reachcrafting.ReachCraftingMod.LOGGER.info("[bulk_budget] remaining={} target={} ingredients={} output={} total={}", remaining, batchTarget, ingredientSlots, outputSlots, total);
		return total;
	}

	static void addEjectedOutput(int count) {
		if (activeSession != null) {
			activeSession = activeSession.withEjected(activeSession.ejectedOutputCount() + count);
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[bulk_craft] Ejected output noted: count={} total_ejected_this_batch={}", count, activeSession.ejectedOutputCount());
		}
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

	static boolean shouldLockToCurrentVariant(
		net.minecraft.world.item.crafting.display.RecipeDisplayId clickedRecipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		return activeSession != null
			&& activeSession.variantContinuationMode() == VariantContinuationMode.STRICT_CURRENT_VARIANT
			&& activeSession.action().sameRecipe(new RecipeBookClickCapture.HeldRecipeAction(
				clickedRecipeId,
				collection,
				ItemStack.EMPTY,
				org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
				explicitVariantSelection
			));
	}

	static VariantContinuationMode currentVariantContinuationMode() {
		return activeSession == null ? null : activeSession.variantContinuationMode();
	}

	static VariantContinuationMode determineVariantContinuationMode(
		net.minecraft.world.item.crafting.display.RecipeDisplayId clickedRecipeId,
		net.minecraft.world.item.crafting.display.RecipeDisplayId selectedRecipeId,
		boolean explicitVariantSelection
	) {
		if (explicitVariantSelection) {
			return VariantContinuationMode.STRICT_CURRENT_VARIANT;
		}

		return switch (ReachCraftingConfig.get().revolvingCraftHandling()) {
			case SPECIFIC_VARIANT_ONLY -> VariantContinuationMode.STRICT_CURRENT_VARIANT;
			case ALWAYS_PREFER_BASED_ON_COUNT -> VariantContinuationMode.FAMILY_FALLBACK;
			case PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK ->
				clickedRecipeId != null && clickedRecipeId.equals(selectedRecipeId)
					? VariantContinuationMode.STRICT_CURRENT_VARIANT
					: VariantContinuationMode.FAMILY_FALLBACK;
		};
	}

	private static int postAutoMoveDelayTicks = 0;

	static void onAutoMoveFinished(Minecraft client, boolean success) {
		if (activeSession == null) {
			return;
		}
		if (!success || !AutoCraftController.isBulkModeEnabled() || !isSupportedScreen(client.screen) || client.player == null) {
			stop(true);
			return;
		}

		int outputPerCraft = Math.max(activeSession.expectedOutput().getCount(), 1);
		int currentOutputCount = countOutputInInventory(client, activeSession.expectedOutput());
		int gainedOutputCount = currentOutputCount - activeSession.lastObservedOutputCount() + activeSession.ejectedOutputCount();
		int craftedCopies = gainedOutputCount / outputPerCraft;
		if (craftedCopies <= 0) {
			stop(true);
			return;
		}

		int completedRecipeCopies = activeSession.completedRecipeCopies() + craftedCopies;
		if (completedRecipeCopies >= activeSession.requestedRecipeCopies()) {
			stop(false);
			return;
		}

		activeSession = activeSession.withProgress(completedRecipeCopies, currentOutputCount);
		
		com.reachcrafting.ReachCraftingMod.LOGGER.info("[bulk_craft] SUCCESS: crafted_this_batch={} (gained={} ejected={}) total_completed={}/{} inv_count={}", 
			craftedCopies, gainedOutputCount, activeSession.ejectedOutputCount(), completedRecipeCopies, activeSession.requestedRecipeCopies(), currentOutputCount);

		// Set a delay to allow inventory to settle before the next batch starts.
		postAutoMoveDelayTicks = 1;
	}

	public static void stop(boolean aborted) {
		if (activeSession != null) {
			String itemName = activeSession.expectedOutput().getHoverName().getString();
			int totalGained = activeSession.completedRecipeCopies() * Math.max(activeSession.expectedOutput().getCount(), 1);
			String formattedCount = ContainerUtils.formatStackBreakdown(totalGained);
			String status = aborted ? "terminated" : "complete";
			ReachCraftingModClient.sendBulkSummaryChat("Bulk craft " + status + ": Crafted " + formattedCount + " " + itemName);
		}
		AutoCraftController.setEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
		if (ReachCraftingConfig.get().autoCraftOffAfterBulk()) {
			AutoCraftController.setEnabled(false);
			ReachCraftingModClient.sendDebugChat("Auto Crafting disabled after bulk craft.");
		} else {
			ReachCraftingModClient.sendDebugChat("Auto Crafting mode reset to normal.");
		}
		OffhandConsolidationController.swapBack(Minecraft.getInstance());
		clear();
	}

	private static java.util.Map<Integer, String> previousSnapshot = new java.util.LinkedHashMap<>();

	public static void tick(Minecraft client) {
		if (activeSession == null || client.player == null || client.player.containerMenu == null) {
			// tickCounter = 0;
			postAutoMoveDelayTicks = 0;
			previousSnapshot.clear();
			return;
		}

		if (postAutoMoveDelayTicks > 0) {
			postAutoMoveDelayTicks--;
			if (postAutoMoveDelayTicks == 0) {
				com.reachcrafting.ReachCraftingMod.LOGGER.info("[bulk_craft] Delay finished. Triggering next batch.");
				RecipeBookClickCapture.scheduleReplay(
					activeSession.action(),
					activeSession.requestedRecipeCopies() - activeSession.completedRecipeCopies(),
					activeSession.allowNearby(),
					false
				);
			}
			return;
		}

		// tickCounter++;
		// com.reachcrafting.ReachCraftingMod.LOGGER.info("#### TICK {} ####", tickCounter);

		// net.minecraft.world.inventory.AbstractContainerMenu menu = client.player.containerMenu;

		// Report state of top inventory row (slots 9-17)
		// logInventoryRow(menu, "Top Row", 9, 17);

		// Report state of crafting grid
		// logCraftingGrid(menu);

		// Change detection: compare every slot to previous tick
		/*
		java.util.Map<Integer, String> currentSnapshot = captureFullSnapshot(menu);
		if (!previousSnapshot.isEmpty()) {
			StringBuilder changes = new StringBuilder();
			java.util.Set<Integer> allSlots = new java.util.TreeSet<>();
			allSlots.addAll(previousSnapshot.keySet());
			allSlots.addAll(currentSnapshot.keySet());
			for (int slotIdx : allSlots) {
				String prev = previousSnapshot.getOrDefault(slotIdx, "EMPTY");
				String curr = currentSnapshot.getOrDefault(slotIdx, "EMPTY");
				if (!prev.equals(curr)) {
					if (changes.length() > 0) changes.append(", ");
					changes.append("slot ").append(slotIdx).append(": ").append(prev).append(" → ").append(curr);
				}
			}
			// }
		}
		*/
		OffhandConsolidationController.resetIdleTimeout();
	}

	/*
	private static java.util.Map<Integer, String> captureFullSnapshot(net.minecraft.world.inventory.AbstractContainerMenu menu) {
		java.util.Map<Integer, String> snapshot = new java.util.LinkedHashMap<>();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.hasItem()) {
				String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).getPath();
				snapshot.put(i, slot.getItem().getCount() + "x" + itemId);
			}
		}
		return snapshot;
	}

	private static void logInventoryRow(net.minecraft.world.inventory.AbstractContainerMenu menu, String label, int startContainerSlot, int endContainerSlot) {
		StringBuilder sb = new StringBuilder(label + ": ");
		boolean first = true;
		for (int i = startContainerSlot; i <= endContainerSlot; i++) {
			Slot found = null;
			for (Slot s : menu.slots) {
				if (s.container instanceof net.minecraft.world.entity.player.Inventory && s.getContainerSlot() == i) {
					found = s;
					break;
				}
			}

			if (found != null && found.hasItem()) {
				if (!first) sb.append(", ");
				sb.append("slot ").append(i).append(": ").append(found.getItem().getCount()).append("x").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(found.getItem().getItem()).getPath());
				first = false;
			}
		}
		if (first) sb.append("empty");
		com.reachcrafting.ReachCraftingMod.LOGGER.info(sb.toString());
	}

	private static void logCraftingGrid(net.minecraft.world.inventory.AbstractContainerMenu menu) {
		StringBuilder sb = new StringBuilder("Grid: ");
		boolean first = true;
		int gridSlots = 0;
		if (menu instanceof net.minecraft.world.inventory.CraftingMenu) {
			gridSlots = 9;
		} else if (menu instanceof net.minecraft.world.inventory.InventoryMenu) {
			gridSlots = 4;
		}

		for (int i = 1; i <= gridSlots; i++) {
			if (i >= menu.slots.size()) break;
			Slot slot = menu.getSlot(i);
			if (slot.hasItem()) {
				if (!first) sb.append(", ");
				sb.append("slot ").append(i).append(": ").append(slot.getItem().getCount()).append("x").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).getPath());
				first = false;
			}
		}
		if (first) sb.append("empty");
		com.reachcrafting.ReachCraftingMod.LOGGER.info(sb.toString());
	}
	*/

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

		// Also count the offhand slot, which is not usually in the menu's slots list in 3x3
		ItemStack offhand = client.player.getOffhandItem();
		if (!offhand.isEmpty() && ItemStack.isSameItemSameComponents(offhand, expectedOutput)) {
			count += offhand.getCount();
		}
		
		return count;
	}

	private record BulkCraftSession(
		RecipeBookClickCapture.HeldRecipeAction action,
		int requestedRecipeCopies,
		int completedRecipeCopies,
		ItemStack expectedOutput,
		boolean allowNearby,
		VariantContinuationMode variantContinuationMode,
		int lastObservedOutputCount,
		int ejectedOutputCount,
		RecipeIngredientSummary ingredientSummary,
		java.util.Map<String, Integer> initialInventoryCounts
	) {
		/*
		private BulkCraftSession withUpdatedCycle(boolean updatedAllowNearby, int requestedCopiesForCycle) {
			return withUpdatedCycle(updatedAllowNearby, requestedCopiesForCycle, variantContinuationMode);
		}
		*/

		private BulkCraftSession withUpdatedCycle(boolean updatedAllowNearby, int requestedCopiesForCycle, VariantContinuationMode updatedVariantContinuationMode) {
			VariantContinuationMode mode = updatedVariantContinuationMode == VariantContinuationMode.UNDECIDED
				? variantContinuationMode
				: updatedVariantContinuationMode;
			return new BulkCraftSession(action, Math.max(requestedRecipeCopies, requestedCopiesForCycle), completedRecipeCopies, expectedOutput.copy(), updatedAllowNearby, mode, lastObservedOutputCount, ejectedOutputCount, ingredientSummary, initialInventoryCounts);
		}

		private BulkCraftSession withProgress(int updatedCompletedRecipeCopies, int updatedLastObservedOutputCount) {
			return new BulkCraftSession(action, requestedRecipeCopies, updatedCompletedRecipeCopies, expectedOutput.copy(), allowNearby, variantContinuationMode, updatedLastObservedOutputCount, 0, ingredientSummary, initialInventoryCounts);
		}

		private BulkCraftSession withEjected(int newEjectedCount) {
			return new BulkCraftSession(action, requestedRecipeCopies, completedRecipeCopies, expectedOutput.copy(), allowNearby, variantContinuationMode, lastObservedOutputCount, newEjectedCount, ingredientSummary, initialInventoryCounts);
		}
	}

	enum VariantContinuationMode {
		UNDECIDED,
		STRICT_CURRENT_VARIANT,
		FAMILY_FALLBACK
	}
}
