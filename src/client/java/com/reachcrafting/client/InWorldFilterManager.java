package com.reachcrafting.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class InWorldFilterManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILTERS_DIR = FabricLoader.getInstance().getConfigDir().resolve("reachcrafting").resolve("filters");
	
	private static final Set<String> INSTANCE_BLACKLIST = new HashSet<>();
	private static final Set<String> INSTANCE_WHITELIST = new HashSet<>();
	private static String currentStorageId = null;
	private static boolean dirty = false;

	public enum InclusionState {
		MANUAL_WHITELIST,
		MANUAL_BLACKLIST,
		UNSET // Determined by global settings
	}

	private InWorldFilterManager() {}

	public static void updateContext() {
		String newId = getStorageId();
		if (!newId.equals(currentStorageId)) {
			saveIfDirty(); // Save old if any
			currentStorageId = newId;
			load();
		}
	}

	private static String getStorageId() {
		Minecraft client = Minecraft.getInstance();
		if (client.isSingleplayer() && client.getSingleplayerServer() != null) {
			return "local_" + client.getSingleplayerServer().getWorldData().getLevelName().replaceAll("[^a-zA-Z0-9_-]", "_");
		} else if (client.getConnection() != null && client.getConnection().getServerData() != null) {
			return "server_" + client.getConnection().getServerData().ip.replaceAll("[^a-zA-Z0-9_-]", "_");
		}
		return "unknown";
	}

	public static boolean isContainerActive(Level level, BlockPos pos, BlockState state) {
		InclusionState manual = getManualState(level, pos);
		if (manual == InclusionState.MANUAL_WHITELIST) return true;
		if (manual == InclusionState.MANUAL_BLACKLIST) return false;
		
		// Unset, use auto logic
		if (!ContainerUtils.isSupportedContainer(state)) return false;
		
		ReachCraftingConfig.InWorldFilterMode mode = ReachCraftingConfig.get().inWorldFilterMode();
		return mode != ReachCraftingConfig.InWorldFilterMode.WHITELIST;
	}

	public static InclusionState getManualState(Level level, BlockPos pos) {
		updateContext();
		String key = getPosKey(level, pos);
		if (INSTANCE_WHITELIST.contains(key)) return InclusionState.MANUAL_WHITELIST;
		if (INSTANCE_BLACKLIST.contains(key)) return InclusionState.MANUAL_BLACKLIST;
		return InclusionState.UNSET;
	}

	/**
	 * Proactively checks all saved filters for the current world and removes any that point to non-containers.
	 */
	public static void validateFilters(Level level) {
		if (level == null || (INSTANCE_BLACKLIST.isEmpty() && INSTANCE_WHITELIST.isEmpty())) return;
		
		updateContext();
		
		// We use a temporary list to avoid ConcurrentModificationException
		Set<String> toRemoveBlack = new HashSet<>();
		Set<String> toRemoveWhite = new HashSet<>();
		
		validateSet(level, INSTANCE_BLACKLIST, toRemoveBlack);
		validateSet(level, INSTANCE_WHITELIST, toRemoveWhite);
		
		if (!toRemoveBlack.isEmpty() || !toRemoveWhite.isEmpty()) {
			INSTANCE_BLACKLIST.removeAll(toRemoveBlack);
			INSTANCE_WHITELIST.removeAll(toRemoveWhite);
			dirty = true;
			saveIfDirty();
		}
	}

	public static Set<String> getBlacklistedKeys() {
		updateContext();
		return Set.copyOf(INSTANCE_BLACKLIST);
	}

	public static Set<String> getWhitelistedKeys() {
		updateContext();
		return Set.copyOf(INSTANCE_WHITELIST);
	}

	private static void validateSet(Level level, Set<String> set, Set<String> toRemove) {
		for (String key : set) {
			// Parse key back to pos
			// Format: dimension:x,y,z
			try {
				int lastColon = key.lastIndexOf(':');
				if (lastColon == -1) continue;
				String dim = key.substring(0, lastColon);
				if (!level.dimension().toString().equals(dim)) continue; // Only validate current dimension
				
				String coords = key.substring(lastColon + 1);
				String[] split = coords.split(",");
				if (split.length != 3) continue;
				
				BlockPos pos = new BlockPos(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]));
				
				// Only remove if the block is loaded AND it's not a container
				if (level.isLoaded(pos)) {
					BlockState state = level.getBlockState(pos);
					if (!ContainerUtils.isPotentiallySupportedContainer(state)) {
						toRemove.add(key);
					}
				}
			} catch (Exception e) {
				// Ignore malformed keys
			}
		}
	}

	public static boolean isInstanceBlacklisted(Level level, BlockPos pos) {
		updateContext();
		return INSTANCE_BLACKLIST.contains(getPosKey(level, pos));
	}

	public static boolean isInstanceWhitelisted(Level level, BlockPos pos) {
		updateContext();
		return INSTANCE_WHITELIST.contains(getPosKey(level, pos));
	}

	public static void toggleInclusion(Level level, BlockPos pos, BlockState state) {
		updateContext();
		String key = getPosKey(level, pos, state);
		InclusionState current = getManualState(level, pos);
		
		if (current == InclusionState.UNSET) {
			// Cycle to the opposite of what it currently is automatically
			if (isContainerActive(level, pos, state)) {
				INSTANCE_BLACKLIST.add(key);
			} else {
				INSTANCE_WHITELIST.add(key);
			}
		} else if (current == InclusionState.MANUAL_BLACKLIST) {
			// Black -> White
			INSTANCE_BLACKLIST.remove(key);
			INSTANCE_WHITELIST.add(key);
		} else if (current == InclusionState.MANUAL_WHITELIST) {
			// White -> Reset
			INSTANCE_WHITELIST.remove(key);
		}
		
		dirty = true;
		saveIfDirty(); // Trigger a save check after toggle
		NearbyContainerCache.bumpRevision();
	}

	private static String getPosKey(Level level, BlockPos pos, BlockState state) {
		BlockPos anchor = ContainerUtils.canonicalizeContainerPos(level, pos, state != null ? state : level.getBlockState(pos));
		return level.dimension().toString() + ":" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
	}

	private static String getPosKey(Level level, BlockPos pos) {
		return getPosKey(level, pos, null);
	}

	private static void load() {
		INSTANCE_BLACKLIST.clear();
		INSTANCE_WHITELIST.clear();
		
		if (currentStorageId.equals("unknown")) return;
		
		Path path = FILTERS_DIR.resolve(currentStorageId + ".json");
		if (Files.notExists(path)) return;
		
		try (Reader reader = Files.newBufferedReader(path)) {
			FilterData data = GSON.fromJson(reader, FilterData.class);
			if (data != null) {
				if (data.blacklist != null) INSTANCE_BLACKLIST.addAll(data.blacklist);
				if (data.whitelist != null) INSTANCE_WHITELIST.addAll(data.whitelist);
			}
		} catch (Exception e) {
			ReachCraftingMod.LOGGER.error("Failed to load filters for {}", currentStorageId, e);
		}
	}

	public static void saveIfDirty() {
		if (!dirty) return;
		save();
	}

	private static void save() {
		if (currentStorageId == null || currentStorageId.equals("unknown")) return;
		
		dirty = false;
		try {
			Files.createDirectories(FILTERS_DIR);
			Path path = FILTERS_DIR.resolve(currentStorageId + ".json");
			
			if (INSTANCE_BLACKLIST.isEmpty() && INSTANCE_WHITELIST.isEmpty()) {
				Files.deleteIfExists(path);
				return;
			}

			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(new FilterData(INSTANCE_BLACKLIST, INSTANCE_WHITELIST), writer);
			}
		} catch (Exception e) {
			ReachCraftingMod.LOGGER.error("Failed to save filters for {}", currentStorageId, e);
		}
	}

	private static class FilterData {
		Set<String> blacklist;
		Set<String> whitelist;
		
		FilterData(Set<String> blacklist, Set<String> whitelist) {
			this.blacklist = blacklist;
			this.whitelist = whitelist;
		}
	}
}
