package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/**
 * NearbyContainerCache owns cached observed contents plus the currently tracked container UI context.
 * Invariants: cache snapshots are independent of UI tracking; filter-dot eligibility is derived from tracked state.
 *
 * <p>Snapshots persist per world through {@link ContainerCacheStore}, so they
 * outlive unloaded chunks and relaunches. An entry is only dropped when its
 * chunk is loaded and the container there is gone or changed shape: block
 * entity and chunk loads queue their chunks for that check.</p>
 */
public final class NearbyContainerCache {
	private static final Map<ContainerKey, ContainerSnapshot> SNAPSHOTS = new LinkedHashMap<>();
	private static final Map<ChunkRef, Set<ContainerKey>> KEYS_BY_CHUNK = new HashMap<>();
	private static final Set<ChunkRef> PENDING_CHUNK_CHECKS = new LinkedHashSet<>();
	// Last container each item id was withdrawn from; the LARGEST_FIRST drain
	// order keeps pulling from it until empty instead of re-targeting the
	// currently fullest container every pull (which depletes stocks evenly).
	private static final Map<String, ContainerKey> LAST_DRAIN_SOURCE_BY_ITEM = new HashMap<>();
	private static long revision;
	private static ViewCacheKey lastViewKey;
	private static ReachableView lastView;
	private static final TrackedContainerContext TRACKED_CONTEXT = new TrackedContainerContext();
	private static ContainerContents lastOpenContents;
	private static Level lastLevel;

	private NearbyContainerCache() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			TRACKED_CONTEXT.tick();
			if (client.level != lastLevel) {
				// A join, a dimension change, or a network's world switch.
				lastLevel = client.level;
				if (client.level != null) {
					ContainerCacheStore.onLevelChanged(client);
				}
			}
			if (client.level == null) {
				PENDING_CHUNK_CHECKS.clear();
				lastOpenContents = null;
				return;
			}
			recordOpenContainerChanges(client);
			checkPendingChunks(client.level);
		});
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> queueChunkCheck(
			level.dimension(), chunk.getPos().getMinBlockX() >> 4, chunk.getPos().getMinBlockZ() >> 4));
		ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> queueBlockCheck(level, blockEntity.getBlockPos()));
		ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> queueBlockCheck(level, blockEntity.getBlockPos()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			// Keys carry a dimension but not a world, so nothing may cross
			// into the next world joined.
			ContainerCacheStore.onDisconnect();
			clearMemory();
		}));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ContainerCacheStore.shutdown());
	}

	/**
	 * Forget every container, on disk too. Only the harness does this:
	 * settings changes keep the saved files, and a player who wants a world
	 * forgotten deletes its file.
	 */
	public static void clear() {
		ContainerCacheStore.journalClear();
		clearMemory();
	}

	/**
	 * Settings turned the cache off: save what is known, then forget it in
	 * memory. Turning it back on loads the file again.
	 */
	public static void forgetKeepingSaved() {
		ContainerCacheStore.detach();
		clearMemory();
	}

	private static void clearMemory() {
		SNAPSHOTS.clear();
		KEYS_BY_CHUNK.clear();
		PENDING_CHUNK_CHECKS.clear();
		LAST_DRAIN_SOURCE_BY_ITEM.clear();
		revision++;
		lastViewKey = null;
		lastView = null;
		lastOpenContents = null;
		TRACKED_CONTEXT.clear();
	}

	/** Harness: what a leave and rejoin does, without leaving. */
	static void reloadFromStore(Minecraft client) {
		ContainerCacheStore.detach();
		clearMemory();
		ContainerCacheStore.refresh(client);
	}

	/** The snapshots to write to disk; immutable values, safe to hand to another thread. */
	static List<ContainerSnapshot> snapshotsForStore() {
		return List.copyOf(SNAPSHOTS.values());
	}

	/**
	 * Adds containers read from disk. Anything already seen this session is
	 * newer and wins. Returns whether memory held containers before the load.
	 */
	static boolean mergeLoaded(List<ContainerSnapshot> loaded) {
		boolean hadUnsaved = !SNAPSHOTS.isEmpty();
		int added = 0;
		for (ContainerSnapshot snapshot : loaded) {
			if (SNAPSHOTS.containsKey(snapshot.key())) {
				continue;
			}
			SNAPSHOTS.put(snapshot.key(), snapshot);
			indexKey(snapshot.key());
			// The chunk may have loaded before the file did, so its own load
			// check came too early for these entries.
			PENDING_CHUNK_CHECKS.add(ChunkRef.of(snapshot.key()));
			added++;
		}
		if (added > 0) {
			bumpRevision();
		}
		ReachCraftingMod.diag("[container_cache] merged_from_disk added={} already_known={}", added, loaded.size() - added);
		return hadUnsaved;
	}

	private static void putSnapshot(ContainerSnapshot snapshot) {
		SNAPSHOTS.put(snapshot.key(), snapshot);
		indexKey(snapshot.key());
		ContainerCacheStore.journalPut(snapshot);
	}

	private static void removeSnapshot(ContainerKey key) {
		if (SNAPSHOTS.remove(key) == null) {
			return;
		}
		ChunkRef ref = ChunkRef.of(key);
		Set<ContainerKey> keys = KEYS_BY_CHUNK.get(ref);
		if (keys != null) {
			keys.remove(key);
			if (keys.isEmpty()) {
				KEYS_BY_CHUNK.remove(ref);
			}
		}
		ContainerCacheStore.journalRemove(key);
	}

	private static void indexKey(ContainerKey key) {
		KEYS_BY_CHUNK.computeIfAbsent(ChunkRef.of(key), ignored -> new LinkedHashSet<>()).add(key);
	}

	private static void queueChunkCheck(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
		ChunkRef ref = new ChunkRef(dimension, chunkX, chunkZ);
		if (KEYS_BY_CHUNK.containsKey(ref)) {
			PENDING_CHUNK_CHECKS.add(ref);
		}
	}

	/**
	 * A block entity came or went. A double chest is keyed at its lower half,
	 * which may sit in the neighbouring chunk, so the neighbours' chunks are
	 * checked too.
	 */
	private static void queueBlockCheck(Level level, BlockPos pos) {
		if (KEYS_BY_CHUNK.isEmpty() || level == null || pos == null) {
			return;
		}
		ResourceKey<Level> dimension = level.dimension();
		queueChunkCheck(dimension, pos.getX() >> 4, pos.getZ() >> 4);
		queueChunkCheck(dimension, (pos.getX() - 1) >> 4, pos.getZ() >> 4);
		queueChunkCheck(dimension, (pos.getX() + 1) >> 4, pos.getZ() >> 4);
		queueChunkCheck(dimension, pos.getX() >> 4, (pos.getZ() - 1) >> 4);
		queueChunkCheck(dimension, pos.getX() >> 4, (pos.getZ() + 1) >> 4);
	}

	/**
	 * Whether the client really holds this chunk. hasChunk is no answer: a
	 * dropped chunk still reads as present on the client, as an empty
	 * stand-in full of void air.
	 */
	private static boolean isChunkLoaded(Level level, int chunkX, int chunkZ) {
		return level.hasChunk(chunkX, chunkZ) && !(level.getChunk(chunkX, chunkZ) instanceof EmptyLevelChunk);
	}

	/** Runs a tick after the events, once the chunk's block entities are in place. */
	private static void checkPendingChunks(Level level) {
		if (PENDING_CHUNK_CHECKS.isEmpty()) {
			return;
		}
		List<ChunkRef> refs = List.copyOf(PENDING_CHUNK_CHECKS);
		PENDING_CHUNK_CHECKS.clear();
		int removed = 0;
		for (ChunkRef ref : refs) {
			// Another dimension or an unloaded chunk tells nothing; its own
			// chunk load queues it again.
			if (!ref.dimension().equals(level.dimension()) || !isChunkLoaded(level, ref.chunkX(), ref.chunkZ())) {
				continue;
			}
			Set<ContainerKey> keys = KEYS_BY_CHUNK.get(ref);
			if (keys == null) {
				continue;
			}
			for (ContainerKey key : List.copyOf(keys)) {
				if (!isSnapshotStillValid(level, key)) {
					ReachCraftingMod.diag(
						"[container_cache] drop key={} has_chunk={} state={} block_entity={}",
						key,
						isChunkLoaded(level, key.anchorPos().getX() >> 4, key.anchorPos().getZ() >> 4),
						level.getBlockState(key.anchorPos()),
						level.getBlockEntity(key.anchorPos())
					);
					removeSnapshot(key);
					removed++;
				}
			}
		}
		if (removed > 0) {
			ReachCraftingMod.diag("[container_cache] dropped_gone_containers count={}", removed);
			bumpRevision();
		}
	}

	/**
	 * A container the player opened by hand: record every change while it is
	 * open, not just at close, so the cache (and the disk) never lag behind.
	 */
	private static void recordOpenContainerChanges(Minecraft client) {
		BlockPos pos = TRACKED_CONTEXT.confirmedPos();
		AbstractContainerMenu menu = client.player != null ? client.player.containerMenu : null;
		if (pos == null || menu == null || !TRACKED_CONTEXT.matchesConfirmedMenu(menu.containerId)
			|| NearbyContainerDryRun.isActiveSessionRunning()) {
			lastOpenContents = null;
			return;
		}
		ContainerContents contents = ContainerUtils.collectContents(menu);
		if (contents.equals(lastOpenContents)) {
			return;
		}
		boolean firstLook = lastOpenContents == null;
		lastOpenContents = contents;
		if (!firstLook) {
			// The open itself was recorded when the contents arrived.
			recordObservedContents(client.level, pos, contents);
		}
	}

	public static boolean hasTrackedContainer() {
		return TRACKED_CONTEXT.hasTrackedContainer();
	}

	public static boolean isTrackedContainerEligibleForFilterUi() {
		BlockPos trackedPos = TRACKED_CONTEXT.trackedPos();
		if (trackedPos == null) {
			return false;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return false;
		}
		if (!(client.screen instanceof AbstractContainerScreen<?>)
			|| client.screen instanceof InventoryScreen
			|| client.screen instanceof CraftingScreen) {
			return false;
		}

		BlockState state = client.level.getBlockState(trackedPos);
		if (!ContainerUtils.isPotentiallySupportedContainer(state) || !ContainerUtils.canAttemptOpen(client.level, trackedPos, state)) {
			return false;
		}

		if (TRACKED_CONTEXT.confirmedContainerId() == -1) {
			return TRACKED_CONTEXT.hasLiveCandidate();
		}

		return client.player.containerMenu != null && TRACKED_CONTEXT.matchesConfirmedMenu(client.player.containerMenu.containerId);
	}

	public static boolean isCurrentContainerSupported() {
		return isTrackedContainerEligibleForFilterUi();
	}

	public static BlockPos getCurrentContainerPos() {
		return TRACKED_CONTEXT.trackedPos();
	}

	public static boolean isCurrentContainerActive() {
		BlockPos trackedPos = TRACKED_CONTEXT.trackedPos();
		if (trackedPos == null || !ReachCraftingConfig.get().enableNearbyContainerUsage()) return false;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return false;
		BlockState state = client.level.getBlockState(trackedPos);
		return InWorldFilterManager.isContainerActive(client.level, trackedPos, state);
	}

	public static void toggleTrackedContainerInclusion() {
		BlockPos trackedPos = TRACKED_CONTEXT.trackedPos();
		if (trackedPos == null) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		BlockState state = client.level.getBlockState(trackedPos);
		InWorldFilterManager.toggleInclusion(client.level, trackedPos, state);
	}

	public static void toggleCurrentContainerInclusion() {
		toggleTrackedContainerInclusion();
	}

	public static ReachableView getReachableView(Level level, Entity cameraEntity, double reachDistance) {
		if (!ReachCraftingConfig.get().cacheContainersForFasterSearch() || !ReachCraftingConfig.get().enableNearbyContainerUsage()) {
			return ReachableView.empty(revision);
		}
		if (level == null || cameraEntity == null) {
			return ReachableView.empty(revision);
		}

		BlockPos center = BlockPos.containing(cameraEntity.getEyePosition(0));
		ViewCacheKey cacheKey = new ViewCacheKey(level.dimension(), center, Mth.ceil(reachDistance), revision);
		if (cacheKey.equals(lastViewKey) && lastView != null) {
			return lastView;
		}

		Vec3 eyePos = cameraEntity.getEyePosition(0);
		int radius = Mth.ceil(reachDistance);
		Map<ContainerKey, ContainerSnapshot> includedSnapshots = new LinkedHashMap<>();
		Map<BlockPos, ContainerKey> accessKeysByPos = new LinkedHashMap<>();
		Map<ContainerKey, BlockPos> nearestAccessByKey = new LinkedHashMap<>();
		Set<ContainerKey> visitedKeys = new LinkedHashSet<>();

		for (BlockPos pos : BlockPos.betweenClosed(
			center.offset(-radius, -radius, -radius),
			center.offset(radius, radius, radius)
		)) {
			BlockPos immutablePos = pos.immutable();
			BlockState state = level.getBlockState(immutablePos);
			if (!InWorldFilterManager.isContainerActive(level, immutablePos, state) || !ContainerUtils.canAttemptOpen(level, immutablePos, state)) {
				continue;
			}
			if (ContainerUtils.squaredDistanceToBlock(eyePos, immutablePos) > Mth.square(reachDistance)) {
				continue;
			}

			ContainerKey key = resolveContainerKey(level, immutablePos, state);
			if (key == null) {
				continue;
			}

			accessKeysByPos.put(immutablePos, key);
			BlockPos currentNearest = nearestAccessByKey.get(key);
			if (currentNearest == null || ContainerUtils.squaredDistanceToBlock(eyePos, immutablePos) < ContainerUtils.squaredDistanceToBlock(eyePos, currentNearest)) {
				nearestAccessByKey.put(key, immutablePos);
			}

			if (!visitedKeys.add(key)) {
				continue;
			}

			ContainerSnapshot snapshot = SNAPSHOTS.get(key);
			if (snapshot != null) {
				includedSnapshots.put(key, snapshot);
			}
		}

		Map<String, Integer> aggregateCounts = new LinkedHashMap<>();
		Map<String, Integer> craftableCounts = new LinkedHashMap<>();
		Map<String, Integer> chainCraftableCounts = new LinkedHashMap<>();
		for (ContainerSnapshot snapshot : includedSnapshots.values()) {
			ContainerContents contents = snapshot.contents();
			contents.itemCounts().forEach((itemId, count) -> aggregateCounts.merge(itemId, count, Integer::sum));
			contents.craftableCounts().forEach((itemId, count) -> craftableCounts.merge(itemId, count, Integer::sum));
			contents.chainCraftableCounts().forEach((itemId, count) -> chainCraftableCounts.merge(itemId, count, Integer::sum));
		}

		lastViewKey = cacheKey;
		Map<String, Integer> aggregate = Map.copyOf(aggregateCounts);
		Map<String, Integer> craftable = Map.copyOf(craftableCounts);
		Map<String, Integer> chainCraftable = Map.copyOf(chainCraftableCounts);
		lastView = new ReachableView(
			cacheKey.revision(),
			aggregate,
			craftable,
			chainCraftable,
			java.util.Objects.hash(aggregate, craftable, chainCraftable),
			Map.copyOf(includedSnapshots),
			Map.copyOf(accessKeysByPos),
			Map.copyOf(nearestAccessByKey)
		);
		return lastView;
	}

	public static List<BlockPos> prioritizeCandidates(
		List<BlockPos> candidates,
		ReachableView reachableView,
		Set<String> acceptedItemIds
	) {
		if (!ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			return candidates;
		}
		if (candidates.isEmpty() || reachableView.isEmpty() || acceptedItemIds.isEmpty()) {
			return candidates;
		}

		Map<BlockPos, Integer> originalOrder = new HashMap<>();
		for (int i = 0; i < candidates.size(); i++) {
			originalOrder.put(candidates.get(i), i);
		}

		List<BlockPos> prioritized = new ArrayList<>(candidates);
		// Containers actually holding the needed items always come first;
		// among them the drain-order policy decides which stock to attack.
		// Smallest-first is naturally sticky (a partially drained container
		// stays the smallest until empty); largest-first needs the recorded
		// drain source boosted or every pull re-targets whichever container
		// is currently fullest and all stocks deplete evenly.
		Comparator<BlockPos> comparator = Comparator.comparingInt(
			(BlockPos pos) -> reachableView.relevantCountAt(pos, acceptedItemIds) > 0 ? 0 : 1);
		if (ReachCraftingConfig.get().containerDrainOrder() == ReachCraftingConfig.ContainerDrainOrder.SMALLEST_FIRST) {
			comparator = comparator.thenComparingInt(pos -> reachableView.relevantCountAt(pos, acceptedItemIds));
		} else {
			comparator = comparator
				.thenComparingInt(pos -> reachableView.isRecentDrainSourceAt(pos, acceptedItemIds) ? 0 : 1)
				.thenComparing(Comparator.comparingInt(
					(BlockPos pos) -> reachableView.relevantCountAt(pos, acceptedItemIds)).reversed());
		}
		prioritized.sort(comparator.thenComparingInt(pos -> originalOrder.getOrDefault(pos, Integer.MAX_VALUE)));
		return List.copyOf(prioritized);
	}

	public static void recordObservedContents(Level level, BlockPos observedPos, ContainerContents observed) {
		if (!ReachCraftingConfig.get().cacheContainersForFasterSearch() || !ReachCraftingConfig.get().enableNearbyContainerUsage()) {
			return;
		}
		ContainerKey key = resolveContainerKey(level, observedPos);
		if (key == null) {
			return;
		}

		ContainerContents normalized = observed.normalized();
		ContainerSnapshot previous = SNAPSHOTS.get(key);
		// The flags count too: an enchanted bow swapped for a plain one keeps
		// the totals but changes what crafting may take.
		if (previous != null && previous.contents().equals(normalized)) {
			// Unchanged, but seen: the time says how fresh the entry is, and
			// the per-world cap drops the stalest first.
			putSnapshot(new ContainerSnapshot(key, normalized, level.getGameTime()));
			return;
		}

		putSnapshot(new ContainerSnapshot(key, normalized, level.getGameTime()));
		bumpRevision();
	}

	public static void applyWithdrawals(Level level, BlockPos observedPos, Map<String, Integer> withdrawnCounts) {
		if (!ReachCraftingConfig.get().cacheContainersForFasterSearch()) {
			return;
		}
		if (withdrawnCounts.isEmpty()) {
			return;
		}

		ContainerKey key = resolveContainerKey(level, observedPos);
		if (key == null) {
			return;
		}

		for (String itemId : withdrawnCounts.keySet()) {
			LAST_DRAIN_SOURCE_BY_ITEM.put(itemId, key);
		}

		ContainerSnapshot snapshot = SNAPSHOTS.get(key);
		if (snapshot == null) {
			return;
		}

		Map<String, Integer> updatedCounts = new LinkedHashMap<>(snapshot.itemCounts());
		boolean changed = false;
		for (Map.Entry<String, Integer> entry : withdrawnCounts.entrySet()) {
			int current = updatedCounts.getOrDefault(entry.getKey(), 0);
			int updated = Math.max(0, current - entry.getValue());
			if (updated != current) {
				changed = true;
			}
			if (updated <= 0) {
				updatedCounts.remove(entry.getKey());
			} else {
				updatedCounts.put(entry.getKey(), updated);
			}
		}

		if (!changed) {
			return;
		}

		// Withdrawals name ids, not stacks: a retrieval may have taken an
		// enchanted copy, so the flags are only clamped to what is left.
		ContainerContents updated = new ContainerContents(updatedCounts, snapshot.contents().flaggedCounts()).normalized();
		putSnapshot(new ContainerSnapshot(key, updated, level.getGameTime()));
		bumpRevision();
	}

	public static void notePotentialContainerInteraction(Level level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}

		BlockState state = level.getBlockState(pos);
		if (!ContainerUtils.isPotentiallySupportedContainer(state) || !ContainerUtils.canAttemptOpen(level, pos, state)) {
			return;
		}

		TRACKED_CONTEXT.noteCandidate(level, pos, state);
	}

	public static void onContainerContentsInitialized(AbstractContainerMenu menu) {
		if (NearbyContainerDryRun.isActiveSessionRunning()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			return;
		}
		if (!TRACKED_CONTEXT.hasLiveCandidate()) {
			return;
		}
		
		BlockState state = client.level.getBlockState(TRACKED_CONTEXT.candidatePos());
		if (!ContainerUtils.isPotentiallySupportedContainer(state)) {
			TRACKED_CONTEXT.clearCandidate();
			return;
		}

		if (menu instanceof InventoryMenu || client.screen instanceof InventoryScreen || client.screen instanceof CraftingScreen) {
			TRACKED_CONTEXT.clearCandidate();
			return;
		}
		if (!(client.screen instanceof AbstractContainerScreen<?>)) {
			return;
		}

		recordObservedContents(client.level, TRACKED_CONTEXT.candidatePos(), ContainerUtils.collectContents(menu));
		TRACKED_CONTEXT.confirmOpen(menu.containerId);
	}

	public static void onContainerScreenRemoved(AbstractContainerMenu menu) {
		if (menu == null || !TRACKED_CONTEXT.matchesConfirmedMenu(menu.containerId)) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.level != null) {
			recordObservedContents(client.level, TRACKED_CONTEXT.confirmedPos(), ContainerUtils.collectContents(menu));
		}
		TRACKED_CONTEXT.clearConfirmed();
	}

	public static void bumpRevision() {
		revision++;
		lastViewKey = null;
		lastView = null;
	}

	private static boolean isSnapshotStillValid(Level level, ContainerKey key) {
		if ("ender_chest".equals(key.type())) {
			return true;
		}

		BlockPos anchorPos = key.anchorPos();
		if (!isChunkLoaded(level, anchorPos.getX() >> 4, anchorPos.getZ() >> 4)) {
			// Unloaded is not gone: the client sees air there.
			return true;
		}
		// Only a missing container counts, not a blocked lid (a cat, a block
		// on top): the reachable view skips that one instead.
		BlockState state = level.getBlockState(anchorPos);
		if (!ContainerUtils.isPotentiallySupportedContainer(state) || !(level.getBlockEntity(anchorPos) instanceof Container)) {
			return false;
		}

		ContainerKey currentKey = resolveContainerKey(level, anchorPos, state);
		return key.equals(currentKey);
	}


	/** Level-based variant for callers without a ReachableView (SearchSession). */
	static boolean isRecentDrainSource(Level level, BlockPos pos, Set<String> itemIds) {
		ContainerKey key = resolveContainerKey(level, pos);
		if (key == null) {
			return false;
		}
		for (String itemId : itemIds) {
			if (key.equals(LAST_DRAIN_SOURCE_BY_ITEM.get(itemId))) {
				return true;
			}
		}
		return false;
	}

	private static ContainerKey resolveContainerKey(Level level, BlockPos pos) {
		if (level == null || pos == null) {
			return null;
		}
		return resolveContainerKey(level, pos, level.getBlockState(pos));
	}

	private static ContainerKey resolveContainerKey(Level level, BlockPos pos, BlockState state) {
		if (!ContainerUtils.isPotentiallySupportedContainer(state)) {
			return null;
		}

		ResourceKey<Level> dimension = level.dimension();
		if (state.getBlock() instanceof EnderChestBlock) {
			return new ContainerKey(dimension, "ender_chest", BlockPos.ZERO);
		}

		BlockPos anchor = ContainerUtils.canonicalizeContainerPos(level, pos, state);
		String type = state.getBlock() instanceof ChestBlock ? "chest_like" : state.getBlock().getClass().getSimpleName();
		return new ContainerKey(dimension, type, anchor);
	}



	/**
	 * The cached containers in reach. aggregateCounts holds every item and is
	 * what retrieval asks about; craftableCounts leaves out the stacks a
	 * craft never takes (see ContainerContents), and chainCraftableCounts
	 * also the non-pristine ones, matching the pristine-only chain placer.
	 */
	public record ReachableView(
		long revision,
		Map<String, Integer> aggregateCounts,
		Map<String, Integer> craftableCounts,
		Map<String, Integer> chainCraftableCounts,
		/** For cache signatures: changes when the totals or the flags do. Hashed once, here. */
		int contentHash,
		Map<ContainerKey, ContainerSnapshot> snapshotsByKey,
		Map<BlockPos, ContainerKey> accessKeyByPos,
		Map<ContainerKey, BlockPos> nearestAccessByKey
	) {
		private static ReachableView empty(long revision) {
			return new ReachableView(revision, Map.of(), Map.of(), Map.of(), 0, Map.of(), Map.of(), Map.of());
		}

		public boolean isEmpty() {
			return aggregateCounts.isEmpty();
		}

		public Map<String, Integer> craftableCountsFor(Set<String> acceptedItemIds) {
			if (acceptedItemIds.isEmpty() || craftableCounts.isEmpty()) {
				return Map.of();
			}

			Map<String, Integer> filteredCounts = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> entry : craftableCounts.entrySet()) {
				if (acceptedItemIds.contains(entry.getKey())) {
					filteredCounts.put(entry.getKey(), entry.getValue());
				}
			}
			return Map.copyOf(filteredCounts);
		}

		public boolean isRecentDrainSourceAt(BlockPos pos, Set<String> acceptedItemIds) {
			ContainerKey key = accessKeyByPos.get(pos);
			if (key == null) {
				return false;
			}
			for (String itemId : acceptedItemIds) {
				if (key.equals(LAST_DRAIN_SOURCE_BY_ITEM.get(itemId))) {
					return true;
				}
			}
			return false;
		}

		public int relevantCountAt(BlockPos pos, Set<String> acceptedItemIds) {
			ContainerKey key = accessKeyByPos.get(pos);
			if (key == null) {
				return 0;
			}

			ContainerSnapshot snapshot = snapshotsByKey.get(key);
			if (snapshot == null) {
				return 0;
			}

			int relevant = 0;
			for (String itemId : acceptedItemIds) {
				relevant += snapshot.itemCounts().getOrDefault(itemId, 0);
			}
			return relevant;
		}

		public Map<String, Integer> craftableCountsAt(BlockPos pos) {
			ContainerKey key = accessKeyByPos.get(pos);
			if (key == null) {
				return Map.of();
			}

			ContainerSnapshot snapshot = snapshotsByKey.get(key);
			return snapshot != null ? snapshot.contents().craftableCounts() : Map.of();
		}
	}

	private record ViewCacheKey(ResourceKey<Level> dimension, BlockPos center, int radius, long revision) {
	}

	public record ContainerKey(ResourceKey<Level> dimension, String type, BlockPos anchorPos) {
	}

	public record ContainerSnapshot(ContainerKey key, ContainerContents contents, long lastSeenGameTime) {
		public Map<String, Integer> itemCounts() {
			return contents.itemCounts();
		}
	}

	/**
	 * A container's items by id, plus its flagged items: for each id, how
	 * many carry each exact combination of StackReasons (damaged, enchanted,
	 * renamed, other components). Unflagged items are pristine. Masks, not
	 * verdicts, so any rule can be asked later without reopening the chest.
	 * Recorded while the container is open, the only time the client sees
	 * the components of its stacks.
	 */
	public record ContainerContents(
		Map<String, Integer> itemCounts,
		Map<String, Map<Integer, Integer>> flaggedCounts
	) {
		/** Contents saved before the flags existed: nothing known to exclude. */
		static ContainerContents unflagged(Map<String, Integer> itemCounts) {
			return new ContainerContents(itemCounts, Map.of());
		}

		/**
		 * Positive counts only, and no more flagged items of an id than it
		 * has. Which stacks a withdrawal took is unknown (it names ids), so a
		 * flag total above what is left is trimmed in mask order.
		 */
		ContainerContents normalized() {
			Map<String, Integer> items = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
				if (entry.getValue() > 0) {
					items.put(entry.getKey(), entry.getValue());
				}
			}
			Map<String, Map<Integer, Integer>> flagged = new LinkedHashMap<>();
			for (Map.Entry<String, Map<Integer, Integer>> entry : flaggedCounts.entrySet()) {
				int room = items.getOrDefault(entry.getKey(), 0);
				Map<Integer, Integer> masks = new java.util.TreeMap<>();
				for (Map.Entry<Integer, Integer> maskEntry : new java.util.TreeMap<>(entry.getValue()).entrySet()) {
					int count = Math.min(maskEntry.getValue(), room);
					if (maskEntry.getKey() != 0 && count > 0) {
						masks.put(maskEntry.getKey(), count);
						room -= count;
					}
				}
				if (!masks.isEmpty()) {
					flagged.put(entry.getKey(), Map.copyOf(masks));
				}
			}
			return new ContainerContents(Map.copyOf(items), Map.copyOf(flagged));
		}

		/** What an ordinary craft may take (StackReasons.CRAFTING). */
		public Map<String, Integer> craftableCounts() {
			return countsAllowing(StackReasons.CRAFTING);
		}

		/** What a pristine-only chain step may take. */
		public Map<String, Integer> chainCraftableCounts() {
			return countsAllowing(StackReasons.PRISTINE);
		}

		Map<String, Integer> countsAllowing(int rule) {
			if (flaggedCounts.isEmpty()) {
				return itemCounts;
			}
			Map<String, Integer> remaining = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
				int count = entry.getValue();
				for (Map.Entry<Integer, Integer> maskEntry : flaggedCounts.getOrDefault(entry.getKey(), Map.of()).entrySet()) {
					if (!StackReasons.allows(rule, maskEntry.getKey())) {
						count -= maskEntry.getValue();
					}
				}
				if (count > 0) {
					remaining.put(entry.getKey(), count);
				}
			}
			return Map.copyOf(remaining);
		}
	}

	private record ChunkRef(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
		static ChunkRef of(ContainerKey key) {
			return new ChunkRef(key.dimension(), key.anchorPos().getX() >> 4, key.anchorPos().getZ() >> 4);
		}
	}
}
