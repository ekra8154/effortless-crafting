package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.player.LocalPlayer;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;

public final class RecipeButtonNearbyIndicator {
	private RecipeButtonNearbyIndicator() {
	}

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

	/** Dev harness: the state and retrievable flag for one recipe, as the book would show it. */
	static String describe(RecipeHolder<?> recipe, RecipeCollection collection) {
		// Same rules as the page button: a rotating (multi-variant) button
		// reports the best member's state and "any member retrievable".
		boolean multi = collection != null && collection.getRecipes().size() > 1;
		IndicatorState state = multi
			? resolveCollectionIndicatorState(collection)
			: indicatorStateForRecipe(recipe, collection, ItemStack.EMPTY, false);
		boolean retrievable = false;
		if (multi) {
			for (RecipeHolder<?> entry : collection.getRecipes()) {
				if (hasRetrievableOutput(entry, collection, ItemStack.EMPTY, true)) {
					retrievable = true;
					break;
				}
			}
		} else {
			retrievable = hasRetrievableOutput(recipe, collection, ItemStack.EMPTY, false);
		}
		// Report what the button actually draws. Both dots are gated by their
		// display settings, and the green one also follows Existing Output
		// Handling, so a probe that skipped those gates would measure a state
		// the player never sees.
		if (!ReachCraftingConfig.get().showCraftabilityIndicators()) {
			state = IndicatorState.NONE;
		}
		if (!retrievableIndicatorEnabled()) {
			retrievable = false;
		}
		return "state=" + state + " retrievable=" + retrievable + " collection_size=" + (collection != null ? collection.getRecipes().size() : 0);
	}

	private static IndicatorState resolveCollectionIndicatorState(RecipeCollection collection) {
		IndicatorState best = IndicatorState.NONE;
		for (RecipeHolder<?> entry : collection.getRecipes()) {
			IndicatorState state = indicatorStateForRecipe(entry, collection, ItemStack.EMPTY, true);
			if (state.ordinal() < best.ordinal()) {
				best = state;
			}
			if (best == IndicatorState.LOCAL) {
				break;
			}
		}
		return best;
	}

	private static IndicatorState indicatorStateForRecipe(RecipeHolder<?> recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		if (recipe == null) {
			return IndicatorState.NONE;
		}
		ItemStack stack = displayStack == null || displayStack.isEmpty()
			? RecipeVariantResolver.resolveDisplayStack(recipe, Minecraft.getInstance())
			: displayStack;
		Craftability craftability = computeCraftability(recipe, collection, stack, explicitVariantSelection);
		if (craftability == Craftability.LOCALLY_CRAFTABLE) {
			return IndicatorState.LOCAL;
		}
		if (craftability == Craftability.NEARBY_CRAFTABLE) {
			return IndicatorState.NEARBY;
		}
		if (ChainCraftabilityCache.isChainCraftableLocally(recipe.id())) {
			return IndicatorState.CHAIN_LOCAL;
		}
		return ChainCraftabilityCache.isChainCraftable(recipe.id()) ? IndicatorState.CHAIN_NEARBY : IndicatorState.NONE;
	}

	private static boolean retrievableIndicatorEnabled() {
		// The green dot promises that a Ctrl-assisted click pulls the copies
		// already in storage. Under Craft only nothing would pull, so showing
		// it would advertise a gesture the mod refuses to perform.
		if (ReachCraftingConfig.get().existingOutputHandling() == ReachCraftingConfig.ExistingOutputHandling.CRAFT_ONLY) {
			return false;
		}
		return ReachCraftingConfig.get().showCraftabilityIndicators();
	}

	/** True when the cached nearby containers hold copies of this recipe's output (the variant a retrieval would pick). */
	public static boolean hasRetrievableOutput(RecipeHolder<?> recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		if (!ReachCraftingConfig.get().enableNearbyContainerUsage()
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
		Map<String, Integer> nearbyTotals = NearbyContainerCache.getReachableView(
			minecraft.level,
			minecraft.getCameraEntity(),
			player.blockInteractionRange()
		).aggregateCounts();
		if (nearbyTotals.isEmpty()) {
			return false;
		}
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

	private static boolean resolveRetrievable(RecipeButton button) {
		if (!retrievableIndicatorEnabled()) {
			return false;
		}
		RecipeCollection collection = button.getCollection();
		if (collection == null) {
			return false;
		}
		if (collection.getRecipes().size() > 1) {
			for (RecipeHolder<?> entry : collection.getRecipes()) {
				if (hasRetrievableOutput(entry, collection, ItemStack.EMPTY, true)) {
					return true;
				}
			}
			return false;
		}
		RecipeHolder<?> recipe = button.getRecipe();
		return recipe != null && hasRetrievableOutput(recipe, collection, ItemStack.EMPTY, false);
	}

	public static boolean shouldShow(RecipeButton button) {
		return shouldShow(button.getRecipe(), button.getCollection(), RecipeVariantResolver.resolveDisplayStack(button.getRecipe(), Minecraft.getInstance()), false);
	}

	/**
	 * Classifies whether the recipe (identified by id within {@code collection}) is craftable using
	 * nearby container contents. Computed live (no persistent cache); resolves the holder and
	 * reuses {@link #shouldShow}.
	 */
	public static Craftability getCraftability(net.minecraft.resources.ResourceLocation recipeId, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		if (recipeId == null || collection == null) {
			return Craftability.NOT_CRAFTABLE;
		}
		RecipeHolder<?> recipe = null;
		for (RecipeHolder<?> candidate : collection.getRecipes()) {
			if (candidate.id().equals(recipeId)) {
				recipe = candidate;
				break;
			}
		}
		if (recipe == null) {
			return Craftability.NOT_CRAFTABLE;
		}
		ItemStack stack = (displayStack == null || displayStack.isEmpty())
			? RecipeVariantResolver.resolveDisplayStack(recipe, Minecraft.getInstance())
			: displayStack;
		return computeCraftability(recipe, collection, stack, explicitVariantSelection);
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
		List<RecipeHolder<?>> recipes = collection.getRecipes();
		boolean explicitVariantSelection = recipes.size() > 1;
		Craftability best = Craftability.NOT_CRAFTABLE;
		for (RecipeHolder<?> recipe : recipes) {
			Craftability craftability =
				computeCraftability(recipe, collection, ItemStack.EMPTY, explicitVariantSelection);
			if (craftability == Craftability.LOCALLY_CRAFTABLE) {
				return craftability;
			}
			if (craftability == Craftability.NEARBY_CRAFTABLE) {
				best = craftability;
			}
		}
		return best;
	}

	/** Nearby-craftability is computed live; there is no persistent cache to clear. */
	public static void clearCaches() {
	}

	/**
	 * Renders the recipe-book button indicator: a yellow dot when the recipe is craftable using
	 * nearby containers, otherwise an orange dot when it is chain-craftable. Directly-craftable
	 * recipes get no dot (vanilla already highlights them).
	 */
	public static void renderButton(GuiGraphics guiGraphics, RecipeButton button) {
		if (!ReachCraftingConfig.get().enabled()) return;
		RecipeHolder<?> recipe = button.getRecipe();
		RecipeCollection collection = button.getCollection();
		if (recipe == null || collection == null) return;

		IndicatorState indicatorState = ReachCraftingConfig.get().showCraftabilityIndicators()
			? (collection.getRecipes().size() > 1
				? resolveCollectionIndicatorState(collection)
				: indicatorStateForRecipe(recipe, collection, ItemStack.EMPTY, false))
			: IndicatorState.NONE;
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
	 * shows as a crescent along its lower-right edge.
	 */
	private static void renderIndicators(GuiGraphics guiGraphics, int x, int y, IndicatorState state, boolean retrievable) {
		if (retrievable) {
			renderGreenDot(guiGraphics, x + 2, y + 2);
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

	public static boolean shouldShow(RecipeHolder<?> recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
		// The blue dot is what this predicate is for: it is drawn only for a
		// craft the inventory alone cannot do. LOCALLY_CRAFTABLE deliberately
		// does not light it.
		return ReachCraftingConfig.get().showCraftabilityIndicators()
			&& computeCraftability(recipe, collection, displayStack, explicitVariantSelection)
				== Craftability.NEARBY_CRAFTABLE;
	}

	/**
	 * Full craftability of one recipe: craftable from the inventory alone,
	 * craftable only by pulling from nearby containers, or neither.
	 *
	 * <p>Note this does NOT check showCraftabilityIndicators - that setting
	 * governs whether the dot is DRAWN, not whether the recipe is craftable,
	 * and the smart sort needs the answer either way.</p>
	 */
	public static Craftability computeCraftability(RecipeHolder<?> recipe, RecipeCollection collection, ItemStack displayStack, boolean explicitVariantSelection) {
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
			minecraft.player != null ? minecraft.player.blockInteractionRange() : 4.5D
		);

		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, screen);
		Map<String, Integer> cachedNearbyCounts = reachableView.aggregateCounts();
		Map<String, Integer> totalAvailable = AvailableItemSnapshot.mergeCounts(availableItems.inventoryCounts(), cachedNearbyCounts);
		
		// If the variant matches the grid, we check for a 'top-up' (current + 1).
		// If it's a DIFFERENT variant, we just check if we can craft 1.
		boolean variantMatchesGrid = false;
		if (availableItems.hasReservedGrid()) {
			RecipeVariantResolver.Selection currentGridSelection = RecipeVariantResolver.resolveMatchForGrid(
				minecraft, player, collection, availableItems.gridStacks(), availableItems.gridStacks().stream().anyMatch(s -> !s.isEmpty()) ? availableItems : AvailableItemSnapshot.capture(player, screen), totalAvailable, totalAvailable, false, 1
			);
			if (currentGridSelection != null && currentGridSelection.recipeId().equals(recipe)) {
				variantMatchesGrid = true;
			}
		}

		int desiredVariantCopies = variantMatchesGrid
			? currentReservedCraftCopies(availableItems) + 1
			: 1;

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

		IngredientPlanning.Policy policy = ReachCraftingConfig.get().toPlanningPolicy();
		IngredientPlanning.PlanResult localPlan = IngredientPlanning.plan(
			selection.ingredientSummary(),
			availableItems.inventoryCounts(),
			availableItems.gridStacks(),
			availableItems.inventoryCounts(),
			availableItems.inventoryCounts(),
			desiredVariantCopies,
			policy
		);
		if (!localPlan.hasMissingIngredients()) {
			return Craftability.LOCALLY_CRAFTABLE;
		}
		// Nothing in reach to make up the difference.
		if (reachableView.isEmpty()) {
			return Craftability.NOT_CRAFTABLE;
		}

		IngredientPlanning.PlanResult cachedPlan = IngredientPlanning.plan(
			selection.ingredientSummary(),
			availableItems.inventoryCounts(),
			availableItems.gridStacks(),
			totalAvailable,
			totalAvailable,
			desiredVariantCopies,
			policy
		);
		return cachedPlan.hasMissingIngredients()
			? Craftability.NOT_CRAFTABLE
			: Craftability.NEARBY_CRAFTABLE;
	}

	private static int currentReservedCraftCopies(AvailableItemSnapshot availableItems) {
		return ContainerUtils.currentReservedCraftCopies(availableItems.gridStacks());
	}

	public static void renderOverlayButton(GuiGraphics guiGraphics, int x, int y, int width, RecipeHolder<?> recipe, RecipeCollection collection) {
		IndicatorState indicatorState = ReachCraftingConfig.get().showCraftabilityIndicators()
			? indicatorStateForRecipe(recipe, collection, ItemStack.EMPTY, true)
			: IndicatorState.NONE;
		boolean retrievable = retrievableIndicatorEnabled() && hasRetrievableOutput(recipe, collection, ItemStack.EMPTY, true);
		if (indicatorState == IndicatorState.NONE && !retrievable) {
			return;
		}
		renderIndicators(guiGraphics, x, y, indicatorState, retrievable);
	}

	/** Same 5x5 outline as {@link #renderDot}; the inner 3x3 becomes a plus (center + four neighbours). */
	public static void renderPlusDot(GuiGraphics guiGraphics, int x, int y, int outer, int inner) {
		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 2, y + 1, x + 3, y + 4, inner);
		guiGraphics.fill(x + 1, y + 2, x + 4, y + 3, inner);
	}

	public static void renderGreenDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC0F6B2E;
		int inner = 0xFF43C567;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC8B7B00;
		int inner = 0xFFFFDD00;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderChainDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC8B4400;
		int inner = 0xFFFF8800;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderBlackDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC000000;
		int inner = 0xFF000000;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderWhiteDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCCFFFFFF;
		int inner = 0xFFFFFFFF;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayDot(GuiGraphics guiGraphics, int x, int y) {
		int outer = 0xCC888888;
		int inner = 0xFF888888;

		guiGraphics.fill(x + 1, y, x + 4, y + 1, outer);
		guiGraphics.fill(x, y + 1, x + 5, y + 4, outer);
		guiGraphics.fill(x + 1, y + 4, x + 4, y + 5, outer);

		guiGraphics.fill(x + 1, y + 1, x + 4, y + 4, inner);
	}

	public static void renderGrayArrow(GuiGraphics guiGraphics, int x, int y) {
		int color = 0x80000000;
		// Stylized arrow with tail:
		//   ###
		//   ###
		//   ###
		//  #####
		//   ###
		//    #
		guiGraphics.fill(x + 0, y,     x + 3, y + 3, color); // Tail (3x3)
		guiGraphics.fill(x - 1, y + 3, x + 4, y + 4, color); // (5x1)
		guiGraphics.fill(x + 0, y + 4, x + 3, y + 5, color); // (3x1)
		guiGraphics.fill(x + 1, y + 5, x + 2, y + 6, color); // (1x1)
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
	public static void renderOrangeArrowOutline(GuiGraphics guiGraphics, int x, int y) {
		int color = 0xFFFF9A1F;
		guiGraphics.fill(x - 1, y - 1, x + 4, y + 2, color); // Stem (5x3)
		guiGraphics.fill(x - 2, y + 2, x + 5, y + 5, color); // Wide base (7x3)
		guiGraphics.fill(x - 1, y + 5, x + 4, y + 6, color); // Taper 1 (5x1)
		guiGraphics.fill(x + 0, y + 6, x + 3, y + 7, color); // Taper 2 (3x1)
	}
}


