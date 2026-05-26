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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

public final class ChainCraftController {
	private static final int STEP_TIMEOUT_TICKS = 200;
	private static ChainCraftRun activeRun;
	private static PendingWarmupRetry pendingWarmupRetry;

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

	static boolean isUsingPreStagedNearbyResources() {
		return activeRun != null && activeRun.preStagedNearbyResources();
	}

	static void abort(boolean report) {
		if (activeRun == null && pendingWarmupRetry == null) {
			return;
		}
		activeRun = null;
		pendingWarmupRetry = null;
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
		ReachCraftingModClient.sendChat(Component.translatable("message.reachcrafting.chain_crafting.failed", itemName).getString());
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
		int baselineOutputCount
	) {
		private static ChainCraftRun start(ChainCraftPlan plan) {
			return new ChainCraftRun(plan, 0, false, false, false, false, 0, plan.steps().getFirst().recipeCopies(), 0, 0);
		}

		ChainCraftPlan.Step currentStep() {
			return plan.steps().get(currentStepIndex);
		}

		int nextBatchCopies() {
			return Math.min(Math.max(remainingStepCopies, 1), maxBatchCopies(currentStep()));
		}

		ChainCraftRun withScheduledBatch(int batchCopies, int outputCountBeforeBatch) {
			return new ChainCraftRun(plan, currentStepIndex, true, false, stagingAttempted, preStagedNearbyResources, 0, remainingStepCopies, Math.max(batchCopies, 1), outputCountBeforeBatch);
		}

		ChainCraftRun withWaitingForStaging() {
			return new ChainCraftRun(plan, currentStepIndex, false, true, true, false, 0, remainingStepCopies, scheduledBatchCopies, baselineOutputCount);
		}

		ChainCraftRun withStagingComplete(boolean preStaged) {
			return new ChainCraftRun(plan, currentStepIndex, false, false, true, preStaged, 0, remainingStepCopies, scheduledBatchCopies, baselineOutputCount);
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
				return new ChainCraftRun(plan, currentStepIndex, false, false, stagingAttempted, preStagedNearbyResources, 0, remaining, 0, 0);
			}
			int nextIndex = currentStepIndex + 1;
			if (nextIndex >= plan.steps().size()) {
				ReachCraftingMod.LOGGER.info("[chain_execute] complete steps={}", plan.steps().size());
				return null;
			}
			ReachCraftingMod.LOGGER.info(
				"[chain_execute] step_complete index={} next_index={}",
				currentStepIndex,
				nextIndex
			);
			return new ChainCraftRun(plan, nextIndex, false, false, stagingAttempted, preStagedNearbyResources, 0, plan.steps().get(nextIndex).recipeCopies(), 0, 0);
		}

		ChainCraftRun withWaiting(boolean updatedWaitingForStep, int updatedWaitTicks) {
			return new ChainCraftRun(plan, currentStepIndex, updatedWaitingForStep, waitingForStaging, stagingAttempted, preStagedNearbyResources, updatedWaitTicks, remainingStepCopies, scheduledBatchCopies, baselineOutputCount);
		}

		private static int maxBatchCopies(ChainCraftPlan.Step step) {
			return 64;
		}

		private int observedProducedRecipeCopies() {
			int currentCount = countAccessibleOutput(Minecraft.getInstance(), currentStep().displayStack());
			int producedItems = Math.max(0, currentCount - baselineOutputCount);
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
