package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

public final class ChainCraftController {
	private static final int STEP_TIMEOUT_TICKS = 200;
	private static final int BATCH_SETTLE_QUIET_TICKS = 8;
	private static ChainCraftRun activeRun;
	private static PendingWarmupRetry pendingWarmupRetry;
	private static RecipeDisplayId activeFinalStepRecipeId;

	private ChainCraftController() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(ChainCraftController::tick);
	}

	static void start(ChainCraftPlan plan) {
		if (plan == null || plan.steps().isEmpty()) {
			return;
		}
		abort(false);
		activeRun = ChainCraftRun.start(plan);
	}

	static boolean isActive() {
		return activeRun != null || pendingWarmupRetry != null;
	}

	static boolean isRunningIntermediateStep() {
		return activeRun != null && activeRun.currentStepIndex() < activeRun.plan().steps().size() - 1;
	}

	static boolean isRunningFinalStep() {
		return activeRun != null && activeRun.currentStepIndex() == activeRun.plan().steps().size() - 1;
	}

	/**
	 * Final-step output thrown by the bulk chain eject path never reaches the
	 * inventory, so settlement must count it explicitly or the batch would
	 * misread as unproduced and re-craft copies whose inputs are spent.
	 */
	static void noteFinalOutputEjected(int itemCount) {
		if (activeRun == null || itemCount <= 0 || !isRunningFinalStep()) {
			return;
		}
		activeRun = activeRun.withBatchEjectedItems(activeRun.batchEjectedItems() + itemCount);
	}

	/**
	 * Rapid eject loop for the final step: after each result throw, restage
	 * the recipe directly so the next craft is ready a tick later, matching
	 * flat bulk's craft-and-eject pace. Recipes with non-stackable
	 * ingredients (a bow per dispenser) stage only one copy per placement, so
	 * without this each craft would cost a whole settlement round.
	 */
	static boolean restageFinalStepForRapidEject(Minecraft client) {
		if (activeRun == null
			|| client.player == null
			|| client.gameMode == null
			|| activeFinalStepRecipeId == null
			|| !isRunningFinalStep()) {
			return false;
		}
		int remaining = activeRun.scheduledBatchCopies() - activeRun.observedProducedRecipeCopies();
		if (remaining <= 0) {
			return false;
		}
		if (isSelfReferentialStep(activeRun.currentStep())) {
			return ManualRecipePlacer.placeStepCrafts(client, activeRun.currentStep(), remaining) > 0;
		}
		client.gameMode.handlePlaceRecipe(client.player.containerMenu.containerId, activeFinalStepRecipeId, true);
		return true;
	}

	/**
	 * Places the current chain step's planned inputs client-side when the
	 * step's recipe accepts its own output as an ingredient (re-dye family).
	 * Returns true when placement was handled here — callers must then skip
	 * handlePlaceRecipe, which would let the server pick the freshly crafted
	 * output or the player's intentionally dyed variants as inputs. Handled
	 * with zero staged copies is still handled: falling back to the server
	 * placement would be worse than an honest step failure.
	 */
	static boolean tryManualSelfReferentialPlacement(Minecraft client, String outputItemId) {
		if (activeRun == null || client.player == null || client.gameMode == null || !activeRun.waitingForStep()) {
			return false;
		}
		ChainCraftPlan.Step step = activeRun.currentStep();
		if (!isSelfReferentialStep(step)) {
			return false;
		}
		if (outputItemId != null && !outputItemId.equals(itemIdOf(step.displayStack()))) {
			return false;
		}
		int remaining = Math.max(activeRun.scheduledBatchCopies() - activeRun.observedProducedRecipeCopies(), 1);
		int staged = ManualRecipePlacer.placeStepCrafts(client, step, remaining);
		ReachCraftingMod.LOGGER.info(
			"[chain_execute] manual_self_ref_placement output={} staged={} remaining={}",
			ContainerUtils.formatStack(step.displayStack()),
			staged,
			remaining
		);
		return true;
	}

	private static boolean isSelfReferentialStep(ChainCraftPlan.Step step) {
		return step != null
			&& !step.displayStack().isEmpty()
			&& step.ingredientSummary().acceptedItemIds().contains(itemIdOf(step.displayStack()));
	}

	private static String itemIdOf(net.minecraft.world.item.ItemStack stack) {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	static boolean isUsingPreStagedNearbyResources() {
		return activeRun != null && activeRun.preStagedNearbyResources();
	}

	/**
	 * Union of every plan step's accepted inputs and outputs. Anything outside
	 * this set that appears during the run is a byproduct the plan cannot use
	 * (e.g. empty buckets left in the grid by milk), so bulk ejection may
	 * throw it; anything any step consumes or produces stays protected.
	 */
	static java.util.Set<String> getActivePlanAcceptedItemIds() {
		ChainCraftRun run = activeRun;
		if (run == null) {
			return null;
		}
		java.util.Set<String> acceptedIds = new java.util.HashSet<>();
		for (ChainCraftPlan.Step step : run.plan().steps()) {
			acceptedIds.addAll(step.ingredientSummary().acceptedItemIds());
			if (!step.displayStack().isEmpty()) {
				acceptedIds.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(step.displayStack().getItem()).toString());
			}
		}
		return acceptedIds;
	}

	static void abort(boolean report) {
		if (activeRun == null && pendingWarmupRetry == null) {
			return;
		}
		activeRun = null;
		pendingWarmupRetry = null;
		activeFinalStepRecipeId = null;
		if (report) {
			ReachCraftingModClient.sendAbortedChat("Crafting session aborted.");
		}
	}

	static void armRetryAfterNearbyWarmup(
		RecipeBookClickCapture.HeldRecipeAction action,
		int remainingClicks,
		boolean allowNearby,
		boolean craftAll,
		boolean refillableBulkMaxMode,
		ItemStack expectedOutput
	) {
		Minecraft client = Minecraft.getInstance();
		int baselineOutputCount = countAccessibleOutput(client, expectedOutput);
		pendingWarmupRetry = new PendingWarmupRetry(
			action,
			Math.max(remainingClicks, 1),
			allowNearby,
			craftAll,
			refillableBulkMaxMode,
			expectedOutput.copy(),
			baselineOutputCount
		);
		ReachCraftingMod.LOGGER.info(
			"[chain_retry] armed_after_nearby_warmup recipe={} clicks={} output={} baseline_count={}",
			action.recipeId(),
			remainingClicks,
			ContainerUtils.formatStack(expectedOutput),
			baselineOutputCount
		);
	}

	static void onAutoMoveFinished(Minecraft client, boolean success) {
		if (activeRun == null) {
			return;
		}
		if (!success) {
			failCurrentStep();
			return;
		}
		if (activeRun.needsBatchSettlement()) {
			activeRun = activeRun.withSettlingBatch();
			return;
		}
		activeRun = activeRun.withCompletedBatch();
		if (activeRun == null || activeRun.currentStepIndex() >= activeRun.plan().steps().size()) {
			activeRun = null;
			return;
		}
		activeRun = activeRun.withWaiting(false, 0);
	}

	private static void tick(Minecraft client) {
		ChainCraftPopupController.tick(client);
		tickPendingWarmupRetry(client);
		if (activeRun == null) {
			return;
		}
		if (client.player == null) {
			activeRun = null;
			return;
		}
		if (!(client.screen instanceof CraftingScreen)
			&& !(client.screen instanceof InventoryScreen)
			&& !NearbyContainerDryRun.isActiveSessionRunning()) {
			ReachCraftingModClient.sendChat(Component.translatable("message.reachcrafting.chain_crafting.context_lost").getString());
			activeRun = null;
			return;
		}
		if (!client.isWindowActive()) {
			abort(true);
			return;
		}
		if (activeRun.settlingBatch()) {
			tickBatchSettlement(client);
			return;
		}
		if (!activeRun.waitingForStep()) {
			if (activeRun.waitingForStaging()) {
				if (NearbyContainerDryRun.isActiveSessionRunning()) {
					return;
				}
				boolean fullyAvailable = ChainCraftStagingPlanner.isFullyAvailableLocally(client, activeRun.plan());
				ReachCraftingMod.LOGGER.info(
					"[chain_stage] completed available={} missing={}",
					fullyAvailable,
					AvailableItemSnapshot.formatCounts(ChainCraftStagingPlanner.missingStagingCounts(client, activeRun.plan()))
				);
				activeRun = activeRun.withStagingComplete(fullyAvailable);
				return;
			}
			if (!activeRun.stagingAttempted() && shouldAttemptPreStage(client, activeRun.plan())) {
				Map<String, Integer> missingCounts = ChainCraftStagingPlanner.missingStagingCounts(client, activeRun.plan());
				if (missingCounts.isEmpty()) {
					activeRun = activeRun.withStagingComplete(true);
					return;
				}
				ReachCraftingMod.LOGGER.info("[chain_stage] request missing={}", AvailableItemSnapshot.formatCounts(missingCounts));
				NearbyContainerDryRun.startCountStaging(missingCounts, "chain_crafting");
				if (NearbyContainerDryRun.isActiveSessionRunning()) {
					activeRun = activeRun.withWaitingForStaging();
					return;
				}
				activeRun = activeRun.withStagingComplete(false);
				return;
			}
			if (!ContainerUtils.isInputQueueActive()
				&& !ContainerUtils.isAutoMovePending()
				&& !NearbyContainerDryRun.isActiveSessionRunning()) {
				// Recipe placement only sees the inventory: a needed
				// ingredient stuck on the cursor (e.g. the last bow) makes
				// the step fail as "missing". Stow it first.
				if (tryStowCarriedStack(client)) {
					return;
				}
				scheduleCurrentStep();
			}
			return;
		}

		int updatedWaitTicks = activeRun.waitTicks() + 1;
		activeRun = activeRun.withWaiting(true, updatedWaitTicks);
		if (updatedWaitTicks > STEP_TIMEOUT_TICKS
			&& !ContainerUtils.isInputQueueActive()
			&& !ContainerUtils.isAutoMovePending()
			&& !NearbyContainerDryRun.isActiveSessionRunning()) {
			failCurrentStep();
		}
	}

	private static void tickBatchSettlement(Minecraft client) {
		if (activeRun == null || client.player == null || client.player.containerMenu == null) {
			activeRun = null;
			return;
		}
		int observedCopies = activeRun.observedProducedRecipeCopies();
		if (observedCopies >= activeRun.scheduledBatchCopies()) {
			ReachCraftingMod.LOGGER.info(
				"[chain_execute] batch_settled reason=observed_target index={} observed_copies={} scheduled_copies={}",
				activeRun.currentStepIndex(),
				observedCopies,
				activeRun.scheduledBatchCopies()
			);
			completeSettledBatch();
			return;
		}
		if (ContainerUtils.isAutoMovePending()) {
			activeRun = activeRun.withSettlingBatchProgress(observedCopies, 0);
			return;
		}

		Slot resultSlot = client.player.containerMenu.getSlot(0);
		if (!ContainerUtils.isAutoMovePending()
			&& resultSlot.hasItem()
			&& ItemStack.isSameItemSameComponents(resultSlot.getItem(), activeRun.currentStep().displayStack())) {
			ReachCraftingMod.LOGGER.info(
				"[chain_execute] batch_settle_auto_move index={} observed_copies={} scheduled_copies={} result={}",
				activeRun.currentStepIndex(),
				observedCopies,
				activeRun.scheduledBatchCopies(),
				ContainerUtils.formatStack(resultSlot.getItem())
			);
			ContainerUtils.scheduleAutoMove(activeRun.currentStep().displayStack());
			activeRun = activeRun.withSettlingBatchProgress(observedCopies, 0);
			return;
		}

		int quietTicks = observedCopies > activeRun.settleObservedCopies()
			? 0
			: activeRun.settleQuietTicks() + 1;
		activeRun = activeRun.withSettlingBatchProgress(observedCopies, quietTicks);
		if (quietTicks >= BATCH_SETTLE_QUIET_TICKS) {
			ReachCraftingMod.LOGGER.info(
				"[chain_execute] batch_settled reason=quiet index={} observed_copies={} scheduled_copies={} quiet_ticks={}",
				activeRun.currentStepIndex(),
				observedCopies,
				activeRun.scheduledBatchCopies(),
				quietTicks
			);
			completeSettledBatch();
		}
	}

	private static void completeSettledBatch() {
		if (activeRun == null) {
			return;
		}
		activeRun = activeRun.withCompletedBatch();
		if (activeRun == null || activeRun.currentStepIndex() >= activeRun.plan().steps().size()) {
			activeRun = null;
			return;
		}
		activeRun = activeRun.withWaiting(false, 0);
	}

	private static void tickPendingWarmupRetry(Minecraft client) {
		if (pendingWarmupRetry == null
			|| activeRun != null
			|| client.player == null
			|| NearbyContainerDryRun.isActiveSessionRunning()
			|| ContainerUtils.isInputQueueActive()
			|| ContainerUtils.isAutoMovePending()) {
			return;
		}
		if (!(client.screen instanceof CraftingScreen) && !(client.screen instanceof InventoryScreen)) {
			return;
		}

		PendingWarmupRetry retry = pendingWarmupRetry;
		pendingWarmupRetry = null;
		int currentOutputCount = countAccessibleOutput(client, retry.expectedOutput());
		if (currentOutputCount > retry.baselineOutputCount()) {
			ReachCraftingMod.LOGGER.info(
				"[chain_retry] skip_after_warmup reason=output_already_created recipe={} output={} baseline_count={} current_count={}",
				retry.action().recipeId(),
				ContainerUtils.formatStack(retry.expectedOutput()),
				retry.baselineOutputCount(),
				currentOutputCount
			);
			return;
		}
		ReachCraftingMod.LOGGER.info(
			"[chain_retry] replay_after_nearby_warmup recipe={} clicks={} output={}",
			retry.action().recipeId(),
			retry.remainingClicks(),
			ContainerUtils.formatStack(retry.expectedOutput())
		);
		AutoCraftController.armHoldSessionForCurrentRequest(true);
		RecipeBookClickCapture.scheduleReplay(
			retry.action(),
			retry.remainingClicks(),
			retry.allowNearby(),
			retry.craftAll(),
			retry.refillableBulkMaxMode(),
			true
		);
	}

	private static boolean tryStowCarriedStack(Minecraft client) {
		if (client.player == null || client.gameMode == null || client.player.containerMenu == null) {
			return false;
		}
		ItemStack carried = client.player.containerMenu.getCarried();
		if (carried.isEmpty()) {
			return false;
		}
		String itemId = BuiltInRegistries.ITEM.getKey(carried.getItem()).toString();
		Slot destination = MenuTransferHelper.findPlayerDestinationSlot(client.player, client.player.containerMenu, itemId);
		if (destination == null) {
			return false;
		}
		ReachCraftingMod.LOGGER.info(
			"[chain_execute] stow_carried item={} count={} dest_slot={}",
			itemId,
			carried.getCount(),
			destination.index
		);
		client.gameMode.handleContainerInput(client.player.containerMenu.containerId, destination.index, 0, ContainerInput.PICKUP, client.player);
		return true;
	}

	private static boolean shouldAttemptPreStage(Minecraft client, ChainCraftPlan plan) {
		if (client == null || client.player == null || plan == null) {
			return false;
		}
		return plan.steps().stream().anyMatch(ChainCraftPlan.Step::allowNearby)
			&& ReachCraftingConfig.get().enableNearbyContainerUsage();
	}

	private static void scheduleCurrentStep() {
		Minecraft client = Minecraft.getInstance();
		if (activeRun == null || client.player == null || client.screen == null) {
			activeRun = null;
			return;
		}
		ChainCraftPlan.Step step = activeRun.currentStep();
		int batchCopies = activeRun.nextBatchCopies();
		int baselineOutputCount = countAccessibleOutput(client, step.displayStack());
		RecipeBookClickCapture.HeldRecipeAction action = resolveExecutableAction(client, step);
		if (action == null) {
			ReachCraftingMod.LOGGER.info(
				"[chain_execute] step_unavailable recipe={} output={} ingredients={} known_recipes={}",
				step.recipeId(),
				ContainerUtils.formatStack(step.displayStack()),
				step.ingredientSummary().compactSummary(),
				((ClientRecipeBookAccessor) client.player.getRecipeBook()).getKnown().size()
			);
			failCurrentStep();
			return;
		}
		ReachCraftingMod.LOGGER.info(
			"[chain_execute] schedule_step index={} recipe={} output={} batch_copies={} remaining_copies={} final_step={}",
			activeRun.currentStepIndex(),
			step.recipeId(),
			ContainerUtils.formatStack(step.displayStack()),
			batchCopies,
			activeRun.remainingStepCopies(),
			step.finalStep()
		);
		AutoCraftController.armHoldSessionForCurrentRequest(true);
		activeFinalStepRecipeId = step.finalStep() ? action.recipeId() : null;
		RecipeBookClickCapture.scheduleReplay(
			action,
			batchCopies,
			step.allowNearby(),
			false,
			false
		);
		activeRun = activeRun.withScheduledBatch(batchCopies, baselineOutputCount);
	}

	private static RecipeBookClickCapture.HeldRecipeAction resolveExecutableAction(Minecraft client, ChainCraftPlan.Step step) {
		if (client.level == null || client.player == null) {
			return null;
		}
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) client.player.getRecipeBook()).getKnown();
		RecipeDisplayEntry directEntry = knownRecipes.get(step.recipeId());
		if (directEntry != null && matchesStep(client, step, directEntry)) {
			RecipeCollection directCollection = resolveCollection(client, step.recipeId());
			if (directCollection != null) {
				return new RecipeBookClickCapture.HeldRecipeAction(
					step.recipeId(),
					directCollection,
					step.displayStack().copy(),
					org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
					true
				);
			}
		}

		for (Map.Entry<RecipeDisplayId, RecipeDisplayEntry> entry : knownRecipes.entrySet()) {
			if (!matchesStep(client, step, entry.getValue())) {
				continue;
			}
			RecipeCollection collection = resolveCollection(client, entry.getKey());
			if (collection == null) {
				continue;
			}
			ReachCraftingMod.LOGGER.info(
				"[chain_execute] resolved_dynamic planned_recipe={} executable_recipe={} output={}",
				step.recipeId(),
				entry.getKey(),
				ContainerUtils.formatStack(step.displayStack())
			);
			return new RecipeBookClickCapture.HeldRecipeAction(
				entry.getKey(),
				collection,
				step.displayStack().copy(),
				org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
				true
			);
		}
		return null;
	}

	private static boolean matchesStep(Minecraft client, ChainCraftPlan.Step step, RecipeDisplayEntry entry) {
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		ItemStack output = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
		if (output.isEmpty()) {
			return false;
		}
		String outputId = BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
		String stepOutputId = BuiltInRegistries.ITEM.getKey(step.displayStack().getItem()).toString();
		if (!outputId.equals(stepOutputId) || output.getCount() != step.displayStack().getCount()) {
			return false;
		}
		RecipeIngredientSummary summary = RecipeIngredientSummary.fromDisplay(entry.display(), context);
		return summary.compactSummary().equals(step.ingredientSummary().compactSummary());
	}

	private static RecipeCollection resolveCollection(Minecraft client, RecipeDisplayId recipeId) {
		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			if (collection.getRecipes().stream().anyMatch(entry -> entry.id().equals(recipeId))) {
				return collection;
			}
		}
		return null;
	}

	private static void failCurrentStep() {
		if (activeRun == null) {
			return;
		}
		String itemName = activeRun.currentStep().displayStack().getHoverName().getString();
		// During a bulk chain session a failed step is an internal retry
		// event: the outer loop accounts real progress and replans, and its
		// summary is the user-facing outcome. Chatting "stopped" here reads
		// as a false alarm right before "complete".
		if (BulkChainCraftController.isActive()) {
			ReachCraftingMod.LOGGER.info("[chain_execute] step_failed_during_bulk_chain item={}", itemName);
		} else {
			ReachCraftingModClient.sendChat(Component.translatable("message.reachcrafting.chain_crafting.failed", itemName).getString());
		}
		activeRun = null;
	}

	static int countAccessibleOutput(Minecraft client, ItemStack expectedOutput) {
		if (client == null || client.player == null || expectedOutput == null || expectedOutput.isEmpty()) {
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
		ItemStack offhand = client.player.getOffhandItem();
		if (!offhand.isEmpty() && ItemStack.isSameItemSameComponents(offhand, expectedOutput)) {
			count += offhand.getCount();
		}
		// A full inventory can leave a produced item on the cursor; it is
		// still produced, and missing it makes settlement re-craft a copy
		// whose ingredients were already consumed.
		ItemStack carried = client.player.containerMenu.getCarried();
		if (!carried.isEmpty() && ItemStack.isSameItemSameComponents(carried, expectedOutput)) {
			count += carried.getCount();
		}
		return count;
	}

	private record ChainCraftRun(
		ChainCraftPlan plan,
		int currentStepIndex,
		boolean waitingForStep,
		boolean waitingForStaging,
		boolean stagingAttempted,
		boolean preStagedNearbyResources,
		int waitTicks,
		int remainingStepCopies,
		int scheduledBatchCopies,
		int baselineOutputCount,
		boolean settlingBatch,
		int settleObservedCopies,
		int settleQuietTicks,
		int batchEjectedItems
	) {
		private static ChainCraftRun start(ChainCraftPlan plan) {
			return new ChainCraftRun(plan, 0, false, false, false, false, 0, plan.steps().getFirst().recipeCopies(), 0, 0, false, 0, 0, 0);
		}

		ChainCraftPlan.Step currentStep() {
			return plan.steps().get(currentStepIndex);
		}

		int nextBatchCopies() {
			return Math.min(Math.max(remainingStepCopies, 1), maxBatchCopies(currentStep()));
		}

		ChainCraftRun withScheduledBatch(int batchCopies, int outputCountBeforeBatch) {
			return new ChainCraftRun(plan, currentStepIndex, true, false, stagingAttempted, preStagedNearbyResources, 0, remainingStepCopies, Math.max(batchCopies, 1), outputCountBeforeBatch, false, 0, 0, 0);
		}

		ChainCraftRun withWaitingForStaging() {
			return new ChainCraftRun(plan, currentStepIndex, false, true, true, false, 0, remainingStepCopies, scheduledBatchCopies, baselineOutputCount, false, 0, 0, 0);
		}

		ChainCraftRun withStagingComplete(boolean preStaged) {
			return new ChainCraftRun(plan, currentStepIndex, false, false, true, preStaged, 0, remainingStepCopies, scheduledBatchCopies, baselineOutputCount, false, 0, 0, 0);
		}

		ChainCraftRun withBatchEjectedItems(int updatedBatchEjectedItems) {
			return new ChainCraftRun(plan, currentStepIndex, waitingForStep, waitingForStaging, stagingAttempted, preStagedNearbyResources, waitTicks, remainingStepCopies, scheduledBatchCopies, baselineOutputCount, settlingBatch, settleObservedCopies, settleQuietTicks, updatedBatchEjectedItems);
		}

		boolean needsBatchSettlement() {
			return scheduledBatchCopies > 1 && observedProducedRecipeCopies() < scheduledBatchCopies;
		}

		ChainCraftRun withSettlingBatch() {
			int observedCopies = observedProducedRecipeCopies();
			ReachCraftingMod.LOGGER.info(
				"[chain_execute] batch_settling index={} scheduled_copies={} observed_copies={} remaining_before={}",
				currentStepIndex,
				scheduledBatchCopies,
				observedCopies,
				remainingStepCopies
			);
			return new ChainCraftRun(plan, currentStepIndex, true, false, stagingAttempted, preStagedNearbyResources, 0, remainingStepCopies, scheduledBatchCopies, baselineOutputCount, true, observedCopies, 0, batchEjectedItems);
		}

		ChainCraftRun withSettlingBatchProgress(int observedCopies, int quietTicks) {
			return new ChainCraftRun(plan, currentStepIndex, true, false, stagingAttempted, preStagedNearbyResources, waitTicks + 1, remainingStepCopies, scheduledBatchCopies, baselineOutputCount, true, observedCopies, quietTicks, batchEjectedItems);
		}

		ChainCraftRun withCompletedBatch() {
			int producedCopies = observedProducedRecipeCopies();
			int completedCopies = Math.max(1, Math.min(Math.max(scheduledBatchCopies, 1), producedCopies));
			int remaining = remainingStepCopies - completedCopies;
			ReachCraftingMod.LOGGER.info(
				"[chain_execute] batch_finished index={} scheduled_copies={} observed_copies={} completed_copies={} remaining_before={}",
				currentStepIndex,
				scheduledBatchCopies,
				producedCopies,
				completedCopies,
				remainingStepCopies
			);
			if (remaining > 0) {
				ReachCraftingMod.LOGGER.info(
					"[chain_execute] step_batch_complete index={} remaining_copies={}",
					currentStepIndex,
					remaining
				);
				return new ChainCraftRun(plan, currentStepIndex, false, false, stagingAttempted, preStagedNearbyResources, 0, remaining, 0, 0, false, 0, 0, 0);
			}
			int nextIndex = currentStepIndex + 1;
			if (nextIndex >= plan.steps().size()) {
				ReachCraftingConfig.get().noteRecentRecipe(currentStep().recipeId());
				ReachCraftingMod.LOGGER.info("[chain_execute] complete steps={}", plan.steps().size());
				return null;
			}
			ReachCraftingMod.LOGGER.info(
				"[chain_execute] step_complete index={} next_index={}",
				currentStepIndex,
				nextIndex
			);
			return new ChainCraftRun(plan, nextIndex, false, false, stagingAttempted, preStagedNearbyResources, 0, plan.steps().get(nextIndex).recipeCopies(), 0, 0, false, 0, 0, 0);
		}

		ChainCraftRun withWaiting(boolean updatedWaitingForStep, int updatedWaitTicks) {
			return new ChainCraftRun(plan, currentStepIndex, updatedWaitingForStep, waitingForStaging, stagingAttempted, preStagedNearbyResources, updatedWaitTicks, remainingStepCopies, scheduledBatchCopies, baselineOutputCount, settlingBatch, settleObservedCopies, settleQuietTicks, batchEjectedItems);
		}

		private static int maxBatchCopies(ChainCraftPlan.Step step) {
			return 64;
		}

		private int observedProducedRecipeCopies() {
			int currentCount = countAccessibleOutput(Minecraft.getInstance(), currentStep().displayStack());
			int producedItems = Math.max(0, currentCount - baselineOutputCount) + batchEjectedItems;
			int outputPerCraft = Math.max(currentStep().displayStack().getCount(), 1);
			return producedItems / outputPerCraft;
		}
	}

	private record PendingWarmupRetry(
		RecipeBookClickCapture.HeldRecipeAction action,
		int remainingClicks,
		boolean allowNearby,
		boolean craftAll,
		boolean refillableBulkMaxMode,
		ItemStack expectedOutput,
		int baselineOutputCount
	) {
	}
}
