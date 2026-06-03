package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.SearchRecipeBookCategory;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

public final class VirtualRetrievalRecipeBookEntries {
	private static final int SYNTHETIC_RECIPE_ID_BASE = 1_000_000_000;
	private static final int PERSISTENT_ONLY_SYNTHETIC_RECIPE_ID_BASE = 1_500_000_000;
	private static final Map<String, RecipeCollection> SYNTHETIC_CACHE = new java.util.HashMap<>();

	private VirtualRetrievalRecipeBookEntries() {
	}

	public static List<RecipeCollection> injectCollections(RecipeBookComponent<?> component, List<RecipeCollection> collections) {
		if (!ExistingOutputRetrievalController.isEnabled() || !ReachCraftingConfig.get().enableExistingOutputRetrieval()) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject skipped reason=mode_disabled retrieval_enabled={} config_enabled={}", ExistingOutputRetrievalController.isEnabled(), ReachCraftingConfig.get().enableExistingOutputRetrieval());
			return collections;
		}
		RecipeBookComponentAccessor accessor = (RecipeBookComponentAccessor) component;
		if (Boolean.TRUE.equals(accessor.getFilterButton().getValue())) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject skipped reason=craftable_filter_on");
			return collections;
		}

		Minecraft minecraft = accessor.getMinecraft();
		if (minecraft == null) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject skipped reason=no_minecraft");
			return collections;
		}
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject skipped reason=unsupported_screen screen={}", screen != null ? screen.getClass().getSimpleName() : "null");
			return collections;
		}
		int gridSlotCount = screen instanceof InventoryScreen ? 4 : 9;
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.getCameraEntity() == null) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject skipped reason=missing_context player={} level={} camera={}", player != null, minecraft.level != null, minecraft.getCameraEntity() != null);
			return collections;
		}
		if (!ReachCraftingConfig.get().enableNearbyContainerUsage() || !ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject skipped reason=config_nearby_disabled nearby_enabled={} cache_enabled={}", ReachCraftingConfig.get().enableNearbyContainerUsage(), ReachCraftingConfig.get().cacheContainersForFasterSearch());
			return collections;
		}

		Set<String> currentlyHeldItemIds = collectHeldItemIds(player);
		ReachCraftingConfig.get().noteExperiencedItemIds(currentlyHeldItemIds);
		Set<String> craftingTableOutputIds = collectCraftingTableOutputIds(player, gridSlotCount);
		Map<String, Integer> nearbyCounts = NearbyContainerCache.getReachableView(
			minecraft.level,
			minecraft.getCameraEntity(),
			player.blockInteractionRange()
		).aggregateCounts();
		Set<String> experiencedItemIds = new java.util.LinkedHashSet<>(ReachCraftingConfig.get().experiencedItemIds());
		experiencedItemIds.addAll(currentlyHeldItemIds);
		if (nearbyCounts.isEmpty() && experiencedItemIds.isEmpty()) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject skipped reason=no_retrieval_candidates");
			return collections;
		}

		String search = accessor.getSearchBox() != null ? accessor.getSearchBox().getValue().trim().toLowerCase(Locale.ROOT) : "";
		Object selectedCategory = accessor.getSelectedTab() != null ? accessor.getSelectedTab().getCategory() : null;
		ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject start base_collections={} nearby_items={} experienced_items={} crafting_outputs={} grid_slots={} search='{}' selected_category={}", collections.size(), nearbyCounts.size(), experiencedItemIds.size(), craftingTableOutputIds.size(), gridSlotCount, search, selectedCategory != null ? selectedCategory.getClass().getSimpleName() + ":" + selectedCategory : "null");
		List<RecipeCollection> synthetic = new ArrayList<>();
		int skippedExistingOutput = 0;
		int skippedNullItem = 0;
		int skippedCategory = 0;
		int skippedSearch = 0;
		Set<String> candidateItemIds = new java.util.LinkedHashSet<>(nearbyCounts.keySet());
		candidateItemIds.addAll(experiencedItemIds);
		for (String itemId : candidateItemIds) {
			Item item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(itemId)).orElse(null);
			if (item == null) {
				skippedNullItem++;
				continue;
			}
			if (craftingTableOutputIds.contains(itemId)) {
				skippedExistingOutput++;
				continue;
			}

			RecipeBookCategory category = categoryFor(item, itemId);
			if (!matchesSelectedCategory(selectedCategory, category)) {
				skippedCategory++;
				continue;
			}

			ItemStack stack = new ItemStack(item);
			if (!matchesSearch(stack, itemId, search)) {
				skippedSearch++;
				continue;
			}

			boolean hasLiveNearbyBacking = nearbyCounts.containsKey(itemId);
			int liveCount = nearbyCounts.getOrDefault(itemId, 1);
			int displayCount = hasLiveNearbyBacking ? Math.min(Math.max(liveCount, 1), stack.getMaxStackSize()) : 1;
			String cacheKey = itemId + ":" + displayCount + ":" + hasLiveNearbyBacking;
			RecipeCollection syntheticCollection = SYNTHETIC_CACHE.computeIfAbsent(cacheKey, k -> {
				RecipeDisplayId id = syntheticIdFor(itemId, hasLiveNearbyBacking);
				RecipeDisplay display = new ShapelessCraftingRecipeDisplay(
					List.of(),
					new SlotDisplay.ItemStackSlotDisplay(new ItemStack(item, displayCount)),
					new SlotDisplay.ItemSlotDisplay(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.asItem())
				);
				RecipeDisplayEntry syntheticEntry = new RecipeDisplayEntry(
					id,
					display,
					java.util.OptionalInt.empty(),
					category,
					java.util.Optional.empty()
				);
				RecipeCollection col = new RecipeCollection(List.of(syntheticEntry));
				col.selectRecipes(new StackedItemContents(), ignored -> true);
				return col;
			});
			synthetic.add(syntheticCollection);
		}

		if (synthetic.isEmpty()) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject produced no synthetic collections skipped_existing={} skipped_null_item={} skipped_category={} skipped_search={}", skippedExistingOutput, skippedNullItem, skippedCategory, skippedSearch);
			return collections;
		}

		synthetic.sort(Comparator.comparing(VirtualRetrievalRecipeBookEntries::syntheticSortKey));
		List<RecipeCollection> combined = new ArrayList<>(collections.size() + synthetic.size());
		combined.addAll(collections);
		combined.addAll(synthetic);
		ReachCraftingMod.LOGGER.info("[retrieval_virtual] inject success synthetic={} combined={} skipped_existing={} skipped_null_item={} skipped_category={} skipped_search={}", synthetic.size(), combined.size(), skippedExistingOutput, skippedNullItem, skippedCategory, skippedSearch);
		return combined;
	}

	static boolean isSyntheticRecipeId(RecipeDisplayId recipeId) {
		return recipeId != null && recipeId.index() >= SYNTHETIC_RECIPE_ID_BASE;
	}

	static boolean hasLiveNearbyBacking(RecipeDisplayId recipeId) {
		return recipeId != null
			&& recipeId.index() >= SYNTHETIC_RECIPE_ID_BASE
			&& recipeId.index() < PERSISTENT_ONLY_SYNTHETIC_RECIPE_ID_BASE;
	}

	static int requestCountForSynthetic(ItemStack stack, boolean shiftRequested) {
		if (stack == null || stack.isEmpty()) {
			return 1;
		}
		return shiftRequested ? Math.max(stack.getMaxStackSize(), 1) : 1;
	}

	static void startRetrievalForSynthetic(RecipeDisplayId recipeId, ItemStack displayStack, int requestedCount) {
		if (displayStack == null || displayStack.isEmpty()) {
			ReachCraftingModClient.sendChat("No matching existing item could be resolved.");
			return;
		}
		String itemId = BuiltInRegistries.ITEM.getKey(displayStack.getItem()).toString();
		String outputLabel = itemId + " x" + displayStack.getCount();
		NearbyContainerDryRun.startExistingOutputRetrieval(
			recipeId,
			recipeId,
			null,
			false,
			itemId,
			outputLabel,
			displayStack,
			Math.max(requestedCount, 1)
		);
	}

	private static String syntheticSortKey(RecipeCollection collection) {
		if (collection == null || collection.getRecipes().isEmpty()) {
			return "";
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return "";
		}
		ItemStack stack = collection.getRecipes().getFirst().resultItems(net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(minecraft.level))
			.stream()
			.findFirst()
			.orElse(ItemStack.EMPTY);
		return stack.isEmpty() ? "" : stack.getHoverName().getString().toLowerCase(Locale.ROOT);
	}

	private static Set<String> collectCraftingTableOutputIds(LocalPlayer player, int gridSlotCount) {
		Set<String> outputIds = new HashSet<>();
		net.minecraft.util.context.ContextMap context = net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(player.level());
		for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				RecipeDisplay display = entry.display();
				if (!(display instanceof ShapedCraftingRecipeDisplay) && !(display instanceof ShapelessCraftingRecipeDisplay)) {
					continue;
				}
				if (!fitsGrid(display, gridSlotCount)) {
					continue;
				}
				ItemStack output = RecipeVariantResolver.resolveDisplayStack(display, context);
				if (!output.isEmpty()) {
					outputIds.add(BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
				}
			}
		}
		return outputIds;
	}

	private static boolean fitsGrid(RecipeDisplay display, int gridSlotCount) {
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			if (gridSlotCount == 4) {
				return shaped.width() <= 2 && shaped.height() <= 2;
			}
			return gridSlotCount == 9 && shaped.width() <= 3 && shaped.height() <= 3;
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return shapeless.ingredients().size() <= gridSlotCount;
		}
		return false;
	}

	private static Set<String> collectHeldItemIds(LocalPlayer player) {
		Set<String> itemIds = new HashSet<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				itemIds.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			}
		}
		return itemIds;
	}

	private static boolean matchesSelectedCategory(Object selectedCategory, RecipeBookCategory category) {
		if (selectedCategory == null) {
			return true;
		}
		if (selectedCategory instanceof RecipeBookCategory recipeBookCategory) {
			return recipeBookCategory == category;
		}
		if (selectedCategory instanceof SearchRecipeBookCategory searchCategory) {
			return searchCategory.includedCategories().contains(category);
		}
		return true;
	}

	private static boolean matchesSearch(ItemStack stack, String itemId, String search) {
		if (search == null || search.isBlank()) {
			return true;
		}
		String lowerName = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
		return lowerName.contains(search) || itemId.toLowerCase(Locale.ROOT).contains(search);
	}

	private static RecipeDisplayId syntheticIdFor(String itemId, boolean hasLiveNearbyBacking) {
		int hash = Math.abs(itemId.hashCode());
		int base = hasLiveNearbyBacking ? SYNTHETIC_RECIPE_ID_BASE : PERSISTENT_ONLY_SYNTHETIC_RECIPE_ID_BASE;
		return new RecipeDisplayId(base + hash);
	}

	private static RecipeBookCategory categoryFor(Item item, String itemId) {
		String path = itemId;
		if (path.contains("helmet")
			|| path.contains("chestplate")
			|| path.contains("leggings")
			|| path.contains("boots")
			|| path.contains("sword")
			|| path.contains("pickaxe")
			|| path.contains("axe")
			|| path.contains("shovel")
			|| path.contains("hoe")
			|| path.contains("bow")
			|| path.contains("crossbow")
			|| path.contains("shield")
			|| path.contains("trident")) {
			return RecipeBookCategories.CRAFTING_EQUIPMENT;
		}
		if (path.contains("redstone")
			|| path.contains("repeater")
			|| path.contains("comparator")
			|| path.contains("observer")
			|| path.contains("piston")
			|| path.contains("dispenser")
			|| path.contains("dropper")
			|| path.contains("lever")
			|| path.contains("pressure_plate")
			|| path.contains("daylight_detector")
			|| path.contains("tripwire")
			|| path.contains("target")
			|| path.contains("hopper")) {
			return RecipeBookCategories.CRAFTING_REDSTONE;
		}
		if (item instanceof BlockItem) {
			return RecipeBookCategories.CRAFTING_BUILDING_BLOCKS;
		}
		return RecipeBookCategories.CRAFTING_MISC;
	}
}
