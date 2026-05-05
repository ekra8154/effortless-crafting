package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

final class RecipeClickExecutor {
	private RecipeClickExecutor() {
	}

	static void executeRecipeButtonClick(
		Minecraft minecraft,
		LocalPlayer player,
		Screen screen,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		ItemStack displayStack,
		int mouseButton,
		boolean craftAll,
		boolean allowNearbyChests,
		boolean forceDryRun,
		boolean explicitVariantSelection,
		int requestedClicks,
		HeldRecipeQueueState state
	) {
		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		ReachCraftingMod.LOGGER.info(
			"[recipe_capture] screen={} inventory={} grid={} slots={} pending={} replay={}",
			screen.getClass().getSimpleName(),
			availableItems.inventorySummary(),
			availableItems.gridSummary(),
			AvailableItemSnapshot.formatInventorySlots(player),
			state.pendingHeldRecipe() != null ? state.pendingHeldRecipe().action().recipeId().index() + "x" + state.pendingHeldRecipe().clickCount() : "<none>",
			state.replayBatch() != null ? state.replayBatch().action().recipeId().index() + "x" + state.replayBatch().remainingClicks() : "<none>"
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
			? RecipeDeficitReport.from(ingredientSummary, availableItems.inventoryCounts(), availableItems.gridStacks(), true)
			: RecipeDeficitReport.from(ingredientSummary, availableItems.inventoryCounts(), availableItems.gridStacks(), desiredVariantCopies);
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

		player.displayClientMessage(Component.literal("[Effortless Crafting] " + chatMessage).withStyle(ChatFormatting.YELLOW), false);

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
					if (ReachCraftingConfig.get().autoCraftMode()) {
						ContainerUtils.scheduleAutoMove(selectedRecipe.displayStack());
					}
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
				ContainerUtils.scheduleAutoMove(selectedRecipe.displayStack());
			}
			player.displayClientMessage(
				Component.literal("[Effortless Crafting] Placed recipe: " + outputLabel).withStyle(ChatFormatting.YELLOW),
				false
			);
			if (explicitVariantSelection) {
				tryCloseOverlayAfterRelease();
			}
		}
	}

	static int resolveGridMatchedCount(
		Minecraft minecraft,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		boolean explicitVariantSelection
	) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null || recipeId == null || collection == null) {
			return 0;
		}
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return 0;
		}

		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(minecraft.player, screen);
		if (!availableItems.hasReservedGrid()) {
			return 0;
		}

		int gridCount = ContainerUtils.currentReservedCraftCopies(availableItems.gridStacks());
		if (gridCount <= 0) {
			return 0;
		}

		RecipeVariantResolver.Selection gridSelection = RecipeVariantResolver.resolveMatchForGrid(
			minecraft,
			minecraft.player,
			collection,
			availableItems.gridStacks(),
			availableItems,
			availableItems.inventoryCounts(),
			availableItems.inventoryCounts(),
			false,
			gridCount
		);
		if (gridSelection == null) {
			return 0;
		}

		if (explicitVariantSelection) {
			return gridSelection.recipeId().equals(recipeId) ? gridCount : 0;
		}
		return gridCount;
	}

	static int resolveRecipeQueueLimit(
		Minecraft minecraft,
		RecipeDisplayId recipeId,
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection
	) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null || recipeId == null) {
			return 64;
		}

		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) minecraft.player.getRecipeBook()).getKnown();
		RecipeDisplayEntry entry = findRecipeEntry(collection, knownRecipes, recipeId);
		if (entry == null) {
			return 64;
		}

		ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
		RecipeIngredientSummary ingredientSummary = RecipeIngredientSummary.fromDisplay(entry.display(), context);
		return ingredientSummary.slots().stream()
			.filter(slot -> !slot.isEmpty())
			.mapToInt(RecipeIngredientSummary.IngredientSlot::maxStackSize)
			.min()
			.orElse(64);
	}

	private static RecipeDisplayEntry findRecipeEntry(
		net.minecraft.client.gui.screens.recipebook.RecipeCollection collection,
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes,
		RecipeDisplayId recipeId
	) {
		if (collection != null) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				if (entry.id().equals(recipeId)) {
					return entry;
				}
			}
		}
		return knownRecipes.get(recipeId);
	}

	static void tryCloseOverlayAfterRelease() {
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
			((com.reachcrafting.client.mixin.OverlayRecipeComponentAccessor) overlay).setIsVisible(false);
		}
	}
}
