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
	private static final Path FILTERS_DIR = FabricLoader.getInstance().getConfigDir().resolve("effortless-crafting").resolve("filters");
	
	private static final Set<String> INSTANCE_BLACKLIST = new HashSet<>();
	private static final Set<String> INSTANCE_WHITELIST = new HashSet<>();
	private static String currentStorageId = null;
	private static boolean dirty = false;
	// What the storage id was last derived from (the singleplayer server or
	// the server entry), so the id is not rebuilt for every container check.
	private static Object storageIdSource;
	private static String storageIdCache;
	// The same entries as packed positions per dimension, so a check is a
	// long lookup instead of building and hashing two key strings. Rebuilt
	// lazily after the sets change (load, validateFilters, toggleInclusion).
	private static final java.util.Map<String, it.unimi.dsi.fastutil.longs.LongSet> BLACKLIST_INDEX = new java.util.HashMap<>();
	private static final java.util.Map<String, it.unimi.dsi.fastutil.longs.LongSet> WHITELIST_INDEX = new java.util.HashMap<>();
	private static boolean indexStale = true;
	private static net.minecraft.resources.ResourceKey<Level> lastDimension;
	private static String lastDimensionPrefix;

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

	/**
	 * Called for every container a nearby scan looks at (hundreds per scan in
	 * a storage room), so the id is cached per world: deriving it runs a
	 * regex over the level name or address, which made the filter check half
	 * of a nearby scan's cost.
	 */
	private static String getStorageId() {
		Minecraft client = Minecraft.getInstance();
		Object source;
		if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
			source = client.getSingleplayerServer();
		} else if (client.getConnection() != null && client.getConnection().getServerData() != null) {
			source = client.getConnection().getServerData();
		} else {
			source = null;
		}
		if (source != storageIdSource || storageIdCache == null) {
			storageIdSource = source;
			if (source instanceof net.minecraft.server.MinecraftServer server) {
				storageIdCache = "local_" + server.getWorldData().getLevelName().replaceAll("[^a-zA-Z0-9_-]", "_");
			} else if (source instanceof net.minecraft.client.multiplayer.ServerData serverData) {
				storageIdCache = "server_" + serverData.ip.replaceAll("[^a-zA-Z0-9_-]", "_");
			} else {
				storageIdCache = "unknown";
			}
		}
		return storageIdCache;
	}

	public static boolean isContainerActive(Level level, BlockPos pos, BlockState state) {
		if (!ReachCraftingConfig.get().enabled() || !ReachCraftingConfig.get().enableNearbyContainerUsage()) return false;
		ReachCraftingConfig.InWorldFilterMode mode = ReachCraftingConfig.get().inWorldFilterMode();
		if (mode == ReachCraftingConfig.InWorldFilterMode.NONE) {
			return ContainerUtils.isSupportedContainer(state);
		}

		InclusionState manual = getManualState(level, pos, state);
		if (manual == InclusionState.MANUAL_WHITELIST) return true;
		if (manual == InclusionState.MANUAL_BLACKLIST) return false;
		
		// Unset, use auto logic
		if (!ContainerUtils.isSupportedContainer(state)) {
			return false;
		}
		return mode != ReachCraftingConfig.InWorldFilterMode.WHITELIST;
	}

	public static InclusionState getManualState(Level level, BlockPos pos) {
		return getManualState(level, pos, null);
	}

	/**
	 * Same answer as looking up keysFor's strings, from the packed-position
	 * index: a nearby scan asks this for every container in reach (hundreds
	 * in a storage room), and building two key strings each time was most of
	 * the filter's cost. {@code state} is the block's state when the caller
	 * already has it, saving a lookup.
	 */
	static InclusionState getManualState(Level level, BlockPos pos, BlockState state) {
		updateContext();
		if (INSTANCE_WHITELIST.isEmpty() && INSTANCE_BLACKLIST.isEmpty()) {
			// Most worlds have no manual entries.
			return InclusionState.UNSET;
		}
		rebuildIndexIfStale();
		String dimension = dimensionPrefix(level);
		it.unimi.dsi.fastutil.longs.LongSet whitelist = WHITELIST_INDEX.get(dimension);
		it.unimi.dsi.fastutil.longs.LongSet blacklist = BLACKLIST_INDEX.get(dimension);
		if (whitelist == null && blacklist == null) {
			return InclusionState.UNSET;
		}
		long own = pos.asLong();
		long partner = ContainerUtils.getOtherHalfOfLargeChest(level, pos, state != null ? state : level.getBlockState(pos))
			.map(BlockPos::asLong)
			.orElse(own);
		if (whitelist != null && (whitelist.contains(own) || whitelist.contains(partner))) return InclusionState.MANUAL_WHITELIST;
		if (blacklist != null && (blacklist.contains(own) || blacklist.contains(partner))) return InclusionState.MANUAL_BLACKLIST;
		return InclusionState.UNSET;
	}

	/** rawPosKey's dimension part, built once per dimension instead of per key. */
	private static String dimensionPrefix(Level level) {
		if (level.dimension() != lastDimension) {
			lastDimension = level.dimension();
			lastDimensionPrefix = lastDimension.toString();
		}
		return lastDimensionPrefix;
	}

	private static void rebuildIndexIfStale() {
		if (!indexStale) {
			return;
		}
		indexStale = false;
		indexInto(INSTANCE_BLACKLIST, BLACKLIST_INDEX);
		indexInto(INSTANCE_WHITELIST, WHITELIST_INDEX);
	}

	/** Parses rawPosKey strings ("<dimension>:x,y,z"); malformed ones are skipped, as validateSet does. */
	private static void indexInto(Set<String> keys, java.util.Map<String, it.unimi.dsi.fastutil.longs.LongSet> index) {
		index.clear();
		for (String key : keys) {
			int lastColon = key.lastIndexOf(':');
			if (lastColon <= 0) {
				continue;
			}
			String[] coords = key.substring(lastColon + 1).split(",");
			if (coords.length != 3) {
				continue;
			}
			try {
				long packed = BlockPos.asLong(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
				index.computeIfAbsent(key.substring(0, lastColon), ignored -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(packed);
			} catch (NumberFormatException ignored) {
				// Malformed: never matches, as with the string lookup.
			}
		}
	}

	/**
	 * The keys this block's filter state may live under: its own position
	 * and, for a double chest, the partner half. States are written to ALL
	 * of these so breaking either half leaves the survivor's state intact
	 * (an anchored single key made the outcome depend on which half broke);
	 * lookups accept any of them, which also reads old anchor-only saves.
	 */
	private static java.util.List<String> keysFor(Level level, BlockPos pos) {
		java.util.List<String> keys = new java.util.ArrayList<>(2);
		keys.add(rawPosKey(level, pos));
		ContainerUtils.getOtherHalfOfLargeChest(level, pos)
			.ifPresent(other -> keys.add(rawPosKey(level, other)));
		return keys;
	}

	private static String rawPosKey(Level level, BlockPos pos) {
		return level.dimension().toString() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
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
			indexStale = true;
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
		return getManualState(level, pos) == InclusionState.MANUAL_BLACKLIST;
	}

	public static boolean isInstanceWhitelisted(Level level, BlockPos pos) {
		return getManualState(level, pos) == InclusionState.MANUAL_WHITELIST;
	}

	private static boolean sneakCycleConsumedPress;

	/**
	 * True when sneak + bare-hand right-click on the crosshair block should
	 * cycle its filter state instead of opening it: a blacklist/whitelist
	 * filter is active, the outlines are currently shown, the player is
	 * sneaking with an empty main hand, and the target is a container.
	 */
	private static boolean isSneakClickCycleTargeted(Minecraft client) {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (!config.enabled()
			|| !config.sneakClickWhileHighlighted()
			|| config.inWorldFilterMode() == ReachCraftingConfig.InWorldFilterMode.NONE
			|| !ContainerFilterRenderer.areOutlinesVisible()) {
			return false;
		}
		if (client.player == null
			|| client.level == null
			|| !client.player.isShiftKeyDown()
			|| !client.player.getMainHandItem().isEmpty()) {
			return false;
		}
		if (!(client.hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit)
			|| blockHit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
			return false;
		}
		BlockState state = client.level.getBlockState(blockHit.getBlockPos());
		return ContainerUtils.isPotentiallySupportedContainer(state);
	}

	/**
	 * Returns true whenever the use action must be cancelled (the container
	 * must not open), but cycles at most once per physical press: startUseItem
	 * auto-repeats every few ticks while the button is held, which would spin
	 * through the three states.
	 */
	public static boolean trySneakClickCycle(Minecraft client) {
		if (!isSneakClickCycleTargeted(client)) {
			return false;
		}
		if (!sneakCycleConsumedPress) {
			sneakCycleConsumedPress = true;
			BlockPos pos = ((net.minecraft.world.phys.BlockHitResult) client.hitResult).getBlockPos();
			toggleInclusion(client.level, pos, client.level.getBlockState(pos));
			client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		}
		return true;
	}

	/** Called every client tick; re-arms the sneak-click cycle once the use button is released. */
	public static void tickSneakCycleLatch(Minecraft client) {
		if (!client.options.keyUse.isDown()) {
			sneakCycleConsumedPress = false;
		}
	}

	public static void toggleInclusion(Level level, BlockPos pos, BlockState state) {
		updateContext();
		java.util.List<String> keys = keysFor(level, pos);
		InclusionState current = getManualState(level, pos);
		// Clear every key the state may live under (both halves, plus any
		// stale anchor from an older save) before writing the next state.
		INSTANCE_BLACKLIST.removeAll(keys);
		INSTANCE_WHITELIST.removeAll(keys);

		if (current == InclusionState.UNSET) {
			INSTANCE_BLACKLIST.addAll(keys);
		} else if (current == InclusionState.MANUAL_BLACKLIST) {
			// Black -> White
			INSTANCE_WHITELIST.addAll(keys);
		}
		// White -> Reset: everything already removed.

		indexStale = true;
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
		indexStale = true;
		
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
