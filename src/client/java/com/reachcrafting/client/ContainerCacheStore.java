package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import com.reachcrafting.client.mixin.BiomeManagerAccessor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * ContainerCacheStore keeps {@link NearbyContainerCache} on disk, so a world's
 * containers are known again after rejoining or relaunching instead of being
 * rescanned.
 *
 * <p>Each world has two files. {@code containers.cache} is the whole cache,
 * gzip compressed, rewritten when the world is left. {@code containers.cache.log}
 * holds every change since then, appended the moment the cache learns it, so a
 * crash loses nothing. Loading reads the cache, replays the log on top and
 * folds the two back into one file.</p>
 *
 * <p>A single-player world keeps them inside its own save folder
 * ({@code saves/<world>/effortless-crafting/}), so deleting or copying the
 * world takes its cache with it and two worlds can never share one. A server
 * cannot be written to, so its files live in the game folder
 * ({@code effortless-crafting/container-cache/}), named by address and the
 * hashed seed the server sends: that tells apart the worlds a network serves
 * behind one address.</p>
 *
 * <p>Every file starts with one line of plain text saying what it is, so a
 * player who opens one knows it is a disposable cache, not part of the
 * save.</p>
 *
 * <p>Both files store each string (dimension, container type, item id) once
 * and refer to it by index. Positions in the cache file are deltas from the
 * previous container. All disk work runs on one background thread in
 * submission order; the game thread only copies immutable snapshots into it.</p>
 */
final class ContainerCacheStore {
	private static final int CACHE_MAGIC = 0x45434331; // ECC1
	private static final int LOG_MAGIC = 0x45434c31; // ECL1
	// Older versions keep their readers, so a mod update upgrades a file
	// instead of starting the cache over.
	private static final int FORMAT_VERSION = 1;
	private static final int OP_DEFINE = 1;
	private static final int OP_PUT = 2;
	private static final int OP_REMOVE = 3;
	private static final int OP_CLEAR = 4;
	private static final long COMPACT_AFTER_LOG_BYTES = 512L * 1024L;
	private static final int MAX_CONTAINERS_PER_WORLD = 50_000;
	private static final long FORGET_UNUSED_WORLD_MILLIS = 180L * 24L * 60L * 60L * 1000L;
	private static final String FOLDER_NAME = "effortless-crafting";
	private static final String CACHE_FILE = "containers.cache";
	private static final String LOG_SUFFIX = ".log";
	private static final Path DIRECTORY = FabricLoader.getInstance().getGameDir()
		.resolve(FOLDER_NAME)
		.resolve("container-cache");
	/** The first line of every file, readable in any text editor. */
	private static final byte[] FILE_HEADER = (
		"Effortless Crafting container cache: what the mod remembers about your containers. "
			+ "Safe to delete, it is rebuilt as you open them.\n"
	).getBytes(StandardCharsets.US_ASCII);

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Effortless Crafting container cache");
		thread.setDaemon(true);
		return thread;
	});

	// Game thread.
	private static WorldFiles attached;
	// The world the in-memory cache describes, saved to disk or not.
	private static String memoryWorldId;
	private static boolean compactionRequested;
	// The load in flight; a clear before it lands discards it.
	private static Object pendingLoad;

	// IO thread.
	private static LogWriter writer;
	private static boolean sweptUnusedWorlds;

	private ContainerCacheStore() {
	}

	record WorldFiles(String worldId, Path cache, Path log) {
	}

	static boolean enabled() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		return config.rememberContainers()
			&& config.cacheContainersForFasterSearch()
			&& config.enableNearbyContainerUsage();
	}

	/** Attach to the current world, or detach, to match the settings. */
	static void refresh(Minecraft client) {
		if (client == null) {
			return;
		}
		WorldFiles world = enabled() ? currentWorld(client) : null;
		if (attached != null && world != null && attached.worldId().equals(world.worldId())) {
			return;
		}
		detach();
		if (world != null) {
			attach(client, world);
		}
	}

	/**
	 * The client got a new level: a join, a dimension change, or a server
	 * network moving the player to another world behind the same address
	 * (which only a respawn with a new hashed seed shows). A new world must
	 * not inherit the old one's containers.
	 */
	static void onLevelChanged(Minecraft client) {
		WorldFiles world = currentWorld(client);
		if (world == null) {
			return;
		}
		if (memoryWorldId != null && !memoryWorldId.equals(world.worldId())) {
			ReachCraftingMod.diag("[container_store] world_switch from={} to={}", memoryWorldId, world.worldId());
			NearbyContainerCache.forgetKeepingSaved();
		}
		memoryWorldId = world.worldId();
		refresh(client);
	}

	/** Left the server or world: save, and let the next one start clean. */
	static void onDisconnect() {
		detach();
		memoryWorldId = null;
	}

	/** Write the whole cache for the attached world and stop journaling. */
	static void detach() {
		if (attached == null) {
			return;
		}
		WorldFiles files = attached;
		if (pendingLoad != null) {
			// Leaving before the file was even read: memory holds only what
			// this short visit saw, so keep the file and just add that.
			pendingLoad = null;
			attached = null;
			List<NearbyContainerCache.ContainerSnapshot> seen = NearbyContainerCache.snapshotsForStore();
			IO.execute(() -> {
				Map<NearbyContainerCache.ContainerKey, NearbyContainerCache.ContainerSnapshot> merged = new LinkedHashMap<>();
				for (NearbyContainerCache.ContainerSnapshot snapshot : load(files)) {
					merged.put(snapshot.key(), snapshot);
				}
				for (NearbyContainerCache.ContainerSnapshot snapshot : seen) {
					merged.put(snapshot.key(), snapshot);
				}
				compact(files, List.copyOf(merged.values()));
			});
			return;
		}
		List<NearbyContainerCache.ContainerSnapshot> state = NearbyContainerCache.snapshotsForStore();
		attached = null;
		compactionRequested = false;
		IO.execute(() -> compact(files, state));
	}

	static void shutdown() {
		detach();
		IO.shutdown();
		try {
			if (!IO.awaitTermination(5, TimeUnit.SECONDS)) {
				ReachCraftingMod.LOGGER.warn("[container_store] cache writes did not finish before shutdown");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static void journalPut(NearbyContainerCache.ContainerSnapshot snapshot) {
		WorldFiles files = attached;
		if (files == null) {
			return;
		}
		IO.execute(() -> append(files, log -> {
			NearbyContainerCache.ContainerKey key = snapshot.key();
			int dimension = log.index(dimensionId(key.dimension()));
			int type = log.index(key.type());
			int[] items = new int[snapshot.itemCounts().size() * 2];
			int i = 0;
			for (Map.Entry<String, Integer> entry : snapshot.itemCounts().entrySet()) {
				items[i++] = log.index(entry.getKey());
				items[i++] = entry.getValue();
			}
			DataOutputStream out = log.out;
			out.writeByte(OP_PUT);
			writePosition(out, dimension, type, key.anchorPos());
			writeVarLong(out, snapshot.lastSeenGameTime());
			writeVarInt(out, items.length / 2);
			for (int value : items) {
				writeVarInt(out, value);
			}
		}));
	}

	static void journalRemove(NearbyContainerCache.ContainerKey key) {
		WorldFiles files = attached;
		if (files == null) {
			return;
		}
		IO.execute(() -> append(files, log -> {
			int dimension = log.index(dimensionId(key.dimension()));
			int type = log.index(key.type());
			log.out.writeByte(OP_REMOVE);
			writePosition(log.out, dimension, type, key.anchorPos());
		}));
	}

	static void journalClear() {
		pendingLoad = null;
		WorldFiles files = attached;
		if (files == null) {
			return;
		}
		IO.execute(() -> append(files, log -> log.out.writeByte(OP_CLEAR)));
	}

	private static void attach(Minecraft client, WorldFiles files) {
		attached = files;
		compactionRequested = false;
		Object load = new Object();
		pendingLoad = load;
		IO.execute(() -> {
			sweepUnusedWorlds(files);
			List<NearbyContainerCache.ContainerSnapshot> loaded = load(files);
			client.execute(() -> {
				if (attached != files || pendingLoad != load) {
					return;
				}
				pendingLoad = null;
				boolean hadUnsaved = NearbyContainerCache.mergeLoaded(loaded);
				if (hadUnsaved) {
					// Containers seen before the store attached (the setting
					// was just turned on) are not on disk yet.
					requestCompaction();
				}
			});
		});
	}

	private static void requestCompaction() {
		if (attached == null) {
			return;
		}
		WorldFiles files = attached;
		List<NearbyContainerCache.ContainerSnapshot> state = NearbyContainerCache.snapshotsForStore();
		compactionRequested = false;
		IO.execute(() -> compact(files, state));
	}

	// ---- World identity -------------------------------------------------

	/** Where the current world's cache lives, or null when there is no world to name yet. */
	static WorldFiles currentWorld(Minecraft client) {
		IntegratedServer server = client.getSingleplayerServer();
		if (client.hasSingleplayerServer() && server != null) {
			// Inside the save itself: a deleted world takes its cache along,
			// and a copied world gets its own copy.
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path folder = root.resolve(FOLDER_NAME);
			return new WorldFiles("local:" + root, folder.resolve(CACHE_FILE), folder.resolve(CACHE_FILE + LOG_SUFFIX));
		}
		ClientPacketListener connection = client.getConnection();
		ServerData data = connection != null ? connection.getServerData() : null;
		if (data == null || data.ip == null || client.level == null) {
			return null;
		}
		String kind = "server";
		String address = data.ip.trim().toLowerCase(Locale.ROOT);
		if (data.isLan()) {
			// A LAN world opens on a new port every time.
			kind = "lan";
			address = withoutPort(address);
		}
		long hashedSeed = ((BiomeManagerAccessor) client.level.getBiomeManager()).getBiomeZoomSeed();
		String worldId = kind + ":" + address + ":" + Long.toHexString(hashedSeed);
		String readable = (kind + "_" + address).replaceAll("[^a-zA-Z0-9._-]", "_");
		if (readable.length() > 48) {
			readable = readable.substring(0, 48);
		}
		CRC32 crc = new CRC32();
		crc.update(worldId.getBytes(StandardCharsets.UTF_8));
		String name = readable + "-" + String.format(Locale.ROOT, "%08x", crc.getValue()) + "." + CACHE_FILE;
		return new WorldFiles(worldId, DIRECTORY.resolve(name), DIRECTORY.resolve(name + LOG_SUFFIX));
	}

	private static String withoutPort(String address) {
		if (address.startsWith("[")) {
			int end = address.indexOf(']');
			return end > 0 ? address.substring(0, end + 1) : address;
		}
		int colon = address.lastIndexOf(':');
		return colon > 0 && address.indexOf(':') == colon ? address.substring(0, colon) : address;
	}

	private static String dimensionId(ResourceKey<Level> dimension) {
		return dimension.location().toString();
	}

	private static ResourceKey<Level> dimensionKey(String id) {
		ResourceLocation identifier = ResourceLocation.tryParse(id);
		return identifier != null ? ResourceKey.create(Registries.DIMENSION, identifier) : null;
	}

	// ---- IO thread ------------------------------------------------------

	private interface LogRecord {
		void write(LogWriter log) throws IOException;
	}

	private static final class LogWriter {
		final WorldFiles files;
		final OutputStream file;
		final DataOutputStream out;
		final Map<String, Integer> palette = new HashMap<>();

		LogWriter(WorldFiles files) throws IOException {
			this.files = files;
			Files.createDirectories(files.log().getParent());
			// The log of a world is always folded into its cache file when the
			// store attaches, so a writer starts a fresh one.
			this.file = Files.newOutputStream(files.log(),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			this.out = new DataOutputStream(new BufferedOutputStream(file, 8192));
			out.write(FILE_HEADER);
			out.writeInt(LOG_MAGIC);
			writeVarInt(out, FORMAT_VERSION);
		}

		int index(String value) throws IOException {
			Integer existing = palette.get(value);
			if (existing != null) {
				return existing;
			}
			int index = palette.size();
			palette.put(value, index);
			out.writeByte(OP_DEFINE);
			out.writeUTF(value);
			return index;
		}

		void close() {
			try {
				out.close();
			} catch (IOException e) {
				ReachCraftingMod.LOGGER.warn("[container_store] failed to close {}: {}", files.log().getFileName(), e.toString());
			}
		}
	}

	private static void append(WorldFiles files, LogRecord record) {
		try {
			if (writer == null || writer.files != files) {
				closeWriter();
				writer = new LogWriter(files);
			}
			record.write(writer);
			writer.out.flush();
			if (writer.out.size() > COMPACT_AFTER_LOG_BYTES) {
				Minecraft client = Minecraft.getInstance();
				client.execute(() -> {
					if (attached == files && !compactionRequested) {
						compactionRequested = true;
						requestCompaction();
					}
				});
			}
		} catch (IOException e) {
			ReachCraftingMod.LOGGER.warn("[container_store] failed to append to {}: {}", files.log().getFileName(), e.toString());
			closeWriter();
			// A fresh writer starts a new log, so save the whole cache first
			// to keep what the torn log held.
			Minecraft.getInstance().execute(() -> {
				if (attached == files) {
					requestCompaction();
				}
			});
		}
	}

	private static void closeWriter() {
		if (writer != null) {
			writer.close();
			writer = null;
		}
	}

	private static List<NearbyContainerCache.ContainerSnapshot> load(WorldFiles files) {
		long startNanos = System.nanoTime();
		Map<NearbyContainerCache.ContainerKey, NearbyContainerCache.ContainerSnapshot> state = new LinkedHashMap<>();
		if (Files.exists(files.cache())) {
			try (InputStream raw = new BufferedInputStream(Files.newInputStream(files.cache()))) {
				skipHeader(raw);
				DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw, 1 << 16)));
				readCache(in, state);
			} catch (IOException | RuntimeException e) {
				ReachCraftingMod.LOGGER.warn("[container_store] unreadable {} (starting fresh): {}", files.cache().getFileName(), e.toString());
				state.clear();
			}
		}
		int replayed = 0;
		boolean hadLog = Files.exists(files.log());
		if (hadLog) {
			try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(files.log())))) {
				skipHeader(in);
				replayed = replayLog(in, state);
			} catch (IOException | RuntimeException e) {
				ReachCraftingMod.LOGGER.warn("[container_store] unreadable {}: {}", files.log().getFileName(), e.toString());
			}
		}
		List<NearbyContainerCache.ContainerSnapshot> loaded = List.copyOf(state.values());
		if (hadLog) {
			compact(files, loaded);
		}
		ReachCraftingMod.diag(
			"[container_store] loaded world={} containers={} log_records={} ms={}",
			files.worldId(),
			loaded.size(),
			replayed,
			(System.nanoTime() - startNanos) / 1_000_000L
		);
		return loaded;
	}

	private static void compact(WorldFiles files, List<NearbyContainerCache.ContainerSnapshot> snapshots) {
		if (writer != null && writer.files == files) {
			closeWriter();
		}
		List<NearbyContainerCache.ContainerSnapshot> kept = new ArrayList<>(snapshots);
		if (kept.size() > MAX_CONTAINERS_PER_WORLD) {
			kept.sort(Comparator.comparingLong(NearbyContainerCache.ContainerSnapshot::lastSeenGameTime).reversed());
			kept = new ArrayList<>(kept.subList(0, MAX_CONTAINERS_PER_WORLD));
		}
		Path temp = files.cache().resolveSibling(files.cache().getFileName() + ".tmp");
		try {
			Files.createDirectories(files.cache().getParent());
			try (OutputStream raw = Files.newOutputStream(temp)) {
				raw.write(FILE_HEADER);
				DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw, 1 << 16)));
				writeCache(out, kept);
				out.close();
			}
			try {
				Files.move(temp, files.cache(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, files.cache(), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.deleteIfExists(files.log());
			ReachCraftingMod.diag(
				"[container_store] saved world={} containers={} bytes={}",
				files.worldId(),
				kept.size(),
				Files.size(files.cache())
			);
		} catch (IOException e) {
			// The log stays in place, so the next load still has every change.
			ReachCraftingMod.LOGGER.warn("[container_store] failed to save {}: {}", files.cache().getFileName(), e.toString());
		}
	}

	private static void sweepUnusedWorlds(WorldFiles current) {
		if (sweptUnusedWorlds || !Files.isDirectory(DIRECTORY)) {
			return;
		}
		sweptUnusedWorlds = true;
		long cutoff = System.currentTimeMillis() - FORGET_UNUSED_WORLD_MILLIS;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(DIRECTORY)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (entry.equals(current.cache()) || entry.equals(current.log())) {
					continue;
				}
				// Files of the unreleased first format, before the header.
				boolean preRelease = name.endsWith(".eccache") || name.endsWith(".eccache.log");
				if (preRelease || Files.getLastModifiedTime(entry).toMillis() < cutoff) {
					Files.deleteIfExists(entry);
					ReachCraftingMod.diag("[container_store] forgot unused world file {}", entry.getFileName());
				}
			}
		} catch (IOException e) {
			ReachCraftingMod.LOGGER.warn("[container_store] failed to sweep old world files: {}", e.toString());
		}
	}

	// ---- Cache file -----------------------------------------------------

	private static void writeCache(DataOutputStream out, List<NearbyContainerCache.ContainerSnapshot> snapshots) throws IOException {
		Map<String, Integer> palette = new LinkedHashMap<>();
		List<NearbyContainerCache.ContainerSnapshot> sorted = new ArrayList<>(snapshots);
		sorted.sort(Comparator
			.comparing((NearbyContainerCache.ContainerSnapshot s) -> dimensionId(s.key().dimension()))
			.thenComparingInt(s -> s.key().anchorPos().getX())
			.thenComparingInt(s -> s.key().anchorPos().getZ())
			.thenComparingInt(s -> s.key().anchorPos().getY()));
		for (NearbyContainerCache.ContainerSnapshot snapshot : sorted) {
			palette.putIfAbsent(dimensionId(snapshot.key().dimension()), palette.size());
			palette.putIfAbsent(snapshot.key().type(), palette.size());
			for (String itemId : snapshot.itemCounts().keySet()) {
				palette.putIfAbsent(itemId, palette.size());
			}
		}

		out.writeInt(CACHE_MAGIC);
		writeVarInt(out, FORMAT_VERSION);
		writeVarInt(out, palette.size());
		for (String value : palette.keySet()) {
			out.writeUTF(value);
		}
		writeVarInt(out, sorted.size());
		int previousDimension = -1;
		int x = 0;
		int y = 0;
		int z = 0;
		long time = 0L;
		for (NearbyContainerCache.ContainerSnapshot snapshot : sorted) {
			NearbyContainerCache.ContainerKey key = snapshot.key();
			int dimension = palette.get(dimensionId(key.dimension()));
			if (dimension != previousDimension) {
				previousDimension = dimension;
				x = 0;
				y = 0;
				z = 0;
			}
			BlockPos pos = key.anchorPos();
			writeVarInt(out, dimension);
			writeVarInt(out, palette.get(key.type()));
			writeVarInt(out, zigzag(pos.getX() - x));
			writeVarInt(out, zigzag(pos.getY() - y));
			writeVarInt(out, zigzag(pos.getZ() - z));
			x = pos.getX();
			y = pos.getY();
			z = pos.getZ();
			writeVarLong(out, zigzag(snapshot.lastSeenGameTime() - time));
			time = snapshot.lastSeenGameTime();
			writeVarInt(out, snapshot.itemCounts().size());
			for (Map.Entry<String, Integer> entry : snapshot.itemCounts().entrySet()) {
				writeVarInt(out, palette.get(entry.getKey()));
				writeVarInt(out, entry.getValue());
			}
		}
	}

	private static void readCache(
		DataInputStream in,
		Map<NearbyContainerCache.ContainerKey, NearbyContainerCache.ContainerSnapshot> state
	) throws IOException {
		if (in.readInt() != CACHE_MAGIC) {
			throw new IOException("not a container cache file");
		}
		int version = readVarInt(in);
		switch (version) {
			case 1 -> readCacheV1(in, state);
			default -> throw new IOException("format version " + version + " is newer than this mod");
		}
	}

	private static void readCacheV1(
		DataInputStream in,
		Map<NearbyContainerCache.ContainerKey, NearbyContainerCache.ContainerSnapshot> state
	) throws IOException {
		int paletteSize = readVarInt(in);
		List<String> palette = new ArrayList<>(paletteSize);
		for (int i = 0; i < paletteSize; i++) {
			palette.add(in.readUTF());
		}
		Map<String, ResourceKey<Level>> dimensions = new HashMap<>();
		int entries = readVarInt(in);
		int previousDimension = -1;
		int x = 0;
		int y = 0;
		int z = 0;
		long time = 0L;
		for (int i = 0; i < entries; i++) {
			int dimension = readVarInt(in);
			if (dimension != previousDimension) {
				previousDimension = dimension;
				x = 0;
				y = 0;
				z = 0;
			}
			String type = palette.get(readVarInt(in));
			x += unzigzag(readVarInt(in));
			y += unzigzag(readVarInt(in));
			z += unzigzag(readVarInt(in));
			time += unzigzag(readVarLong(in));
			Map<String, Integer> items = readItems(in, palette);
			ResourceKey<Level> dimensionKey = dimensions.computeIfAbsent(palette.get(dimension), ContainerCacheStore::dimensionKey);
			if (dimensionKey == null) {
				continue;
			}
			NearbyContainerCache.ContainerKey key = new NearbyContainerCache.ContainerKey(dimensionKey, type, new BlockPos(x, y, z));
			state.put(key, new NearbyContainerCache.ContainerSnapshot(key, items, time));
		}
	}

	// ---- Log file -------------------------------------------------------

	/** Applies log records in order; a torn last record (a crash mid-write) ends the replay. */
	private static int replayLog(
		DataInputStream in,
		Map<NearbyContainerCache.ContainerKey, NearbyContainerCache.ContainerSnapshot> state
	) throws IOException {
		if (in.readInt() != LOG_MAGIC) {
			throw new IOException("not a container cache log");
		}
		int version = readVarInt(in);
		if (version > FORMAT_VERSION) {
			throw new IOException("log format version " + version + " is newer than this mod");
		}
		List<String> palette = new ArrayList<>();
		Map<String, ResourceKey<Level>> dimensions = new HashMap<>();
		int records = 0;
		try {
			while (true) {
				int op = in.read();
				if (op == -1) {
					break;
				}
				switch (op) {
					case OP_DEFINE -> palette.add(in.readUTF());
					case OP_PUT -> {
						NearbyContainerCache.ContainerKey key = readPosition(in, palette, dimensions);
						long time = readVarLong(in);
						Map<String, Integer> items = readItems(in, palette);
						if (key != null) {
							state.put(key, new NearbyContainerCache.ContainerSnapshot(key, items, time));
						}
					}
					case OP_REMOVE -> {
						NearbyContainerCache.ContainerKey key = readPosition(in, palette, dimensions);
						if (key != null) {
							state.remove(key);
						}
					}
					case OP_CLEAR -> state.clear();
					default -> throw new IOException("unknown log record " + op);
				}
				records++;
			}
		} catch (EOFException e) {
			ReachCraftingMod.diag("[container_store] log ended mid-record after {} records", records);
		}
		return records;
	}

	private static void writePosition(DataOutputStream out, int dimension, int type, BlockPos pos) throws IOException {
		writeVarInt(out, dimension);
		writeVarInt(out, type);
		writeVarInt(out, zigzag(pos.getX()));
		writeVarInt(out, zigzag(pos.getY()));
		writeVarInt(out, zigzag(pos.getZ()));
	}

	private static NearbyContainerCache.ContainerKey readPosition(
		DataInputStream in,
		List<String> palette,
		Map<String, ResourceKey<Level>> dimensions
	) throws IOException {
		String dimension = palette.get(readVarInt(in));
		String type = palette.get(readVarInt(in));
		BlockPos pos = new BlockPos(unzigzag(readVarInt(in)), unzigzag(readVarInt(in)), unzigzag(readVarInt(in)));
		ResourceKey<Level> dimensionKey = dimensions.computeIfAbsent(dimension, ContainerCacheStore::dimensionKey);
		return dimensionKey != null ? new NearbyContainerCache.ContainerKey(dimensionKey, type, pos) : null;
	}

	private static Map<String, Integer> readItems(DataInputStream in, List<String> palette) throws IOException {
		int count = readVarInt(in);
		Map<String, Integer> items = new LinkedHashMap<>(Math.max(4, count * 2));
		for (int i = 0; i < count; i++) {
			String itemId = palette.get(readVarInt(in));
			int amount = readVarInt(in);
			if (amount > 0) {
				items.merge(itemId, amount, Integer::sum);
			}
		}
		return Map.copyOf(items);
	}

	// ---- Header ----------------------------------------------

	/** Skips the plain-text first line. */
	private static void skipHeader(InputStream in) throws IOException {
		for (int i = 0; i < 1024; i++) {
			int b = in.read();
			if (b == -1) {
				throw new EOFException();
			}
			if (b == '\n') {
				return;
			}
		}
		throw new IOException("no header line");
	}

	// ---- Varints --------------------------------------------------------

	private static int zigzag(int value) {
		return (value << 1) ^ (value >> 31);
	}

	private static long zigzag(long value) {
		return (value << 1) ^ (value >> 63);
	}

	private static int unzigzag(int value) {
		return (value >>> 1) ^ -(value & 1);
	}

	private static long unzigzag(long value) {
		return (value >>> 1) ^ -(value & 1L);
	}

	private static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	private static void writeVarLong(DataOutputStream out, long value) throws IOException {
		while ((value & ~0x7FL) != 0L) {
			out.writeByte((int) (value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte((int) value);
	}

	private static int readVarInt(InputStream in) throws IOException {
		int value = 0;
		for (int shift = 0; shift < 35; shift += 7) {
			int b = in.read();
			if (b == -1) {
				throw new EOFException();
			}
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new IOException("varint too long");
	}

	private static long readVarLong(InputStream in) throws IOException {
		long value = 0L;
		for (int shift = 0; shift < 70; shift += 7) {
			int b = in.read();
			if (b == -1) {
				throw new EOFException();
			}
			value |= (long) (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new IOException("varlong too long");
	}
}
