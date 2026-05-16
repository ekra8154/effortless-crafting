package com.reachcrafting.client;

import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.lwjgl.glfw.GLFW;

final class RecipeBookInputController {
	private static final RecipeBookInputController INSTANCE = new RecipeBookInputController();
	private static final int MODIFIER_RELEASE_GRACE_TICKS = 2;
	private static final int MODIFIER_RELEASE_WINDOW_TICKS = MODIFIER_RELEASE_GRACE_TICKS + 1;

	/**
	 * Owns all held-recipe queue semantics, including visible-count fallback,
	 * queued zero state, release behavior, and per-recipe wrap limits.
	 */
	private final HeldRecipeQueueState state = new HeldRecipeQueueState();

	private RecipeBookInputController() {
	}

	static RecipeBookInputController getInstance() {
		return INSTANCE;
	}

	void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ReachCraftingConfig.get().enabled()) {
				state.clear();
				return;
			}
			boolean controlDown = RecipeBookFocusManager.isControlKeyDown(client);
			boolean shiftDown = RecipeBookFocusManager.isShiftKeyDown(client);
			boolean altDown = RecipeBookFocusManager.isAltKeyDown(client);
			boolean spaceDown = RecipeBookFocusManager.isSpaceKeyDown(client);
			updateModifierReleaseWindows(controlDown, shiftDown, altDown);

			if (controlDown || shiftDown || (altDown && ReachCraftingConfig.get().altAsRequestKey())) {
				RecipeBookFocusManager.defocusRecipeBookSearch(client, state);
			} else if (state.wasSearchBoxFocusedByMod()
				&& !spaceDown
				&& state.pendingHeldRecipe() == null
				&& state.replayBatch() == null
				&& !NearbyContainerDryRun.isActiveSessionRunning()) {
				RecipeBookFocusManager.refocusRecipeBookSearch(client, state);
			}

			if (state.pendingHeldRecipe() != null) {
				ModifierState activeModifiers = currentModifierState(controlDown, shiftDown, altDown);
				boolean anyRelevantReleaseThisTick = (state.wasControlDown() && !controlDown)
					|| (state.wasShiftDown() && !shiftDown)
					|| (ReachCraftingConfig.get().altAsRequestKey() && state.wasAltDown() && !altDown);

				if (anyRelevantReleaseThisTick) {
					state.setWasModifierReleasedWhileSpaceHeld(true);
				}

				if (state.wasModifierReleasedWhileSpaceHeld() && !activeModifiers.anyHeld() && !spaceDown) {
					releasePendingHeldRecipe(activeModifiers);
					state.setWasModifierReleasedWhileSpaceHeld(false);
				}
			} else {
				state.setWasModifierReleasedWhileSpaceHeld(false);
			}

			state.setWasControlDown(controlDown);
			state.setWasShiftDown(shiftDown);
			state.setWasAltDown(altDown);

			if (state.replayDelayTicks() > 0) {
				state.decrementReplayDelayTicks();
			} else {
				processReplayBatch(client);
			}

			decrementModifierReleaseWindows(controlDown, shiftDown, altDown);

			if (!controlDown && !shiftDown && !altDown && !ReachCraftingConfig.get().reachCraftHoldAndRelease()) {
				state.setPendingHeldRecipe(null);
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			ScreenMouseEvents.allowMouseClick(screen).register((currentScreen, event) -> {
				if (!ReachCraftingConfig.get().enabled()) {
					return true;
				}
				if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
					return true;
				}
				RecipeBookClickCapture.HeldRecipeAction action = RecipeBookFocusManager.findHoveredHeldRecipeAction(currentScreen, event.x(), event.y());
				return action == null || !clearHeldRecipe(action);
			});
			ScreenMouseEvents.allowMouseScroll(screen).register((currentScreen, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
				if (!ReachCraftingConfig.get().enabled()) {
					return true;
				}
				if (handleHeldRecipeScroll(currentScreen, mouseX, mouseY, verticalAmount)) {
					return false;
				}
				if (ScrollToPullHandler.handleScroll(currentScreen, mouseX, mouseY, verticalAmount)) {
					return false;
				}
				return true;
			});
		});
	}

	void onRecipeButtonClicked(
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean shiftModifierDown,
		boolean ctrlModifierDown,
		boolean altModifierDown,
		boolean explicitVariantSelection
	) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return;
		}
		if (state.replayBatch() != null) {
			return;
		}

		if (shiftModifierDown || ctrlModifierDown || (altModifierDown && ReachCraftingConfig.get().altAsRequestKey())) {
			RecipeBookFocusManager.defocusRecipeBookSearch(minecraft);
		}

		boolean autoCraftRequested = altModifierDown && ReachCraftingConfig.get().altAsRequestKey();
		boolean maxCraftRequested = shiftModifierDown;
		boolean craftAll = maxCraftRequested;
		boolean allowNearbyChests = ctrlModifierDown
			&& ReachCraftingConfig.get().enableNearbyContainerUsage();
		boolean refillableBulkMaxMode = maxCraftRequested && allowNearbyChests && AutoCraftController.isBulkModeEnabled();
		int requestedClicks = maxCraftRequested
			? resolveMaxCraftRequestCount(minecraft, player, recipeId, collection, displayStack, explicitVariantSelection, allowNearbyChests)
			: 1;

		if (shouldQueueHeldRecipe(minecraft, maxCraftRequested, autoCraftRequested) && state.replayBatch() == null) {
			if (autoCraftRequested) {
				AutoCraftController.consumeQuickCraft();
			}
			queueHeldRecipe(recipeId, collection, displayStack, mouseButton, explicitVariantSelection, requestedClicks);
			return;
		}

		if (autoCraftRequested) {
			AutoCraftController.consumeQuickCraft();
		}
		AutoCraftController.armHoldSessionForCurrentRequest(autoCraftRequested);

		if (!ContainerUtils.isGridEmpty(player.containerMenu)) {
			ContainerUtils.flushCraftingGrid(minecraft, allowNearbyChests, true);
			state.setReplayDelayTicks(1);
			RecipeBookClickCapture.HeldRecipeAction action = new RecipeBookClickCapture.HeldRecipeAction(
				recipeId,
				collection,
				displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
				mouseButton,
				explicitVariantSelection
			);
			state.setReplayBatch(new RecipeBookClickCapture.ReplayBatch(action, requestedClicks, allowNearbyChests, craftAll, refillableBulkMaxMode));
			return;
		}

		RecipeClickExecutor.executeRecipeButtonClick(
			minecraft,
			player,
			screen,
			recipeId,
			collection,
			displayStack,
			mouseButton,
			craftAll,
			allowNearbyChests,
			false,
			explicitVariantSelection,
			requestedClicks,
			refillableBulkMaxMode,
			state
		);
	}

	void onVanillaRecipeButtonClicked(
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection,
		boolean altModifierDown
	) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		boolean autoCraftRequested = altModifierDown && ReachCraftingConfig.get().altAsRequestKey();
		if (autoCraftRequested) {
			AutoCraftController.consumeQuickCraft();
		}
		AutoCraftController.armHoldSessionForCurrentRequest(autoCraftRequested);
		if (!AutoCraftController.isEnabled()) {
			if (explicitVariantSelection) {
				RecipeClickExecutor.tryCloseOverlayAfterRelease();
			}
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || recipeId == null || collection == null) {
			return;
		}

		ItemStack expectedStack = RecipeClickExecutor.resolveExpectedOutputStack(
			minecraft,
			player,
			recipeId,
			collection,
			displayStack,
			explicitVariantSelection
		);
		ContainerUtils.scheduleAutoMove(expectedStack);
		if (explicitVariantSelection) {
			RecipeClickExecutor.tryCloseOverlayAfterRelease();
		}
	}

	boolean onRecipeButtonRightClicked(
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection
	) {
		if (!ReachCraftingConfig.get().enabled() || recipeId == null || collection == null) {
			return false;
		}
		return clearHeldRecipe(new RecipeBookClickCapture.HeldRecipeAction(
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			GLFW.GLFW_MOUSE_BUTTON_LEFT,
			explicitVariantSelection
		));
	}

	QueuedRecipeCountState getQueuedCountState(
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		if (ReachCraftingConfig.get().inputCounterVisibility() == ReachCraftingConfig.InputCounterVisibility.DISABLED
			|| !ReachCraftingConfig.get().reachCraftHoldAndRelease()
			|| recipeId == null
			|| collection == null) {
			return QueuedRecipeCountState.hidden();
		}

		if (state.pendingHeldRecipe() != null && matchesAction(state.pendingHeldRecipe().action(), recipeId, collection, explicitVariantSelection)) {
			return QueuedRecipeCountState.visible(
				state.pendingHeldRecipe().clickCount(),
				true,
				resolvePendingOutputStack(Minecraft.getInstance())
			);
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (!shouldShowCurrentRecipeCount(minecraft)) {
			return QueuedRecipeCountState.hidden();
		}

		int currentCount = RecipeClickExecutor.resolveGridMatchedCount(minecraft, recipeId, collection, explicitVariantSelection);
		if (currentCount <= 0) {
			return QueuedRecipeCountState.hidden();
		}

		return QueuedRecipeCountState.visible(currentCount, false, ItemStack.EMPTY);
	}

	QueuedRecipeCountState getQueuedCountState(RecipeButton button) {
		if (button == null || button.getCollection() == null || button.getCurrentRecipe() == null) {
			return QueuedRecipeCountState.hidden();
		}
		return getQueuedCountState(button.getCurrentRecipe(), button.getCollection(), false);
	}

	int getHeldQueuedCount(
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		return getQueuedCountState(recipeId, collection, explicitVariantSelection).displayedCount();
	}

	int getHeldQueuedCount(RecipeButton button) {
		return getQueuedCountState(button).displayedCount();
	}

	void tryCloseOverlayAfterRelease() {
		RecipeClickExecutor.tryCloseOverlayAfterRelease();
	}

	void refocusRecipeBookSearch(Minecraft minecraft) {
		RecipeBookFocusManager.refocusRecipeBookSearch(minecraft, state);
	}

	void defocusRecipeBookSearch(Minecraft minecraft) {
		RecipeBookFocusManager.defocusRecipeBookSearch(minecraft);
	}

	RecipeBookClickCapture.PendingHeldRecipe getPendingHeldRecipe() {
		return state.pendingHeldRecipe();
	}

	boolean isInputQueueActive() {
		return state.pendingHeldRecipe() != null || state.replayBatch() != null;
	}

	boolean isAltRequestActive() {
		return state.pendingHeldRecipe() != null && currentModifierState().altRequested();
	}

	void clearInputQueue() {
		state.setPendingHeldRecipe(null);
		state.setReplayBatch(null);
		state.setWasModifierReleasedWhileSpaceHeld(false);
		
		Minecraft minecraft = Minecraft.getInstance();
		if (ReachCraftingConfig.get().inputCounterVisibility() == ReachCraftingConfig.InputCounterVisibility.ALWAYS_SHOW
			&& minecraft.player != null
			&& !ContainerUtils.isGridEmpty(minecraft.player.containerMenu)) {
			ContainerUtils.flushCraftingGrid(minecraft, true, true);
		}
	}

	void scheduleReplay(RecipeBookClickCapture.HeldRecipeAction action, int remainingClicks, boolean allowNearby, boolean craftAll, boolean refillableBulkMaxMode) {
		if (!ReachCraftingConfig.get().enabled() || action == null || remainingClicks <= 0) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		if (!ContainerUtils.isGridEmpty(minecraft.player.containerMenu)) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[recipe_replay] flushing grid before replay remainingClicks={} allowNearby={} craftAll={} refillable={} bulkActive={}",
				remainingClicks,
				allowNearby,
				craftAll,
				refillableBulkMaxMode,
				BulkAutoCraftController.isActive()
			);
			ContainerUtils.flushCraftingGrid(minecraft, allowNearby, true);
			// During bulk mode, use a longer delay to let the server process
			// the THROW + flush and sync grid state back. This allows us to
			// see and eject byproducts (e.g. glass bottles) that appear in
			// the grid server-side before handlePlaceRecipe clears them.
			if (AutoCraftController.isBulkModeEnabled() && BulkAutoCraftController.isActive()) {
				state.setReplayDelayTicks(1);
			} else {
				state.setReplayDelayTicks(1);
			}
		}

		com.reachcrafting.ReachCraftingMod.LOGGER.info(
			"[recipe_replay] scheduleReplay remainingClicks={} allowNearby={} craftAll={} refillable={} recipe={}",
			remainingClicks,
			allowNearby,
			craftAll,
			refillableBulkMaxMode,
			action.recipeId()
		);
		state.setReplayBatch(new RecipeBookClickCapture.ReplayBatch(action, remainingClicks, allowNearby, craftAll, refillableBulkMaxMode));
	}

	boolean hasPendingHeldRecipe(
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		if (state.pendingHeldRecipe() == null) {
			return false;
		}
		return matchesAction(state.pendingHeldRecipe().action(), recipeId, collection, explicitVariantSelection);
	}

	ItemStack resolvePendingOutputStack(Minecraft minecraft) {
		if (state.pendingHeldRecipe() == null || minecraft.level == null || minecraft.player == null) {
			return ItemStack.EMPTY;
		}

		RecipeBookClickCapture.HeldRecipeAction action = state.pendingHeldRecipe().action();
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) minecraft.player.getRecipeBook()).getKnown();
		RecipeDisplayEntry entry = knownRecipes.get(action.recipeId());
		if (entry == null) {
			return ItemStack.EMPTY;
		}

		ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
		return RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
	}

	private boolean shouldQueueHeldRecipe(Minecraft minecraft, boolean maxCraftRequested, boolean autoCraftRequested) {
		if (autoCraftRequested && ReachCraftingConfig.get().altClickInstantCraft()) {
			return false;
		}
		return ReachCraftingConfig.get().reachCraftHoldAndRelease()
			&& !maxCraftRequested
			&& (RecipeBookFocusManager.isControlKeyDown(minecraft)
				|| RecipeBookFocusManager.isShiftKeyDown(minecraft)
				|| (ReachCraftingConfig.get().altAsRequestKey() && RecipeBookFocusManager.isAltKeyDown(minecraft)));
	}

	private int resolveMaxCraftRequestCount(
		Minecraft minecraft,
		LocalPlayer player,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection,
		boolean allowNearbyChests
	) {
		if (allowNearbyChests && AutoCraftController.isBulkModeEnabled()) {
			return RecipeClickExecutor.bulkRecipeQueueLimit();
		}

		ItemStack expectedOutput = RecipeClickExecutor.resolveExpectedOutputStack(
			minecraft,
			player,
			recipeId,
			collection,
			displayStack,
			explicitVariantSelection
		);
		if (!expectedOutput.isEmpty()) {
			return Math.max(expectedOutput.getMaxStackSize(), 1);
		}

		return Math.max(RecipeClickExecutor.resolveRecipeQueueLimit(minecraft, recipeId, collection), 1);
	}

	private void queueHeldRecipe(
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean explicitVariantSelection,
		int initialCount
	) {
		RecipeBookClickCapture.HeldRecipeAction action = new RecipeBookClickCapture.HeldRecipeAction(
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			mouseButton,
			explicitVariantSelection
		);

		if (state.pendingHeldRecipe() == null) {
			if (initialCount <= 0) {
				state.setPendingHeldRecipe(null);
				return;
			}
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, initialCount, initialCount >= 2));
			return;
		}

		if (state.pendingHeldRecipe().action().sameRecipe(action)) {
			int delta = RecipeBookFocusManager.isSpaceKeyDown(Minecraft.getInstance()) ? 16 : 1;
			int updatedCount = wrapQueuedCount(Minecraft.getInstance(), action, state.pendingHeldRecipe().clickCount(), delta);
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, updatedCount, updatedCount >= 2));
			return;
		}

		if (!state.pendingHeldRecipe().locked()) {
			int newInitialCount = nextQueuedCountFromCurrentState(Minecraft.getInstance(), action, 1);
			if (newInitialCount <= 0) {
				state.setPendingHeldRecipe(null);
				return;
			}
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, newInitialCount, newInitialCount >= 2));
		}
	}

	private void releasePendingHeldRecipe(ModifierState modifierState) {
		if (!ReachCraftingConfig.get().enabled() || state.pendingHeldRecipe() == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ResolvedRequest resolvedRequest = resolveRequest(modifierState);
		if (state.pendingHeldRecipe().clickCount() <= 0) {
			if (!ContainerUtils.isGridEmpty(minecraft.player.containerMenu)) {
				ContainerUtils.flushCraftingGrid(minecraft, resolvedRequest.allowNearby(), true);
			}
			state.setPendingHeldRecipe(null);
			return;
		}

		if (!ContainerUtils.isGridEmpty(minecraft.player.containerMenu)) {
			ContainerUtils.flushCraftingGrid(minecraft, resolvedRequest.allowNearby(), true);
		}

		if (resolvedRequest.autoCraftRequested()) {
			AutoCraftController.consumeQuickCraft();
		}
		AutoCraftController.armHoldSessionForCurrentRequest(resolvedRequest.autoCraftRequested());

		state.setReplayBatch(new RecipeBookClickCapture.ReplayBatch(
			state.pendingHeldRecipe().action(),
			state.pendingHeldRecipe().clickCount(),
			resolvedRequest.allowNearby(),
			false,
			false
		));
		state.setPendingHeldRecipe(null);
	}

	private void processReplayBatch(Minecraft minecraft) {
		if (!ReachCraftingConfig.get().enabled() || state.replayBatch() == null) {
			return;
		}
		if (NearbyContainerDryRun.isActiveSessionRunning()) {
			return;
		}

		Screen screen = minecraft.screen;
		LocalPlayer player = minecraft.player;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			state.setReplayBatch(null);
			return;
		}
		if (player == null || minecraft.level == null) {
			state.setReplayBatch(null);
			return;
		}

		NearbyContainerDryRun.runPendingPostReturnCompaction(minecraft);

		com.reachcrafting.ReachCraftingMod.LOGGER.debug(
			"[recipe_replay] screen={} recipe_idx={} remaining_clicks={} allow_nearby={} craft_all={}",
			screen.getClass().getSimpleName(),
			state.replayBatch().action().recipeId().index(),
			state.replayBatch().remainingClicks(),
			state.replayBatch().allowNearby(),
			state.replayBatch().craftAll()
		);

		RecipeClickExecutor.executeRecipeButtonClick(
			minecraft,
			player,
			screen,
			state.replayBatch().action().recipeId(),
			state.replayBatch().action().collection(),
			state.replayBatch().action().displayStack().copy(),
			state.replayBatch().action().mouseButton(),
			state.replayBatch().craftAll(),
			state.replayBatch().allowNearby(),
			true,
			state.replayBatch().action().explicitVariantSelection(),
			state.replayBatch().remainingClicks(),
			state.replayBatch().refillableBulkMaxMode(),
			state
		);
		state.setReplayBatch(null);
	}

	private boolean handleHeldRecipeScroll(Screen screen, double mouseX, double mouseY, double verticalAmount) {
		if (!ReachCraftingConfig.get().enabled()) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!canUseHeldQueueControls(minecraft) || verticalAmount == 0.0D) {
			return false;
		}

		RecipeBookClickCapture.HeldRecipeAction action = RecipeBookFocusManager.findHoveredHeldRecipeAction(screen, mouseX, mouseY);
		if (action == null) {
			return false;
		}

		int delta = verticalAmount > 0.0D ? 1 : -1;
		return adjustHeldRecipeCount(minecraft, action, delta);
	}

	private boolean canUseHeldQueueControls(Minecraft minecraft) {
		return ReachCraftingConfig.get().reachCraftHoldAndRelease()
			&& state.replayBatch() == null
			&& (RecipeBookFocusManager.isControlKeyDown(minecraft)
				|| RecipeBookFocusManager.isShiftKeyDown(minecraft)
				|| (RecipeBookFocusManager.isAltKeyDown(minecraft) && ReachCraftingConfig.get().altAsRequestKey()));
	}

	private boolean adjustHeldRecipeCount(Minecraft minecraft, RecipeBookClickCapture.HeldRecipeAction action, int delta) {
		if (delta == 0) {
			return false;
		}

		int multiplier = RecipeBookFocusManager.isSpaceKeyDown(minecraft) ? 16 : 1;
		int effectiveDelta = delta * multiplier;

		if (state.pendingHeldRecipe() == null) {
			int updatedCount = nextQueuedCountFromCurrentState(minecraft, action, effectiveDelta);
			if (RecipeBookFocusManager.isAltKeyDown(minecraft) && ReachCraftingConfig.get().altAsRequestKey()) {
				AutoCraftController.consumeQuickCraft();
			}
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, updatedCount, updatedCount >= 2));
			return true;
		}

		if (state.pendingHeldRecipe().action().sameRecipe(action)) {
			int updatedCount = wrapQueuedCount(minecraft, action, state.pendingHeldRecipe().clickCount(), effectiveDelta);
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(
				action,
				updatedCount,
				updatedCount >= 2
			));
			return true;
		}

		if (delta > 0) {
			int updatedCount = nextQueuedCountFromCurrentState(minecraft, action, effectiveDelta);
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, updatedCount, updatedCount >= 2));
			return true;
		}

		return false;
	}

	private boolean clearHeldRecipe(RecipeBookClickCapture.HeldRecipeAction action) {
		Minecraft minecraft = Minecraft.getInstance();
		boolean hasDisplayedCount = getHeldQueuedCount(action.recipeId(), action.collection(), action.explicitVariantSelection()) > 0;
		if (!hasDisplayedCount) {
			return false;
		}

		boolean samePendingRecipe = state.pendingHeldRecipe() != null && state.pendingHeldRecipe().action().sameRecipe(action);
		state.setPendingHeldRecipe(null);
		state.setReplayBatch(null);
		state.setWasModifierReleasedWhileSpaceHeld(false);
		if (ReachCraftingConfig.get().inputCounterVisibility() == ReachCraftingConfig.InputCounterVisibility.ALWAYS_SHOW
			&& minecraft.player != null
			&& !ContainerUtils.isGridEmpty(minecraft.player.containerMenu)) {
			ContainerUtils.flushCraftingGrid(minecraft, true, true);
		}
		return samePendingRecipe || hasDisplayedCount;
	}

	private int nextQueuedCountFromCurrentState(Minecraft minecraft, RecipeBookClickCapture.HeldRecipeAction action, int delta) {
		int queueLimit = resolveQueueLimit(minecraft, action);
		int baseCount = Math.min(
			RecipeClickExecutor.resolveGridMatchedCount(minecraft, action.recipeId(), action.collection(), action.explicitVariantSelection()),
			queueLimit
		);
		if (AutoCraftController.isBulkModeEnabled()) {
			return clampQueuedCount(baseCount, delta, queueLimit);
		}
		int updatedCount = baseCount + delta;
		if (updatedCount < 0) {
			return queueLimit;
		}
		if (updatedCount == 0) {
			return 0;
		}
		return Math.min(updatedCount, queueLimit);
	}

	private boolean shouldShowCurrentRecipeCount(Minecraft minecraft) {
		ReachCraftingConfig.InputCounterVisibility visibility = ReachCraftingConfig.get().inputCounterVisibility();
		if (visibility == ReachCraftingConfig.InputCounterVisibility.DISABLED) {
			return false;
		}
		if (visibility == ReachCraftingConfig.InputCounterVisibility.ALWAYS_SHOW) {
			return true;
		}
		return RecipeBookFocusManager.isControlKeyDown(minecraft)
			|| RecipeBookFocusManager.isShiftKeyDown(minecraft)
			|| (RecipeBookFocusManager.isAltKeyDown(minecraft) && ReachCraftingConfig.get().altAsRequestKey());
	}

	private int wrapQueuedCount(Minecraft minecraft, RecipeBookClickCapture.HeldRecipeAction action, int currentCount, int delta) {
		int queueLimit = resolveQueueLimit(minecraft, action);
		if (AutoCraftController.isBulkModeEnabled()) {
			return clampQueuedCount(currentCount, delta, queueLimit);
		}
		return Math.floorMod(currentCount + delta, queueLimit + 1);
	}

	private int resolveQueueLimit(Minecraft minecraft, RecipeBookClickCapture.HeldRecipeAction action) {
		int queueLimit = Math.max(RecipeClickExecutor.resolveRecipeQueueLimit(minecraft, action.recipeId(), action.collection()), 1);
		if (AutoCraftController.isBulkModeEnabled()) {
			return Math.max(queueLimit, RecipeClickExecutor.bulkRecipeQueueLimit());
		}
		return queueLimit;
	}

	private int clampQueuedCount(int currentCount, int delta, int queueLimit) {
		long updatedCount = (long) currentCount + delta;
		if (updatedCount <= 0L) {
			return 0;
		}
		return (int) Math.min(updatedCount, queueLimit);
	}

	private boolean matchesAction(
		RecipeBookClickCapture.HeldRecipeAction action,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		if (action == null) {
			return false;
		}
		return action.sameRecipe(new RecipeBookClickCapture.HeldRecipeAction(
			recipeId,
			collection,
			ItemStack.EMPTY,
			org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
			explicitVariantSelection
		));
	}

	private void updateModifierReleaseWindows(boolean controlDown, boolean shiftDown, boolean altDown) {
		if (state.wasControlDown() && !controlDown) {
			state.setControlReleaseWindowTicks(MODIFIER_RELEASE_WINDOW_TICKS);
		}
		if (state.wasShiftDown() && !shiftDown) {
			state.setShiftReleaseWindowTicks(MODIFIER_RELEASE_WINDOW_TICKS);
		}
		if (state.wasAltDown() && !altDown) {
			state.setAltReleaseWindowTicks(MODIFIER_RELEASE_WINDOW_TICKS);
		}
	}

	private void decrementModifierReleaseWindows(boolean controlDown, boolean shiftDown, boolean altDown) {
		if (!controlDown && state.controlReleaseWindowTicks() > 0) {
			state.setControlReleaseWindowTicks(state.controlReleaseWindowTicks() - 1);
		}
		if (!shiftDown && state.shiftReleaseWindowTicks() > 0) {
			state.setShiftReleaseWindowTicks(state.shiftReleaseWindowTicks() - 1);
		}
		if (!altDown && state.altReleaseWindowTicks() > 0) {
			state.setAltReleaseWindowTicks(state.altReleaseWindowTicks() - 1);
		}
	}

	private ModifierState currentModifierState() {
		Minecraft minecraft = Minecraft.getInstance();
		return currentModifierState(
			RecipeBookFocusManager.isControlKeyDown(minecraft),
			RecipeBookFocusManager.isShiftKeyDown(minecraft),
			RecipeBookFocusManager.isAltKeyDown(minecraft)
		);
	}

	private ModifierState currentModifierState(boolean controlDown, boolean shiftDown, boolean altDown) {
		boolean altRequested = ReachCraftingConfig.get().altAsRequestKey()
			&& (altDown || state.altReleaseWindowTicks() > 0);
		return new ModifierState(
			controlDown || state.controlReleaseWindowTicks() > 0,
			shiftDown || state.shiftReleaseWindowTicks() > 0,
			altRequested,
			controlDown || shiftDown || (ReachCraftingConfig.get().altAsRequestKey() && altDown)
		);
	}

	private ResolvedRequest resolveRequest(ModifierState modifierState) {
		boolean allowNearby = modifierState.controlRequested() && ReachCraftingConfig.get().enableNearbyContainerUsage();
		boolean maxCraftRequested = modifierState.shiftRequested();
		boolean autoCraftRequested = modifierState.altRequested();
		return new ResolvedRequest(allowNearby, maxCraftRequested, autoCraftRequested);
	}

	private record ModifierState(boolean controlRequested, boolean shiftRequested, boolean altRequested, boolean anyHeld) {
	}

	private record ResolvedRequest(boolean allowNearby, boolean maxCraftRequested, boolean autoCraftRequested) {
	}
}
