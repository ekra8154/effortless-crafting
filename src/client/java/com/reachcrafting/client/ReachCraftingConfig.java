package com.reachcrafting.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.reachcrafting.ReachCraftingMod;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

public final class ReachCraftingConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("reachcrafting.json");
	private static final Set<String> DEFAULT_BLACKLIST = Set.of(
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
	private boolean rememberPreviousSearch;
	private OutlineDisplayMode showFilterOutlines;
	private boolean autoCraftMode;
	private boolean showTotalOutputCounts;
	private Set<String> blacklistedContainerIds;

	private static String lastSearchText = "";

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
			instance.redistributeToCraftWhenNeeded = stored.redistributeToCraftWhenNeeded;
			instance.inWorldFilterMode = stored.inWorldFilterMode != null ? stored.inWorldFilterMode : InWorldFilterMode.NONE;
			instance.revolvingCraftHandling = stored.revolvingCraftHandling != null
				? stored.revolvingCraftHandling
				: RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK;
			instance.countPreference = stored.countPreference != null
				? stored.countPreference
				: IngredientPlanning.CountPreference.HIGHEST_TOTAL;
			instance.showNearbyCraftableIndicator = stored.showNearbyCraftableIndicator;
			instance.cacheContainersForFasterSearch = stored.cacheContainersForFasterSearch == null
				? true
				: stored.cacheContainersForFasterSearch;
			instance.reachCraftHoldAndRelease = stored.reachCraftHoldAndRelease;
			instance.reachCraftCloseOverlayAfterRelease = stored.reachCraftCloseOverlayAfterRelease == null ? true : stored.reachCraftCloseOverlayAfterRelease;
			instance.reachCraftPreferInventory = stored.reachCraftPreferInventory == null ? true : stored.reachCraftPreferInventory;
			instance.putPulledResourcesBack = stored.putPulledResourcesBack;
			instance.restoreInventoryItemPositions = stored.restoreInventoryItemPositions == null ? true : stored.restoreInventoryItemPositions;
			instance.rememberPreviousSearch = stored.rememberPreviousSearch == null ? true : stored.rememberPreviousSearch;
			instance.showFilterOutlines = stored.showFilterOutlines != null ? stored.showFilterOutlines : OutlineDisplayMode.OFF;
			instance.autoCraftMode = stored.autoCraftMode != null ? stored.autoCraftMode : false;
			instance.showTotalOutputCounts = stored.showTotalOutputCounts != null ? stored.showTotalOutputCounts : true;
			instance.blacklistedContainerIds = stored.blacklistedContainerIds != null ? new HashSet<>(stored.blacklistedContainerIds) : new HashSet<>(DEFAULT_BLACKLIST);
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

	public boolean rememberPreviousSearch() {
		return rememberPreviousSearch;
	}

	public void setRememberPreviousSearch(boolean rememberPreviousSearch) {
		this.rememberPreviousSearch = rememberPreviousSearch;
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

	public boolean autoCraftMode() {
		return autoCraftMode;
	}

	public void setAutoCraftMode(boolean autoCraftMode) {
		this.autoCraftMode = autoCraftMode;
	}

	public boolean showTotalOutputCounts() {
		return showTotalOutputCounts;
	}

	public void setShowTotalOutputCounts(boolean showTotalOutputCounts) {
		this.showTotalOutputCounts = showTotalOutputCounts;
	}

	public static String getLastSearchText() {
		return lastSearchText;
	}

	public static void setLastSearchText(String text) {
		lastSearchText = text != null ? text : "";
	}
	
	public Set<String> blacklistedContainerIds() {
		return Collections.unmodifiableSet(blacklistedContainerIds);
	}

	public void setBlacklistedContainerIds(Set<String> blacklistedContainerIds) {
		this.blacklistedContainerIds = new HashSet<>(blacklistedContainerIds);
		NearbyContainerCache.clear();
	}

	private static ReachCraftingConfig defaults() {
		ReachCraftingConfig defaults = new ReachCraftingConfig();
		defaults.redistributeToCraftWhenNeeded = false;
		defaults.inWorldFilterMode = InWorldFilterMode.NONE;
		defaults.revolvingCraftHandling = RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK;
		defaults.countPreference = IngredientPlanning.CountPreference.HIGHEST_TOTAL;
		defaults.showNearbyCraftableIndicator = false;
		defaults.cacheContainersForFasterSearch = true;
		defaults.reachCraftHoldAndRelease = false;
		defaults.reachCraftCloseOverlayAfterRelease = true;
		defaults.reachCraftPreferInventory = true;
		defaults.putPulledResourcesBack = false;
		defaults.restoreInventoryItemPositions = true;
		defaults.rememberPreviousSearch = true;
		defaults.showFilterOutlines = OutlineDisplayMode.OFF;
		defaults.autoCraftMode = false;
		defaults.showTotalOutputCounts = true;
		defaults.blacklistedContainerIds = new HashSet<>(DEFAULT_BLACKLIST);
		return defaults;
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

	private static final class StoredConfig {
		private boolean redistributeToCraftWhenNeeded;
		private InWorldFilterMode inWorldFilterMode;
		private RevolvingCraftHandling revolvingCraftHandling;
		private IngredientPlanning.CountPreference countPreference;
		private boolean showNearbyCraftableIndicator;
		private Boolean cacheContainersForFasterSearch;
		private boolean reachCraftHoldAndRelease;
		private Boolean reachCraftCloseOverlayAfterRelease;
		private Boolean reachCraftPreferInventory;
		private boolean putPulledResourcesBack;
		private Boolean restoreInventoryItemPositions;
		private Boolean rememberPreviousSearch;
		private OutlineDisplayMode showFilterOutlines;
		private Boolean autoCraftMode;
		private Boolean showTotalOutputCounts;
		private Set<String> blacklistedContainerIds;

		private StoredConfig(ReachCraftingConfig config) {
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
			this.rememberPreviousSearch = config.rememberPreviousSearch;
			this.showFilterOutlines = config.showFilterOutlines;
			this.autoCraftMode = config.autoCraftMode;
			this.showTotalOutputCounts = config.showTotalOutputCounts;
			this.blacklistedContainerIds = config.blacklistedContainerIds;
		}
	}

	public enum OutlineDisplayMode {
		OFF,
		ON,
		KEYBIND
	}
}
