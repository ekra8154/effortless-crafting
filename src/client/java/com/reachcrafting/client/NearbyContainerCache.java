package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

public final class NearbyContainerCache {
	private static final Map<ContainerKey, ContainerSnapshot> SNAPSHOTS = new LinkedHashMap<>();
	private static long revision;
	private static ViewCacheKey lastViewKey;
	private static ReachableView lastView;

	private NearbyContainerCache() {
	}

	public static void init() {
	}

	public static ReachableView getReachableView(Level level, Entity cameraEntity, double reachDistance) {
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
			if (!isSupportedContainer(state) || !canAttemptOpen(level, immutablePos, state)) {
				continue;
			}
			if (squaredDistanceToBlock(eyePos, immutablePos) > Mth.square(reachDistance)) {
				continue;
			}

			ContainerKey key = resolveContainerKey(level, immutablePos, state);
			if (key == null) {
				continue;
			}

			accessKeysByPos.put(immutablePos, key);
			BlockPos currentNearest = nearestAccessByKey.get(key);
			if (currentNearest == null || squaredDistanceToBlock(eyePos, immutablePos) < squaredDistanceToBlock(eyePos, currentNearest)) {
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

	private static Map<String, Integer> normalizeCounts(Map<String, Integer> itemCounts) {
		Map<String, Integer> normalized = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
			if (entry.getValue() > 0) {
				normalized.put(entry.getKey(), entry.getValue());
			}
		}
		return Map.copyOf(normalized);
	}

	private static void bumpRevision() {
		revision++;
		lastViewKey = null;
		lastView = null;
	}

	private static ContainerKey resolveContainerKey(Level level, BlockPos pos) {
		if (level == null || pos == null) {
			return null;
		}
		return resolveContainerKey(level, pos, level.getBlockState(pos));
	}

	private static ContainerKey resolveContainerKey(Level level, BlockPos pos, BlockState state) {
		if (!isSupportedContainer(state)) {
			return null;
		}

		ResourceKey<Level> dimension = level.dimension();
		if (state.getBlock() instanceof EnderChestBlock) {
			return new ContainerKey(dimension, "ender_chest", BlockPos.ZERO);
		}

		BlockPos anchor = canonicalizeContainerPos(level, pos, state);
		String type = state.getBlock() instanceof ChestBlock ? "chest_like" : state.getBlock().getClass().getSimpleName();
		return new ContainerKey(dimension, type, anchor);
	}

	private static BlockPos canonicalizeContainerPos(Level level, BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof ChestBlock) {
			Optional<BlockPos> otherHalf = getOtherHalfOfLargeChest(level, pos);
			if (otherHalf.isPresent()) {
				BlockPos other = otherHalf.get();
				return compareBlockPos(pos, other) <= 0 ? pos.immutable() : other.immutable();
			}
		}
		return pos.immutable();
	}

	private static int compareBlockPos(BlockPos left, BlockPos right) {
		if (left.getX() != right.getX()) {
			return Integer.compare(left.getX(), right.getX());
		}
		if (left.getY() != right.getY()) {
			return Integer.compare(left.getY(), right.getY());
		}
		return Integer.compare(left.getZ(), right.getZ());
	}

	private static boolean isSupportedContainer(BlockState state) {
		return state.getBlock() instanceof ChestBlock
			|| state.getBlock() instanceof BarrelBlock
			|| state.getBlock() instanceof ShulkerBoxBlock
			|| state.getBlock() instanceof EnderChestBlock;
	}

	private static boolean canAttemptOpen(Level level, BlockPos pos, BlockState state) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof Container) && !(state.getBlock() instanceof EnderChestBlock)) {
			return false;
		}
		if (state.getBlock() instanceof ChestBlock || state.getBlock() instanceof EnderChestBlock) {
			if (ChestBlock.isChestBlockedAt(level, pos)) {
				return false;
			}
			return getOtherHalfOfLargeChest(level, pos)
				.map(otherHalf -> !ChestBlock.isChestBlockedAt(level, otherHalf))
				.orElse(true);
		}
		return true;
	}

	private static Optional<BlockPos> getOtherHalfOfLargeChest(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
			return Optional.empty();
		}

		BlockPos otherHalfPos = pos.relative(ChestBlock.getConnectedDirection(state));
		BlockState otherHalf = level.getBlockState(otherHalfPos);
		if (!(otherHalf.getBlock() instanceof ChestBlock)) {
			return Optional.empty();
		}
		if (state.getValue(ChestBlock.FACING) != otherHalf.getValue(ChestBlock.FACING)) {
			return Optional.empty();
		}
		if (ChestBlock.getConnectedDirection(state) != ChestBlock.getConnectedDirection(otherHalf).getOpposite()) {
			return Optional.empty();
		}
		return Optional.of(otherHalfPos.immutable());
	}

	private static double squaredDistanceToBlock(Vec3 eyePos, BlockPos pos) {
		return closestPointOnUnitBlock(eyePos, pos).distanceToSqr(eyePos);
	}

	private static Vec3 closestPointOnUnitBlock(Vec3 origin, BlockPos pos) {
		double x = Mth.clamp(origin.x, pos.getX(), pos.getX() + 1.0D);
		double y = Mth.clamp(origin.y, pos.getY(), pos.getY() + 1.0D);
		double z = Mth.clamp(origin.z, pos.getZ(), pos.getZ() + 1.0D);
		return new Vec3(x, y, z);
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
	}

	private record ViewCacheKey(ResourceKey<Level> dimension, BlockPos center, int radius, long revision) {
	}

	public record ContainerKey(ResourceKey<Level> dimension, String type, BlockPos anchorPos) {
	}

	public record ContainerSnapshot(ContainerKey key, Map<String, Integer> itemCounts, long lastSeenGameTime) {
	}
}
