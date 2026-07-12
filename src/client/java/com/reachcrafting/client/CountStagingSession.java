package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class CountStagingSession extends BaseCraftSession {
	private static final int OPEN_TIMEOUT_TICKS = 40;
	private static final int RESUME_DELAY_TICKS = 5;
	private static final int REOPEN_TIMEOUT_TICKS = 20;
	private static final int REOPEN_SETTLE_TICKS = 2;

	private final CountStagingRequest request;
	private final ScreenContextSnapshot originalContext;
	private final Set<String> acceptedItemIds;
	private final List<BlockPos> candidates;
	private final Map<String, Integer> remainingCounts;
	private final Map<String, Integer> withdrawnCounts = new LinkedHashMap<>();
	private final Set<BlockPos> visited = new LinkedHashSet<>();
	private StageState state = StageState.OPEN_NEXT;
	private BlockPos pendingContainerPos;
	private int nextCandidateIndex;
	private int timeoutTicks;
	private int reopenSettledTicks;

	CountStagingSession(
		NearbyCraftCoordinator coordinator,
		Minecraft client,
		LocalPlayer player,
		Level level,
		MultiPlayerGameMode gameMode,
		Entity cameraEntity,
		CountStagingRequest request
	) {
		super(coordinator, client, player, level, gameMode, cameraEntity);
		this.request = request;
		this.remainingCounts = normalize(request.desiredCounts());
		this.acceptedItemIds = Set.copyOf(this.remainingCounts.keySet());
		AvailableItemSnapshot localItems = AvailableItemSnapshot.capture(player, client.screen);
		this.originalContext = ScreenContextSnapshot.capture(client, cameraEntity, player.blockInteractionRange(), localItems);
		NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(level, cameraEntity, player.blockInteractionRange());
		this.candidates = NearbyContainerCache.prioritizeCandidates(
			NearbyDiscoveryPlanner.findCandidates(level, cameraEntity, player.blockInteractionRange()),
			reachableView,
			acceptedItemIds
		);
	}

	boolean canStart() {
		return !remainingCounts.isEmpty()
			&& !player.isSpectator()
			&& !player.isHandsBusy()
			&& player.containerMenu.getCarried().isEmpty()
			&& StagingInventorySimulator.canFit(player, remainingCounts);
	}

	@Override
	public void start() {
		// A bulk chain session stages repeatedly; its withdrawals accumulate
		// so leftovers can be returned to their source chests at session end.
		if (!BulkChainCraftController.isActive()) {
			PulledResourcesTracker.clearWithdrawals();
		}
		ReachCraftingMod.LOGGER.info(
			"[chain_stage] start reason={} desired={} candidates={}",
			request.reason(),
			AvailableItemSnapshot.formatCounts(remainingCounts),
			candidates.size()
		);
		state = StageState.OPEN_NEXT;
	}

	@Override
	public void tick() {
		if (client.player != player || client.level != level) {
			finishSession(false);
			return;
		}
		if (state == StageState.OPEN_NEXT) {
			if (remainingCounts.isEmpty() || !openNextContainer()) {
				beginResume();
			}
			return;
		}
		if (state == StageState.WAITING_FOR_CONTAINER) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				onOpenFailed("timeout");
			}
			return;
		}
		if (state == StageState.RESUME_CONTEXT) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				resumeOriginalContext(originalContext);
				timeoutTicks = REOPEN_TIMEOUT_TICKS;
				state = StageState.WAITING_FOR_REOPEN;
			}
			return;
		}
		if (state == StageState.WAITING_FOR_REOPEN) {
			if (isOriginalContextReady(originalContext)) {
				reopenSettledTicks++;
				if (reopenSettledTicks >= REOPEN_SETTLE_TICKS) {
					ReachCraftingMod.LOGGER.info(
						"[chain_stage] finish withdrawn={} remaining={}",
						AvailableItemSnapshot.formatCounts(withdrawnCounts),
						AvailableItemSnapshot.formatCounts(remainingCounts)
					);
					finishSession(false);
				}
				return;
			}
			reopenSettledTicks = 0;
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				finishSession(false);
			}
		}
	}

	@Override
	public void onContainerContentsInitialized(AbstractContainerMenu menu) {
		if (state != StageState.WAITING_FOR_CONTAINER) {
			return;
		}
		if (client.screen instanceof InventoryScreen || client.screen instanceof CraftingScreen) {
			return;
		}
		if (menu.containerId == player.inventoryMenu.containerId) {
			return;
		}

		NearbyContainerCache.recordObservedContents(level, pendingContainerPos, ContainerUtils.collectAllItems(menu));
		Map<String, Integer> executedWithdrawals = executeWithdrawalPlan(menu, buildWithdrawalPlan(menu));
		for (Map.Entry<String, Integer> entry : executedWithdrawals.entrySet()) {
			subtract(remainingCounts, entry.getKey(), entry.getValue());
			withdrawnCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
		}
		NearbyContainerCache.applyWithdrawals(level, pendingContainerPos, executedWithdrawals);
		player.closeContainer();
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = StageState.OPEN_NEXT;
	}

	@Override
	public void onOpenFailed(String reason) {
		ReachCraftingMod.LOGGER.debug("[chain_stage] container skipped pos={} reason={}", ContainerUtils.formatPos(pendingContainerPos), reason);
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = StageState.OPEN_NEXT;
	}

	@Override
	public void stop(boolean closeContainer) {
		if (closeContainer && player != null) {
			player.closeContainer();
		}
	}

	private boolean openNextContainer() {
		Vec3 eyePos = cameraEntity.getEyePosition(0);
		while (nextCandidateIndex < candidates.size()) {
			BlockPos pos = candidates.get(nextCandidateIndex++);
			if (visited.contains(pos)) {
				continue;
			}
			BlockState blockState = level.getBlockState(pos);
			if (!InWorldFilterManager.isContainerActive(level, pos, blockState) || !ContainerUtils.canAttemptOpen(level, pos, blockState)) {
				continue;
			}
			if (ContainerUtils.squaredDistanceToBlock(eyePos, pos) > Mth.square(player.blockInteractionRange())) {
				continue;
			}

			markVisited(pos);
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
			state = StageState.WAITING_FOR_CONTAINER;
			return true;
		}
		return false;
	}

	private WithdrawalPlan buildWithdrawalPlan(AbstractContainerMenu menu) {
		Map<String, Integer> plannedWithdrawals = new LinkedHashMap<>();
		List<WithdrawalPlan.PlannedMove> moves = new ArrayList<>();
		Map<Integer, Integer> virtualPlayerCounts = capturePlayerOccupancy(menu);
		Map<Integer, String> virtualPlayerItemIds = capturePlayerItemIds(menu);
		boolean blockedByInventory = false;

		for (Map.Entry<String, Integer> needEntry : remainingCounts.entrySet()) {
			int stillNeeded = needEntry.getValue();
			for (Slot sourceSlot : sortedMatchingContainerSources(menu, needEntry.getKey())) {
				if (stillNeeded <= 0) {
					break;
				}
				int sourceRemaining = sourceSlot.getItem().getCount();
				while (sourceRemaining > 0 && stillNeeded > 0) {
					Slot targetSlot = findPlannedDestinationSlot(menu, needEntry.getKey(), virtualPlayerCounts, virtualPlayerItemIds);
					if (targetSlot == null) {
						blockedByInventory = true;
						break;
					}
					int currentTargetCount = virtualPlayerCounts.getOrDefault(targetSlot.index, targetSlot.hasItem() ? targetSlot.getItem().getCount() : 0);
					int maxTargetCount = targetSlot.hasItem()
						? Math.min(targetSlot.getMaxStackSize(), targetSlot.getItem().getMaxStackSize())
						: Math.min(targetSlot.getMaxStackSize(), sourceSlot.getItem().getMaxStackSize());
					int moveCount = Math.min(stillNeeded, Math.min(sourceRemaining, maxTargetCount - currentTargetCount));
					if (moveCount <= 0) {
						break;
					}
					moves.add(new WithdrawalPlan.PlannedMove(sourceSlot, targetSlot, needEntry.getKey(), moveCount));
					virtualPlayerCounts.put(targetSlot.index, currentTargetCount + moveCount);
					virtualPlayerItemIds.put(targetSlot.index, needEntry.getKey());
					plannedWithdrawals.merge(needEntry.getKey(), moveCount, Integer::sum);
					sourceRemaining -= moveCount;
					stillNeeded -= moveCount;
				}
				if (blockedByInventory) {
					break;
				}
			}
			if (blockedByInventory) {
				break;
			}
		}

		return new WithdrawalPlan(moves, plannedWithdrawals, Map.of(), blockedByInventory);
	}

	private Map<String, Integer> executeWithdrawalPlan(AbstractContainerMenu menu, WithdrawalPlan plan) {
		Map<String, Integer> executedWithdrawals = new LinkedHashMap<>();
		for (WithdrawalPlan.PlannedMove move : plan.moves()) {
			if (!move.source().hasItem() || !move.source().mayPickup(player)) {
				break;
			}
			ItemStack withdrawnStack = move.source().getItem().copy();
			withdrawnStack.setCount(Math.min(move.count(), withdrawnStack.getCount()));
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
			withdrawnStack.setCount(result.moved());
			PulledResourcesTracker.recordWithdrawal(pendingContainerPos, move.source().index, withdrawnStack);
			executedWithdrawals.merge(move.itemId(), result.moved(), Integer::sum);
		}
		return executedWithdrawals;
	}

	private void beginResume() {
		player.closeContainer();
		timeoutTicks = RESUME_DELAY_TICKS;
		reopenSettledTicks = 0;
		state = StageState.RESUME_CONTEXT;
	}

	private static Map<String, Integer> normalize(Map<String, Integer> counts) {
		Map<String, Integer> normalized = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (entry.getValue() > 0) {
				normalized.put(entry.getKey(), entry.getValue());
			}
		}
		return normalized;
	}

	private static void subtract(Map<String, Integer> counts, String itemId, int count) {
		int current = counts.getOrDefault(itemId, 0);
		if (current <= count) {
			counts.remove(itemId);
		} else {
			counts.put(itemId, current - count);
		}
	}

	private void markVisited(BlockPos pos) {
		visited.add(pos);
		ContainerUtils.getOtherHalfOfLargeChest(level, pos).ifPresent(visited::add);
	}

	private static List<Slot> sortedMatchingContainerSources(AbstractContainerMenu menu, String itemId) {
		List<Slot> sources = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory || !slot.hasItem()) {
				continue;
			}
			String slotItemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
			if (itemId.equals(slotItemId)) {
				sources.add(slot);
			}
		}
		sources.sort(Comparator.comparingInt(slot -> slot.getItem().getCount()));
		return sources;
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
			if (slot.container instanceof Inventory && slot.hasItem()) {
				itemIds.put(slot.index, BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());
			}
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
			int currentCount = virtualPlayerCounts.getOrDefault(slot.index, slot.hasItem() ? slot.getItem().getCount() : 0);
			if (virtualItemId == null || currentCount <= 0) {
				if (emptySlot == null) {
					emptySlot = slot;
				}
				continue;
			}
			if (!itemId.equals(virtualItemId)) {
				continue;
			}
			// The slot may be virtually filled while the real stack is still
			// empty mid-planning; capping by the real stack then reads 64 and
			// funnels every copy of an unstackable item (milk buckets) into
			// one slot, where the move-count math zeroes out — withdrawing a
			// single item per container. Cap by the item's own max stack.
			ItemStack stack = slot.getItem();
			int maxStackSize = stack.isEmpty()
				? Math.min(slot.getMaxStackSize(), maxStackSizeForItem(itemId))
				: Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
			if (currentCount < maxStackSize) {
				return slot;
			}
		}
		return emptySlot;
	}

	private static int maxStackSizeForItem(String itemId) {
		try {
			var item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(itemId));
			return item == null ? 64 : Math.max(item.getDefaultInstance().getMaxStackSize(), 1);
		} catch (Exception ignored) {
			return 64;
		}
	}

	private enum StageState {
		OPEN_NEXT,
		WAITING_FOR_CONTAINER,
		RESUME_CONTEXT,
		WAITING_FOR_REOPEN
	}
}
