package com.reachcrafting.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tracks one container interaction through its candidate and confirmed states.
 * The UI dot is only valid when the tracked context matches the currently open container flow.
 */
final class TrackedContainerContext {
	private BlockPos candidatePos;
	private int candidateTicks;
	private BlockPos confirmedPos;
	private int confirmedContainerId = -1;

	boolean hasTrackedContainer() {
		return candidatePos != null || confirmedPos != null;
	}

	BlockPos trackedPos() {
		return confirmedPos != null ? confirmedPos : candidatePos;
	}

	void clear() {
		candidatePos = null;
		candidateTicks = 0;
		confirmedPos = null;
		confirmedContainerId = -1;
	}

	void noteCandidate(Level level, BlockPos pos, BlockState state) {
		BlockPos canonicalPos = ContainerUtils.canonicalizeContainerPos(level, pos, state);
		candidatePos = canonicalPos;
		candidateTicks = 10;
		confirmedPos = canonicalPos;
		confirmedContainerId = -1;
	}

	void tick() {
		if (candidateTicks <= 0) {
			return;
		}
		candidateTicks--;
		if (candidateTicks == 0) {
			if (confirmedContainerId == -1) {
				confirmedPos = null;
			}
			candidatePos = null;
		}
	}

	boolean hasLiveCandidate() {
		return candidatePos != null && candidateTicks > 0;
	}

	BlockPos candidatePos() {
		return candidatePos;
	}

	void clearCandidate() {
		candidatePos = null;
		candidateTicks = 0;
	}

	void confirmOpen(int containerId) {
		if (candidatePos == null) {
			return;
		}
		confirmedPos = candidatePos;
		confirmedContainerId = containerId;
		candidatePos = null;
		candidateTicks = 0;
	}

	boolean matchesConfirmedMenu(int containerId) {
		return confirmedPos != null && confirmedContainerId != -1 && confirmedContainerId == containerId;
	}

	int confirmedContainerId() {
		return confirmedContainerId;
	}

	BlockPos confirmedPos() {
		return confirmedPos;
	}

	void clearConfirmed() {
		confirmedPos = null;
		confirmedContainerId = -1;
	}
}
