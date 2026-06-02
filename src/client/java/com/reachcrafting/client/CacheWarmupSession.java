package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class CacheWarmupSession extends BaseCraftSession {
	private static final int OPEN_TIMEOUT_TICKS = 40;
	private static final int RESUME_DELAY_TICKS = 5;
	private static final int REOPEN_TIMEOUT_TICKS = 20;
	private static final int REOPEN_SETTLE_TICKS = 2;

	private final CacheWarmupRequest request;
	private final ScreenContextSnapshot originalContext;
	private final List<BlockPos> candidates;
	private final Set<BlockPos> visited = new LinkedHashSet<>();
	private int nextCandidateIndex;
	private int timeoutTicks;
	private int reopenSettledTicks;
	private int scannedContainers;
	private BlockPos pendingContainerPos;
	private WarmupState state = WarmupState.OPEN_NEXT;

	CacheWarmupSession(
		NearbyCraftCoordinator coordinator,
		Minecraft client,
		LocalPlayer player,
		Level level,
		MultiPlayerGameMode gameMode,
		Entity cameraEntity,
		CacheWarmupRequest request
	) {
		super(coordinator, client, player, level, gameMode, cameraEntity);
		this.request = request;
		AvailableItemSnapshot localItems = AvailableItemSnapshot.capture(player, client.screen);
		this.originalContext = ScreenContextSnapshot.capture(client, cameraEntity, player.blockInteractionRange(), localItems);
		NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(level, cameraEntity, player.blockInteractionRange());
		this.candidates = NearbyDiscoveryPlanner.uncachedCandidates(
			NearbyDiscoveryPlanner.findCandidates(level, cameraEntity, player.blockInteractionRange()),
			reachableView
		);
	}

	boolean canStart() {
		return !player.isSpectator() && !player.isHandsBusy() && player.containerMenu.getCarried().isEmpty() && !candidates.isEmpty();
	}

	@Override
	public void start() {
		ReachCraftingMod.LOGGER.info("[retrieval_virtual] warmup start reason={} candidates={}", request.reason(), candidates.size());
		sendDebugChat("Scanning nearby containers...");
		state = WarmupState.OPEN_NEXT;
	}

	@Override
	public void tick() {
		if (client.player != player || client.level != level) {
			finishAndRefresh(false);
			return;
		}
		if (state == WarmupState.OPEN_NEXT) {
			if (!openNextContainer()) {
				beginResume();
			}
			return;
		}
		if (state == WarmupState.WAITING_FOR_CONTAINER) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				onOpenFailed("timeout");
			}
			return;
		}
		if (state == WarmupState.RESUME_CONTEXT) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				resumeOriginalContext(originalContext);
				timeoutTicks = REOPEN_TIMEOUT_TICKS;
				state = WarmupState.WAITING_FOR_REOPEN;
			}
			return;
		}
		if (state == WarmupState.WAITING_FOR_REOPEN) {
			if (isOriginalContextReady(originalContext)) {
				reopenSettledTicks++;
				if (reopenSettledTicks >= REOPEN_SETTLE_TICKS) {
					finishAndRefresh(false);
				}
				return;
			}
			reopenSettledTicks = 0;
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				finishAndRefresh(false);
			}
		}
	}

	@Override
	public void onContainerContentsInitialized(AbstractContainerMenu menu) {
		if (state != WarmupState.WAITING_FOR_CONTAINER) {
			return;
		}
		if (client.screen instanceof InventoryScreen || client.screen instanceof CraftingScreen) {
			return;
		}
		if (menu instanceof InventoryMenu || menu.containerId == player.inventoryMenu.containerId) {
			return;
		}

		NearbyContainerCache.recordObservedContents(level, pendingContainerPos, ContainerUtils.collectAllItems(menu));
		scannedContainers++;
		player.closeContainer();
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = WarmupState.OPEN_NEXT;
	}

	@Override
	public void onOpenFailed(String reason) {
		ReachCraftingMod.LOGGER.info("[retrieval_virtual] warmup skip pos={} reason={}", ContainerUtils.formatPos(pendingContainerPos), reason);
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = WarmupState.OPEN_NEXT;
	}

	@Override
	public void stop(boolean closeContainer) {
		if (closeContainer && player != null) {
			player.closeContainer();
		}
	}

	private void beginResume() {
		player.closeContainer();
		timeoutTicks = RESUME_DELAY_TICKS;
		reopenSettledTicks = 0;
		state = WarmupState.RESUME_CONTEXT;
	}

	private void finishAndRefresh(boolean closeContainer) {
		ReachCraftingMod.LOGGER.info("[retrieval_virtual] warmup finish scanned={} candidates={}", scannedContainers, candidates.size());
		finishSession(closeContainer);
		RecipeBookChunkedScheduler.forceVisibleRecipeBookRefresh();
	}

	private boolean openNextContainer() {
		Vec3 eyePos = cameraEntity.getEyePosition(0);
		while (nextCandidateIndex < candidates.size()) {
			BlockPos pos = candidates.get(nextCandidateIndex++);
			if (!visited.add(pos)) {
				continue;
			}
			BlockState blockState = level.getBlockState(pos);
			if (!InWorldFilterManager.isContainerActive(level, pos, blockState) || !ContainerUtils.canAttemptOpen(level, pos, blockState)) {
				continue;
			}
			if (ContainerUtils.squaredDistanceToBlock(eyePos, pos) > Mth.square(player.blockInteractionRange())) {
				continue;
			}

			Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, pos);
			Direction face = Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
			BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);
			withSuppressedSecondaryUse(() -> {
				boolean wasSneaking = player.isShiftKeyDown() || (player.input != null && player.input.keyPresses != null && player.input.keyPresses.shift());
				if (wasSneaking) {
					sendShiftOverride(client, player, false);
					player.setShiftKeyDown(false);
				}
				gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
				if (wasSneaking) {
					player.setShiftKeyDown(true);
					sendShiftOverride(client, player, true);
				}
			});
			pendingContainerPos = pos;
			timeoutTicks = OPEN_TIMEOUT_TICKS;
			state = WarmupState.WAITING_FOR_CONTAINER;
			return true;
		}
		return false;
	}

	private enum WarmupState {
		OPEN_NEXT,
		WAITING_FOR_CONTAINER,
		RESUME_CONTEXT,
		WAITING_FOR_REOPEN
	}
}
