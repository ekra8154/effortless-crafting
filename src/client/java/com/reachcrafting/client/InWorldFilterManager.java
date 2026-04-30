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

	private InWorldFilterManager() {}

	public static void updateContext() {
		String newId = getStorageId();
		if (!newId.equals(currentStorageId)) {
			save(); // Save old if any
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
		updateContext();
		String key = getPosKey(level, pos);
		
		// 1. Whitelist Override
		if (INSTANCE_WHITELIST.contains(key)) {
			return true;
		}
		
		// 2. Blacklist Override
		if (INSTANCE_BLACKLIST.contains(key)) {
			return false;
		}
		
		// 3. Global Type Blacklist
		if (!ContainerUtils.isSupportedContainer(state)) {
			return false;
		}
		
		// 4. Mode Logic
		ReachCraftingConfig.InWorldFilterMode mode = ReachCraftingConfig.get().inWorldFilterMode();
		if (mode == ReachCraftingConfig.InWorldFilterMode.WHITELIST) {
			return false; // Only whitelisted items are active in whitelist mode
		}
		
		return true;
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
		String key = getPosKey(level, pos);
		boolean currentlyActive = isContainerActive(level, pos, state);
		
		if (currentlyActive) {
			// We want to make it INACTIVE
			INSTANCE_WHITELIST.remove(key);
			INSTANCE_BLACKLIST.add(key);
		} else {
			// We want to make it ACTIVE
			INSTANCE_BLACKLIST.remove(key);
			INSTANCE_WHITELIST.add(key);
		}
		
		save();
		NearbyContainerCache.bumpRevision();
	}

	private static String getPosKey(Level level, BlockPos pos) {
		BlockPos anchor = ContainerUtils.canonicalizeContainerPos(level, pos, level.getBlockState(pos));
		return level.dimension().toString() + ":" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
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

	private static void save() {
		if (currentStorageId == null || currentStorageId.equals("unknown")) return;
		if (INSTANCE_BLACKLIST.isEmpty() && INSTANCE_WHITELIST.isEmpty()) {
			// Optional: delete file if empty?
			return;
		}

		try {
			Files.createDirectories(FILTERS_DIR);
			Path path = FILTERS_DIR.resolve(currentStorageId + ".json");
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
