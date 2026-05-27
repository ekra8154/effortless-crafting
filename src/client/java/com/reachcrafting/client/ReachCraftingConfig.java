package com.reachcrafting.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.reachcrafting.ReachCraftingMod;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

public final class ReachCraftingConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("effortless-crafting.json");
	private static final boolean DEFAULT_ENABLED = true;
	private static final boolean DEFAULT_ENABLE_NEARBY_CONTAINER_USAGE = true;
	private static final boolean DEFAULT_REDISTRIBUTE_TO_CRAFT_WHEN_NEEDED = true;
	private static final InWorldFilterMode DEFAULT_IN_WORLD_FILTER_MODE = InWorldFilterMode.NONE;
	private static final RevolvingCraftHandling DEFAULT_REVOLVING_CRAFT_HANDLING = RevolvingCraftHandling.SPECIFIC_VARIANT_ONLY;
	private static final IngredientPlanning.CountPreference DEFAULT_COUNT_PREFERENCE = IngredientPlanning.CountPreference.HIGHEST_TOTAL;
	private static final boolean DEFAULT_SHOW_NEARBY_CRAFTABLE_INDICATOR = true;
	private static final boolean DEFAULT_CACHE_CONTAINERS_FOR_FASTER_SEARCH = true;
	private static final boolean DEFAULT_REACH_CRAFT_HOLD_AND_RELEASE = true;
	private static final boolean DEFAULT_REACH_CRAFT_CLOSE_OVERLAY_AFTER_RELEASE = true;
	private static final boolean DEFAULT_REACH_CRAFT_PREFER_INVENTORY = true;
	private static final boolean DEFAULT_PUT_PULLED_RESOURCES_BACK = true;
	private static final boolean DEFAULT_RESTORE_INVENTORY_ITEM_POSITIONS = true;
	private static final SearchHistoryMode DEFAULT_SEARCH_HISTORY_MODE = SearchHistoryMode.ON;
	private static final RecipeBookSortingMode DEFAULT_RECIPE_BOOK_SORTING_MODE = RecipeBookSortingMode.SMART;
	private static final AutoFocusSearchMode DEFAULT_AUTO_FOCUS_SEARCH_MODE = AutoFocusSearchMode.DISABLED;
	private static final OutlineDisplayMode DEFAULT_SHOW_FILTER_OUTLINES = OutlineDisplayMode.KEYBIND;
	private static final boolean DEFAULT_AUTO_CRAFT_ENABLED = false;
	private static final AutoCraftMode DEFAULT_AUTO_CRAFT_ENABLED_MODE = AutoCraftMode.NORMAL;
	private static final boolean DEFAULT_SHOW_TOTAL_OUTPUT_COUNTS = true;
	private static final InputCounterVisibility DEFAULT_INPUT_COUNTER_VISIBILITY = InputCounterVisibility.SHOW_WHILE_QUEUEING;
	private static final boolean DEFAULT_INVENTORY_2X2_OFFHAND_CONSOLIDATION = true;
	private static final ScrollToPullMode DEFAULT_SCROLL_TO_PULL_MODE = ScrollToPullMode.WHILE_RESULT_OR_INVENTORY_SLOT_HOVERED;
	private static final boolean DEFAULT_TYPE_TO_FOCUS_SEARCH = true;
	private static final boolean DEFAULT_EJECT_ITEMS_WHEN_FULL = true;
	private static final AutoCraftCapability DEFAULT_AUTO_CRAFT_CAPABILITY = AutoCraftCapability.BULK;
	private static final boolean DEFAULT_AUTO_CRAFT_OFF_AFTER_BULK = false;
	private static final boolean DEFAULT_BULK_VARIANT_SWITCHING = false;
	private static final AutoCraftHandling DEFAULT_AUTO_CRAFT_HANDLING = AutoCraftHandling.HOLD;
	private static final ChainCraftingMode DEFAULT_CHAIN_CRAFTING_MODE = ChainCraftingMode.CONFIRM;
	private static final boolean DEFAULT_SHOW_CRAFT_ABORTED_MESSAGE = true;
	private static final boolean DEFAULT_SHOW_BULK_CRAFT_SUMMARY_MESSAGE = true;
	private static final boolean DEFAULT_SHOW_MISSING_INGREDIENTS_MESSAGE = true;
	private static final boolean DEFAULT_SHOW_CHAIN_CRAFT_MESSAGES = true;
	private static final boolean DEFAULT_ALT_AS_REQUEST_KEY = true;
	private static final boolean DEFAULT_ALT_CLICK_INSTANT_CRAFT = true;
	private static final boolean DEFAULT_DEBUG_MESSAGES_ENABLED = false;
	public static final List<String> DEFAULT_BLACKLIST = List.of(
		"minecraft:ender_chest",
		"minecraft:hopper",
		"minecraft:copper_chest",
		"minecraft:exposed_copper_chest",
		"minecraft:weathered_copper_chest",
		"minecraft:oxidized_copper_chest",
		"minecraft:waxed_copper_chest",
		"minecraft:waxed_exposed_copper_chest",
		"minecraft:waxed_weathered_copper_chest",
		"minecraft:waxed_oxidized_copper_chest"
	);
	private static ReachCraftingConfig instance = defaults();
	private static final int MAX_SEARCH_HISTORY = 20;

	private boolean enabled;
	private boolean enableNearbyContainerUsage;
	private boolean redistributeToCraftWhenNeeded;
	private InWorldFilterMode inWorldFilterMode;
	private RevolvingCraftHandling revolvingCraftHandling;
	private IngredientPlanning.CountPreference countPreference;
	private boolean showNearbyCraftableIndicator;
	private boolean cacheContainersForFasterSearch;
	private boolean reachCraftHoldAndRelease;
	private boolean reachCraftCloseOverlayAfterRelease;
	private boolean reachCraftPreferInventory;
	private boolean putPulledResourcesBack;
	private boolean restoreInventoryItemPositions;
	private SearchHistoryMode searchHistoryMode;
	private RecipeBookSortingMode recipeBookSortingMode;
	private AutoFocusSearchMode autoFocusSearchMode;
	private OutlineDisplayMode showFilterOutlines;
	private boolean autoCraftEnabled;
	private AutoCraftMode autoCraftEnabledMode;
	private boolean showTotalOutputCounts;
	private InputCounterVisibility inputCounterVisibility;
	private boolean inventory2x2OffhandConsolidation;
	private ScrollToPullMode scrollToPullMode;
	private boolean typeToFocusSearch;
	private boolean ejectItemsWhenFull;
	private AutoCraftCapability autoCraftCapability;
	private boolean autoCraftOffAfterBulk;
	private boolean bulkVariantSwitching;
	private AutoCraftHandling autoCraftHandling;
	private ChainCraftingMode chainCraftingMode;
	private boolean showCraftAbortedMessage;
	private boolean showBulkCraftSummaryMessage;
	private boolean showMissingIngredientsMessage;
	private boolean showChainCraftMessages;
	private boolean altAsRequestKey;
	private boolean altClickInstantCraft;
	private boolean debugMessagesEnabled;
	private Set<String> blacklistedContainerIds;
	private List<Integer> recentRecipeDisplayIds;
	private Map<String, List<Integer>> recentRecipeDisplayIdsByContext;

	private static String lastSearchText = "";
	private static final List<String> searchHistory = new ArrayList<>();

	private ReachCraftingConfig() {
	}

	public static void load() {
		instance = defaults();
		if (Files.notExists(CONFIG_PATH)) {
			save();
			return;
		}

		try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
			StoredConfig stored = GSON.fromJson(reader, StoredConfig.class);
			if (stored == null) {
				save();
				return;
			}
			instance.enabled = stored.enabled != null ? stored.enabled : DEFAULT_ENABLED;
			instance.enableNearbyContainerUsage = stored.enableNearbyContainerUsage != null ? stored.enableNearbyContainerUsage : DEFAULT_ENABLE_NEARBY_CONTAINER_USAGE;
			instance.redistributeToCraftWhenNeeded = stored.redistributeToCraftWhenNeeded != null ? stored.redistributeToCraftWhenNeeded : DEFAULT_REDISTRIBUTE_TO_CRAFT_WHEN_NEEDED;
			instance.inWorldFilterMode = stored.inWorldFilterMode != null ? stored.inWorldFilterMode : DEFAULT_IN_WORLD_FILTER_MODE;
			instance.revolvingCraftHandling = stored.revolvingCraftHandling != null
				? stored.revolvingCraftHandling
				: DEFAULT_REVOLVING_CRAFT_HANDLING;
			instance.countPreference = stored.countPreference != null
				? stored.countPreference
				: DEFAULT_COUNT_PREFERENCE;
			instance.showNearbyCraftableIndicator = stored.showNearbyCraftableIndicator != null ? stored.showNearbyCraftableIndicator : DEFAULT_SHOW_NEARBY_CRAFTABLE_INDICATOR;
			instance.cacheContainersForFasterSearch = stored.cacheContainersForFasterSearch != null
				? stored.cacheContainersForFasterSearch
				: DEFAULT_CACHE_CONTAINERS_FOR_FASTER_SEARCH;
			instance.reachCraftHoldAndRelease = stored.reachCraftHoldAndRelease != null ? stored.reachCraftHoldAndRelease : DEFAULT_REACH_CRAFT_HOLD_AND_RELEASE;
			instance.reachCraftCloseOverlayAfterRelease = stored.reachCraftCloseOverlayAfterRelease != null ? stored.reachCraftCloseOverlayAfterRelease : DEFAULT_REACH_CRAFT_CLOSE_OVERLAY_AFTER_RELEASE;
			instance.reachCraftPreferInventory = stored.reachCraftPreferInventory != null ? stored.reachCraftPreferInventory : DEFAULT_REACH_CRAFT_PREFER_INVENTORY;
			instance.putPulledResourcesBack = stored.putPulledResourcesBack != null ? stored.putPulledResourcesBack : DEFAULT_PUT_PULLED_RESOURCES_BACK;
			instance.restoreInventoryItemPositions = stored.restoreInventoryItemPositions != null ? stored.restoreInventoryItemPositions : DEFAULT_RESTORE_INVENTORY_ITEM_POSITIONS;
			instance.searchHistoryMode = stored.searchHistoryMode != null
				? stored.searchHistoryMode
				: (stored.rememberPreviousSearch != null
					? (stored.rememberPreviousSearch ? SearchHistoryMode.ON_AND_RESTORE_LAST_SEARCH : SearchHistoryMode.OFF)
					: DEFAULT_SEARCH_HISTORY_MODE);
			instance.recipeBookSortingMode = stored.recipeBookSortingMode != null ? stored.recipeBookSortingMode : DEFAULT_RECIPE_BOOK_SORTING_MODE;
			instance.autoFocusSearchMode = stored.autoFocusSearchMode != null ? stored.autoFocusSearchMode : DEFAULT_AUTO_FOCUS_SEARCH_MODE;
			instance.showFilterOutlines = stored.showFilterOutlines != null ? stored.showFilterOutlines : DEFAULT_SHOW_FILTER_OUTLINES;
			instance.showTotalOutputCounts = stored.showTotalOutputCounts != null ? stored.showTotalOutputCounts : DEFAULT_SHOW_TOTAL_OUTPUT_COUNTS;
			instance.inputCounterVisibility = stored.inputCounterVisibility != null ? stored.inputCounterVisibility : DEFAULT_INPUT_COUNTER_VISIBILITY;
			instance.inventory2x2OffhandConsolidation = stored.inventory2x2OffhandConsolidation != null ? stored.inventory2x2OffhandConsolidation : DEFAULT_INVENTORY_2X2_OFFHAND_CONSOLIDATION;
			instance.scrollToPullMode = parseScrollToPullMode(stored.scrollToPullMode);
			instance.typeToFocusSearch = stored.typeToFocusSearch != null ? stored.typeToFocusSearch : DEFAULT_TYPE_TO_FOCUS_SEARCH;
			instance.ejectItemsWhenFull = stored.ejectItemsWhenFull != null ? stored.ejectItemsWhenFull : DEFAULT_EJECT_ITEMS_WHEN_FULL;
			instance.autoCraftEnabled = stored.autoCraftEnabled != null ? stored.autoCraftEnabled : (stored.autoCraftMode != null ? stored.autoCraftMode : DEFAULT_AUTO_CRAFT_ENABLED);
			instance.autoCraftEnabledMode = stored.autoCraftEnabledMode != null ? stored.autoCraftEnabledMode : DEFAULT_AUTO_CRAFT_ENABLED_MODE;
			instance.autoCraftCapability = stored.autoCraftCapability != null ? stored.autoCraftCapability : (stored.enableEnablingBulkMode != null ? (stored.enableEnablingBulkMode ? AutoCraftCapability.BULK : AutoCraftCapability.NORMAL) : DEFAULT_AUTO_CRAFT_CAPABILITY);
			instance.autoCraftOffAfterBulk = stored.autoCraftOffAfterBulk != null ? stored.autoCraftOffAfterBulk : DEFAULT_AUTO_CRAFT_OFF_AFTER_BULK;
			instance.bulkVariantSwitching = stored.bulkVariantSwitching != null ? stored.bulkVariantSwitching : DEFAULT_BULK_VARIANT_SWITCHING;
			instance.autoCraftHandling = stored.autoCraftHandling != null ? stored.autoCraftHandling : DEFAULT_AUTO_CRAFT_HANDLING;
			instance.chainCraftingMode = stored.chainCraftingMode != null ? stored.chainCraftingMode : DEFAULT_CHAIN_CRAFTING_MODE;
			instance.showCraftAbortedMessage = stored.showCraftAbortedMessage != null ? stored.showCraftAbortedMessage : DEFAULT_SHOW_CRAFT_ABORTED_MESSAGE;
			instance.showBulkCraftSummaryMessage = stored.showBulkCraftSummaryMessage != null ? stored.showBulkCraftSummaryMessage : DEFAULT_SHOW_BULK_CRAFT_SUMMARY_MESSAGE;
			instance.showMissingIngredientsMessage = stored.showMissingIngredientsMessage != null ? stored.showMissingIngredientsMessage : DEFAULT_SHOW_MISSING_INGREDIENTS_MESSAGE;
			instance.showChainCraftMessages = stored.showChainCraftMessages != null ? stored.showChainCraftMessages : DEFAULT_SHOW_CHAIN_CRAFT_MESSAGES;
			instance.altAsRequestKey = stored.altAsRequestKey != null ? stored.altAsRequestKey : DEFAULT_ALT_AS_REQUEST_KEY;
			instance.altClickInstantCraft = stored.altClickInstantCraft != null ? stored.altClickInstantCraft : DEFAULT_ALT_CLICK_INSTANT_CRAFT;
			instance.debugMessagesEnabled = stored.debugMessagesEnabled != null ? stored.debugMessagesEnabled : DEFAULT_DEBUG_MESSAGES_ENABLED;
			
			// Enforce capability gate on load
			if (instance.autoCraftCapability == AutoCraftCapability.NONE) {
				instance.autoCraftEnabled = false;
			}
			if (instance.autoCraftCapability != AutoCraftCapability.BULK && instance.autoCraftEnabledMode == AutoCraftMode.BULK) {
				instance.autoCraftEnabledMode = AutoCraftMode.NORMAL;
			}

			instance.blacklistedContainerIds = stored.blacklistedContainerIds != null 
				? new LinkedHashSet<>(stored.blacklistedContainerIds) 
				: new LinkedHashSet<>(DEFAULT_BLACKLIST);
			instance.recentRecipeDisplayIds = stored.recentRecipeDisplayIds != null
				? new ArrayList<>(stored.recentRecipeDisplayIds)
				: new ArrayList<>();
			trimRecentRecipeDisplayIds(instance.recentRecipeDisplayIds);
			instance.recentRecipeDisplayIdsByContext = normalizeRecentRecipeContexts(stored.recentRecipeDisplayIdsByContext);
			if (instance.searchHistoryMode == SearchHistoryMode.OFF) {
				clearSearchHistory();
			}
		} catch (Exception exception) {
			ReachCraftingMod.LOGGER.warn("Failed to load reachcrafting config from {}", CONFIG_PATH, exception);
			instance = defaults();
			save();
		}
	}

	public static void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
				GSON.toJson(new StoredConfig(instance), writer);
			}
		} catch (Exception exception) {
			ReachCraftingMod.LOGGER.warn("Failed to save reachcrafting config to {}", CONFIG_PATH, exception);
		}
	}

	public static ReachCraftingConfig get() {
		return instance;
	}

	public boolean enabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean enableNearbyContainerUsage() {
		return enableNearbyContainerUsage;
	}

	public void setEnableNearbyContainerUsage(boolean enableNearbyContainerUsage) {
		this.enableNearbyContainerUsage = enableNearbyContainerUsage;
		if (!enableNearbyContainerUsage) {
			NearbyContainerCache.clear();
		}
	}

	public IngredientPlanning.Policy toPlanningPolicy() {
		return new IngredientPlanning.Policy(countPreference, redistributeToCraftWhenNeeded, reachCraftPreferInventory);
	}

	public boolean redistributeToCraftWhenNeeded() {
		return redistributeToCraftWhenNeeded;
	}

	public void setRedistributeToCraftWhenNeeded(boolean redistributeToCraftWhenNeeded) {
		this.redistributeToCraftWhenNeeded = redistributeToCraftWhenNeeded;
	}

	public InWorldFilterMode inWorldFilterMode() {
		return inWorldFilterMode;
	}

	public void setInWorldFilterMode(InWorldFilterMode inWorldFilterMode) {
		this.inWorldFilterMode = inWorldFilterMode;
	}

	public RevolvingCraftHandling revolvingCraftHandling() {
		return revolvingCraftHandling;
	}

	public void setRevolvingCraftHandling(RevolvingCraftHandling revolvingCraftHandling) {
		this.revolvingCraftHandling = revolvingCraftHandling;
	}

	public IngredientPlanning.CountPreference countPreference() {
		return countPreference;
	}

	public void setCountPreference(IngredientPlanning.CountPreference countPreference) {
		this.countPreference = countPreference;
	}

	public boolean showNearbyCraftableIndicator() {
		return showNearbyCraftableIndicator;
	}

	public void setShowNearbyCraftableIndicator(boolean showNearbyCraftableIndicator) {
		this.showNearbyCraftableIndicator = showNearbyCraftableIndicator;
	}

	public boolean cacheContainersForFasterSearch() {
		return cacheContainersForFasterSearch;
	}

	public void setCacheContainersForFasterSearch(boolean cacheContainersForFasterSearch) {
		this.cacheContainersForFasterSearch = cacheContainersForFasterSearch;
		if (!cacheContainersForFasterSearch) {
			NearbyContainerCache.clear();
		}
	}

	public boolean reachCraftHoldAndRelease() {
		return reachCraftHoldAndRelease;
	}

	public void setReachCraftHoldAndRelease(boolean reachCraftHoldAndRelease) {
		this.reachCraftHoldAndRelease = reachCraftHoldAndRelease;
	}

	public boolean reachCraftCloseOverlayAfterRelease() {
		return reachCraftCloseOverlayAfterRelease;
	}

	public void setReachCraftCloseOverlayAfterRelease(boolean reachCraftCloseOverlayAfterRelease) {
		this.reachCraftCloseOverlayAfterRelease = reachCraftCloseOverlayAfterRelease;
	}

	public boolean reachCraftPreferInventory() {
		return reachCraftPreferInventory;
	}

	public void setReachCraftPreferInventory(boolean reachCraftPreferInventory) {
		this.reachCraftPreferInventory = reachCraftPreferInventory;
	}

	public SearchHistoryMode searchHistoryMode() {
		return searchHistoryMode;
	}

	public void setSearchHistoryMode(SearchHistoryMode searchHistoryMode) {
		this.searchHistoryMode = searchHistoryMode != null ? searchHistoryMode : DEFAULT_SEARCH_HISTORY_MODE;
		if (this.searchHistoryMode == SearchHistoryMode.OFF) {
			clearSearchHistory();
		}
	}

	public boolean putPulledResourcesBack() {
		return putPulledResourcesBack;
	}

	public void setPutPulledResourcesBack(boolean putPulledResourcesBack) {
		this.putPulledResourcesBack = putPulledResourcesBack;
	}

	public boolean restoreInventoryItemPositions() {
		return restoreInventoryItemPositions;
	}

	public void setRestoreInventoryItemPositions(boolean restoreInventoryItemPositions) {
		this.restoreInventoryItemPositions = restoreInventoryItemPositions;
	}

	public OutlineDisplayMode showFilterOutlines() {
		return showFilterOutlines;
	}

	public void setShowFilterOutlines(OutlineDisplayMode showFilterOutlines) {
		this.showFilterOutlines = showFilterOutlines;
	}

	public boolean autoCraftEnabled() {
		if (autoCraftHandling == AutoCraftHandling.HOLD) {
			return AutoCraftController.isHoldRuntimeEnabled() && autoCraftCapability != AutoCraftCapability.NONE;
		}
		return autoCraftEnabled && autoCraftCapability != AutoCraftCapability.NONE;
	}

	public void setAutoCraftEnabled(boolean autoCraftEnabled) {
		if (autoCraftCapability == AutoCraftCapability.NONE) {
			autoCraftEnabled = false;
		}
		this.autoCraftEnabled = autoCraftEnabled;
		if (!autoCraftEnabled) {
			this.autoCraftEnabledMode = AutoCraftMode.NORMAL;
			BulkAutoCraftController.clear();
		}
	}

	public AutoCraftMode autoCraftEnabledMode() {
		return autoCraftEnabledMode;
	}

	public void setAutoCraftEnabledMode(AutoCraftMode autoCraftEnabledMode) {
		if (autoCraftCapability != AutoCraftCapability.BULK && autoCraftEnabledMode == AutoCraftMode.BULK) {
			autoCraftEnabledMode = AutoCraftMode.NORMAL;
		}
		this.autoCraftEnabledMode = autoCraftEnabledMode != null ? autoCraftEnabledMode : DEFAULT_AUTO_CRAFT_ENABLED_MODE;
	}

	public AutoCraftCapability autoCraftCapability() {
		return autoCraftCapability;
	}

	public void setAutoCraftCapability(AutoCraftCapability capability) {
		this.autoCraftCapability = capability != null ? capability : DEFAULT_AUTO_CRAFT_CAPABILITY;
		if (this.autoCraftCapability == AutoCraftCapability.NONE) {
			this.autoCraftEnabled = false;
			this.autoCraftEnabledMode = AutoCraftMode.NORMAL;
			BulkAutoCraftController.clear();
		} else if (this.autoCraftCapability != AutoCraftCapability.BULK && this.autoCraftEnabledMode == AutoCraftMode.BULK) {
			this.autoCraftEnabledMode = AutoCraftMode.NORMAL;
			BulkAutoCraftController.clear();
		}
	}

	public boolean isBulkAutoCraftMode() {
		return autoCraftEnabled() && autoCraftEnabledMode == AutoCraftMode.BULK && autoCraftCapability == AutoCraftCapability.BULK;
	}

	public boolean showTotalOutputCounts() {
		return showTotalOutputCounts;
	}

	public void setShowTotalOutputCounts(boolean showTotalOutputCounts) {
		this.showTotalOutputCounts = showTotalOutputCounts;
	}

	public InputCounterVisibility inputCounterVisibility() {
		return inputCounterVisibility;
	}

	public void setInputCounterVisibility(InputCounterVisibility inputCounterVisibility) {
		this.inputCounterVisibility = inputCounterVisibility;
	}

	public boolean inventory2x2OffhandConsolidation() {
		return inventory2x2OffhandConsolidation;
	}

	public void setInventory2x2OffhandConsolidation(boolean inventory2x2OffhandConsolidation) {
		this.inventory2x2OffhandConsolidation = inventory2x2OffhandConsolidation;
	}

	public ScrollToPullMode scrollToPullMode() {
		return scrollToPullMode;
	}

	public void setScrollToPullMode(ScrollToPullMode scrollToPullMode) {
		this.scrollToPullMode = scrollToPullMode;
	}

	public boolean typeToFocusSearch() {
		return typeToFocusSearch;
	}

	public void setTypeToFocusSearch(boolean typeToFocusSearch) {
		this.typeToFocusSearch = typeToFocusSearch;
	}

	public boolean ejectItemsWhenFull() {
		return ejectItemsWhenFull;
	}

	public void setEjectItemsWhenFull(boolean ejectItemsWhenFull) {
		this.ejectItemsWhenFull = ejectItemsWhenFull;
	}

	public boolean autoCraftOffAfterBulk() {
		return autoCraftOffAfterBulk;
	}

	public void setAutoCraftOffAfterBulk(boolean autoCraftOffAfterBulk) {
		this.autoCraftOffAfterBulk = autoCraftOffAfterBulk;
	}

	public boolean bulkVariantSwitching() {
		return bulkVariantSwitching;
	}

	public void setBulkVariantSwitching(boolean bulkVariantSwitching) {
		this.bulkVariantSwitching = bulkVariantSwitching;
	}

	public AutoCraftHandling autoCraftHandling() {
		return autoCraftHandling;
	}

	public void setAutoCraftHandling(AutoCraftHandling autoCraftHandling) {
		this.autoCraftHandling = autoCraftHandling != null ? autoCraftHandling : DEFAULT_AUTO_CRAFT_HANDLING;
	}

	public ChainCraftingMode chainCraftingMode() {
		return chainCraftingMode;
	}

	public void setChainCraftingMode(ChainCraftingMode chainCraftingMode) {
		this.chainCraftingMode = chainCraftingMode != null ? chainCraftingMode : DEFAULT_CHAIN_CRAFTING_MODE;
	}

	public boolean showCraftAbortedMessage() {
		return showCraftAbortedMessage;
	}

	public void setShowCraftAbortedMessage(boolean showCraftAbortedMessage) {
		this.showCraftAbortedMessage = showCraftAbortedMessage;
	}

	public boolean showBulkCraftSummaryMessage() {
		return showBulkCraftSummaryMessage;
	}

	public void setShowBulkCraftSummaryMessage(boolean showBulkCraftSummaryMessage) {
		this.showBulkCraftSummaryMessage = showBulkCraftSummaryMessage;
	}

	public boolean showMissingIngredientsMessage() {
		return showMissingIngredientsMessage;
	}

	public void setShowMissingIngredientsMessage(boolean showMissingIngredientsMessage) {
		this.showMissingIngredientsMessage = showMissingIngredientsMessage;
	}

	public boolean showChainCraftMessages() {
		return showChainCraftMessages;
	}

	public void setShowChainCraftMessages(boolean showChainCraftMessages) {
		this.showChainCraftMessages = showChainCraftMessages;
	}

	public boolean altAsRequestKey() {
		return altAsRequestKey;
	}

	public void setAltAsRequestKey(boolean altAsRequestKey) {
		this.altAsRequestKey = altAsRequestKey;
	}

	public boolean altClickInstantCraft() {
		return altClickInstantCraft;
	}

	public void setAltClickInstantCraft(boolean altClickInstantCraft) {
		this.altClickInstantCraft = altClickInstantCraft;
	}

	public boolean debugMessagesEnabled() {
		return debugMessagesEnabled;
	}

	public void setDebugMessagesEnabled(boolean debugMessagesEnabled) {
		this.debugMessagesEnabled = debugMessagesEnabled;
	}

	public static String getLastSearchText() {
		return lastSearchText;
	}

	public static void setLastSearchText(String text) {
		if (get().searchHistoryMode() == SearchHistoryMode.OFF) {
			lastSearchText = "";
			return;
		}
		lastSearchText = text != null ? text : "";
	}

	public static void pushSearchHistory(String text) {
		if (!get().isSearchHistoryEnabled()) {
			return;
		}
		String normalized = text != null ? text.trim() : "";
		if (normalized.isEmpty()) {
			return;
		}
		searchHistory.remove(normalized);
		searchHistory.add(0, normalized);
		while (searchHistory.size() > MAX_SEARCH_HISTORY) {
			searchHistory.remove(searchHistory.size() - 1);
		}
	}

	public static int getSearchHistorySize() {
		return searchHistory.size();
	}

	public static String getSearchHistoryEntry(int index) {
		if (index < 0 || index >= searchHistory.size()) {
			return "";
		}
		return searchHistory.get(index);
	}

	public static void clearSearchHistory() {
		lastSearchText = "";
		searchHistory.clear();
	}

	public boolean isSearchHistoryEnabled() {
		return searchHistoryMode != SearchHistoryMode.OFF;
	}

	public boolean shouldRestoreLastSearch() {
		return searchHistoryMode == SearchHistoryMode.ON_AND_RESTORE_LAST_SEARCH;
	}

	public RecipeBookSortingMode recipeBookSortingMode() {
		return recipeBookSortingMode;
	}

	public void setRecipeBookSortingMode(RecipeBookSortingMode recipeBookSortingMode) {
		this.recipeBookSortingMode = recipeBookSortingMode != null ? recipeBookSortingMode : DEFAULT_RECIPE_BOOK_SORTING_MODE;
	}

	public AutoFocusSearchMode autoFocusSearchMode() {
		return autoFocusSearchMode;
	}

	public void setAutoFocusSearchMode(AutoFocusSearchMode autoFocusSearchMode) {
		this.autoFocusSearchMode = autoFocusSearchMode != null ? autoFocusSearchMode : DEFAULT_AUTO_FOCUS_SEARCH_MODE;
	}

	public List<Integer> recentRecipeDisplayIds() {
		String contextId = recipeHistoryContextId();
		if (contextId == null) {
			return List.of();
		}
		return List.copyOf(recentRecipeDisplayIdsByContext.getOrDefault(contextId, List.of()));
	}

	public void noteRecentRecipe(net.minecraft.world.item.crafting.display.RecipeDisplayId recipeId) {
		if (recipeId == null) {
			return;
		}
		String contextId = recipeHistoryContextId();
		if (contextId == null) {
			return;
		}
		List<Integer> contextRecent = new ArrayList<>(recentRecipeDisplayIdsByContext.getOrDefault(contextId, List.of()));
		contextRecent.remove(Integer.valueOf(recipeId.index()));
		contextRecent.add(0, recipeId.index());
		trimRecentRecipeDisplayIds(contextRecent);
		recentRecipeDisplayIdsByContext.put(contextId, contextRecent);
		save();
	}
	
	public Set<String> blacklistedContainerIds() {
		return Collections.unmodifiableSet(blacklistedContainerIds);
	}

	public void setBlacklistedContainerIds(Set<String> blacklistedContainerIds) {
		this.blacklistedContainerIds = new LinkedHashSet<>(blacklistedContainerIds);
		NearbyContainerCache.clear();
	}

	private static ReachCraftingConfig defaults() {
		ReachCraftingConfig defaults = new ReachCraftingConfig();
		defaults.enabled = DEFAULT_ENABLED;
		defaults.enableNearbyContainerUsage = DEFAULT_ENABLE_NEARBY_CONTAINER_USAGE;
		defaults.redistributeToCraftWhenNeeded = DEFAULT_REDISTRIBUTE_TO_CRAFT_WHEN_NEEDED;
		defaults.inWorldFilterMode = DEFAULT_IN_WORLD_FILTER_MODE;
		defaults.revolvingCraftHandling = DEFAULT_REVOLVING_CRAFT_HANDLING;
		defaults.countPreference = DEFAULT_COUNT_PREFERENCE;
		defaults.showNearbyCraftableIndicator = DEFAULT_SHOW_NEARBY_CRAFTABLE_INDICATOR;
		defaults.cacheContainersForFasterSearch = DEFAULT_CACHE_CONTAINERS_FOR_FASTER_SEARCH;
		defaults.reachCraftHoldAndRelease = DEFAULT_REACH_CRAFT_HOLD_AND_RELEASE;
		defaults.reachCraftCloseOverlayAfterRelease = DEFAULT_REACH_CRAFT_CLOSE_OVERLAY_AFTER_RELEASE;
		defaults.reachCraftPreferInventory = DEFAULT_REACH_CRAFT_PREFER_INVENTORY;
		defaults.putPulledResourcesBack = DEFAULT_PUT_PULLED_RESOURCES_BACK;
		defaults.restoreInventoryItemPositions = DEFAULT_RESTORE_INVENTORY_ITEM_POSITIONS;
		defaults.searchHistoryMode = DEFAULT_SEARCH_HISTORY_MODE;
		defaults.recipeBookSortingMode = DEFAULT_RECIPE_BOOK_SORTING_MODE;
		defaults.autoFocusSearchMode = DEFAULT_AUTO_FOCUS_SEARCH_MODE;
		defaults.showFilterOutlines = DEFAULT_SHOW_FILTER_OUTLINES;
		defaults.autoCraftEnabled = DEFAULT_AUTO_CRAFT_ENABLED;
		defaults.autoCraftEnabledMode = DEFAULT_AUTO_CRAFT_ENABLED_MODE;
		defaults.showTotalOutputCounts = DEFAULT_SHOW_TOTAL_OUTPUT_COUNTS;
		defaults.inputCounterVisibility = DEFAULT_INPUT_COUNTER_VISIBILITY;
		defaults.inventory2x2OffhandConsolidation = DEFAULT_INVENTORY_2X2_OFFHAND_CONSOLIDATION;
		defaults.scrollToPullMode = DEFAULT_SCROLL_TO_PULL_MODE;
		defaults.typeToFocusSearch = DEFAULT_TYPE_TO_FOCUS_SEARCH;
		defaults.ejectItemsWhenFull = DEFAULT_EJECT_ITEMS_WHEN_FULL;
		defaults.autoCraftCapability = DEFAULT_AUTO_CRAFT_CAPABILITY;
		defaults.autoCraftOffAfterBulk = DEFAULT_AUTO_CRAFT_OFF_AFTER_BULK;
		defaults.bulkVariantSwitching = DEFAULT_BULK_VARIANT_SWITCHING;
		defaults.autoCraftHandling = DEFAULT_AUTO_CRAFT_HANDLING;
		defaults.chainCraftingMode = DEFAULT_CHAIN_CRAFTING_MODE;
		defaults.showCraftAbortedMessage = DEFAULT_SHOW_CRAFT_ABORTED_MESSAGE;
		defaults.showBulkCraftSummaryMessage = DEFAULT_SHOW_BULK_CRAFT_SUMMARY_MESSAGE;
		defaults.showMissingIngredientsMessage = DEFAULT_SHOW_MISSING_INGREDIENTS_MESSAGE;
		defaults.showChainCraftMessages = DEFAULT_SHOW_CHAIN_CRAFT_MESSAGES;
		defaults.altAsRequestKey = DEFAULT_ALT_AS_REQUEST_KEY;
		defaults.altClickInstantCraft = DEFAULT_ALT_CLICK_INSTANT_CRAFT;
		defaults.debugMessagesEnabled = DEFAULT_DEBUG_MESSAGES_ENABLED;
		defaults.blacklistedContainerIds = new LinkedHashSet<>(DEFAULT_BLACKLIST);
		defaults.recentRecipeDisplayIds = new ArrayList<>();
		defaults.recentRecipeDisplayIdsByContext = new HashMap<>();
		return defaults;
	}

	private static void trimRecentRecipeDisplayIds(List<Integer> ids) {
		ids.removeIf(java.util.Objects::isNull);
		while (ids.size() > 40) {
			ids.remove(ids.size() - 1);
		}
	}

	private static Map<String, List<Integer>> normalizeRecentRecipeContexts(Map<String, List<Integer>> stored) {
		Map<String, List<Integer>> normalized = new HashMap<>();
		if (stored == null) {
			return normalized;
		}
		for (Map.Entry<String, List<Integer>> entry : stored.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
				continue;
			}
			List<Integer> ids = new ArrayList<>(entry.getValue());
			trimRecentRecipeDisplayIds(ids);
			if (!ids.isEmpty()) {
				normalized.put(entry.getKey(), ids);
			}
		}
		return normalized;
	}

	private static String recipeHistoryContextId() {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client == null || client.player == null || client.level == null) {
			return null;
		}
		if (client.isSingleplayer() && client.getSingleplayerServer() != null) {
			return "local_" + sanitizeStorageId(client.getSingleplayerServer().getWorldData().getLevelName());
		}
		if (client.getConnection() != null && client.getConnection().getServerData() != null) {
			return "server_" + sanitizeStorageId(client.getConnection().getServerData().ip);
		}
		return null;
	}

	private static String sanitizeStorageId(String raw) {
		return raw == null ? "unknown" : raw.replaceAll("[^a-zA-Z0-9_-]", "_");
	}

	private static ScrollToPullMode parseScrollToPullMode(JsonElement rawValue) {
		if (rawValue == null || rawValue.isJsonNull()) {
			return DEFAULT_SCROLL_TO_PULL_MODE;
		}
		if (rawValue.isJsonPrimitive()) {
			JsonPrimitive primitive = rawValue.getAsJsonPrimitive();
			if (primitive.isBoolean()) {
				return primitive.getAsBoolean()
					? ScrollToPullMode.WHILE_RESULT_SLOT_HOVERED
					: ScrollToPullMode.NONE;
			}
			if (primitive.isString()) {
				try {
					return ScrollToPullMode.valueOf(primitive.getAsString());
				} catch (IllegalArgumentException exception) {
					ReachCraftingMod.LOGGER.warn("Unknown scrollToPullMode value in config: {}", primitive.getAsString());
				}
			}
		}
		return DEFAULT_SCROLL_TO_PULL_MODE;
	}


	public enum InWorldFilterMode {
		NONE,
		BLACKLIST,
		WHITELIST
	}

	public enum RevolvingCraftHandling {
		ALWAYS_PREFER_BASED_ON_COUNT,
		PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK,
		SPECIFIC_VARIANT_ONLY
	}

	public enum InputCounterVisibility {
		SHOW_WHILE_QUEUEING,
		ALWAYS_SHOW,
		DISABLED
	}

	public enum ScrollToPullMode {
		NONE,
		WHILE_RESULT_SLOT_HOVERED,
		WHILE_RESULT_OR_INVENTORY_SLOT_HOVERED
	}

	public enum SearchHistoryMode {
		OFF,
		ON,
		ON_AND_RESTORE_LAST_SEARCH
	}

	public enum RecipeBookSortingMode {
		VANILLA,
		SMART
	}

	public enum AutoFocusSearchMode {
		DISABLED,
		CRAFTING_3X3,
		INVENTORY_2X2_AND_3X3
	}

	public enum AutoCraftHandling {
		TOGGLE,
		HOLD
	}

	public enum ChainCraftingMode {
		DISABLED,
		CONFIRM,
		ALWAYS
	}

	public enum AutoCraftMode {
		NORMAL,
		BULK
	}

	public enum AutoCraftCapability {
		NONE,
		NORMAL,
		BULK
	}

	private static final class StoredConfig {
		private Boolean enabled;
		private Boolean enableNearbyContainerUsage;
		private Boolean redistributeToCraftWhenNeeded;
		private InWorldFilterMode inWorldFilterMode;
		private RevolvingCraftHandling revolvingCraftHandling;
		private IngredientPlanning.CountPreference countPreference;
		private Boolean showNearbyCraftableIndicator;
		private Boolean cacheContainersForFasterSearch;
		private Boolean reachCraftHoldAndRelease;
		private Boolean reachCraftCloseOverlayAfterRelease;
		private Boolean reachCraftPreferInventory;
		private Boolean putPulledResourcesBack;
		private Boolean restoreInventoryItemPositions;
		private Boolean rememberPreviousSearch;
		private SearchHistoryMode searchHistoryMode;
		private RecipeBookSortingMode recipeBookSortingMode;
		private AutoFocusSearchMode autoFocusSearchMode;
		private OutlineDisplayMode showFilterOutlines;
		private Boolean showTotalOutputCounts;
		private InputCounterVisibility inputCounterVisibility;
		private Boolean inventory2x2OffhandConsolidation;
		private JsonElement scrollToPullMode;
		private Boolean typeToFocusSearch;
		private Boolean ejectItemsWhenFull;
		private Boolean autoCraftMode;
		private Boolean autoCraftEnabled;
		private AutoCraftMode autoCraftEnabledMode;
		private AutoCraftCapability autoCraftCapability;
		private Boolean autoCraftOffAfterBulk;
		private Boolean bulkVariantSwitching;
		private AutoCraftHandling autoCraftHandling;
		private ChainCraftingMode chainCraftingMode;
		private Boolean showCraftAbortedMessage;
		private Boolean showBulkCraftSummaryMessage;
		private Boolean showMissingIngredientsMessage;
		private Boolean showChainCraftMessages;
		private Boolean altAsRequestKey;
		private Boolean altClickInstantCraft;
		private Boolean debugMessagesEnabled;
		private Boolean enableEnablingBulkMode;
		private Set<String> blacklistedContainerIds;
		private List<Integer> recentRecipeDisplayIds;
		private Map<String, List<Integer>> recentRecipeDisplayIdsByContext;

		private StoredConfig(ReachCraftingConfig config) {
			this.enabled = config.enabled;
			this.enableNearbyContainerUsage = config.enableNearbyContainerUsage;
			this.redistributeToCraftWhenNeeded = config.redistributeToCraftWhenNeeded;
			this.inWorldFilterMode = config.inWorldFilterMode;
			this.revolvingCraftHandling = config.revolvingCraftHandling;
			this.countPreference = config.countPreference;
			this.showNearbyCraftableIndicator = config.showNearbyCraftableIndicator;
			this.cacheContainersForFasterSearch = config.cacheContainersForFasterSearch;
			this.reachCraftHoldAndRelease = config.reachCraftHoldAndRelease;
			this.reachCraftCloseOverlayAfterRelease = config.reachCraftCloseOverlayAfterRelease;
			this.reachCraftPreferInventory = config.reachCraftPreferInventory;
			this.putPulledResourcesBack = config.putPulledResourcesBack;
			this.restoreInventoryItemPositions = config.restoreInventoryItemPositions;
			this.searchHistoryMode = config.searchHistoryMode;
			this.recipeBookSortingMode = config.recipeBookSortingMode;
			this.autoFocusSearchMode = config.autoFocusSearchMode;
			this.showFilterOutlines = config.showFilterOutlines;
			this.showTotalOutputCounts = config.showTotalOutputCounts;
			this.inputCounterVisibility = config.inputCounterVisibility;
			this.inventory2x2OffhandConsolidation = config.inventory2x2OffhandConsolidation;
			this.scrollToPullMode = new JsonPrimitive(config.scrollToPullMode.name());
			this.typeToFocusSearch = config.typeToFocusSearch;
			this.ejectItemsWhenFull = config.ejectItemsWhenFull;
			this.autoCraftEnabled = config.autoCraftEnabled;
			this.autoCraftEnabledMode = config.autoCraftEnabledMode;
			this.autoCraftCapability = config.autoCraftCapability;
			this.autoCraftOffAfterBulk = config.autoCraftOffAfterBulk;
			this.bulkVariantSwitching = config.bulkVariantSwitching;
			this.autoCraftHandling = config.autoCraftHandling;
			this.chainCraftingMode = config.chainCraftingMode;
			this.showCraftAbortedMessage = config.showCraftAbortedMessage;
			this.showBulkCraftSummaryMessage = config.showBulkCraftSummaryMessage;
			this.showMissingIngredientsMessage = config.showMissingIngredientsMessage;
			this.showChainCraftMessages = config.showChainCraftMessages;
			this.altAsRequestKey = config.altAsRequestKey;
			this.altClickInstantCraft = config.altClickInstantCraft;
			this.debugMessagesEnabled = config.debugMessagesEnabled;
			this.blacklistedContainerIds = config.blacklistedContainerIds;
			this.recentRecipeDisplayIds = config.recentRecipeDisplayIds;
			this.recentRecipeDisplayIdsByContext = config.recentRecipeDisplayIdsByContext;
		}
	}

	public enum OutlineDisplayMode {
		OFF,
		ON,
		KEYBIND
	}
}
