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
	private static final Set<Integer> LIVE_SYNTHETIC_RECIPE_IDS = new java.util.HashSet<>();
	private static final Set<Integer> PERSISTENT_SYNTHETIC_RECIPE_IDS = new java.util.HashSet<>();

	private VirtualRetrievalRecipeBookEntries() {
	}

	/**
	 * Appends an entry for each item no known recipe makes that is nearby or
	 * that this world has seen, so the book can retrieve it. Shown in
	 * Retrieval Mode, and in the normal book too when Retrieval-Only Recipes
	 * says so (RecipeBookSmartSorter places them after everything craftable).
	 *
	 * <p>Vanilla rebuilds the book list on every update (a tab, a search key,
	 * an inventory change), so this runs that often. Only the tab and search
	 * filter are redone each time; the candidates themselves are rebuilt
	 * only when what they depend on changes (see candidates).</p>
	 */
	public static List<RecipeCollection> injectCollections(RecipeBookComponent<?> component, List<RecipeCollection> collections) {
		boolean retrievalMode = ExistingOutputRetrievalController.isEnabled();
		ReachCraftingConfig config = ReachCraftingConfig.get();
		boolean shownHere = retrievalMode
			|| config.retrievalOnlyRecipes() == ReachCraftingConfig.RetrievalOnlyRecipes.RETRIEVAL_AND_CRAFTING;
		if (!shownHere || !config.enableExistingOutputRetrieval()) {
			ReachCraftingMod.diag("[retrieval_virtual] inject skipped reason=mode_disabled retrieval_enabled={} config_enabled={} setting={}", retrievalMode, config.enableExistingOutputRetrieval(), config.retrievalOnlyRecipes());
			return collections;
		}
		RecipeBookComponentAccessor accessor = (RecipeBookComponentAccessor) component;
		if (accessor.getFilterButton().isStateTriggered()) {
			ReachCraftingMod.diag("[retrieval_virtual] inject skipped reason=craftable_filter_on");
			return collections;
		}

		Minecraft minecraft = accessor.getMinecraft();
		if (minecraft == null) {
			ReachCraftingMod.diag("[retrieval_virtual] inject skipped reason=no_minecraft");
			return collections;
		}
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			ReachCraftingMod.diag("[retrieval_virtual] inject skipped reason=unsupported_screen screen={}", screen != null ? screen.getClass().getSimpleName() : "null");
			return collections;
		}
		int gridSlotCount = screen instanceof InventoryScreen ? 4 : 9;
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.getCameraEntity() == null) {
			ReachCraftingMod.diag("[retrieval_virtual] inject skipped reason=missing_context player={} level={} camera={}", player != null, minecraft.level != null, minecraft.getCameraEntity() != null);
			return collections;
		}
		if (!ReachCraftingConfig.get().enableNearbyContainerUsage() || !ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			ReachCraftingMod.diag("[retrieval_virtual] inject skipped reason=config_nearby_disabled nearby_enabled={} cache_enabled={}", ReachCraftingConfig.get().enableNearbyContainerUsage(), ReachCraftingConfig.get().cacheContainersForFasterSearch());
			return collections;
		}

		Set<String> currentlyHeldItemIds = collectHeldItemIds(player);
		config.noteExperiencedItemIds(currentlyHeldItemIds);
		List<Candidate> candidates = candidates(minecraft, player, gridSlotCount, currentlyHeldItemIds);
		if (candidates.isEmpty()) {
			ReachCraftingMod.diag("[retrieval_virtual] inject skipped reason=no_retrieval_candidates");
			return collections;
		}

		String search = accessor.getSearchBox() != null ? accessor.getSearchBox().getValue().trim().toLowerCase(Locale.ROOT) : "";
		Object selectedCategory = accessor.getSelectedTab() != null ? accessor.getSelectedTab().getCategory() : null;
		List<RecipeCollection> synthetic = new ArrayList<>();
		for (Candidate candidate : candidates) {
			if (matchesSelectedCategory(selectedCategory, candidate.category()) && candidate.matchesSearch(search)) {
				synthetic.add(candidate.collection());
			}
		}
		if (synthetic.isEmpty()) {
			ReachCraftingMod.diag("[retrieval_virtual] inject produced no synthetic collections candidates={} search='{}'", candidates.size(), search);
			return collections;
		}

		List<RecipeCollection> combined = new ArrayList<>(collections.size() + synthetic.size());
		combined.addAll(collections);
		combined.addAll(synthetic);
		ReachCraftingMod.diag("[retrieval_virtual] inject success synthetic={} combined={} candidates={} retrieval_mode={} search='{}'", synthetic.size(), combined.size(), candidates.size(), retrievalMode, search);
		return combined;
	}

	/** One item the book can offer to retrieve, ready to filter by tab and search. */
	private record Candidate(String itemId, RecipeBookCategory category, String lowerName, RecipeCollection collection) {
		boolean matchesSearch(String search) {
			return search == null || search.isBlank() || lowerName.contains(search) || itemId.contains(search);
		}
	}

	/** Everything the candidate list depends on; it is rebuilt only when this changes. */
	private record CandidatesKey(
		int levelId,
		String language,
		Set<String> craftingOutputIds,
		Map<String, Integer> nearbyCounts,
		Set<String> experiencedItemIds
	) {
	}

	/** Recipes known and the grid size decide which items a real recipe makes. */
	private record OutputIdsKey(int levelId, int knownRecipes, int gridSlotCount) {
	}

	private static CandidatesKey candidatesKey;
	private static List<Candidate> cachedCandidates = List.of();
	private static OutputIdsKey outputIdsKey;
	private static Set<String> cachedOutputIds = Set.of();

	/**
	 * The items to offer, sorted by name: nearby ones (with their count and a
	 * green dot) and the ones this world has seen, minus anything a known
	 * recipe makes. Cached: walking around or reopening the book reuses it,
	 * and the scan over every known recipe only reruns on a recipe unlock or
	 * a grid size change.
	 */
	private static List<Candidate> candidates(Minecraft minecraft, LocalPlayer player, int gridSlotCount, Set<String> heldItemIds) {
		int levelId = System.identityHashCode(minecraft.level);
		Set<String> outputIds = craftingOutputIds(player, gridSlotCount, levelId);
		Map<String, Integer> nearbyCounts = NearbyContainerCache.getReachableView(
			minecraft.level,
			minecraft.getCameraEntity(),
			player.blockInteractionRange()
		).aggregateCounts();
		Set<String> experiencedItemIds = new java.util.LinkedHashSet<>(ReachCraftingConfig.get().experiencedItemIds());
		experiencedItemIds.addAll(heldItemIds);
		CandidatesKey key = new CandidatesKey(
			levelId, minecraft.getLanguageManager().getSelected(), outputIds, nearbyCounts, experiencedItemIds);
		if (key.equals(candidatesKey)) {
			return cachedCandidates;
		}

		long startNanos = System.nanoTime();
		List<Candidate> built = new ArrayList<>();
		Set<String> usedCacheKeys = new HashSet<>();
		Set<String> candidateItemIds = new java.util.LinkedHashSet<>(nearbyCounts.keySet());
		candidateItemIds.addAll(experiencedItemIds);
		for (String itemId : candidateItemIds) {
			if (outputIds.contains(itemId)) {
				continue;
			}
			Item item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.parse(itemId)).orElse(null);
			if (item == null) {
				continue;
			}
			ItemStack stack = new ItemStack(item);
			boolean hasLiveNearbyBacking = nearbyCounts.containsKey(itemId);
			int displayCount = hasLiveNearbyBacking
				? Math.min(Math.max(nearbyCounts.get(itemId), 1), stack.getMaxStackSize())
				: 1;
			usedCacheKeys.add(syntheticCacheKey(itemId, displayCount, hasLiveNearbyBacking));
			RecipeBookCategory category = categoryFor(item, itemId);
			built.add(new Candidate(
				itemId,
				category,
				stack.getHoverName().getString().toLowerCase(Locale.ROOT),
				syntheticCollectionFor(itemId, item, displayCount, hasLiveNearbyBacking, category)
			));
		}
		built.sort(Comparator.comparing(Candidate::lowerName));
		// Entries for counts no chest holds any more would otherwise pile up
		// for the whole session.
		SYNTHETIC_CACHE.keySet().retainAll(usedCacheKeys);
		candidatesKey = key;
		cachedCandidates = List.copyOf(built);
		ReachCraftingMod.diag(
			"[retrieval_virtual] candidates_rebuilt candidates={} nearby_items={} experienced_items={} crafting_outputs={} us={}",
			cachedCandidates.size(), nearbyCounts.size(), experiencedItemIds.size(), outputIds.size(),
			(System.nanoTime() - startNanos) / 1_000L
		);
		return cachedCandidates;
	}

	private static Set<String> craftingOutputIds(LocalPlayer player, int gridSlotCount, int levelId) {
		int knownRecipes = 0;
		for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
			knownRecipes += collection.getRecipes().size();
		}
		OutputIdsKey key = new OutputIdsKey(levelId, knownRecipes, gridSlotCount);
		if (!key.equals(outputIdsKey)) {
			outputIdsKey = key;
			cachedOutputIds = Set.copyOf(collectCraftingTableOutputIds(player, gridSlotCount));
		}
		return cachedOutputIds;
	}

	private static String syntheticCacheKey(String itemId, int displayCount, boolean hasLiveNearbyBacking) {
		return itemId + ":" + displayCount + ":" + hasLiveNearbyBacking;
	}

	private static RecipeCollection syntheticCollectionFor(String itemId, Item item, int displayCount, boolean hasLiveNearbyBacking, RecipeBookCategory category) {
		return SYNTHETIC_CACHE.computeIfAbsent(syntheticCacheKey(itemId, displayCount, hasLiveNearbyBacking), k -> {
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
	}

	/**
	 * Dev harness only: the synthetic (no-recipe) entry the retrieval-mode
	 * book would show for this item, built the same way {@link #injectCollections}
	 * builds it, so scripted clicks on it exercise the real synthetic paths.
	 * Null when the item id is unknown.
	 */
	static RecipeCollection harnessSyntheticCollection(String itemId) {
		Item item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.parse(itemId)).orElse(null);
		if (item == null) {
			return null;
		}
		Minecraft minecraft = Minecraft.getInstance();
		Map<String, Integer> nearbyCounts = minecraft.level != null && minecraft.getCameraEntity() != null && minecraft.player != null
			? NearbyContainerCache.getReachableView(minecraft.level, minecraft.getCameraEntity(), minecraft.player.blockInteractionRange()).aggregateCounts()
			: Map.of();
		boolean hasLiveNearbyBacking = nearbyCounts.containsKey(itemId);
		ItemStack stack = new ItemStack(item);
		int displayCount = hasLiveNearbyBacking ? Math.min(Math.max(nearbyCounts.get(itemId), 1), stack.getMaxStackSize()) : 1;
		return syntheticCollectionFor(itemId, item, displayCount, hasLiveNearbyBacking, categoryFor(item, itemId));
	}

	/**
	 * Membership, not a range: real recipe display ids are not guaranteed
	 * small (a singleplayer world handed out 1326191332 for a stained glass
	 * pane), and a range test made that click read as a synthetic entry, so
	 * it retrieved the collection's first colour instead of the clicked one.
	 */
	static boolean isSyntheticRecipeId(RecipeDisplayId recipeId) {
		return recipeId != null
			&& (LIVE_SYNTHETIC_RECIPE_IDS.contains(recipeId.index()) || PERSISTENT_SYNTHETIC_RECIPE_IDS.contains(recipeId.index()));
	}

	static boolean hasLiveNearbyBacking(RecipeDisplayId recipeId) {
		return recipeId != null && LIVE_SYNTHETIC_RECIPE_IDS.contains(recipeId.index());
	}

	static int requestCountForSynthetic(ItemStack stack, boolean shiftRequested) {
		if (stack == null || stack.isEmpty()) {
			return 1;
		}
		// Shift = all of it; the session stops when nearby stock runs out.
		return shiftRequested ? RecipeClickExecutor.bulkRecipeQueueLimit() : 1;
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

	// Room for each id family above its base without crossing Integer.MAX_VALUE:
	// live ids sit in [1.0e9, 1.4e9), persistent-only in [1.5e9, 1.9e9).
	private static final int SYNTHETIC_HASH_SPAN = 400_000_000;

	static RecipeDisplayId syntheticIdFor(String itemId, boolean hasLiveNearbyBacking) {
		// floorMod, not abs: base + abs(hash) overflowed to a NEGATIVE index
		// for any item whose hash exceeded ~1.1e9 ("minecraft:egg" did), and a
		// negative index fails isSyntheticRecipeId, so clicks on that entry
		// silently took the real-recipe path instead of the synthetic one.
		int hash = Math.floorMod(itemId.hashCode(), SYNTHETIC_HASH_SPAN);
		int base = hasLiveNearbyBacking ? SYNTHETIC_RECIPE_ID_BASE : PERSISTENT_ONLY_SYNTHETIC_RECIPE_ID_BASE;
		int index = base + hash;
		if (hasLiveNearbyBacking) {
			LIVE_SYNTHETIC_RECIPE_IDS.add(index);
			PERSISTENT_SYNTHETIC_RECIPE_IDS.remove(index);
		} else {
			PERSISTENT_SYNTHETIC_RECIPE_IDS.add(index);
			LIVE_SYNTHETIC_RECIPE_IDS.remove(index);
		}
		return new RecipeDisplayId(index);
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
