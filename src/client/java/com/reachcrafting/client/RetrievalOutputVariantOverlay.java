package com.reachcrafting.client;

import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

public final class RetrievalOutputVariantOverlay {
	private RetrievalOutputVariantOverlay() {
	}

	public static boolean openForButton(RecipeButton button) {
		if (button == null || button.getCollection() == null || button.getCurrentRecipe() == null) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (!(minecraft.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen) || minecraft.level == null) {
			return false;
		}

		LocalPlayer player = minecraft.player;
		if (player == null) {
			return false;
		}

		RecipeCollection groupedCollection = buildGroupedCollection(
			player,
			button.getCollection(),
			button.getCurrentRecipe(),
			button.getDisplayStack()
		);
		if (groupedCollection == null || groupedCollection.getRecipes().size() <= 1) {
			return false;
		}

		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) componentAccessor.getRecipeBookPage();
		OverlayRecipeComponent overlay = pageAccessor.getOverlay();
		if (overlay == null) {
			return false;
		}

		ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
		int width = componentAccessor.getWidth();
		int height = componentAccessor.getHeight();
		overlay.init(
			groupedCollection,
			context,
			pageAccessor.getIsFiltering(),
			button.getX(),
			button.getY(),
			width / 2,
			(height / 2) + 13,
			button.getWidth()
		);
		return true;
	}

	private static RecipeCollection buildGroupedCollection(
		LocalPlayer player,
		RecipeCollection collection,
		RecipeDisplayId clickedRecipeId,
		ItemStack clickedDisplayStack
	) {
		RecipeCollection canonicalCollection = resolveCanonicalCollection(player, collection, clickedRecipeId);
		if (canonicalCollection == null || canonicalCollection.getRecipes().size() <= 1) {
			return canonicalCollection;
		}

		ContextMap context = SlotDisplayContext.fromLevel(player.level());
		Map<String, Integer> nearbyCounts = NearbyContainerCache.getReachableView(
			player.level(),
			Minecraft.getInstance().getCameraEntity(),
			player.blockInteractionRange()
		).aggregateCounts();
		String clickedOutputItemId = resolveOutputItemId(clickedRecipeId, clickedDisplayStack, canonicalCollection, context);
		Map<String, RecipeDisplayEntry> groupedEntries = new LinkedHashMap<>();
		for (RecipeDisplayEntry entry : canonicalCollection.getRecipes()) {
			ItemStack resolvedStack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
			if (resolvedStack.isEmpty()) {
				continue;
			}
			String outputItemId = BuiltInRegistries.ITEM.getKey(resolvedStack.getItem()).toString();
			boolean hasLiveNearbyBacking = nearbyCounts.getOrDefault(outputItemId, 0) > 0;
			RecipeDisplayEntry syntheticEntry = syntheticOverlayEntry(entry, resolvedStack, hasLiveNearbyBacking);
			RecipeDisplayEntry existing = groupedEntries.get(outputItemId);
			if (existing == null || (outputItemId.equals(clickedOutputItemId) && entry.id().equals(clickedRecipeId))) {
				groupedEntries.put(outputItemId, syntheticEntry);
			}
		}

		if (groupedEntries.size() <= 1) {
			return null;
		}

		RecipeCollection groupedCollection = new RecipeCollection(List.copyOf(groupedEntries.values()));
		groupedCollection.selectRecipes(new StackedItemContents(), ignored -> true);
		return groupedCollection;
	}

	private static RecipeDisplayEntry syntheticOverlayEntry(RecipeDisplayEntry representative, ItemStack resolvedStack, boolean hasLiveNearbyBacking) {
		Item item = resolvedStack.getItem();
		RecipeDisplayId syntheticId = VirtualRetrievalRecipeBookEntries.syntheticIdFor(
			BuiltInRegistries.ITEM.getKey(item).toString(),
			hasLiveNearbyBacking
		);
		RecipeBookCategory category = representative.category();
		RecipeDisplay display = new ShapelessCraftingRecipeDisplay(
			List.of(new SlotDisplay.ItemStackSlotDisplay(new ItemStackTemplate(item, 1))),
			new SlotDisplay.ItemStackSlotDisplay(new ItemStackTemplate(item, Math.max(resolvedStack.getCount(), 1))),
			new SlotDisplay.ItemSlotDisplay(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.asItem())
		);
		return new RecipeDisplayEntry(
			syntheticId,
			display,
			java.util.OptionalInt.empty(),
			category,
			java.util.Optional.empty()
		);
	}

	private static String resolveOutputItemId(
		RecipeDisplayId clickedRecipeId,
		ItemStack clickedDisplayStack,
		RecipeCollection collection,
		ContextMap context
	) {
		if (clickedDisplayStack != null && !clickedDisplayStack.isEmpty()) {
			return BuiltInRegistries.ITEM.getKey(clickedDisplayStack.getItem()).toString();
		}
		for (RecipeDisplayEntry entry : collection.getRecipes()) {
			if (!entry.id().equals(clickedRecipeId)) {
				continue;
			}
			ItemStack resolvedStack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
			if (!resolvedStack.isEmpty()) {
				return BuiltInRegistries.ITEM.getKey(resolvedStack.getItem()).toString();
			}
			break;
		}
		return "";
	}

	private static RecipeCollection resolveCanonicalCollection(
		LocalPlayer player,
		RecipeCollection collection,
		RecipeDisplayId recipeId
	) {
		RecipeCollection bestCollection = collection;
		int bestSize = collection != null ? collection.getRecipes().size() : 0;
		for (RecipeCollection candidate : player.getRecipeBook().getCollections()) {
			boolean containsRecipe = candidate.getRecipes().stream()
				.anyMatch(entry -> entry.id().equals(recipeId));
			if (!containsRecipe) {
				continue;
			}
			int candidateSize = candidate.getRecipes().size();
			if (bestCollection == null || candidateSize > bestSize) {
				bestCollection = candidate;
				bestSize = candidateSize;
			}
		}
		return bestCollection;
	}
}
