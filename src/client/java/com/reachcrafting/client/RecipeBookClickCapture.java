package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.OverlayRecipeButtonAccessor;
import com.reachcrafting.client.mixin.OverlayRecipeComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
// import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.util.context.ContextMap;
import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

public final class RecipeBookClickCapture {
	private static PendingHeldRecipe pendingHeldRecipe;
	private static ReplayBatch replayBatch;
	private static boolean wasControlDown;
	private static boolean wasShiftDown;
	private static boolean wasSearchBoxFocusedByMod;
	private static int replayDelayTicks = 0;

	private RecipeBookClickCapture() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean controlDown = isControlKeyDown(client);
			boolean shiftDown = isShiftKeyDown(client);

			if (controlDown || shiftDown) {
				defocusRecipeBookSearch(client);
			} else if (wasSearchBoxFocusedByMod && pendingHeldRecipe == null && replayBatch == null && !NearbyContainerDryRun.isActiveSessionRunning()) {
				refocusRecipeBookSearch(client);
			}

			if (pendingHeldRecipe != null) {
				if (pendingHeldRecipe.allowNearby()) {
					if (wasControlDown && !controlDown) {
						releasePendingHeldRecipe();
					}
				} else {
					if (wasShiftDown && !shiftDown) {
						releasePendingHeldRecipe();
					}
				}
			}

			wasControlDown = controlDown;
			wasShiftDown = shiftDown;

			if (replayDelayTicks > 0) {
				replayDelayTicks--;
			} else {
				processReplayBatch(client);
			}

			if (!controlDown && !shiftDown && !ReachCraftingConfig.get().reachCraftHoldAndRelease()) {
				pendingHeldRecipe = null;
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			ScreenMouseEvents.allowMouseClick(screen).register((currentScreen, event) -> {
				if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
					return true;
				}
				HeldRecipeAction action = findHoveredHeldRecipeAction(currentScreen, event.x(), event.y());
				if (action != null && clearHeldRecipe(action)) {
					return false;
				}
				return true;
			});
			ScreenMouseEvents.allowMouseScroll(screen).register((currentScreen, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
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
		boolean allowNearbyChests = ctrlModifierDown || isControlKeyDown(minecraft);

		if (shouldQueueHeldRecipe(minecraft, allowNearbyChests, craftAll) && replayBatch == null) {
			queueHeldRecipe(recipeId, collection, displayStack, mouseButton, explicitVariantSelection, allowNearbyChests);
			return;
		}

		if (!ContainerUtils.isGridEmpty(player.containerMenu)) {
			ContainerUtils.flushCraftingGrid(minecraft, allowNearbyChests, true);
			replayDelayTicks = 1;
			HeldRecipeAction action = new HeldRecipeAction(recipeId, collection, displayStack != null ? displayStack.copy() : ItemStack.EMPTY, mouseButton, explicitVariantSelection);
			replayBatch = new ReplayBatch(action, 1, allowNearbyChests, craftAll);
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
			allowNearbyChests,
			false,
			explicitVariantSelection,
			1
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
		boolean allowNearbyChests,
		boolean forceDryRun,
		boolean explicitVariantSelection,
		int requestedClicks
	) {
		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		ReachCraftingMod.LOGGER.info(
			"[recipe_capture] screen={} inventory={} grid={} slots={} pending={} replay={}",
			screen.getClass().getSimpleName(),
			availableItems.inventorySummary(),
			availableItems.gridSummary(),
			AvailableItemSnapshot.formatInventorySlots(player),
			pendingHeldRecipe != null ? pendingHeldRecipe.action().recipeId().index() + "x" + pendingHeldRecipe.clickCount() : "<none>",
			replayBatch != null ? replayBatch.action().recipeId().index() + "x" + replayBatch.remainingClicks() : "<none>"
		);
		boolean allowReservedGridVariantSwitch = false;
		int desiredVariantCopies = availableItems.hasReservedGrid() && !craftAll
			? ContainerUtils.currentReservedCraftCopies(availableItems.gridStacks()) + requestedClicks
			: Math.max(requestedClicks, 1);

		boolean allowVariantSwitching = allowNearbyChests || forceDryRun;

		String screenKind = screen instanceof InventoryScreen ? "inventory_2x2" : "crafting_table_3x3";
		RecipeVariantResolver.Selection selectedRecipe = RecipeVariantResolver.resolve(
			minecraft,
			player,
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			explicitVariantSelection,
			allowVariantSwitching,
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
		RecipeDeficitReport deficitReport = craftAll
			? RecipeDeficitReport.from(
				ingredientSummary,
				availableItems.inventoryCounts(),
				availableItems.gridStacks(),
				true
			)
			: RecipeDeficitReport.from(
				ingredientSummary,
				availableItems.inventoryCounts(),
				availableItems.gridStacks(),
				desiredVariantCopies
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
			allowNearbyChests,
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

		boolean useDryRun = forceDryRun || allowNearbyChests || craftAll;

		if (useDryRun) {
			if (!deficitReport.hasMissingIngredients() && availableItems.hasReservedGrid()) {
				if (NearbyContainerDryRun.tryExpandReservedGrid(
					selectedRecipe.recipeId(),
					collection,
					explicitVariantSelection,
					recipeIndex,
					outputLabel,
					ingredientSummary,
					availableItems,
					craftAll,
					requestedClicks,
					allowNearbyChests
				)) {
					return;
				}
			}
			NearbyContainerDryRun.start(
				selectedRecipe.recipeId(),
				collection,
				explicitVariantSelection,
				recipeIndex,
				outputLabel,
				ingredientSummary,
				availableItems,
				craftAll,
				requestedClicks,
				allowNearbyChests
			);
			return;
		}

		MultiPlayerGameMode gameMode = minecraft.gameMode;
		if (gameMode != null) {
			gameMode.handlePlaceRecipe(player.containerMenu.containerId, selectedRecipe.recipeId(), craftAll);
			if (ReachCraftingConfig.get().autoCraftMode()) {
				ContainerUtils.scheduleAutoMove();
			}
			player.displayClientMessage(
				Component.literal("[Reach Crafting] Placed recipe: " + outputLabel)
					.withStyle(ChatFormatting.YELLOW),
				false
			);
			if (explicitVariantSelection) {
				tryCloseOverlayAfterRelease();
			}
			return;
		}
	}

	private static boolean shouldQueueHeldRecipe(Minecraft minecraft, boolean allowNearbyFallback, boolean craftAll) {
		return ReachCraftingConfig.get().reachCraftHoldAndRelease()
			&& !craftAll
			&& (allowNearbyFallback || isShiftKeyDown(minecraft));
	}

	private static void queueHeldRecipe(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean explicitVariantSelection,
		boolean allowNearby
	) {
		HeldRecipeAction action = new HeldRecipeAction(
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			mouseButton,
			explicitVariantSelection
		);
		if (pendingHeldRecipe == null) {
			pendingHeldRecipe = new PendingHeldRecipe(action, 1, false, allowNearby);
			return;
		}

		if (pendingHeldRecipe.action().sameRecipe(action)) {
			int delta = isSpaceKeyDown(Minecraft.getInstance()) ? 16 : 1;
			int updatedCount = Math.floorMod(pendingHeldRecipe.clickCount() + delta, 65);
			if (updatedCount <= 0) {
				pendingHeldRecipe = null;
				return;
			}
			pendingHeldRecipe = new PendingHeldRecipe(action, updatedCount, updatedCount >= 2, allowNearby);
			return;
		}

		if (!pendingHeldRecipe.locked()) {
			pendingHeldRecipe = new PendingHeldRecipe(action, 1, false, allowNearby);
		}
	}

	public static boolean onRecipeButtonRightClicked(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		boolean explicitVariantSelection
	) {
		if (!canUseHeldQueueControls(Minecraft.getInstance()) || recipeId == null || collection == null) {
			return false;
		}
		return clearHeldRecipe(new HeldRecipeAction(
			recipeId,
			collection,
			displayStack != null ? displayStack.copy() : ItemStack.EMPTY,
			GLFW.GLFW_MOUSE_BUTTON_LEFT,
			explicitVariantSelection
		));
	}

	private static void releasePendingHeldRecipe() {
		if (pendingHeldRecipe == null) {
			return;
		}
		
		Minecraft minecraft = Minecraft.getInstance();
		if (!ContainerUtils.isGridEmpty(minecraft.player.containerMenu)) {
			ContainerUtils.flushCraftingGrid(minecraft, pendingHeldRecipe.allowNearby(), true);
		}
		
		// Always delay by at least 1 tick to ensure server/client state sync after key release
		replayDelayTicks = 1;

		replayBatch = new ReplayBatch(pendingHeldRecipe.action(), pendingHeldRecipe.clickCount(), pendingHeldRecipe.allowNearby(), false);
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

		NearbyContainerDryRun.runPendingPostReturnCompaction(minecraft);

		ReachCraftingMod.LOGGER.info(
			"[recipe_replay] screen={} recipe_idx={} remaining_clicks={} allow_nearby={} craft_all={}",
			screen.getClass().getSimpleName(),
			replayBatch.action().recipeId().index(),
			replayBatch.remainingClicks(),
			replayBatch.allowNearby(),
			replayBatch.craftAll()
		);

		executeRecipeButtonClick(
			minecraft,
			player,
			screen,
			replayBatch.action().recipeId(),
			replayBatch.action().collection(),
			replayBatch.action().displayStack().copy(),
			replayBatch.action().mouseButton(),
			replayBatch.craftAll(),
			replayBatch.allowNearby(),
			true,
			replayBatch.action().explicitVariantSelection(),
			replayBatch.remainingClicks()
		);
		replayBatch = null;
	}

	private static boolean handleHeldRecipeScroll(Screen screen, double mouseX, double mouseY, double verticalAmount) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!canUseHeldQueueControls(minecraft) || verticalAmount == 0.0D) {
			return false;
		}

		HeldRecipeAction action = findHoveredHeldRecipeAction(screen, mouseX, mouseY);
		if (action == null) {
			return false;
		}

		int delta = verticalAmount > 0.0D ? 1 : -1;
		return adjustHeldRecipeCount(minecraft, action, delta);
	}

	private static boolean canUseHeldQueueControls(Minecraft minecraft) {
		return ReachCraftingConfig.get().reachCraftHoldAndRelease()
			&& replayBatch == null
			&& (isControlKeyDown(minecraft) || isShiftKeyDown(minecraft));
	}

	private static HeldRecipeAction findHoveredHeldRecipeAction(Screen screen, double mouseX, double mouseY) {
		if (!(screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return null;
		}

		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) componentAccessor.getRecipeBookPage();
		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		if (overlay != null && overlay.isVisible() && overlay.getRecipeCollection() != null) {
			for (Object entry : ((OverlayRecipeComponentAccessor) overlay).getRecipeButtons()) {
				if (!(entry instanceof AbstractWidget widget) || !widget.isMouseOver(mouseX, mouseY)) {
					continue;
				}
				RecipeDisplayId recipeId = ((OverlayRecipeButtonAccessor) entry).getRecipe();
				if (recipeId == null) {
					continue;
				}
				return new HeldRecipeAction(
					recipeId,
					overlay.getRecipeCollection(),
					ItemStack.EMPTY,
					GLFW.GLFW_MOUSE_BUTTON_LEFT,
					true
				);
			}
			return null;
		}

		for (RecipeButton button : pageAccessor.getButtons()) {
			if (!button.isMouseOver(mouseX, mouseY) || button.getCurrentRecipe() == null || button.getCollection() == null) {
				continue;
			}
			return new HeldRecipeAction(
				button.getCurrentRecipe(),
				button.getCollection(),
				button.getDisplayStack().copy(),
				GLFW.GLFW_MOUSE_BUTTON_LEFT,
				false
			);
		}
		return null;
	}

	private static boolean adjustHeldRecipeCount(Minecraft minecraft, HeldRecipeAction action, int delta) {
		if (delta == 0) {
			return false;
		}

		int multiplier = isSpaceKeyDown(minecraft) ? 16 : 1;
		int effectiveDelta = delta * multiplier;

		if (pendingHeldRecipe == null) {
			int updatedCount = Math.floorMod(effectiveDelta, 65);
			if (updatedCount == 0) {
				pendingHeldRecipe = null;
				return true;
			}
			pendingHeldRecipe = new PendingHeldRecipe(action, updatedCount, updatedCount >= 2, isControlKeyDown(minecraft));
			return true;
		}

		if (pendingHeldRecipe.action().sameRecipe(action)) {
			int updatedCount = Math.floorMod(pendingHeldRecipe.clickCount() + effectiveDelta, 65);

			if (updatedCount <= 0) {
				pendingHeldRecipe = null;
				return true;
			}
			pendingHeldRecipe = new PendingHeldRecipe(action, updatedCount, updatedCount >= 2, pendingHeldRecipe.allowNearby());
			return true;
		}

		if (delta > 0 && !pendingHeldRecipe.locked()) {
			int updatedCount = Math.floorMod(effectiveDelta, 65);
			if (updatedCount == 0) {
				pendingHeldRecipe = null;
				return true;
			}
			pendingHeldRecipe = new PendingHeldRecipe(action, updatedCount, updatedCount >= 2, isControlKeyDown(minecraft));
			return true;
		}

		return false;
	}

	private static boolean clearHeldRecipe(HeldRecipeAction action) {
		if (pendingHeldRecipe == null || !pendingHeldRecipe.action().sameRecipe(action)) {
			return false;
		}
		pendingHeldRecipe = null;
		return true;
	}

	public static void tryCloseOverlayAfterRelease() {
		if (!ReachCraftingConfig.get().reachCraftCloseOverlayAfterRelease()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!(minecraft.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) componentAccessor.getRecipeBookPage();
		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		if (overlay != null && overlay.isVisible()) {
			((OverlayRecipeComponentAccessor) overlay).setIsVisible(false);
		}
	}

	public static int getHeldQueuedCount(RecipeButton button) {
		if (button == null) {
			return 0;
		}
		if (button.getCollection() == null || button.getCurrentRecipe() == null) {
			return 0;
		}
		return getHeldQueuedCount(button.getCurrentRecipe(), button.getCollection(), false);
	}

	public static int getHeldQueuedCount(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		if (!ReachCraftingConfig.get().reachCraftHoldAndRelease()
			|| pendingHeldRecipe == null
			|| recipeId == null
			|| collection == null) {
			return 0;
		}

		HeldRecipeAction action = pendingHeldRecipe.action();
		if (action.explicitVariantSelection() != explicitVariantSelection) {
			return 0;
		}
		if (explicitVariantSelection) {
			return action.recipeId().equals(recipeId) ? pendingHeldRecipe.clickCount() : 0;
		}
		if (action.collection() != null && action.collection() == collection) {
			return pendingHeldRecipe.clickCount();
		}
		return action.recipeId().equals(recipeId) ? pendingHeldRecipe.clickCount() : 0;
	}

	private static boolean isShiftKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private static boolean isControlKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
	}
	
	private static boolean isSpaceKeyDown(Minecraft minecraft) {
		return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_SPACE);
	}


	private static void defocusRecipeBookSearch(Minecraft minecraft) {
		if (!(minecraft.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (componentAccessor != null) {
			EditBox searchBox = componentAccessor.getSearchBox();
			if (searchBox != null && searchBox.isFocused()) {
				searchBox.setFocused(false);
				wasSearchBoxFocusedByMod = true;
			}
		}
	}

	public static void refocusRecipeBookSearch(Minecraft minecraft) {
		if (!(minecraft.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return;
		}
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (componentAccessor != null) {
			EditBox searchBox = componentAccessor.getSearchBox();
			if (searchBox != null) {
				searchBox.setFocused(true);
				searchBox.setCursorPosition(searchBox.getValue().length());
				searchBox.setHighlightPos(0);
			}
		}
		wasSearchBoxFocusedByMod = false;
	}

	public static PendingHeldRecipe getPendingHeldRecipe() {
		return pendingHeldRecipe;
	}

	public static ItemStack resolvePendingOutputStack(Minecraft minecraft) {
		if (pendingHeldRecipe == null || minecraft.level == null) {
			return ItemStack.EMPTY;
		}

		HeldRecipeAction action = pendingHeldRecipe.action();
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) minecraft.player.getRecipeBook()).getKnown();
		RecipeDisplayEntry entry = knownRecipes.get(action.recipeId());
		if (entry == null) {
			return ItemStack.EMPTY;
		}

		ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
		return RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
	}

	public record HeldRecipeAction(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean explicitVariantSelection
	) {
		private boolean sameRecipe(HeldRecipeAction other) {
			if (other == null || explicitVariantSelection != other.explicitVariantSelection) {
				return false;
			}
			if (explicitVariantSelection) {
				return recipeId.equals(other.recipeId);
			}
			if (collection != null && other.collection != null) {
				return collection == other.collection || collection.equals(other.collection);
			}
			return recipeId.equals(other.recipeId);
		}
	}

	public record PendingHeldRecipe(HeldRecipeAction action, int clickCount, boolean locked, boolean allowNearby) {
	}

	public record ReplayBatch(HeldRecipeAction action, int remainingClicks, boolean allowNearby, boolean craftAll) {
	}
}
