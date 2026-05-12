package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class BulkAutoCraftController {
	private static final int REFILL_WINDOW_COPIES = 10_000;
	private static final int REFILL_THRESHOLD_COPIES = 5_000;
	private static BulkCraftSession activeSession;
	private static BulkOutputDisposition currentBatchOutputDisposition = BulkOutputDisposition.NORMAL_KEEP;
	// private static int tickCounter = 0;
	private static boolean performedDiscoveryThisSession = false;

	private BulkAutoCraftController() {
	}

	static void startOrUpdate(
		RecipeBookClickCapture.HeldRecipeAction action,
		int requestedRecipeCopies,
		boolean allowNearby,
		boolean refillableBulkMaxMode,
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
		boolean sameRecipe = activeSession != null && activeSession.action().sameRecipe(action);
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[bulk_session] start_or_update active={} same_recipe={} requested={} completed={} refillable={} allow_nearby={} action_recipe={} expected_output={}",
			activeSession != null,
			sameRecipe,
			requestedRecipeCopies,
			activeSession == null ? 0 : activeSession.completedRecipeCopies(),
			refillableBulkMaxMode,
			allowNearby,
			action.recipeId(),
			expectedCopy.getHoverName().getString()
		);
		if (activeSession == null || !sameRecipe) {
			int baselineOutputCount = countAccessibleOutput(client, expectedCopy);
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[bulk_session] create_new previous_active={} previous_action={} previous_completed={} previous_requested={} previous_refillable={}",
				activeSession != null,
				activeSession == null ? "<none>" : activeSession.action().recipeId(),
				activeSession == null ? 0 : activeSession.completedRecipeCopies(),
				activeSession == null ? 0 : activeSession.requestedRecipeCopies(),
				activeSession != null && activeSession.refillableBulkMaxMode()
			);
			activeSession = new BulkCraftSession(
				action,
				requestedRecipeCopies,
				0,
				expectedOutput.copy(),
				allowNearby,
				variantContinuationMode,
				baselineOutputCount,
				0,
				refillableBulkMaxMode,
				ingredientSummary,
				AvailableItemSnapshot.capture(client.player, client.screen).totalCounts(),
				captureProtectedOutputInventorySlots(client, expectedCopy),
				new java.util.LinkedHashMap<>()
			);
			performedDiscoveryThisSession = false;

			// Trigger offhand swap immediately for 3x3 grids if needed
			int slotsNeeded = estimatedRequiredSlotsForNextBatch();
			OffhandConsolidationController.prepareSwapIfNeeded(client, expectedCopy, slotsNeeded);
			return;
		}

		if (!ItemStack.isSameItemSameComponents(activeSession.expectedOutput(), expectedCopy)) {
			if (sameRecipe) {
				String previousOutputName = activeSession.expectedOutput().getHoverName().getString();
				int baselineOutputCount = countAccessibleOutput(client, expectedCopy);
				activeSession = activeSession.withResolvedOutputTransition(
					expectedCopy,
					ingredientSummary,
					baselineOutputCount,
					captureProtectedOutputInventorySlots(client, expectedCopy),
					refillableBulkMaxMode,
					variantContinuationMode
				);
				com.reachcrafting.ReachCraftingMod.LOGGER.info(
					"[bulk_session] transition_output current_output={} new_output={} requested={} completed={} remaining={} refillable={}",
					previousOutputName,
					expectedCopy.getHoverName().getString(),
					activeSession.requestedRecipeCopies(),
					activeSession.completedRecipeCopies(),
					remainingRequestedRecipeCopies(),
					activeSession.refillableBulkMaxMode()
				);
				return;
			}

			com.reachcrafting.ReachCraftingMod.LOGGER.warn(
				"[bulk_session] clear_output_mismatch current_output={} new_output={} requested={} completed={} refillable={}",
				activeSession.expectedOutput().getHoverName().getString(),
				expectedCopy.getHoverName().getString(),
				activeSession.requestedRecipeCopies(),
				activeSession.completedRecipeCopies(),
				activeSession.refillableBulkMaxMode()
			);
			clear();
			return;
		}

		activeSession = activeSession.withUpdatedCycle(allowNearby, requestedRecipeCopies, refillableBulkMaxMode, variantContinuationMode);
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[bulk_session] updated requested={} completed={} remaining={} refillable={}",
			activeSession.requestedRecipeCopies(),
			activeSession.completedRecipeCopies(),
			remainingRequestedRecipeCopies(),
			activeSession.refillableBulkMaxMode()
		);
	}

	static void clear() {
		activeSession = null;
		currentBatchOutputDisposition = BulkOutputDisposition.NORMAL_KEEP;
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

	static boolean isProtectedOutputInventorySlot(int inventoryIndex) {
		return activeSession != null && activeSession.protectedOutputInventorySlots().contains(inventoryIndex);
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

	static int predictedDirectEjectOutputCount(Minecraft client, ItemStack currentResult) {
		if (activeSession == null || client == null || currentResult == null || currentResult.isEmpty()) {
			return 0;
		}

		int stagedCraftCopies = getCurrentStagedCraftCopies(client);
		int creditedCraftCopies = Math.min(stagedCraftCopies, remainingRequestedRecipeCopies());
		return Math.max(0, creditedCraftCopies) * Math.max(currentResult.getCount(), 1);
	}

	static int remainingRequestedRecipeCopies() {
		if (activeSession == null) {
			return 0;
		}
		return Math.max(0, activeSession.requestedRecipeCopies() - activeSession.completedRecipeCopies());
	}

	static boolean isRefillableBulkMaxMode() {
		return activeSession != null && activeSession.refillableBulkMaxMode();
	}

	static boolean shouldRetainPulledResourcesForNextBulkCraft() {
		return activeSession != null && activeSession.allowNearby();
	}

	static BulkOutputDisposition determineCurrentBatchOutputDisposition(Minecraft client, ItemStack currentResult) {
		if (activeSession == null
			|| client.player == null
			|| currentResult == null
			|| currentResult.isEmpty()
			|| !AutoCraftController.isBulkModeEnabled()
			|| !ReachCraftingConfig.get().ejectItemsWhenFull()) {
			currentBatchOutputDisposition = BulkOutputDisposition.NORMAL_KEEP;
			return currentBatchOutputDisposition;
		}

		int stagedCraftCopies = getCurrentStagedCraftCopies(client);
		int remainingCopies = remainingRequestedRecipeCopies();
		boolean finalBatchKeep = stagedCraftCopies > 0 && stagedCraftCopies >= remainingCopies;
		boolean hasPartialOutputStackRoom = !finalBatchKeep && hasProtectedPartialOutputStackRoom(client, currentResult);
		currentBatchOutputDisposition = finalBatchKeep
			? BulkOutputDisposition.FINAL_BATCH_KEEP
			: hasPartialOutputStackRoom
				? BulkOutputDisposition.PARTIAL_STACK_KEEP
				: BulkOutputDisposition.DIRECT_EJECT_BATCH;

		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[bulk_craft] batch_disposition={} staged_copies={} remaining_copies={} partial_stack_room={} result={}",
			currentBatchOutputDisposition,
			stagedCraftCopies,
			remainingCopies,
			hasPartialOutputStackRoom,
			ContainerUtils.formatStack(currentResult)
		);
		return currentBatchOutputDisposition;
	}

	static BulkOutputDisposition currentBatchOutputDisposition() {
		return currentBatchOutputDisposition;
	}

	static boolean shouldSweepExpectedOutput() {
		return currentBatchOutputDisposition == BulkOutputDisposition.DIRECT_EJECT_BATCH
			|| currentBatchOutputDisposition == BulkOutputDisposition.NORMAL_KEEP;
	}

	static void resetCurrentBatchOutputDisposition() {
		currentBatchOutputDisposition = BulkOutputDisposition.NORMAL_KEEP;
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
		if (client.player == null) {
			stop(true, "player_missing_during_auto_move_finish");
			return;
		}

		boolean bulkEnabled = AutoCraftController.isBulkModeEnabled();
		boolean supportedScreen = isSupportedScreen(client.screen);
		int outputPerCraft = Math.max(activeSession.expectedOutput().getCount(), 1);
		int currentOutputCount = countAccessibleOutput(client, activeSession.expectedOutput());
		int inventoryIncrease = Math.max(0, currentOutputCount - activeSession.lastObservedOutputCount());
		int gainedOutputCount = inventoryIncrease + activeSession.ejectedOutputCount();
		int craftedCopies = gainedOutputCount / outputPerCraft;

		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[bulk_craft] onAutoMoveFinished success={} bulk_enabled={} supported_screen={} inventory_increase={} ejected={} current_output={} last_output={} crafted_copies={}",
			success,
			bulkEnabled,
			supportedScreen,
			inventoryIncrease,
			activeSession.ejectedOutputCount(),
			currentOutputCount,
			activeSession.lastObservedOutputCount(),
			craftedCopies
		);

		if ((!success || !bulkEnabled || !supportedScreen) && craftedCopies <= 0) {
			resetCurrentBatchOutputDisposition();
			stop(true, "auto_move_failed_without_progress success=" + success + " bulkEnabled=" + bulkEnabled + " supportedScreen=" + supportedScreen);
			return;
		}

		if (!bulkEnabled || !supportedScreen) {
			activeSession = activeSession.withProgress(craftedCopies, currentOutputCount);
			resetCurrentBatchOutputDisposition();
			stop(true, "bulk_mode_or_screen_lost_after_progress bulkEnabled=" + bulkEnabled + " supportedScreen=" + supportedScreen + " craftedCopies=" + craftedCopies);
			return;
		}

		if (craftedCopies <= 0) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[bulk_craft] No progress detected. inventory_increase={} ejected={} current_output={} last_output={} output_per_craft={}",
				inventoryIncrease,
				activeSession.ejectedOutputCount(),
				currentOutputCount,
				activeSession.lastObservedOutputCount(),
				outputPerCraft
			);
			resetCurrentBatchOutputDisposition();
			stop(true, "no_progress_detected");
			return;
		}

		activeSession = activeSession.withProgress(craftedCopies, currentOutputCount);
		int completedRecipeCopies = activeSession.completedRecipeCopies();
		topUpRequestedCopiesIfNeeded("post_batch");
		if (completedRecipeCopies >= activeSession.requestedRecipeCopies()) {
			resetCurrentBatchOutputDisposition();
			stop(false, "requested_copies_completed");
			return;
		}
		
		com.reachcrafting.ReachCraftingMod.LOGGER.info("[bulk_craft] SUCCESS: crafted_this_batch={} (gained={} ejected={}) total_completed={}/{} inv_count={}", 
			craftedCopies, gainedOutputCount, activeSession.ejectedOutputCount(), completedRecipeCopies, activeSession.requestedRecipeCopies(), currentOutputCount);

		resetCurrentBatchOutputDisposition();
		// Set a delay to allow inventory to settle before the next batch starts.
		postAutoMoveDelayTicks = 1;
	}

	public static void stop(boolean aborted) {
		stop(aborted, aborted ? "unspecified_abort" : "unspecified_complete");
	}

	public static void stop(boolean aborted, String reason) {
		if (activeSession != null) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[bulk_craft] STOP aborted={} reason={} completed={}/{} expected_output={} disposition={} refillable={} allow_nearby={}",
				aborted,
				reason,
				activeSession.completedRecipeCopies(),
				activeSession.requestedRecipeCopies(),
				ContainerUtils.formatStack(activeSession.expectedOutput()),
				currentBatchOutputDisposition,
				activeSession.refillableBulkMaxMode(),
				activeSession.allowNearby()
			);
		} else {
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[bulk_craft] STOP aborted={} reason={} activeSession=false", aborted, reason);
		}
		if (activeSession != null) {
			String status = aborted ? "terminated" : "complete";
			if (activeSession.summary().isEmpty()) {
				String itemName = activeSession.expectedOutput().getHoverName().getString();
				ReachCraftingModClient.sendBulkSummaryChat("Bulk craft " + status + ": Crafted 0 " + itemName);
			} else {
				int totalItems = 0;
				for (int count : activeSession.summary().values()) {
					totalItems += count;
				}

				if (activeSession.summary().size() == 1) {
					java.util.Map.Entry<String, Integer> entry = activeSession.summary().entrySet().iterator().next();
					ReachCraftingModClient.sendBulkSummaryChat("Bulk craft " + status + ": Crafted " + ContainerUtils.formatStackBreakdown(entry.getValue()) + " " + entry.getKey());
				} else {
					StringBuilder sb = new StringBuilder();
					sb.append(ContainerUtils.formatStackBreakdown(totalItems)).append(" items: ");
					boolean first = true;
					for (java.util.Map.Entry<String, Integer> entry : activeSession.summary().entrySet()) {
						if (!first) sb.append(", ");
						sb.append(ContainerUtils.formatStackBreakdown(entry.getValue())).append(" ").append(entry.getKey());
						first = false;
					}
					ReachCraftingModClient.sendBulkSummaryChat("Bulk craft " + status + ": Crafted " + sb.toString());
				}
			}
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
				topUpRequestedCopiesIfNeeded("pre_replay");
				com.reachcrafting.ReachCraftingMod.LOGGER.info(
					"[bulk_craft] Delay finished. Triggering next batch. remaining={} action_recipe={} allow_nearby={} refillable={}",
					activeSession.requestedRecipeCopies() - activeSession.completedRecipeCopies(),
					activeSession.action().recipeId(),
					activeSession.allowNearby(),
					activeSession.refillableBulkMaxMode()
				);
				RecipeBookClickCapture.scheduleReplay(
					activeSession.action(),
					activeSession.requestedRecipeCopies() - activeSession.completedRecipeCopies(),
					activeSession.allowNearby(),
					false,
					activeSession.refillableBulkMaxMode()
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

	private static int getCurrentStagedCraftCopies(Minecraft client) {
		if (client.player == null || client.screen == null) {
			return 0;
		}
		AvailableItemSnapshot snapshot = AvailableItemSnapshot.capture(client.player, client.screen);
		return ContainerUtils.currentReservedCraftCopies(snapshot.gridStacks());
	}

	private static int countAccessibleOutput(Minecraft client, ItemStack expectedOutput) {
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

		ItemStack carried = client.player.containerMenu.getCarried();
		if (!carried.isEmpty() && ItemStack.isSameItemSameComponents(carried, expectedOutput)) {
			count += carried.getCount();
		}

		if (!client.player.containerMenu.slots.isEmpty()) {
			ItemStack result = client.player.containerMenu.getSlot(0).getItem();
			if (!result.isEmpty() && ItemStack.isSameItemSameComponents(result, expectedOutput)) {
				count += result.getCount();
			}
		}
		
		return count;
	}

	private static boolean hasProtectedPartialOutputStackRoom(Minecraft client, ItemStack expectedOutput) {
		if (client.player == null || expectedOutput == null || expectedOutput.isEmpty()) {
			return false;
		}

		for (Slot slot : client.player.containerMenu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (ItemStack.isSameItemSameComponents(stack, expectedOutput) && stack.getCount() < stack.getMaxStackSize()) {
				return true;
			}
		}

		ItemStack offhand = client.player.getOffhandItem();
		return !offhand.isEmpty()
			&& ItemStack.isSameItemSameComponents(offhand, expectedOutput)
			&& offhand.getCount() < offhand.getMaxStackSize();
	}

	private static java.util.Set<Integer> captureProtectedOutputInventorySlots(Minecraft client, ItemStack expectedOutput) {
		java.util.Set<Integer> protectedSlots = new java.util.HashSet<>();
		if (client.player == null || expectedOutput == null || expectedOutput.isEmpty()) {
			return protectedSlots;
		}

		for (Slot slot : client.player.containerMenu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
				continue;
			}
			if (ItemStack.isSameItemSameComponents(slot.getItem(), expectedOutput)) {
				protectedSlots.add(slot.getContainerSlot());
			}
		}
		return protectedSlots;
	}

	private static void topUpRequestedCopiesIfNeeded(String reason) {
		if (activeSession == null || !activeSession.refillableBulkMaxMode()) {
			return;
		}

		int remaining = remainingRequestedRecipeCopies();
		if (remaining > REFILL_THRESHOLD_COPIES) {
			return;
		}

		int previousRequested = activeSession.requestedRecipeCopies();
		int refilledRequested = activeSession.completedRecipeCopies() + REFILL_WINDOW_COPIES;
		if (refilledRequested <= previousRequested) {
			return;
		}

		activeSession = activeSession.withRequestedRecipeCopies(refilledRequested);
		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[bulk_refill] reason={} completed={} previous_requested={} previous_remaining={} new_requested={} new_remaining={}",
			reason,
			activeSession.completedRecipeCopies(),
			previousRequested,
			remaining,
			refilledRequested,
			remainingRequestedRecipeCopies()
		);
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
		boolean refillableBulkMaxMode,
		RecipeIngredientSummary ingredientSummary,
		java.util.Map<String, Integer> initialInventoryCounts,
		java.util.Set<Integer> protectedOutputInventorySlots,
		java.util.Map<String, Integer> summary
	) {
		/*
		private BulkCraftSession withUpdatedCycle(boolean updatedAllowNearby, int requestedCopiesForCycle) {
			return withUpdatedCycle(updatedAllowNearby, requestedCopiesForCycle, variantContinuationMode);
		}
		*/

		private BulkCraftSession withUpdatedCycle(boolean updatedAllowNearby, int requestedCopiesForCycle, boolean updatedRefillableBulkMaxMode, VariantContinuationMode updatedVariantContinuationMode) {
			VariantContinuationMode mode = updatedVariantContinuationMode == VariantContinuationMode.UNDECIDED
				? variantContinuationMode
				: updatedVariantContinuationMode;
			return new BulkCraftSession(action, Math.max(requestedRecipeCopies, requestedCopiesForCycle), completedRecipeCopies, expectedOutput.copy(), updatedAllowNearby, mode, lastObservedOutputCount, ejectedOutputCount, refillableBulkMaxMode || updatedRefillableBulkMaxMode, ingredientSummary, initialInventoryCounts, protectedOutputInventorySlots, summary);
		}

		private BulkCraftSession withProgress(int additionalCopies, int updatedLastObservedOutputCount) {
			int updatedCompletedRecipeCopies = completedRecipeCopies + additionalCopies;
			java.util.Map<String, Integer> newSummary = new java.util.LinkedHashMap<>(summary);
			String itemName = expectedOutput.getHoverName().getString();
			int itemAmount = additionalCopies * Math.max(expectedOutput.getCount(), 1);
			if (itemAmount > 0) {
				newSummary.put(itemName, newSummary.getOrDefault(itemName, 0) + itemAmount);
			}
			return new BulkCraftSession(action, requestedRecipeCopies, updatedCompletedRecipeCopies, expectedOutput.copy(), allowNearby, variantContinuationMode, updatedLastObservedOutputCount, 0, refillableBulkMaxMode, ingredientSummary, initialInventoryCounts, protectedOutputInventorySlots, newSummary);
		}

		private BulkCraftSession withEjected(int newEjectedCount) {
			return new BulkCraftSession(action, requestedRecipeCopies, completedRecipeCopies, expectedOutput.copy(), allowNearby, variantContinuationMode, lastObservedOutputCount, newEjectedCount, refillableBulkMaxMode, ingredientSummary, initialInventoryCounts, protectedOutputInventorySlots, summary);
		}

		private BulkCraftSession withRequestedRecipeCopies(int updatedRequestedRecipeCopies) {
			return new BulkCraftSession(action, updatedRequestedRecipeCopies, completedRecipeCopies, expectedOutput.copy(), allowNearby, variantContinuationMode, lastObservedOutputCount, ejectedOutputCount, refillableBulkMaxMode, ingredientSummary, initialInventoryCounts, protectedOutputInventorySlots, summary);
		}

		private BulkCraftSession withResolvedOutputTransition(
			ItemStack updatedExpectedOutput,
			RecipeIngredientSummary updatedIngredientSummary,
			int updatedLastObservedOutputCount,
			java.util.Set<Integer> updatedProtectedOutputInventorySlots,
			boolean updatedRefillableBulkMaxMode,
			VariantContinuationMode updatedVariantContinuationMode
		) {
			VariantContinuationMode mode = updatedVariantContinuationMode == VariantContinuationMode.UNDECIDED
				? variantContinuationMode
				: updatedVariantContinuationMode;
			return new BulkCraftSession(
				action,
				requestedRecipeCopies,
				completedRecipeCopies,
				updatedExpectedOutput.copy(),
				allowNearby,
				mode,
				updatedLastObservedOutputCount,
				0,
				refillableBulkMaxMode || updatedRefillableBulkMaxMode,
				updatedIngredientSummary,
				initialInventoryCounts,
				updatedProtectedOutputInventorySlots,
				summary
			);
		}

	}

	enum VariantContinuationMode {
		UNDECIDED,
		STRICT_CURRENT_VARIANT,
		FAMILY_FALLBACK
	}

	enum BulkOutputDisposition {
		NORMAL_KEEP,
		DIRECT_EJECT_BATCH,
		FINAL_BATCH_KEEP,
		PARTIAL_STACK_KEEP
	}
}
