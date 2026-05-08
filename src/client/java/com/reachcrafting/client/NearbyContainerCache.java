package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/**
 * NearbyContainerCache owns cached observed contents plus the currently tracked container UI context.
 * Invariants: cache snapshots are independent of UI tracking; filter-dot eligibility is derived from tracked state.
 */
public final class NearbyContainerCache {
	private static final int PERIODIC_PRUNE_INTERVAL_TICKS = 60;
	private static final Map<ContainerKey, ContainerSnapshot> SNAPSHOTS = new LinkedHashMap<>();
	private static long revision;
	private static ViewCacheKey lastViewKey;
	private static ReachableView lastView;
	private static final TrackedContainerContext TRACKED_CONTEXT = new TrackedContainerContext();
	private static int pruneCooldownTicks = PERIODIC_PRUNE_INTERVAL_TICKS;

	private NearbyContainerCache() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			TRACKED_CONTEXT.tick();
			if (client.level == null || SNAPSHOTS.isEmpty()) {
				pruneCooldownTicks = PERIODIC_PRUNE_INTERVAL_TICKS;
				return;
			}
			if (--pruneCooldownTicks <= 0) {
				pruneCooldownTicks = PERIODIC_PRUNE_INTERVAL_TICKS;
				pruneStaleSnapshots(client.level);
			}
		});
	}

	public static void clear() {
		SNAPSHOTS.clear();
		revision++;
		lastViewKey = null;
		lastView = null;
		TRACKED_CONTEXT.clear();
		pruneCooldownTicks = PERIODIC_PRUNE_INTERVAL_TICKS;
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

		pruneStaleSnapshots(level);

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
		for (ContainerSnapshot snapshot : includedSnapshots.values()) {
			snapshot.itemCounts().forEach((itemId, count) -> aggregateCounts.merge(itemId, count, Integer::sum));
		}

		lastViewKey = cacheKey;
		lastView = new ReachableView(
			cacheKey.revision(),
			Map.copyOf(aggregateCounts),
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
		prioritized.sort(
			Comparator.comparingInt((BlockPos pos) -> reachableView.relevantCountAt(pos, acceptedItemIds))
				.reversed()
				.thenComparingInt(pos -> originalOrder.getOrDefault(pos, Integer.MAX_VALUE))
		);
		return List.copyOf(prioritized);
	}

	public static void recordObservedContents(Level level, BlockPos observedPos, Map<String, Integer> itemCounts) {
		if (!ReachCraftingConfig.get().cacheContainersForFasterSearch() || !ReachCraftingConfig.get().enableNearbyContainerUsage()) {
			return;
		}
		ContainerKey key = resolveContainerKey(level, observedPos);
		if (key == null) {
			return;
		}

		Map<String, Integer> normalizedCounts = normalizeCounts(itemCounts);
		ContainerSnapshot previous = SNAPSHOTS.get(key);
		if (previous != null && previous.itemCounts().equals(normalizedCounts)) {
			return;
		}

		SNAPSHOTS.put(key, new ContainerSnapshot(key, normalizedCounts, level.getGameTime()));
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

		SNAPSHOTS.put(key, new ContainerSnapshot(key, Map.copyOf(updatedCounts), level.getGameTime()));
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

		recordObservedContents(client.level, TRACKED_CONTEXT.candidatePos(), ContainerUtils.collectAllItems(menu));
		TRACKED_CONTEXT.confirmOpen(menu.containerId);
	}

	public static void onContainerScreenRemoved(AbstractContainerMenu menu) {
		if (menu == null || !TRACKED_CONTEXT.matchesConfirmedMenu(menu.containerId)) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.level != null) {
			recordObservedContents(client.level, TRACKED_CONTEXT.confirmedPos(), ContainerUtils.collectAllItems(menu));
		}
		TRACKED_CONTEXT.clearConfirmed();
	}

	private static Map<String, Integer> normalizeCounts(Map<String, Integer> itemCounts) {
		Map<String, Integer> normalized = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
			if (entry.getValue() > 0) {
				normalized.put(entry.getKey(), entry.getValue());
			}
		}
		return Map.copyOf(normalized);
	}

	public static void bumpRevision() {
		revision++;
		lastViewKey = null;
		lastView = null;
	}

	private static void pruneStaleSnapshots(Level level) {
		if (level == null || SNAPSHOTS.isEmpty()) {
			return;
		}

		boolean changed = false;
		var iterator = SNAPSHOTS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ContainerKey, ContainerSnapshot> entry = iterator.next();
			if (!entry.getKey().dimension().equals(level.dimension())) {
				continue;
			}
			if (!isSnapshotStillValid(level, entry.getKey())) {
				iterator.remove();
				changed = true;
			}
		}

		if (changed) {
			bumpRevision();
		}
	}

	private static boolean isSnapshotStillValid(Level level, ContainerKey key) {
		if ("ender_chest".equals(key.type())) {
			return true;
		}

		BlockPos anchorPos = key.anchorPos();
		BlockState state = level.getBlockState(anchorPos);
		if (!ContainerUtils.isPotentiallySupportedContainer(state) || !ContainerUtils.canAttemptOpen(level, anchorPos, state)) {
			return false;
		}

		ContainerKey currentKey = resolveContainerKey(level, anchorPos, state);
		return key.equals(currentKey);
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



	public record ReachableView(
		long revision,
		Map<String, Integer> aggregateCounts,
		Map<ContainerKey, ContainerSnapshot> snapshotsByKey,
		Map<BlockPos, ContainerKey> accessKeyByPos,
		Map<ContainerKey, BlockPos> nearestAccessByKey
	) {
		private static ReachableView empty(long revision) {
			return new ReachableView(revision, Map.of(), Map.of(), Map.of(), Map.of());
		}

		public boolean isEmpty() {
			return aggregateCounts.isEmpty();
		}

		public Map<String, Integer> countsFor(Set<String> acceptedItemIds) {
			if (acceptedItemIds.isEmpty() || snapshotsByKey.isEmpty()) {
				return Map.of();
			}

			Map<String, Integer> filteredCounts = new LinkedHashMap<>();
			for (ContainerSnapshot snapshot : snapshotsByKey.values()) {
				for (Map.Entry<String, Integer> entry : snapshot.itemCounts().entrySet()) {
					if (acceptedItemIds.contains(entry.getKey())) {
						filteredCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
					}
				}
			}
			return Map.copyOf(filteredCounts);
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

		public Map<String, Integer> itemCountsAt(BlockPos pos) {
			ContainerKey key = accessKeyByPos.get(pos);
			if (key == null) {
				return Map.of();
			}

			ContainerSnapshot snapshot = snapshotsByKey.get(key);
			return snapshot != null ? snapshot.itemCounts() : Map.of();
		}
	}

	private record ViewCacheKey(ResourceKey<Level> dimension, BlockPos center, int radius, long revision) {
	}

	public record ContainerKey(ResourceKey<Level> dimension, String type, BlockPos anchorPos) {
	}

	public record ContainerSnapshot(ContainerKey key, Map<String, Integer> itemCounts, long lastSeenGameTime) {
	}
}
