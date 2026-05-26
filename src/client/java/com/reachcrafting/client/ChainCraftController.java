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
		activeRun = new ChainCraftRun(plan, 0, false, 0);
		scheduleCurrentStep();
	}

	static boolean isActive() {
		return activeRun != null;
	}

	static boolean isRunningIntermediateStep() {
		return activeRun != null && activeRun.currentStepIndex() < activeRun.plan().steps().size() - 1;
	}

	static void abort(boolean report) {
		if (activeRun == null) {
			return;
		}
		activeRun = null;
		if (report) {
			ReachCraftingModClient.sendAbortedChat("Crafting session aborted.");
		}
	}

	static void onAutoMoveFinished(Minecraft client, boolean success) {
		if (activeRun == null) {
			return;
		}
		if (!success) {
			failCurrentStep();
			return;
		}
		activeRun = activeRun.withAdvancedStep();
		if (activeRun.currentStepIndex() >= activeRun.plan().steps().size()) {
			activeRun = null;
			return;
		}
		activeRun = activeRun.withWaiting(false, 0);
	}

	private static void tick(Minecraft client) {
		ChainCraftPopupController.tick(client);
		if (activeRun == null) {
			return;
		}
		if (client.player == null || (!(client.screen instanceof CraftingScreen) && !(client.screen instanceof InventoryScreen))) {
			ReachCraftingModClient.sendChat(Component.translatable("message.reachcrafting.chain_crafting.context_lost").getString());
			activeRun = null;
			return;
		}
		if (!client.isWindowActive()) {
			abort(true);
			return;
		}
		if (!activeRun.waitingForStep()) {
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

	private static void scheduleCurrentStep() {
		Minecraft client = Minecraft.getInstance();
		if (activeRun == null || client.player == null || client.screen == null) {
			activeRun = null;
			return;
		}
		ChainCraftPlan.Step step = activeRun.currentStep();
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
		AutoCraftController.armHoldSessionForCurrentRequest(true);
		RecipeBookClickCapture.scheduleReplay(
			action,
			step.recipeCopies(),
			step.allowNearby(),
			false,
			false
		);
		activeRun = activeRun.withWaiting(true, 0);
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
		int waitTicks
	) {
		ChainCraftPlan.Step currentStep() {
			return plan.steps().get(currentStepIndex);
		}

		ChainCraftRun withAdvancedStep() {
			return new ChainCraftRun(plan, currentStepIndex + 1, false, 0);
		}

		ChainCraftRun withWaiting(boolean updatedWaitingForStep, int updatedWaitTicks) {
			return new ChainCraftRun(plan, currentStepIndex, updatedWaitingForStep, updatedWaitTicks);
		}
	}
}
