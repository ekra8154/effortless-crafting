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
			boolean spaceDown = RecipeBookFocusManager.isSpaceKeyDown(client);

			if (controlDown || shiftDown) {
				RecipeBookFocusManager.defocusRecipeBookSearch(client, state);
			} else if (state.wasSearchBoxFocusedByMod()
				&& !spaceDown
				&& state.pendingHeldRecipe() == null
				&& state.replayBatch() == null
				&& !NearbyContainerDryRun.isActiveSessionRunning()) {
				RecipeBookFocusManager.refocusRecipeBookSearch(client, state);
			}

			if (state.pendingHeldRecipe() != null) {
				boolean modifierDown = state.pendingHeldRecipe().ctrlTriggered() ? controlDown : shiftDown;
				boolean modifierJustReleased = state.pendingHeldRecipe().ctrlTriggered()
					? (state.wasControlDown() && !controlDown)
					: (state.wasShiftDown() && !shiftDown);

				if (modifierJustReleased) {
					state.setWasModifierReleasedWhileSpaceHeld(true);
				}

				if (state.wasModifierReleasedWhileSpaceHeld() && !modifierDown && !spaceDown) {
					releasePendingHeldRecipe();
					state.setWasModifierReleasedWhileSpaceHeld(false);
				}

				if (modifierDown) {
					state.setWasModifierReleasedWhileSpaceHeld(false);
				}
			} else {
				state.setWasModifierReleasedWhileSpaceHeld(false);
			}

			state.setWasControlDown(controlDown);
			state.setWasShiftDown(shiftDown);

			if (state.replayDelayTicks() > 0) {
				state.decrementReplayDelayTicks();
			} else {
				processReplayBatch(client);
			}

			if (!controlDown && !shiftDown && !ReachCraftingConfig.get().reachCraftHoldAndRelease()) {
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

		boolean craftAll = shiftModifierDown || RecipeBookFocusManager.isShiftKeyDown(minecraft);
		boolean allowNearbyChests = (ctrlModifierDown || RecipeBookFocusManager.isControlKeyDown(minecraft))
			&& ReachCraftingConfig.get().enableNearbyContainerUsage();

		if (shouldQueueHeldRecipe(minecraft, allowNearbyChests, craftAll) && state.replayBatch() == null) {
			boolean ctrlTriggered = ctrlModifierDown || RecipeBookFocusManager.isControlKeyDown(minecraft);
			queueHeldRecipe(recipeId, collection, displayStack, mouseButton, explicitVariantSelection, allowNearbyChests, ctrlTriggered);
			return;
		}

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
			state.setReplayBatch(new RecipeBookClickCapture.ReplayBatch(action, 1, allowNearbyChests, craftAll));
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
			1,
			state
		);
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

	int getHeldQueuedCount(
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		if (!ReachCraftingConfig.get().reachCraftHoldAndRelease() || recipeId == null || collection == null) {
			return 0;
		}

		if (state.pendingHeldRecipe() != null) {
			RecipeBookClickCapture.HeldRecipeAction action = state.pendingHeldRecipe().action();
			if (action.explicitVariantSelection() == explicitVariantSelection) {
				if (explicitVariantSelection) {
					if (action.recipeId().equals(recipeId)) {
						return state.pendingHeldRecipe().clickCount();
					}
				} else if ((action.collection() != null && action.collection() == collection) || action.recipeId().equals(recipeId)) {
					return state.pendingHeldRecipe().clickCount();
				}
			}
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (!shouldShowCurrentRecipeCount(minecraft)) {
			return 0;
		}

		return RecipeClickExecutor.resolveGridMatchedCount(minecraft, recipeId, collection, explicitVariantSelection);
	}

	int getHeldQueuedCount(RecipeButton button) {
		if (button == null || button.getCollection() == null || button.getCurrentRecipe() == null) {
			return 0;
		}
		return getHeldQueuedCount(button.getCurrentRecipe(), button.getCollection(), false);
	}

	void tryCloseOverlayAfterRelease() {
		RecipeClickExecutor.tryCloseOverlayAfterRelease();
	}

	void refocusRecipeBookSearch(Minecraft minecraft) {
		RecipeBookFocusManager.refocusRecipeBookSearch(minecraft, state);
	}

	RecipeBookClickCapture.PendingHeldRecipe getPendingHeldRecipe() {
		return state.pendingHeldRecipe();
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

	private boolean shouldQueueHeldRecipe(Minecraft minecraft, boolean allowNearbyFallback, boolean craftAll) {
		return ReachCraftingConfig.get().reachCraftHoldAndRelease()
			&& !craftAll
			&& (allowNearbyFallback || RecipeBookFocusManager.isShiftKeyDown(minecraft));
	}

	private void queueHeldRecipe(
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean explicitVariantSelection,
		boolean allowNearby,
		boolean ctrlTriggered
	) {
		RecipeBookClickCapture.HeldRecipeAction action = new RecipeBookClickCapture.HeldRecipeAction(
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			mouseButton,
			explicitVariantSelection
		);

		if (state.pendingHeldRecipe() == null) {
			int initialCount = nextQueuedCountFromCurrentState(Minecraft.getInstance(), action, 1);
			if (initialCount <= 0) {
				state.setPendingHeldRecipe(null);
				return;
			}
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, initialCount, initialCount >= 2, allowNearby, ctrlTriggered));
			return;
		}

		if (state.pendingHeldRecipe().action().sameRecipe(action)) {
			int delta = RecipeBookFocusManager.isSpaceKeyDown(Minecraft.getInstance()) ? 16 : 1;
			int updatedCount = wrapQueuedCount(Minecraft.getInstance(), action, state.pendingHeldRecipe().clickCount(), delta);
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, updatedCount, updatedCount >= 2, allowNearby, ctrlTriggered));
			return;
		}

		if (!state.pendingHeldRecipe().locked()) {
			int initialCount = nextQueuedCountFromCurrentState(Minecraft.getInstance(), action, 1);
			if (initialCount <= 0) {
				state.setPendingHeldRecipe(null);
				return;
			}
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, initialCount, initialCount >= 2, allowNearby, ctrlTriggered));
		}
	}

	private void releasePendingHeldRecipe() {
		if (!ReachCraftingConfig.get().enabled() || state.pendingHeldRecipe() == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (state.pendingHeldRecipe().clickCount() <= 0) {
			if (!ContainerUtils.isGridEmpty(minecraft.player.containerMenu)) {
				ContainerUtils.flushCraftingGrid(minecraft, state.pendingHeldRecipe().allowNearby(), true);
			}
			state.setPendingHeldRecipe(null);
			return;
		}

		if (!ContainerUtils.isGridEmpty(minecraft.player.containerMenu)) {
			ContainerUtils.flushCraftingGrid(minecraft, state.pendingHeldRecipe().allowNearby(), true);
		}

		state.setReplayDelayTicks(1);
		state.setReplayBatch(new RecipeBookClickCapture.ReplayBatch(
			state.pendingHeldRecipe().action(),
			state.pendingHeldRecipe().clickCount(),
			state.pendingHeldRecipe().allowNearby(),
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

		com.reachcrafting.ReachCraftingMod.LOGGER.info(
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
			&& (RecipeBookFocusManager.isControlKeyDown(minecraft) || RecipeBookFocusManager.isShiftKeyDown(minecraft));
	}

	private boolean adjustHeldRecipeCount(Minecraft minecraft, RecipeBookClickCapture.HeldRecipeAction action, int delta) {
		if (delta == 0) {
			return false;
		}

		int multiplier = RecipeBookFocusManager.isSpaceKeyDown(minecraft) ? 16 : 1;
		int effectiveDelta = delta * multiplier;

		if (state.pendingHeldRecipe() == null) {
			int updatedCount = nextQueuedCountFromCurrentState(minecraft, action, effectiveDelta);
			boolean allowNearby = RecipeBookFocusManager.isControlKeyDown(minecraft) && ReachCraftingConfig.get().enableNearbyContainerUsage();
			boolean ctrlTriggered = RecipeBookFocusManager.isControlKeyDown(minecraft);
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, updatedCount, updatedCount >= 2, allowNearby, ctrlTriggered));
			return true;
		}

		if (state.pendingHeldRecipe().action().sameRecipe(action)) {
			int updatedCount = wrapQueuedCount(minecraft, action, state.pendingHeldRecipe().clickCount(), effectiveDelta);
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(
				action,
				updatedCount,
				updatedCount >= 2,
				state.pendingHeldRecipe().allowNearby(),
				state.pendingHeldRecipe().ctrlTriggered()
			));
			return true;
		}

		if (delta > 0) {
			int updatedCount = nextQueuedCountFromCurrentState(minecraft, action, effectiveDelta);
			boolean allowNearby = RecipeBookFocusManager.isControlKeyDown(minecraft) && ReachCraftingConfig.get().enableNearbyContainerUsage();
			boolean ctrlTriggered = RecipeBookFocusManager.isControlKeyDown(minecraft);
			state.setPendingHeldRecipe(new RecipeBookClickCapture.PendingHeldRecipe(action, updatedCount, updatedCount >= 2, allowNearby, ctrlTriggered));
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
		if (visibility == ReachCraftingConfig.InputCounterVisibility.ALWAYS_SHOW) {
			return true;
		}
		return RecipeBookFocusManager.isControlKeyDown(minecraft) || RecipeBookFocusManager.isShiftKeyDown(minecraft);
	}

	private int wrapQueuedCount(Minecraft minecraft, RecipeBookClickCapture.HeldRecipeAction action, int currentCount, int delta) {
		int queueLimit = resolveQueueLimit(minecraft, action);
		return Math.floorMod(currentCount + delta, queueLimit + 1);
	}

	private int resolveQueueLimit(Minecraft minecraft, RecipeBookClickCapture.HeldRecipeAction action) {
		return Math.max(RecipeClickExecutor.resolveRecipeQueueLimit(minecraft, action.recipeId(), action.collection()), 1);
	}

	private boolean matchesAction(
		RecipeBookClickCapture.HeldRecipeAction action,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		if (action == null || action.explicitVariantSelection() != explicitVariantSelection) {
			return false;
		}
		if (explicitVariantSelection) {
			return action.recipeId().equals(recipeId);
		}
		return (action.collection() != null && action.collection() == collection) || action.recipeId().equals(recipeId);
	}
}
