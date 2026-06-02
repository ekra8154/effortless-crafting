package com.reachcrafting.client;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

public final class RecipeButtonNearbyIndicator {
	private static StateKey currentStateKey;
	private static EvaluationContext currentContext;

	public enum Craftability {
		NOT_CRAFTABLE,
		LOCALLY_CRAFTABLE,
		NEARBY_CRAFTABLE
	}

	private enum IndicatorState {
		NONE,
		RETRIEVABLE,
		NEARBY,
		CHAIN
	}

	private static final Map<RecipeDisplayId, Craftability> mainCache = new java.util.HashMap<>();
	private static final Map<RecipeDisplayId, Craftability> overlayCache = new java.util.HashMap<>();
	private static final Map<RecipeCollection, IndicatorState> collectionIndicatorCache = new IdentityHashMap<>();

	private RecipeButtonNearbyIndicator() {
	}

	public static void clearCaches() {
		currentStateKey = null;
		currentContext = null;
		mainCache.clear();
		overlayCache.clear();
		collectionIndicatorCache.clear();
	}

	public static boolean shouldShow(RecipeButton button) {
		return resolveIndicatorState(button) == IndicatorState.NEARBY;
	}

	public static boolean isRetrievable(RecipeButton button) {
		return resolveIndicatorState(button) == IndicatorState.RETRIEVABLE;
	}

	public static boolean isChainCraftable(RecipeButton button) {
		return resolveIndicatorState(button) == IndicatorState.CHAIN;
	}

	public static void renderButton(net.minecraft.client.gui.GuiGraphics guiGraphics, RecipeButton button) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}

		IndicatorState indicatorState = resolveIndicatorState(button);
		if (indicatorState == IndicatorState.NONE) {
			return;
		}

		AbstractWidget widget = (AbstractWidget) (Object) button;
		if (indicatorState == IndicatorState.RETRIEVABLE) {
			renderGreenDot(guiGraphics, widget.getX() + 3, widget.getY() + 3);
		} else if (indicatorState == IndicatorState.NEARBY) {
			renderDot(guiGraphics, widget.getX() + 3, widget.getY() + 3);
		} else {
			renderChainDot(guiGraphics, widget.getX() + 3, widget.getY() + 3);
		}
	}

	public static Craftability getCraftability(RecipeDisplayId recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		if (!ReachCraftingConfig.get().enabled()
			|| !ReachCraftingConfig.get().enableNearbyContainerUsage()
			|| !ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			return Craftability.NOT_CRAFTABLE;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return Craftability.NOT_CRAFTABLE;
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.getCameraEntity() == null) {
			return Craftability.NOT_CRAFTABLE;
		}
		if (recipe == null || collection == null) {
			return Craftability.NOT_CRAFTABLE;
		}

		NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(
			minecraft.level,
			minecraft.getCameraEntity(),
			player.blockInteractionRange()
		);
		long nearbyRevision = reachableView.revision();
		long inventoryHash = computeInventoryHash(player, screen);
		StateKey stateKey = stateKeyFor(screen, inventoryHash, nearbyRevision, reachableView);

		if (!stateKey.equals(currentStateKey)) {
			mainCache.clear();
			overlayCache.clear();
			collectionIndicatorCache.clear();
			currentStateKey = stateKey;
			currentContext = null;
		}

		Map<RecipeDisplayId, Craftability> cacheMap = explicitVariantSelection ? overlayCache : mainCache;
		if (cacheMap.containsKey(recipe)) {
			return cacheMap.get(recipe);
		}

		EvaluationContext context = contextFor(minecraft, player, screen, reachableView, stateKey);
		long startNanos = PerformanceProfiler.start();
		Craftability result = context.computeCraftability(recipe, collection, displayStack, explicitVariantSelection);
		cacheMap.put(recipe, result);
		PerformanceProfiler.record(
			"indicator.craftability_miss",
			startNanos,
			"result=" + result.name().toLowerCase() + " overlay=" + explicitVariantSelection + " recipe=" + recipe.index()
		);
		return result;
	}

	public static void renderOverlayButton(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y, int width, RecipeDisplayId recipe, RecipeCollection collection) {
		if (!ReachCraftingConfig.get().showNearbyCraftableIndicator()) {
			return;
		}
		IndicatorState indicatorState = indicatorStateForRecipe(recipe, collection, ItemStack.EMPTY, true);
		if (indicatorState == IndicatorState.RETRIEVABLE) {
			renderGreenDot(guiGraphics, x, y);
		} else if (indicatorState == IndicatorState.NEARBY) {
			renderDot(guiGraphics, x, y);
		} else if (indicatorState == IndicatorState.CHAIN) {
			renderChainDot(guiGraphics, x, y);
		}
	}

	private static IndicatorState resolveIndicatorState(RecipeButton button) {
		if (!ReachCraftingConfig.get().showNearbyCraftableIndicator()) {
			return IndicatorState.NONE;
		}

		RecipeCollection collection = button.getCollection();
		if (collection == null) {
			return IndicatorState.NONE;
		}

		if (collection.getRecipes().size() > 1) {
			return collectionIndicatorCache.computeIfAbsent(collection, RecipeButtonNearbyIndicator::resolveCollectionIndicatorState);
		}

		return indicatorStateForRecipe(button.getCurrentRecipe(), collection, button.getDisplayStack().copy(), false);
	}

	private static IndicatorState resolveCollectionIndicatorState(RecipeCollection collection) {
		if (ExistingOutputRetrievalController.isEnabled()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				if (hasRetrievableOutput(entry.id(), collection, ItemStack.EMPTY, true)) {
					return IndicatorState.RETRIEVABLE;
				}
			}
			return IndicatorState.NONE;
		}

		for (RecipeDisplayEntry entry : collection.getRecipes()) {
			if (getCraftability(entry.id(), collection, ItemStack.EMPTY, true) != Craftability.NOT_CRAFTABLE) {
				return IndicatorState.NEARBY;
			}
		}
		for (RecipeDisplayEntry entry : collection.getRecipes()) {
			if (ChainCraftabilityCache.isChainCraftable(entry.id())) {
				return IndicatorState.CHAIN;
			}
		}
		return IndicatorState.NONE;
	}

	private static IndicatorState indicatorStateForRecipe(RecipeDisplayId recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		if (ExistingOutputRetrievalController.isEnabled()) {
			return hasRetrievableOutput(recipe, collection, displayStack, explicitVariantSelection) ? IndicatorState.RETRIEVABLE : IndicatorState.NONE;
		}
		if (getCraftability(recipe, collection, displayStack, explicitVariantSelection) != Craftability.NOT_CRAFTABLE) {
			return IndicatorState.NEARBY;
		}
		return ChainCraftabilityCache.isChainCraftable(recipe) ? IndicatorState.CHAIN : IndicatorState.NONE;
	}

	private static boolean hasRetrievableOutput(RecipeDisplayId recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		if (!ReachCraftingConfig.get().enableExistingOutputRetrieval()
			|| !ReachCraftingConfig.get().enableNearbyContainerUsage()
			|| !ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return false;
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.getCameraEntity() == null || recipe == null || collection == null) {
			return false;
		}

		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		Map<String, Integer> nearbyTotals = NearbyContainerCache.getReachableView(
			minecraft.level,
			minecraft.getCameraEntity(),
			player.blockInteractionRange()
		).aggregateCounts();
		RecipeVariantResolver.Selection selection = RecipeVariantResolver.resolve(
			minecraft,
			player,
			recipe,
			collection,
			displayStack,
			explicitVariantSelection,
			true,
			availableItems,
			nearbyTotals,
			nearbyTotals,
			false,
			ReachCraftingConfig.get().redistributeToCraftWhenNeeded(),
			1
		);
		if (selection == null || selection.displayStack().isEmpty()) {
			return false;
		}

		String outputItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(selection.displayStack().getItem()).toString();
		return nearbyTotals.getOrDefault(outputItemId, 0) > 0;
	}

	private static EvaluationContext contextFor(
		Minecraft minecraft,
		LocalPlayer player,
		Screen screen,
		NearbyContainerCache.ReachableView reachableView,
		StateKey stateKey
	) {
		if (!stateKey.equals(currentStateKey) || currentContext == null) {
			currentStateKey = stateKey;
			currentContext = new EvaluationContext(minecraft, player, screen, reachableView);
		}
		return currentContext;
	}

	private static StateKey stateKeyFor(
		Screen screen,
		long inventoryHash,
		long nearbyRevision,
		NearbyContainerCache.ReachableView reachableView
	) {
		int reachableSignature = 1;
		reachableSignature = (31 * reachableSignature) + reachableView.aggregateCounts().hashCode();
		reachableSignature = (31 * reachableSignature) + reachableView.snapshotsByKey().keySet().hashCode();
		return new StateKey(screen.getClass(), inventoryHash, nearbyRevision, reachableSignature);
	}

	private static long computeInventoryHash(LocalPlayer player, Screen screen) {
		long hash = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				hash = hash * 31 + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).hashCode();
				hash = hash * 31 + stack.getCount();
			} else {
				hash = hash * 31;
			}
		}
		if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen) {
			int gridSlotCount = screen instanceof InventoryScreen ? 4 : screen instanceof CraftingScreen ? 9 : 0;
			for (int slotIndex = 1; slotIndex <= gridSlotCount; slotIndex++) {
				ItemStack stack = containerScreen.getMenu().getSlot(slotIndex).getItem();
				if (!stack.isEmpty()) {
					hash = hash * 31 + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).hashCode();
					hash = hash * 31 + stack.getCount();
				} else {
					hash = hash * 31;
				}
			}
		}
		return hash;
	}

	private static int currentReservedCraftCopies(AvailableItemSnapshot availableItems) {
		return ContainerUtils.currentReservedCraftCopies(availableItems.gridStacks());
	}

	private record StateKey(Class<?> screenClass, long inventoryHash, long nearbyRevision, int reachableSignature) {
	}

	private static final class EvaluationContext {
		private final Minecraft minecraft;
		private final LocalPlayer player;
		private final AvailableItemSnapshot availableItems;
		private final NearbyContainerCache.ReachableView reachableView;
		private final Map<String, Integer> inventoryCounts;
		private final Map<String, Integer> totalAvailable;
		private final IngredientPlanning.Policy planningPolicy;
		private final boolean hasReservedGrid;
		private final int reservedCraftCopies;
		private final Map<RecipeCollection, RecipeDisplayId> gridMatchRecipeIds = new IdentityHashMap<>();

		private EvaluationContext(
			Minecraft minecraft,
			LocalPlayer player,
			Screen screen,
			NearbyContainerCache.ReachableView reachableView
		) {
			this.minecraft = minecraft;
			this.player = player;
			this.availableItems = AvailableItemSnapshot.capture(player, screen);
			this.reachableView = reachableView;
			this.inventoryCounts = this.availableItems.inventoryCounts();
			this.totalAvailable = AvailableItemSnapshot.mergeCounts(this.inventoryCounts, reachableView.aggregateCounts());
			this.planningPolicy = ReachCraftingConfig.get().toPlanningPolicy();
			this.hasReservedGrid = this.availableItems.hasReservedGrid();
			this.reservedCraftCopies = currentReservedCraftCopies(this.availableItems);
		}

		private Craftability computeCraftability(RecipeDisplayId recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
			int desiredVariantCopies = desiredVariantCopies(recipe, collection);
			RecipeVariantResolver.Selection selection = RecipeVariantResolver.resolve(
				minecraft,
				player,
				recipe,
				collection,
				displayStack,
				explicitVariantSelection,
				true,
				availableItems,
				totalAvailable,
				totalAvailable,
				false,
				ReachCraftingConfig.get().redistributeToCraftWhenNeeded(),
				desiredVariantCopies
			);
			if (selection == null) {
				return Craftability.NOT_CRAFTABLE;
			}

			IngredientPlanning.PlanResult localPlan = IngredientPlanning.plan(
				selection.ingredientSummary(),
				inventoryCounts,
				availableItems.gridStacks(),
				inventoryCounts,
				inventoryCounts,
				desiredVariantCopies,
				planningPolicy
			);
			if (!localPlan.hasMissingIngredients()) {
				return Craftability.LOCALLY_CRAFTABLE;
			}

			if (reachableView.isEmpty()) {
				return Craftability.NOT_CRAFTABLE;
			}

			IngredientPlanning.PlanResult cachedPlan = IngredientPlanning.plan(
				selection.ingredientSummary(),
				inventoryCounts,
				availableItems.gridStacks(),
				totalAvailable,
				totalAvailable,
				desiredVariantCopies,
				planningPolicy
			);
			return !cachedPlan.hasMissingIngredients() ? Craftability.NEARBY_CRAFTABLE : Craftability.NOT_CRAFTABLE;
		}

		private int desiredVariantCopies(RecipeDisplayId recipe, RecipeCollection collection) {
			if (!hasReservedGrid || collection == null) {
				return 1;
			}
			RecipeDisplayId matchedRecipeId = gridMatchRecipeIds.computeIfAbsent(collection, this::resolveGridMatchRecipeId);
			return recipe.equals(matchedRecipeId) ? reservedCraftCopies + 1 : 1;
		}

		private RecipeDisplayId resolveGridMatchRecipeId(RecipeCollection collection) {
			RecipeVariantResolver.Selection currentGridSelection = RecipeVariantResolver.resolveMatchForGrid(
				minecraft,
				player,
				collection,
				availableItems.gridStacks(),
				availableItems,
				totalAvailable,
				totalAvailable,
				false,
				1
			);
			return currentGridSelection != null ? currentGridSelection.recipeId() : null;
		}
	}

	public static void renderDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC8B7B00;
		int inner = 0xFFFFDD00;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGreenDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC0F6B2E;
		int inner = 0xFF43C567;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderChainDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC8B4400;
		int inner = 0xFFFF8800;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderBlackDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC000000;
		int inner = 0xFF000000;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderWhiteDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCCFFFFFF;
		int inner = 0xFFFFFFFF;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC888888;
		int inner = 0xFF888888;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayArrow(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int color = 0x80000000;
		guiGraphics.fill(x + 0, y, x + 3, y + 3, color);
		guiGraphics.fill(x - 1, y + 3, x + 4, y + 4, color);
		guiGraphics.fill(x + 0, y + 4, x + 3, y + 5, color);
		guiGraphics.fill(x + 1, y + 5, x + 2, y + 6, color);
	}

	// Stylized arrow with tail:
	//   #####
	//   #####
	//   #####
	//   #####
	//  #######
	//   #####
	//    ###
	//     #
	public static void renderOrangeArrowOutline(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int color = 0xFFFF9A1F;
		guiGraphics.fill(x - 1, y - 1, x + 4, y + 2, color);
		guiGraphics.fill(x - 2, y + 2, x + 5, y + 5, color);
		guiGraphics.fill(x - 1, y + 5, x + 4, y + 6, color);
		guiGraphics.fill(x + 0, y + 6, x + 3, y + 7, color);
	}

	public static void renderRetrievalX(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int color = 0xCC147A38;
		guiGraphics.fill(x - 2, y - 2, x - 1, y - 1, color);
		guiGraphics.fill(x + 2, y - 2, x + 3, y - 1, color);
		guiGraphics.fill(x - 1, y - 1, x + 0, y + 0, color);
		guiGraphics.fill(x + 1, y - 1, x + 2, y + 0, color);
		guiGraphics.fill(x + 0, y + 0, x + 1, y + 1, color);
		guiGraphics.fill(x - 1, y + 1, x + 0, y + 2, color);
		guiGraphics.fill(x + 1, y + 1, x + 2, y + 2, color);
		guiGraphics.fill(x - 2, y + 2, x - 1, y + 3, color);
		guiGraphics.fill(x + 2, y + 2, x + 3, y + 3, color);
	}
}
