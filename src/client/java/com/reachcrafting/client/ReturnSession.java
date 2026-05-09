package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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

/**
 * ReturnSession owns post-craft container returns for one closing screen.
 * Invariants: excess is computed once at start; each opened container gets a plan before any moves execute.
 */
final class ReturnSession extends BaseCraftSession {
	private final List<PulledResourcesTracker.WithdrawnItem> itemsToReturn;
	private final List<BlockPos> uniquePositions;
	private final AbstractContainerMenu closingMenu;
	private final ScreenContextSnapshot originalContext;
	private final boolean reopenScreen;
	private int nextPositionIndex = 0;
	private int timeoutTicks = 0;
	private BlockPos pendingContainerPos;
	private SearchState state = SearchState.OPEN_NEXT;
	private final Map<String, Integer> currentExcess = new HashMap<>();

	ReturnSession(
		NearbyCraftCoordinator coordinator,
		Minecraft client,
		LocalPlayer player,
		Level level,
		MultiPlayerGameMode gameMode,
		Entity cameraEntity,
		ReturnRequest request
	) {
		super(coordinator, client, player, level, gameMode, cameraEntity);
		this.closingMenu = request.closingMenu();
		this.itemsToReturn = new ArrayList<>(request.items());
		this.originalContext = request.originalContext();
		this.reopenScreen = request.reopenScreen();
		LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
		for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
			positions.add(item.containerPos());
		}
		this.uniquePositions = new ArrayList<>(positions);
	}

	@Override
	public void start() {
		com.reachcrafting.ReachCraftingMod.LOGGER.debug("[nearby_return] starting return session with menu {} ({} slots)", closingMenu.getClass().getSimpleName(), closingMenu.slots.size());
		sendDebugChat("Returning items to containers...");
		
		Map<String, Integer> currentCounts = new HashMap<>();
		for (Slot slot : closingMenu.slots) {
			// Skip result slots (slot 0 in both 2x2 and 3x3)
			if (slot.index == 0 && (closingMenu instanceof net.minecraft.world.inventory.CraftingMenu || closingMenu instanceof net.minecraft.world.inventory.InventoryMenu)) {
				continue;
			}
			if (slot.hasItem()) {
				String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				currentCounts.merge(itemId, slot.getItem().getCount(), Integer::sum);
			}
		}

		Set<String> trackedItemIds = new HashSet<>();
		for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
			trackedItemIds.add(BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString());
		}

		for (String itemId : trackedItemIds) {
			int current = currentCounts.getOrDefault(itemId, 0);
			int excess = PulledResourcesTracker.getExcessCount(itemId, current);
			
			com.reachcrafting.ReachCraftingMod.LOGGER.debug("[nearby_return] item={} current={} excess={}", itemId, current, excess);
			
			if (excess > 0) {
				currentExcess.put(itemId, excess);
			}
		}
	}

	@Override
	public void tick() {
		if (client.player != player || client.level != level) {
			finishSession(false);
			return;
		}

		if (state == SearchState.OPEN_NEXT) {
			if (!openNextContainer()) {
				if (reopenScreen && originalContext.kind() != ScreenKind.NONE) {
					state = SearchState.RESUME_CONTEXT;
					timeoutTicks = 2; // Short delay before re-opening
				} else {
					finishSession(false);
				}
			}
			return;
		}

		if (state == SearchState.RESUME_CONTEXT) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				resumeOriginalContext();
				finishSession(false);
			}
			return;
		}

		if (state == SearchState.WAITING_FOR_CONTAINER) {
			timeoutTicks--;
			if (timeoutTicks <= 0) {
				onOpenFailed("timeout");
			}
			return;
		}
	}

	@Override
	public void onOpenFailed(String reason) {
		ReachCraftingMod.LOGGER.debug("[nearby_return] pos={} skipped={}", ContainerUtils.formatPos(pendingContainerPos), reason);
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = SearchState.OPEN_NEXT;
	}

	private boolean openNextContainer() {
		Vec3 eyePos = cameraEntity.getEyePosition(0);
		while (nextPositionIndex < uniquePositions.size()) {
			BlockPos pos = uniquePositions.get(nextPositionIndex++);
			BlockState blockState = level.getBlockState(pos);
			if (!InWorldFilterManager.isContainerActive(level, pos, blockState)) continue;
			if (!ContainerUtils.canAttemptOpen(level, pos, blockState)) continue;
			if (ContainerUtils.squaredDistanceToBlock(eyePos, pos) > net.minecraft.util.Mth.square(player.blockInteractionRange())) continue;

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
			timeoutTicks = 40;
			state = SearchState.WAITING_FOR_CONTAINER;
			return true;
		}
		return false;
	}

	@Override
	public void onContainerContentsInitialized(AbstractContainerMenu menu) {
		if (state != SearchState.WAITING_FOR_CONTAINER) return;

		if (client.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen || client.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen) {
			return;
		}
		if (menu.containerId == player.inventoryMenu.containerId) {
			return;
		}


		ReachCraftingMod.LOGGER.debug("[nearby_return] Container opened at {}. Preparing return plan...", ContainerUtils.formatPos(pendingContainerPos));

		ReturnPlan plan = buildReturnPlan(menu);
		executeReturnPlan(menu, plan);
		if (ReachCraftingConfig.get().restoreInventoryItemPositions()) {
			restoreProtectedInventoryLayout(menu);
		}
		NearbyContainerCache.recordObservedContents(level, pendingContainerPos, ContainerUtils.collectAllItems(menu));

		player.closeContainer();
		pendingContainerPos = null;
		timeoutTicks = 0;
		state = SearchState.OPEN_NEXT;
	}

	private void resumeOriginalContext() {
		super.resumeOriginalContext(originalContext);
	}

	private int getAvailableCountInSlot(Slot slot, List<ReturnPlan.PlannedMove> plan) {
		int planned = 0;
		for (ReturnPlan.PlannedMove p : plan) if (p.source() == slot) planned += p.count();
		if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
			return 0;
		}

		int protectedCount = PulledResourcesTracker.getProtectedSlotCount(slot.getContainerSlot(), slot.getItem());
		int returnableCount = Math.max(0, slot.getItem().getCount() - protectedCount);
		return Math.max(0, returnableCount - planned);
	}

	private Slot findAvailableInventorySlot(AbstractContainerMenu menu, String itemId, List<ReturnPlan.PlannedMove> plan) {
		Slot bestSlot = null;
		int bestAvailable = 0;
		int bestProtectedCount = Integer.MAX_VALUE;
		for (Slot slot : menu.slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.hasItem()) {
				String id = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				if (!id.equals(itemId)) {
					continue;
				}

				int available = getAvailableCountInSlot(slot, plan);
				if (available <= 0) {
					continue;
				}

				int protectedCount = PulledResourcesTracker.getProtectedSlotCount(slot.getContainerSlot(), slot.getItem());
				if (bestSlot == null
					|| protectedCount < bestProtectedCount
					|| (protectedCount == bestProtectedCount && available > bestAvailable)) {
					bestSlot = slot;
					bestAvailable = available;
					bestProtectedCount = protectedCount;
				}
			}
		}
		return bestSlot;
	}

	private Slot findBestTargetSlot(AbstractContainerMenu menu, String itemId, ItemStack stack, Map<Integer, Integer> simulatedOccupancy) {
		// A. Try original slots first
		for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
			if (item.containerPos().equals(pendingContainerPos)) {
				String id = BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString();
				if (id.equals(itemId)) {
					Slot slot = menu.getSlot(item.slotIndex());
					if (canAcceptMore(slot, stack, simulatedOccupancy)) return slot;
				}
			}
		}
		
		// B. Try any matching slot
		for (Slot slot : menu.slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
			if (slot.hasItem() && canAcceptMore(slot, stack, simulatedOccupancy)) {
				String id = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				if (id.equals(itemId)) return slot;
			}
		}
		
		// C. Fallback to empty slot
		for (Slot slot : menu.slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
			if (simulatedOccupancy.get(slot.index) == 0 && slot.mayPlace(stack)) return slot;
		}
		
		return null;
	}

	private ReturnPlan buildReturnPlan(AbstractContainerMenu menu) {
		Map<String, Integer> providedByThisChest = providedByCurrentContainer();
		Map<Integer, Integer> simulatedOccupancy = captureContainerOccupancy(menu);
		Map<String, Integer> plannedExcess = new HashMap<>(currentExcess);
		List<ReturnPlan.PlannedMove> moves = new ArrayList<>();

		for (Map.Entry<String, Integer> entry : providedByThisChest.entrySet()) {
			String itemId = entry.getKey();
			int amountToReturnToThisChest = Math.min(entry.getValue(), plannedExcess.getOrDefault(itemId, 0));
			if (amountToReturnToThisChest <= 0) {
				ReachCraftingMod.LOGGER.debug("[nearby_return]   Skipping {}: no excess available to return", itemId);
				continue;
			}

			int remaining = amountToReturnToThisChest;
			ReachCraftingMod.LOGGER.debug("[nearby_return]   Planning {}x {} for this container...", remaining, itemId);
			while (remaining > 0) {
				Slot inventorySlot = findAvailableInventorySlot(menu, itemId, moves);
				if (inventorySlot == null) {
					ReachCraftingMod.LOGGER.warn("[nearby_return]   Plan break: no more {} in inventory", itemId);
					break;
				}

				int availableInInvSlot = getAvailableCountInSlot(inventorySlot, moves);
				Slot targetSlot = findBestTargetSlot(menu, itemId, inventorySlot.getItem(), simulatedOccupancy);
				if (targetSlot == null) {
					ReachCraftingMod.LOGGER.warn("[nearby_return]   Plan break: no more space for {} in container", itemId);
					break;
				}

				int currentInChest = simulatedOccupancy.get(targetSlot.index);
				int max = Math.min(targetSlot.getMaxStackSize(), inventorySlot.getItem().getMaxStackSize());
				int canAccept = max - currentInChest;
				int amountToMove = Math.min(Math.min(availableInInvSlot, remaining), canAccept);
				if (amountToMove <= 0) {
					ReachCraftingMod.LOGGER.warn("[nearby_return]   Plan break: target slot {} cannot accept more ({} / {})", targetSlot.index, currentInChest, max);
					break;
				}

				moves.add(new ReturnPlan.PlannedMove(inventorySlot, targetSlot, amountToMove));
				simulatedOccupancy.put(targetSlot.index, currentInChest + amountToMove);
				remaining -= amountToMove;
				plannedExcess.put(itemId, plannedExcess.get(itemId) - amountToMove);
				ReachCraftingMod.LOGGER.debug("[nearby_return]   Added to plan: {}x from inv {} to chest {}", amountToMove, inventorySlot.index, targetSlot.index);
			}
		}

		return new ReturnPlan(moves, plannedExcess);
	}

	private Map<String, Integer> providedByCurrentContainer() {
		Map<String, Integer> providedByThisChest = new LinkedHashMap<>();
		for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
			if (item.containerPos().equals(pendingContainerPos)) {
				String itemId = BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString();
				providedByThisChest.merge(itemId, item.stack().getCount(), Integer::sum);
			}
		}
		return providedByThisChest;
	}

	private Map<Integer, Integer> captureContainerOccupancy(AbstractContainerMenu menu) {
		Map<Integer, Integer> simulatedOccupancy = new HashMap<>();
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) {
				simulatedOccupancy.put(slot.index, slot.hasItem() ? slot.getItem().getCount() : 0);
			}
		}
		return simulatedOccupancy;
	}

	private void executeReturnPlan(AbstractContainerMenu menu, ReturnPlan plan) {
		ReachCraftingMod.LOGGER.debug("[nearby_return] Plan complete. Executing {} moves.", plan.moves().size());
		for (ReturnPlan.PlannedMove move : plan.moves()) {
			String itemId = BuiltInRegistries.ITEM.getKey(move.source().getItem().getItem()).toString();
			ReachCraftingMod.LOGGER.debug("[nearby_return]   Executing: {}x {} (slot {} -> {})", move.count(), itemId, move.source().index, move.target().index);
			moveItem(menu, move.source(), move.target(), move.count());
		}
		currentExcess.clear();
		currentExcess.putAll(plan.updatedExcess());
	}

	private void restoreProtectedInventoryLayout(AbstractContainerMenu menu) {
		Map<Integer, ItemStack> snapshots = PulledResourcesTracker.getInitialSlotSnapshots();
		if (snapshots.isEmpty()) {
			return;
		}

		for (Map.Entry<Integer, ItemStack> entry : snapshots.entrySet()) {
			int inventorySlot = entry.getKey();
			ItemStack snapshot = entry.getValue();
			if (snapshot.isEmpty()) {
				continue;
			}

			Slot targetSlot = findInventoryMenuSlot(menu, inventorySlot);
			if (targetSlot == null) {
				continue;
			}

			ItemStack current = targetSlot.getItem();
			if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, snapshot)) {
				continue;
			}

			int currentCount = current.isEmpty() ? 0 : current.getCount();
			int missing = snapshot.getCount() - currentCount;
			if (missing <= 0) {
				continue;
			}

			while (missing > 0) {
				Slot donor = findProtectedLayoutDonor(menu, inventorySlot, snapshot, missing);
				if (donor == null) {
					ReachCraftingMod.LOGGER.debug(
						"[nearby_return]   Layout restore stalled for invSlot {}: missing {}x {}",
						inventorySlot,
						missing,
						BuiltInRegistries.ITEM.getKey(snapshot.getItem())
					);
					break;
				}

				int donorProtected = PulledResourcesTracker.getProtectedSlotCount(donor.getContainerSlot(), donor.getItem());
				int donorAvailable = donor.getItem().getCount() - donorProtected;
				int toMove = Math.min(missing, donorAvailable);
				if (toMove <= 0) {
					break;
				}

				ReachCraftingMod.LOGGER.debug(
					"[nearby_return]   Restoring layout: {}x {} from inv {} to inv {}",
					toMove,
					BuiltInRegistries.ITEM.getKey(snapshot.getItem()),
					donor.getContainerSlot(),
					inventorySlot
				);
				moveItem(menu, donor, targetSlot, toMove);
				missing -= toMove;
			}
		}
	}

	private Slot findInventoryMenuSlot(AbstractContainerMenu menu, int inventorySlot) {
		return MenuTransferHelper.findInventoryMenuSlot(menu, inventorySlot);
	}

	private Slot findProtectedLayoutDonor(AbstractContainerMenu menu, int excludedInventorySlot, ItemStack desiredStack, int needed) {
		Slot bestSlot = null;
		int bestAvailable = 0;
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
				continue;
			}
			if (slot.getContainerSlot() == excludedInventorySlot) {
				continue;
			}
			if (!ItemStack.isSameItemSameComponents(slot.getItem(), desiredStack)) {
				continue;
			}

			int protectedCount = PulledResourcesTracker.getProtectedSlotCount(slot.getContainerSlot(), slot.getItem());
			int available = slot.getItem().getCount() - protectedCount;
			if (available <= 0) {
				continue;
			}
			if (bestSlot == null || available < bestAvailable || (available == bestAvailable && available >= needed)) {
				bestSlot = slot;
				bestAvailable = available;
			}
		}
		return bestSlot;
	}

	private boolean canAcceptMore(Slot slot, ItemStack stack, Map<Integer, Integer> simulatedOccupancy) {
		if (slot.container instanceof net.minecraft.world.entity.player.Inventory) return false;
		if (!slot.mayPlace(stack)) return false;
		
		int count = simulatedOccupancy.getOrDefault(slot.index, 0);
		int max = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
		if (count == 0) return true;
		
		ItemStack current = slot.hasItem() ? slot.getItem() : ItemStack.EMPTY;
		boolean same = current.isEmpty() || ItemStack.isSameItemSameComponents(current, stack);
		return same && count < max;
	}



	private void moveItem(AbstractContainerMenu menu, Slot source, Slot target, int count) {
		MenuTransferHelper.moveItem(menu, source, target, count, player, gameMode);
	}

	@Override
	public void stop(boolean closeContainer) {
		if (closeContainer) {
			player.closeContainer();
		}

		if (!ReachCraftingConfig.get().restoreInventoryItemPositions()) {
			Set<String> trackedItemIds = new LinkedHashSet<>();
			for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
				trackedItemIds.add(BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString());
			}
			if (reopenScreen) {
				coordinator.queuePostReturnCompaction(trackedItemIds);
				ReachCraftingMod.LOGGER.debug("[nearby_return] Deferred compaction queued for {}", trackedItemIds);
			} else {
				InventoryGridRestoreTracker.compactTrackedInventoryStacks(player.containerMenu, gameMode, trackedItemIds);
				ReachCraftingMod.LOGGER.debug(
					"[nearby_return] Post-stop tracked slots={}",
					AvailableItemSnapshot.formatInventorySlots(player, trackedItemIds)
				);
			}
		}

		Map<String, Integer> inventoryCounts = new LinkedHashMap<>();
		for (Slot slot : player.inventoryMenu.slots) {
			if (slot.hasItem()) {
				String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				inventoryCounts.merge(itemId, slot.getItem().getCount(), Integer::sum);
			}
		}
		ReachCraftingMod.LOGGER.debug(
			"[nearby_return] Post-stop inventory summary={} reopen_screen={}",
			AvailableItemSnapshot.formatCounts(inventoryCounts),
			reopenScreen
		);
		
		// Log final inventory state for debugging
		ReachCraftingMod.LOGGER.debug("[nearby_return] Session stopped. Final inventory state:");
		for (Slot slot : player.inventoryMenu.slots) {
			if (slot.hasItem()) {
				ReachCraftingMod.LOGGER.debug("  slot {}: {}x {}", slot.index, slot.getItem().getCount(), BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()));
			}
		}
		
		PulledResourcesTracker.clear();
	}
}
