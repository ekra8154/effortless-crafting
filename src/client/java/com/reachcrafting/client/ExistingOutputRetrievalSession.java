package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class ExistingOutputRetrievalSession extends BaseCraftSession {
	private static final int OPEN_TIMEOUT_TICKS = 40;
	private static final int RESUME_DELAY_TICKS = 5;
	private static final int REOPEN_TIMEOUT_TICKS = 20;
	private static final int REOPEN_SETTLE_TICKS = 2;
	private static final int MAX_REOPEN_ATTEMPTS = 3;

	private final ExistingOutputRetrievalRequest request;
	private final ScreenContextSnapshot originalContext;
	private final List<BlockPos> candidates;
	private final java.util.Set<BlockPos> visited = new java.util.HashSet<>();
	private int nextCandidateIndex;
	private int timeoutTicks;
	private int reopenAttemptsRemaining;
	private int reopenSettledTicks;
	private int remainingCount;
	private int retrievedCount;
	private BlockPos pendingContainerPos;
	private boolean inventorySpaceBlocked;
	private RetrievalState state = RetrievalState.OPEN_NEXT;

	ExistingOutputRetrievalSession(
		NearbyCraftCoordinator coordinator,
		Minecraft client,
		LocalPlayer player,
		Level level,
		MultiPlayerGameMode gameMode,
		Entity cameraEntity,
		ExistingOutputRetrievalRequest request
	) {
		super(coordinator, client, player, level, gameMode, cameraEntity);
		this.request = request;
		AvailableItemSnapshot localItems = AvailableItemSnapshot.capture(player, client.gui.screen());
		this.originalContext = ScreenContextSnapshot.capture(client, cameraEntity, player.blockInteractionRange(), localItems);
		NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(level, cameraEntity, player.blockInteractionRange());
		this.candidates = NearbyContainerCache.prioritizeCandidates(
			NearbyDiscoveryPlanner.findCandidates(level, cameraEntity, player.blockInteractionRange()),
			reachableView,
			Set.of(request.outputItemId())
		);
		this.reopenAttemptsRemaining = MAX_REOPEN_ATTEMPTS;
		this.remainingCount = Math.max(request.requestedCount(), 1);
	}

	boolean canStart() {
		return !player.isSpectator() && !player.isHandsBusy() && player.containerMenu.getCarried().isEmpty();
	}

	@Override
	public void start() {
		if (candidates.isEmpty()) {
			sendMissingIngredientsChat("No nearby containers available for retrieval.");
			finishSession(false);
			return;
		}
		ReachCraftingMod.LOGGER.info(
			"[retrieve_existing] start item={} requested={} candidates={}",
			request.outputItemId(),
			remainingCount,
			candidates.size()
		);
		sendDebugChat("Retrieving existing: " + request.outputLabel());
	}

	@Override
	public void tick() {
		if (client.player != player || client.level != level) {
			finishSession(false);
			return;
		}

		if (state == RetrievalState.OPEN_NEXT) {
			if (remainingCount <= 0 || inventorySpaceBlocked) {
				beginResume();
				return;
			}
			if (!openNextContainer()) {
				beginResume();
			}
			return;
		}

		if (state == RetrievalState.WAITING_FOR_CONTAINER) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				onOpenFailed("timeout");
			}
			return;
		}

		if (state == RetrievalState.RESUME_CONTEXT) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				resumeOriginalContext(originalContext);
				timeoutTicks = REOPEN_TIMEOUT_TICKS;
				state = RetrievalState.WAITING_FOR_REOPEN;
			}
			return;
		}

		if (state == RetrievalState.WAITING_FOR_REOPEN) {
			if (isOriginalContextReady(originalContext)) {
				if (isOriginalContextSettled()) {
					reopenSettledTicks++;
				} else {
					reopenSettledTicks = 0;
				}
				if (reopenSettledTicks >= REOPEN_SETTLE_TICKS) {
					finishAfterResume();
				}
				return;
			}

			reopenSettledTicks = 0;
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				if (originalContext.kind() != ScreenKind.NONE && reopenAttemptsRemaining > 0) {
					reopenAttemptsRemaining--;
					resumeOriginalContext(originalContext);
					timeoutTicks = REOPEN_TIMEOUT_TICKS;
					return;
				}
				finishAfterResume();
			}
		}
	}

	@Override
	public void onOpenFailed(String reason) {
		ReachCraftingMod.LOGGER.debug(
			"[retrieve_existing] pos={} skipped={}",
			ContainerUtils.formatPos(pendingContainerPos),
			reason
		);
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = RetrievalState.OPEN_NEXT;
	}

	@Override
	public void onContainerContentsInitialized(AbstractContainerMenu menu) {
		if (state != RetrievalState.WAITING_FOR_CONTAINER) {
			return;
		}
		if (client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
			|| client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen) {
			return;
		}
		if (menu.containerId == player.inventoryMenu.containerId) {
			return;
		}

		Map<String, Integer> allItems = ContainerUtils.collectAllItems(menu);
		NearbyContainerCache.recordObservedContents(level, pendingContainerPos, allItems);
		Map<String, Integer> executed = executeWithdrawalPlan(menu, buildWithdrawalPlan(menu));
		applyWithdrawalResults(executed);
		player.closeContainer();
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = RetrievalState.OPEN_NEXT;
	}

	@Override
	public void stop(boolean closeContainer) {
		if (closeContainer && player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}

	private void beginResume() {
		player.closeContainer();
		timeoutTicks = RESUME_DELAY_TICKS;
		reopenAttemptsRemaining = MAX_REOPEN_ATTEMPTS;
		reopenSettledTicks = 0;
		state = RetrievalState.RESUME_CONTEXT;
	}

	private void finishAfterResume() {
		if (retrievedCount <= 0) {
			sendMissingIngredientsChat("No matching existing items nearby.");
		} else if (inventorySpaceBlocked) {
			sendChat("Retrieved " + ContainerUtils.formatStackBreakdown(retrievedCount) + " " + ContainerUtils.getItemName(request.outputItemId()) + ", then ran out of inventory space.");
		} else if (remainingCount > 0) {
			sendChat("Retrieved " + ContainerUtils.formatStackBreakdown(retrievedCount) + " " + ContainerUtils.getItemName(request.outputItemId()) + ".");
		} else {
			sendChat("Retrieved " + ContainerUtils.formatStackBreakdown(retrievedCount) + " " + ContainerUtils.getItemName(request.outputItemId()) + ".");
		}
		if (retrievedCount > 0 && request.requestedRecipeId() != null) {
			ReachCraftingConfig.get().noteRecentRecipe(request.requestedRecipeId());
		}
		finishSession(false);
	}

	private boolean isOriginalContextSettled() {
		if (!(client.gui.screen() instanceof AbstractContainerScreen<?> containerScreen)) {
			return originalContext.kind() == ScreenKind.NONE;
		}

		AbstractContainerMenu menu = containerScreen.getMenu();
		if (menu == null || player.containerMenu != menu) {
			return false;
		}
		if (!menu.getCarried().isEmpty()) {
			return false;
		}

		int expectedGridSlots = switch (originalContext.kind()) {
			case INVENTORY_2X2 -> 4;
			case CRAFTING_TABLE_3X3 -> 9;
			case NONE -> 0;
		};
		return menu.slots.size() > expectedGridSlots;
	}

	private boolean openNextContainer() {
		Vec3 eyePos = cameraEntity.getEyePosition(0);
		while (nextCandidateIndex < candidates.size()) {
			BlockPos pos = candidates.get(nextCandidateIndex++);
			if (visited.contains(pos)) {
				continue;
			}

			BlockState blockState = level.getBlockState(pos);
			if (!InWorldFilterManager.isContainerActive(level, pos, blockState)) {
				continue;
			}
			if (!ContainerUtils.canAttemptOpen(level, pos, blockState)) {
				continue;
			}
			if (ContainerUtils.squaredDistanceToBlock(eyePos, pos) > Mth.square(player.blockInteractionRange())) {
				continue;
			}

			markVisited(pos);
			Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, pos);
			Direction face = Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
			BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);
			boolean wasSneaking = player.isShiftKeyDown() || (player.input != null && player.input.keyPresses != null && player.input.keyPresses.shift());
			withSuppressedSecondaryUse(() -> {
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
			state = RetrievalState.WAITING_FOR_CONTAINER;
			return true;
		}
		return false;
	}

	private void markVisited(BlockPos pos) {
		visited.add(pos);
		ContainerUtils.getOtherHalfOfLargeChest(level, pos).ifPresent(visited::add);
	}

	private WithdrawalPlan buildWithdrawalPlan(AbstractContainerMenu menu) {
		Map<String, Integer> plannedWithdrawals = new LinkedHashMap<>();
		List<WithdrawalPlan.PlannedMove> moves = new ArrayList<>();
		Map<Integer, Integer> virtualPlayerCounts = capturePlayerOccupancy(menu);
		Map<Integer, String> virtualPlayerItemIds = capturePlayerItemIds(menu);
		boolean blockedByInventory = false;
		int stillNeeded = remainingCount;

		for (Slot sourceSlot : sortedMatchingContainerSources(menu, request.outputItemId())) {
			if (stillNeeded <= 0) {
				break;
			}

			int sourceRemaining = sourceSlot.getItem().getCount();
			while (sourceRemaining > 0 && stillNeeded > 0) {
				Slot targetSlot = findPlannedDestinationSlot(menu, request.outputItemId(), virtualPlayerCounts, virtualPlayerItemIds);
				if (targetSlot == null) {
					blockedByInventory = true;
					break;
				}

				int currentTargetCount = virtualPlayerCounts.getOrDefault(targetSlot.index, targetSlot.hasItem() ? targetSlot.getItem().getCount() : 0);
				int maxTargetCount = targetSlot.hasItem()
					? Math.min(targetSlot.getMaxStackSize(), targetSlot.getItem().getMaxStackSize())
					: Math.min(targetSlot.getMaxStackSize(), sourceSlot.getItem().getMaxStackSize());
				int roomInTarget = maxTargetCount - currentTargetCount;
				int moveCount = Math.min(stillNeeded, Math.min(sourceRemaining, roomInTarget));
				if (moveCount <= 0) {
					break;
				}

				moves.add(new WithdrawalPlan.PlannedMove(sourceSlot, targetSlot, request.outputItemId(), moveCount));
				virtualPlayerCounts.put(targetSlot.index, currentTargetCount + moveCount);
				virtualPlayerItemIds.put(targetSlot.index, request.outputItemId());
				plannedWithdrawals.merge(request.outputItemId(), moveCount, Integer::sum);
				sourceRemaining -= moveCount;
				stillNeeded -= moveCount;
			}

			if (blockedByInventory) {
				break;
			}
		}

		Map<String, Integer> unresolvedNeeds = stillNeeded > 0 ? Map.of(request.outputItemId(), stillNeeded) : Map.of();
		return new WithdrawalPlan(moves, plannedWithdrawals, unresolvedNeeds, blockedByInventory);
	}

	private Map<String, Integer> executeWithdrawalPlan(AbstractContainerMenu menu, WithdrawalPlan plan) {
		Map<String, Integer> executedWithdrawals = new LinkedHashMap<>();
		for (WithdrawalPlan.PlannedMove move : plan.moves()) {
			if (!move.source().hasItem() || !move.source().mayPickup(player)) {
				break;
			}

			MenuTransferHelper.WithdrawalMoveResult result = MenuTransferHelper.moveExactCountFromContainerToInventory(
				menu,
				move.source(),
				move.target(),
				move.count(),
				player,
				gameMode
			);
			if (result.moved() <= 0) {
				break;
			}
			executedWithdrawals.merge(move.itemId(), result.moved(), Integer::sum);
		}
		return executedWithdrawals;
	}

	private void applyWithdrawalResults(Map<String, Integer> executedWithdrawals) {
		if (executedWithdrawals.isEmpty()) {
			return;
		}

		int moved = executedWithdrawals.getOrDefault(request.outputItemId(), 0);
		if (moved <= 0) {
			return;
		}

		retrievedCount += moved;
		remainingCount = Math.max(remainingCount - moved, 0);
		NearbyContainerCache.applyWithdrawals(level, pendingContainerPos, executedWithdrawals);
		if (remainingCount > 0) {
			WithdrawalPlan probePlan = buildWithdrawalPlan(player.containerMenu);
			inventorySpaceBlocked = probePlan.inventorySpaceBlocked();
		}
	}

	private static Map<Integer, Integer> capturePlayerOccupancy(AbstractContainerMenu menu) {
		Map<Integer, Integer> occupancy = new HashMap<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory) {
				occupancy.put(slot.index, slot.hasItem() ? slot.getItem().getCount() : 0);
			}
		}
		return occupancy;
	}

	private static Map<Integer, String> capturePlayerItemIds(AbstractContainerMenu menu) {
		Map<Integer, String> itemIds = new HashMap<>();
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
				continue;
			}
			itemIds.put(slot.index, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());
		}
		return itemIds;
	}

	private static Slot findPlannedDestinationSlot(
		AbstractContainerMenu menu,
		String itemId,
		Map<Integer, Integer> virtualPlayerCounts,
		Map<Integer, String> virtualPlayerItemIds
	) {
		Slot emptySlot = null;
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory)) {
				continue;
			}

			String virtualItemId = virtualPlayerItemIds.get(slot.index);
			int virtualCount = virtualPlayerCounts.getOrDefault(slot.index, 0);
			if (virtualItemId == null || virtualCount <= 0) {
				if (emptySlot == null) {
					emptySlot = slot;
				}
				continue;
			}

			if (!itemId.equals(virtualItemId)) {
				continue;
			}

			int maxCount = slot.hasItem()
				? Math.min(slot.getMaxStackSize(), slot.getItem().getMaxStackSize())
				: slot.getMaxStackSize();
			if (virtualCount < maxCount) {
				return slot;
			}
		}
		return emptySlot;
	}

	private List<Slot> sortedMatchingContainerSources(AbstractContainerMenu menu, String itemId) {
		List<Slot> matchingSources = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory || !slot.hasItem() || !slot.mayPickup(player)) {
				continue;
			}
			String slotItemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
			if (itemId.equals(slotItemId)) {
				matchingSources.add(slot);
			}
		}
		matchingSources.sort(Comparator.comparingInt((Slot slot) -> slot.getItem().getCount()).thenComparingInt(slot -> slot.index));
		return matchingSources;
	}

	private enum RetrievalState {
		OPEN_NEXT,
		WAITING_FOR_CONTAINER,
		RESUME_CONTEXT,
		WAITING_FOR_REOPEN
	}
}
