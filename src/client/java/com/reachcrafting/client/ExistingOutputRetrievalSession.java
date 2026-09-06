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
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pulls already-crafted copies of a recipe output out of nearby containers.
 *
 * <p>Requests are never capped: the session walks containers (cache-priority
 * order) until the request is met or nearby stock runs out. Within one
 * container the inventory is filled first; once it has no room left for the
 * item and {@code ejectItemsWhenFull} is on, the remaining stacks are thrown
 * onto the ground straight from the container slot (one THROW per stack,
 * nothing ever on the cursor). A session that ejected anything therefore ends
 * with the inventory still full. With eject off it stops at the first full
 * inventory, as it always did.</p>
 */
final class ExistingOutputRetrievalSession extends BaseCraftSession {
	private static final int OPEN_TIMEOUT_TICKS = 40;
	private static final int RESUME_DELAY_TICKS = 5;
	private static final int REOPEN_TIMEOUT_TICKS = 20;
	private static final int REOPEN_SETTLE_TICKS = 2;
	private static final int MAX_REOPEN_ATTEMPTS = 3;
	// The click governor's window is a 7 s sliding expiry, so a wait longer
	// than the window is the only wait that can ever succeed; past that we
	// proceed anyway (a slow retrieval beats a lost one).
	private static final int BUDGET_WAIT_LIMIT_TICKS = 200;

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
	private int ejectedCount;
	private int containerVisits;
	private int budgetWaits;
	private int budgetWaitTicks;
	private final long startedAtMillis = System.currentTimeMillis();
	private boolean finished;
	private boolean despawnClockArmed;
	private BlockPos pendingContainerPos;
	private AbstractContainerMenu pendingMenu;
	private RetrievalPlan pendingPlan;
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

	private static boolean ejectAllowed() {
		return ReachCraftingConfig.get().ejectItemsWhenFull();
	}

	@Override
	public void start() {
		if (candidates.isEmpty()) {
			sendMissingIngredientsChat("No nearby containers available for retrieval.");
			logComplete("no_candidates");
			finishSession(false);
			return;
		}
		ReachCraftingMod.diag(
			"[retrieve_existing] start item={} requested={} candidates={} eject_allowed={}",
			request.outputItemId(),
			remainingCount,
			candidates.size(),
			ejectAllowed()
		);
		sendDebugChat("Retrieving existing: " + request.outputLabel());
	}

	@Override
	public void tick() {
		if (client.player != player || client.level != level) {
			finishSession(false);
			return;
		}
		if (despawnClockArmed) {
			BulkDespawnWarning.tick();
		}

		if (state == RetrievalState.OPEN_NEXT) {
			if (remainingCount <= 0 || (inventorySpaceBlocked && !ejectAllowed())) {
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

		if (state == RetrievalState.WAITING_FOR_BUDGET) {
			if (pendingMenu == null || player.containerMenu != pendingMenu) {
				onOpenFailed("container_closed_during_budget_wait");
				return;
			}
			budgetWaitTicks++;
			int estimate = pendingPlan.estimatedClicks();
			if (GridTopUp.clickBudgetAllowsQuietly(estimate) || budgetWaitTicks >= BUDGET_WAIT_LIMIT_TICKS) {
				ReachCraftingMod.diag(
					"[retrieve_existing] budget_wait_end pos={} waited_ticks={} estimate={} window={}/{} forced={}",
					ContainerUtils.formatPos(pendingContainerPos),
					budgetWaitTicks,
					estimate,
					GridTopUp.clickWindowCount(),
					GridTopUp.clickWindowCap(),
					budgetWaitTicks >= BUDGET_WAIT_LIMIT_TICKS
				);
				executePendingPlan();
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
		pendingMenu = null;
		pendingPlan = null;
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
		containerVisits++;
		pendingMenu = menu;
		pendingPlan = buildPlan(menu);
		int estimate = pendingPlan.estimatedClicks();
		if (estimate > 0 && !GridTopUp.clickBudgetAllowsQuietly(estimate)) {
			budgetWaits++;
			budgetWaitTicks = 0;
			ReachCraftingMod.diag(
				"[retrieve_existing] budget_wait pos={} estimate={} window={}/{}",
				ContainerUtils.formatPos(pendingContainerPos),
				estimate,
				GridTopUp.clickWindowCount(),
				GridTopUp.clickWindowCap()
			);
			state = RetrievalState.WAITING_FOR_BUDGET;
			return;
		}
		executePendingPlan();
	}

	private void executePendingPlan() {
		AbstractContainerMenu menu = pendingMenu;
		RetrievalPlan plan = pendingPlan;
		int available = 0;
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory) && slot.hasItem() && isRequestedItem(slot.getItem())) {
				available += slot.getItem().getCount();
			}
		}
		long clicksBefore = MenuTransferHelper.clicksIssued();
		int moved = executeFills(menu, plan.fills());
		int ejected = executeEjects(menu, plan.ejects());
		long transferClicks = MenuTransferHelper.clicksIssued() - clicksBefore;
		for (long i = 0; i < transferClicks; i++) {
			GridTopUp.recordClick();
		}
		ReachCraftingMod.diag(
			"[retrieve_existing] visit pos={} available={} planned={} moved={} planned_eject={} ejected={} clicks={} remaining_before={} space_blocked={}",
			ContainerUtils.formatPos(pendingContainerPos),
			available,
			plan.fillCount(),
			moved,
			plan.ejectCount(),
			ejected,
			transferClicks + plan.ejectClicksIssued(),
			remainingCount,
			plan.inventorySpaceBlocked()
		);
		applyResults(moved, ejected);
		player.closeContainer();
		pendingContainerPos = null;
		pendingMenu = null;
		pendingPlan = null;
		timeoutTicks = 0;
		state = RetrievalState.OPEN_NEXT;
	}

	@Override
	public void stop(boolean closeContainer) {
		if (!finished) {
			// Reached only through cancelCurrent/abortAllSessions: the normal
			// path logs retrieve_complete first and sets finished.
			finished = true;
			ReachCraftingMod.diag(
				"[retrieve_existing] retrieve_aborted item={} requested={} retrieved={} ejected={} remaining={} visits={} state={} carried={}",
				request.outputItemId(),
				request.requestedCount(),
				retrievedCount,
				ejectedCount,
				remainingCount,
				containerVisits,
				state,
				ContainerUtils.formatStack(player.containerMenu.getCarried())
			);
		}
		if (despawnClockArmed) {
			BulkDespawnWarning.clear();
			despawnClockArmed = false;
		}
		if (closeContainer && player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}

	/** One line per finished session, in a fixed key=value shape the e2e driver parses. */
	private void logComplete(String outcome) {
		finished = true;
		ReachCraftingMod.diag(
			"[retrieve_existing] retrieve_complete outcome={} item={} requested={} retrieved={} ejected={} remaining={} visits={} budget_waits={} space_blocked={} ms={}",
			outcome,
			request.outputItemId(),
			request.requestedCount(),
			retrievedCount,
			ejectedCount,
			remainingCount,
			containerVisits,
			budgetWaits,
			inventorySpaceBlocked,
			System.currentTimeMillis() - startedAtMillis
		);
	}

	private void beginResume() {
		player.closeContainer();
		timeoutTicks = RESUME_DELAY_TICKS;
		reopenAttemptsRemaining = MAX_REOPEN_ATTEMPTS;
		reopenSettledTicks = 0;
		state = RetrievalState.RESUME_CONTEXT;
	}

	private void finishAfterResume() {
		String itemName = ContainerUtils.getItemName(request.outputItemId());
		String outcome;
		if (retrievedCount <= 0 && ejectedCount <= 0) {
			sendMissingIngredientsChat("No matching existing items nearby.");
			outcome = "none_found";
		} else if (ejectedCount > 0) {
			sendChat("Retrieved " + ContainerUtils.formatStackBreakdown(retrievedCount) + " " + itemName
				+ ", " + ContainerUtils.formatStackBreakdown(ejectedCount) + " more ejected to the ground.");
			outcome = "ejected";
		} else if (inventorySpaceBlocked) {
			sendChat("Retrieved " + ContainerUtils.formatStackBreakdown(retrievedCount) + " " + itemName + ", then ran out of inventory space.");
			outcome = "inventory_full";
		} else if (remainingCount > 0) {
			sendChat("Retrieved " + ContainerUtils.formatStackBreakdown(retrievedCount) + " " + itemName + ".");
			outcome = "stock_exhausted";
		} else {
			sendChat("Retrieved " + ContainerUtils.formatStackBreakdown(retrievedCount) + " " + itemName + ".");
			outcome = "satisfied";
		}
		if ((retrievedCount > 0 || ejectedCount > 0) && request.requestedRecipeId() != null) {
			ReachCraftingConfig.get().noteRecentRecipe(request.requestedRecipeId());
		}
		logComplete(outcome);
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
		// Re-read per open: every withdrawal bumps the cache revision.
		NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(level, cameraEntity, player.blockInteractionRange());
		Set<String> wanted = Set.of(request.outputItemId());
		while (nextCandidateIndex < candidates.size()) {
			BlockPos pos = candidates.get(nextCandidateIndex++);
			if (visited.contains(pos)) {
				continue;
			}
			// A container the cache has already seen and knows holds none of
			// the item is not worth a screen flash and an open/close packet
			// pair. Uncached containers are still visited so a cold cache
			// discovers stock; cached ones with stock are visited as usual.
			if (isCachedWithoutItem(reachableView, pos, wanted)) {
				markVisited(pos);
				ReachCraftingMod.diag("[retrieve_existing] skip_cached_empty pos={}", ContainerUtils.formatPos(pos));
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

	private static boolean isCachedWithoutItem(NearbyContainerCache.ReachableView reachableView, BlockPos pos, Set<String> wanted) {
		NearbyContainerCache.ContainerKey key = reachableView.accessKeyByPos().get(pos);
		if (key == null || !reachableView.snapshotsByKey().containsKey(key)) {
			return false;
		}
		return reachableView.relevantCountAt(pos, wanted) <= 0;
	}

	private void markVisited(BlockPos pos) {
		visited.add(pos);
		ContainerUtils.getOtherHalfOfLargeChest(level, pos).ifPresent(visited::add);
	}

	private boolean isRequestedItem(ItemStack stack) {
		return !stack.isEmpty()
			&& request.outputItemId().equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}

	/**
	 * Fill moves first (into the inventory, per-stack room measured against
	 * the ITEM's max stack size so 1-, 16- and 64-stack items all plan
	 * correctly), then, only when the inventory has no room left and eject is
	 * allowed, eject moves for what is still owed. Virtual player stacks are
	 * real {@link ItemStack} copies so a chest stack with different components
	 * (renamed, damaged) is never planned into a slot the move would refuse.
	 */
	private RetrievalPlan buildPlan(AbstractContainerMenu menu) {
		Map<Integer, ItemStack> virtualPlayerStacks = capturePlayerVirtualStacks(menu);
		List<WithdrawalPlan.PlannedMove> fills = new ArrayList<>();
		List<EjectMove> ejects = new ArrayList<>();
		Map<Integer, Integer> plannedFromSource = new HashMap<>();
		boolean blockedByInventory = false;
		int stillNeeded = remainingCount;
		int fillCount = 0;
		int ejectCount = 0;
		int ejectClicks = 0;
		int splitMoves = 0;

		List<Slot> sources = sortedMatchingContainerSources(menu, request.outputItemId());
		for (Slot sourceSlot : sources) {
			if (stillNeeded <= 0 || blockedByInventory) {
				break;
			}
			ItemStack sourceStack = sourceSlot.getItem();
			int sourceRemaining = sourceStack.getCount();
			while (sourceRemaining > 0 && stillNeeded > 0) {
				Slot targetSlot = findPlannedDestinationSlot(menu, sourceStack, virtualPlayerStacks);
				if (targetSlot == null) {
					blockedByInventory = true;
					break;
				}

				ItemStack virtualTarget = virtualPlayerStacks.getOrDefault(targetSlot.index, ItemStack.EMPTY);
				int currentTargetCount = virtualTarget.isEmpty() ? 0 : virtualTarget.getCount();
				int maxTargetCount = Math.min(targetSlot.getMaxStackSize(), sourceStack.getMaxStackSize());
				int roomInTarget = maxTargetCount - currentTargetCount;
				int moveCount = Math.min(stillNeeded, Math.min(sourceRemaining, roomInTarget));
				if (moveCount <= 0) {
					// Defensive: the destination finder only returns slots with room.
					blockedByInventory = true;
					break;
				}

				fills.add(new WithdrawalPlan.PlannedMove(sourceSlot, targetSlot, request.outputItemId(), moveCount));
				virtualPlayerStacks.put(targetSlot.index, sourceStack.copyWithCount(currentTargetCount + moveCount));
				plannedFromSource.merge(sourceSlot.index, moveCount, Integer::sum);
				if (moveCount != sourceStack.getCount()) {
					splitMoves++;
				}
				sourceRemaining -= moveCount;
				stillNeeded -= moveCount;
				fillCount += moveCount;
			}
		}

		if (blockedByInventory && stillNeeded > 0 && ejectAllowed()) {
			for (Slot sourceSlot : sources) {
				if (stillNeeded <= 0) {
					break;
				}
				int left = sourceSlot.getItem().getCount() - plannedFromSource.getOrDefault(sourceSlot.index, 0);
				if (left <= 0) {
					continue;
				}
				if (left <= stillNeeded) {
					ejects.add(new EjectMove(sourceSlot, left, true));
					ejectClicks += 1;
					stillNeeded -= left;
					ejectCount += left;
				} else {
					// Exact remainder: one single-item throw per unit.
					ejects.add(new EjectMove(sourceSlot, stillNeeded, false));
					ejectClicks += stillNeeded;
					ejectCount += stillNeeded;
					stillNeeded = 0;
				}
			}
		}

		// 2 clicks per whole-stack move; a split costs a few more (the helper
		// picks the cheapest of three strategies, all under ~4 for one split).
		int estimatedClicks = fills.size() * 2 + splitMoves * 2 + ejectClicks;
		return new RetrievalPlan(fills, ejects, fillCount, ejectCount, blockedByInventory, estimatedClicks, ejectClicks);
	}

	private int executeFills(AbstractContainerMenu menu, List<WithdrawalPlan.PlannedMove> fills) {
		int moved = 0;
		for (WithdrawalPlan.PlannedMove move : fills) {
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
			moved += result.moved();
		}
		return moved;
	}

	/**
	 * THROW straight from the container slot: button 1 drops the whole stack,
	 * button 0 drops one item. The client applies the click locally, so the
	 * slot's count after the click is the ejected amount.
	 */
	private int executeEjects(AbstractContainerMenu menu, List<EjectMove> ejects) {
		int ejected = 0;
		for (EjectMove eject : ejects) {
			Slot source = eject.source();
			if (!source.hasItem() || !source.mayPickup(player) || !isRequestedItem(source.getItem())) {
				continue;
			}
			if (!player.containerMenu.getCarried().isEmpty()) {
				ReachCraftingMod.diag("[retrieve_existing] eject skipped: cursor holds {}", ContainerUtils.formatStack(player.containerMenu.getCarried()));
				break;
			}
			int before = source.getItem().getCount();
			if (eject.wholeStack()) {
				gameMode.handleContainerInput(menu.containerId, source.index, 1, ContainerInput.THROW, player);
				GridTopUp.recordClick();
			} else {
				for (int i = 0; i < eject.count() && source.hasItem(); i++) {
					gameMode.handleContainerInput(menu.containerId, source.index, 0, ContainerInput.THROW, player);
					GridTopUp.recordClick();
				}
			}
			int after = source.hasItem() ? source.getItem().getCount() : 0;
			int thrown = Math.max(before - after, 0);
			if (thrown > 0 && !despawnClockArmed) {
				despawnClockArmed = true;
				BulkDespawnWarning.noteSessionStart();
			}
			ejected += thrown;
		}
		return ejected;
	}

	private void applyResults(int moved, int ejected) {
		int total = moved + ejected;
		if (total <= 0) {
			return;
		}
		retrievedCount += moved;
		ejectedCount += ejected;
		remainingCount = Math.max(remainingCount - total, 0);
		NearbyContainerCache.applyWithdrawals(level, pendingContainerPos, Map.of(request.outputItemId(), total));
		if (remainingCount > 0) {
			inventorySpaceBlocked = !inventoryHasRoom(player.containerMenu);
		}
	}

	/** True if any player slot could take at least one more of the requested item. */
	private boolean inventoryHasRoom(AbstractContainerMenu menu) {
		ItemStack sample = request.displayStack().isEmpty()
			? ItemStack.EMPTY
			: request.displayStack().copyWithCount(1);
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory)) {
				continue;
			}
			if (!slot.hasItem()) {
				return true;
			}
			ItemStack stack = slot.getItem();
			if (!isRequestedItem(stack)) {
				continue;
			}
			if (!sample.isEmpty() && !ItemStack.isSameItemSameComponents(stack, sample)) {
				continue;
			}
			if (stack.getCount() < Math.min(slot.getMaxStackSize(), stack.getMaxStackSize())) {
				return true;
			}
		}
		return false;
	}

	private static Map<Integer, ItemStack> capturePlayerVirtualStacks(AbstractContainerMenu menu) {
		Map<Integer, ItemStack> stacks = new HashMap<>();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory) {
				stacks.put(slot.index, slot.hasItem() ? slot.getItem().copy() : ItemStack.EMPTY);
			}
		}
		return stacks;
	}

	private static Slot findPlannedDestinationSlot(
		AbstractContainerMenu menu,
		ItemStack sourceStack,
		Map<Integer, ItemStack> virtualPlayerStacks
	) {
		Slot emptySlot = null;
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory)) {
				continue;
			}

			ItemStack virtualStack = virtualPlayerStacks.getOrDefault(slot.index, ItemStack.EMPTY);
			if (virtualStack.isEmpty()) {
				if (emptySlot == null) {
					emptySlot = slot;
				}
				continue;
			}

			if (!ItemStack.isSameItemSameComponents(virtualStack, sourceStack)) {
				continue;
			}

			int maxCount = Math.min(slot.getMaxStackSize(), sourceStack.getMaxStackSize());
			if (virtualStack.getCount() < maxCount) {
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

	private record EjectMove(Slot source, int count, boolean wholeStack) {
	}

	private record RetrievalPlan(
		List<WithdrawalPlan.PlannedMove> fills,
		List<EjectMove> ejects,
		int fillCount,
		int ejectCount,
		boolean inventorySpaceBlocked,
		int estimatedClicks,
		int ejectClicksIssued
	) {
	}

	private enum RetrievalState {
		OPEN_NEXT,
		WAITING_FOR_CONTAINER,
		WAITING_FOR_BUDGET,
		RESUME_CONTEXT,
		WAITING_FOR_REOPEN
	}
}
