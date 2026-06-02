package com.reachcrafting.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class NearbyDiscoveryPlanner {
	private NearbyDiscoveryPlanner() {
	}

	static List<BlockPos> findCandidates(Level level, Entity cameraEntity, double reachDistance) {
		long startNanos = PerformanceProfiler.start();
		Vec3 eyePos = cameraEntity.getEyePosition(0);
		int radius = Mth.ceil(reachDistance);
		BlockPos center = BlockPos.containing(eyePos);
		List<BlockPos> candidates = new ArrayList<>();

		for (BlockPos pos : BlockPos.betweenClosed(
			center.offset(-radius, -radius, -radius),
			center.offset(radius, radius, radius)
		)) {
			BlockState state = level.getBlockState(pos);
			if (InWorldFilterManager.isContainerActive(level, pos, state)) {
				candidates.add(pos.immutable());
			}
		}

		candidates.sort(Comparator.comparingDouble(pos -> ContainerUtils.squaredDistanceToBlock(eyePos, pos)));
		PerformanceProfiler.record(
			"nearby.find_candidates",
			startNanos,
			"found=" + candidates.size() + " radius=" + radius
		);
		return List.copyOf(candidates);
	}

	static List<BlockPos> uncachedCandidates(List<BlockPos> source, NearbyContainerCache.ReachableView reachableView) {
		if (source.isEmpty()) {
			return source;
		}

		List<BlockPos> uncached = new ArrayList<>();
		for (BlockPos pos : source) {
			var key = reachableView.accessKeyByPos().get(pos);
			if (key == null || !reachableView.snapshotsByKey().containsKey(key)) {
				uncached.add(pos);
			}
		}
		return List.copyOf(uncached);
	}
}
