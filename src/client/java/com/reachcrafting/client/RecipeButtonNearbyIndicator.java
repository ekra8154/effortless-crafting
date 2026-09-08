package com.reachcrafting.client;

import java.util.List;
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
	private static final String RETRIEVAL_X_PATTERN = "#OOO#\nO#O#O\nOO#OO\nO#O#O\n#OOO#";
	private static final float RETRIEVAL_X_SCALE = 1.2f;
	private static StateKey currentStateKey;
	private static EvaluationContext currentContext;

	public enum Craftability {
		NOT_CRAFTABLE,
		LOCALLY_CRAFTABLE,
		NEARBY_CRAFTABLE
	}

	/**
	 * How this click would craft, independent of retrieval. Shape tells the
	 * player whether a chest gets opened: filled dot = from the inventory,
	 * plus = nearby containers required (hold Ctrl). Colour tells direct
	 * (yellow) from chain (orange). Ordered best-first for collections.
	 */
	enum IndicatorState {
		LOCAL,
		NEARBY,
		CHAIN_LOCAL,
		CHAIN_NEARBY,
		NONE
	}

	private static final Map<RecipeDisplayId, Craftability> mainCache = new java.util.HashMap<>();
	private static final Map<RecipeDisplayId, Craftability> overlayCache = new java.util.HashMap<>();
	private static final Map<RecipeDisplayId, Boolean> retrievableCache = new java.util.HashMap<>();
	private static final Map<RecipeCollection, IndicatorState> collectionIndicatorCache = new IdentityHashMap<>();
	private static final Map<RecipeCollection, Boolean> collectionRetrievableCache = new IdentityHashMap<>();
	private static final Map<RecipeCollection, Craftability> collectionCraftabilityCache = new IdentityHashMap<>();

	private RecipeButtonNearbyIndicator() {
	}

	public static void clearCaches() {
		currentStateKey = null;
		currentContext = null;
		mainCache.clear();
		overlayCache.clear();
		retrievableCache.clear();
		collectionIndicatorCache.clear();
		collectionRetrievableCache.clear();
		collectionCraftabilityCache.clear();
	}

	public static boolean shouldShow(RecipeButton button) {
		IndicatorState state = resolveIndicatorState(button);
		return state == IndicatorState.LOCAL || state == IndicatorState.NEARBY;
	}

	public static boolean isRetrievable(RecipeButton button) {
		return resolveRetrievable(button);
	}

	public static boolean isChainCraftable(RecipeButton button) {
		IndicatorState state = resolveIndicatorState(button);
		return state == IndicatorState.CHAIN_LOCAL || state == IndicatorState.CHAIN_NEARBY;
	}

	/** Dev harness: the state and retrievable flag for one recipe, as the book would show it. */
	static String describe(RecipeDisplayId recipe, RecipeCollection collection) {
		// Same rules as the page button: a rotating (multi-variant) button
		// reports the best member's state and "any member retrievable".
		boolean multi = collection != null && collection.getRecipes().size() > 1;
		IndicatorState state = multi
			? resolveCollectionIndicatorState(collection)
			: indicatorStateForRecipe(recipe, collection, ItemStack.EMPTY, false);
		boolean retrievable = false;
		if (multi) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				if (hasRetrievableOutput(entry.id(), collection, ItemStack.EMPTY, true)) {
					retrievable = true;
					break;
				}
			}
		} else {
			retrievable = hasRetrievableOutput(recipe, collection, ItemStack.EMPTY, false);
		}
		return "state=" + state + " retrievable=" + retrievable + " collection_size=" + (collection != null ? collection.getRecipes().size() : 0);
	}

	public static void renderButton(net.minecraft.client.gui.GuiGraphics guiGraphics, RecipeButton button) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}

		IndicatorState indicatorState = resolveIndicatorState(button);
		boolean retrievable = resolveRetrievable(button);
		if (indicatorState == IndicatorState.NONE && !retrievable) {
			return;
		}

		AbstractWidget widget = (AbstractWidget) (Object) button;
		renderIndicators(guiGraphics, widget.getX() + 3, widget.getY() + 3, indicatorState, retrievable);
	}

	/**
	 * Green (retrievable) sits two pixels down-right of the craft dot and is
	 * drawn first, so the yellow/orange dot covers most of it and the green
	 * shows as a crescent along its lower-right edge. In retrieval mode the
	 * craft dot is hidden and the green stands alone at the same spot.
	 */
	private static void renderIndicators(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y, IndicatorState state, boolean retrievable) {
		if (retrievable) {
			renderGreenDot(guiGraphics, x + 2, y + 2);
		}
		if (ExistingOutputRetrievalController.isEnabled()) {
			return;
		}
		switch (state) {
			case LOCAL -> renderDot(guiGraphics, x, y);
			case NEARBY -> renderPlusDot(guiGraphics, x, y, 0xCC8B7B00, 0xFFFFDD00);
			case CHAIN_LOCAL -> renderChainDot(guiGraphics, x, y);
			case CHAIN_NEARBY -> renderPlusDot(guiGraphics, x, y, 0xCC8B4400, 0xFFFF8800);
			case NONE -> {
			}
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
			retrievableCache.clear();
			collectionIndicatorCache.clear();
			collectionRetrievableCache.clear();
			collectionCraftabilityCache.clear();
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
		IndicatorState indicatorState = ReachCraftingConfig.get().showNearbyCraftableIndicator()
			? indicatorStateForRecipe(recipe, collection, ItemStack.EMPTY, true)
			: IndicatorState.NONE;
		boolean retrievable = retrievableIndicatorEnabled()
			&& retrievableCache.computeIfAbsent(recipe, id -> hasRetrievableOutput(id, collection, ItemStack.EMPTY, true));
		if (indicatorState == IndicatorState.NONE && !retrievable) {
			return;
		}
		renderIndicators(guiGraphics, x, y, indicatorState, retrievable);
	}

	private static boolean retrievableIndicatorEnabled() {
		return ReachCraftingConfig.get().showRetrievableIndicator() || ExistingOutputRetrievalController.isEnabled();
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

	private static boolean resolveRetrievable(RecipeButton button) {
		if (!retrievableIndicatorEnabled()) {
			return false;
		}
		RecipeCollection collection = button.getCollection();
		if (collection == null) {
			return false;
		}
		if (collection.getRecipes().size() > 1) {
			return collectionRetrievableCache.computeIfAbsent(collection, col -> {
				for (RecipeDisplayEntry entry : col.getRecipes()) {
					if (hasRetrievableOutput(entry.id(), col, ItemStack.EMPTY, true)) {
						return true;
					}
				}
				return false;
			});
		}
		RecipeDisplayId recipe = button.getCurrentRecipe();
		if (recipe == null) {
			return false;
		}
		ItemStack displayStack = button.getDisplayStack().copy();
		return retrievableCache.computeIfAbsent(recipe, id -> hasRetrievableOutput(id, collection, displayStack, false));
	}

	/**
	 * The craftability of a whole collection, asked the same way the ICON asks
	 * it: over every recipe in {@link RecipeCollection#getRecipes()}.
	 *
	 * <p>The smart sort must use THIS rather than walking
	 * {@code getSelectedRecipes(...)}, which vanilla filters by the open
	 * menu's grid size - that made a recipe show a craftable icon while
	 * sorting into the uncraftable tier, and made the 2x2 inventory book
	 * disagree with the 3x3 table for the same recipe.</p>
	 */
	public static Craftability collectionCraftability(RecipeCollection collection) {
		if (collection == null) {
			return Craftability.NOT_CRAFTABLE;
		}
		Craftability cached = collectionCraftabilityCache.get(collection);
		if (cached != null) {
			return cached;
		}
		List<RecipeDisplayEntry> recipes = collection.getRecipes();
		boolean explicitVariantSelection = recipes.size() > 1;
		Craftability best = Craftability.NOT_CRAFTABLE;
		for (RecipeDisplayEntry entry : recipes) {
			Craftability craftability =
				getCraftability(entry.id(), collection, ItemStack.EMPTY, explicitVariantSelection);
			if (craftability == Craftability.LOCALLY_CRAFTABLE) {
				best = craftability;
				break;
			}
			if (craftability == Craftability.NEARBY_CRAFTABLE) {
				best = craftability;
			}
		}
		collectionCraftabilityCache.put(collection, best);
		return best;
	}

	private static IndicatorState resolveCollectionIndicatorState(RecipeCollection collection) {
		IndicatorState best = IndicatorState.NONE;
		for (RecipeDisplayEntry entry : collection.getRecipes()) {
			IndicatorState state = indicatorStateForRecipe(entry.id(), collection, ItemStack.EMPTY, true);
			if (state.ordinal() < best.ordinal()) {
				best = state;
			}
			if (best == IndicatorState.LOCAL) {
				break;
			}
		}
		return best;
	}

	private static IndicatorState indicatorStateForRecipe(RecipeDisplayId recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		Craftability craftability = getCraftability(recipe, collection, displayStack, explicitVariantSelection);
		if (craftability == Craftability.LOCALLY_CRAFTABLE) {
			return IndicatorState.LOCAL;
		}
		if (craftability == Craftability.NEARBY_CRAFTABLE) {
			return IndicatorState.NEARBY;
		}
		if (ChainCraftabilityCache.isChainCraftableLocally(recipe)) {
			return IndicatorState.CHAIN_LOCAL;
		}
		return ChainCraftabilityCache.isChainCraftable(recipe) ? IndicatorState.CHAIN_NEARBY : IndicatorState.NONE;
	}

	public static boolean hasRetrievableOutput(RecipeDisplayId recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
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

		if (VirtualRetrievalRecipeBookEntries.isSyntheticRecipeId(recipe)) {
			return VirtualRetrievalRecipeBookEntries.hasLiveNearbyBacking(recipe);
		}

		Map<String, Integer> nearbyTotals = NearbyContainerCache.getReachableView(
			minecraft.level,
			minecraft.getCameraEntity(),
			player.blockInteractionRange()
		).aggregateCounts();
		RecipeVariantResolver.Selection selection = RecipeVariantResolver.resolveRetrievalVariant(
			minecraft,
			player,
			recipe,
			collection,
			displayStack,
			explicitVariantSelection,
			true,
			AvailableItemSnapshot.empty(),
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

	/** Same 5x5 outline as {@link #renderDot}; the inner 3x3 becomes a plus (center + four neighbours). */
	public static void renderPlusDot(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y, int outer, int inner) {
		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 2, y + 1, x + 3, y + 4, inner);
		guiGraphics.fill(x + 1, y + 2, x + 4, y + 3, inner);
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

	private static void renderPattern(net.minecraft.client.gui.GuiGraphics guiGraphics, int centerX, int centerY, String pattern, int color) {
		renderPattern(guiGraphics, centerX, centerY, pattern, color, 1.0f);
	}

	private static void renderPattern(net.minecraft.client.gui.GuiGraphics guiGraphics, int centerX, int centerY, String pattern, int color, float scale) {
		if (pattern == null || pattern.isBlank()) {
			return;
		}

		String[] rows = pattern.split("\\n");
		int height = rows.length;
		int width = 0;
		for (String row : rows) {
			width = Math.max(width, row.length());
		}

		float scaledWidth = width * scale;
		float scaledHeight = height * scale;
		float startX = centerX - (scaledWidth / 2.0f);
		float startY = centerY - (scaledHeight / 2.0f);

		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(startX, startY, 0);
		guiGraphics.pose().scale(scale, scale, 1.0f);
		try {
			for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
				String row = rows[rowIndex];
				for (int columnIndex = 0; columnIndex < row.length(); columnIndex++) {
					if (row.charAt(columnIndex) != '#') {
						continue;
					}
					guiGraphics.fill(columnIndex, rowIndex, columnIndex + 1, rowIndex + 1, color);
				}
			}
		} finally {
			guiGraphics.pose().popPose();
		}
	}

	public static void renderRetrievalX(net.minecraft.client.gui.GuiGraphics guiGraphics, int x, int y) {
		int color = 0x80000000;
		renderPattern(guiGraphics, x, y, RETRIEVAL_X_PATTERN, color, RETRIEVAL_X_SCALE);
	}
}
