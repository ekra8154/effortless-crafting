package com.reachcrafting.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

final class MenuTransferHelper {
	enum WithdrawalExecutionMode {
		FULL_STACK,
		SPLIT_PICKUP,
		REMAINDER_BACK_TO_SOURCE,
		REPEATED_TARGET_PLACEMENT,
		FAILED
	}

	record WithdrawalMoveResult(int moved, WithdrawalExecutionMode mode) {
		static WithdrawalMoveResult failed() {
			return new WithdrawalMoveResult(0, WithdrawalExecutionMode.FAILED);
		}
	}

	private MenuTransferHelper() {
	}

	static void pickup(MultiPlayerGameMode gameMode, LocalPlayer player, AbstractContainerMenu menu, Slot slot, int mouseButton) {
		gameMode.handleContainerInput(menu.containerId, slot.index, mouseButton, ContainerInput.PICKUP, player);
	}

	static Slot findPlayerDestinationSlot(LocalPlayer player, AbstractContainerMenu menu, String itemId) {
		Slot emptySlot = null;
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory)) {
				continue;
			}
			if (!slot.hasItem()) {
				if (emptySlot == null && slot.mayPlace(player.containerMenu.getCarried())) {
					emptySlot = slot;
				}
				continue;
			}

			ItemStack stack = slot.getItem();
			String slotItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if (itemId.equals(slotItemId) && stack.getCount() < stack.getMaxStackSize()) {
				return slot;
			}
		}
		return emptySlot;
	}

	static Slot findMatchingInventorySourceSlot(AbstractContainerMenu menu, ItemStack desiredStack, LocalPlayer player) {
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem() || !slot.mayPickup(player)) {
				continue;
			}
			if (ItemStack.isSameItemSameComponents(slot.getItem(), desiredStack) && slot.mayPlace(desiredStack)) {
				return slot;
			}
		}
		return null;
	}

	static int moveExactCount(AbstractContainerMenu menu, Slot sourceSlot, Slot targetSlot, int remaining, LocalPlayer player, MultiPlayerGameMode gameMode) {
		ItemStack sourceStack = sourceSlot.getItem();
		ItemStack targetStack = targetSlot.getItem();
		if (sourceStack.isEmpty()) {
			return 0;
		}

		int moveCount = Math.min(remaining, sourceStack.getCount());
		boolean canLeftClickMerge = targetStack.isEmpty() || (ItemStack.isSameItemSameComponents(sourceStack, targetStack) && targetStack.getCount() + moveCount <= targetStack.getMaxStackSize());

		if (canLeftClickMerge && moveCount == sourceStack.getCount()) {
			pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			pickup(gameMode, player, menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			if (!player.containerMenu.getCarried().isEmpty()) {
				pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			}
			return moveCount;
		}

		pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
		ItemStack carriedStack = player.containerMenu.getCarried();
		if (carriedStack.isEmpty()) {
			return 0;
		}

		int placed = 0;
		if (canLeftClickMerge && moveCount == carriedStack.getCount()) {
			pickup(gameMode, player, menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			placed = moveCount;
		} else {
			while (placed < moveCount && !player.containerMenu.getCarried().isEmpty()) {
				pickup(gameMode, player, menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
				placed++;
			}
		}

		if (!player.containerMenu.getCarried().isEmpty()) {
			Slot returnSlot = findMatchingInventorySourceSlot(menu, player.containerMenu.getCarried(), player);
			if (returnSlot != null) {
				pickup(gameMode, player, menu, returnSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			} else {
				for (Slot slot : menu.slots) {
					if (slot.container instanceof Inventory && !slot.hasItem() && slot.mayPlace(player.containerMenu.getCarried())) {
						pickup(gameMode, player, menu, slot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
						break;
					}
				}
			}
		}
		return placed;
	}

	static WithdrawalMoveResult moveExactCountFromContainerToInventory(
		AbstractContainerMenu menu,
		Slot sourceSlot,
		Slot targetSlot,
		int remaining,
		LocalPlayer player,
		MultiPlayerGameMode gameMode
	) {
		ItemStack sourceStack = sourceSlot.getItem();
		ItemStack targetStack = targetSlot.getItem();
		if (sourceStack.isEmpty() || remaining <= 0) {
			return WithdrawalMoveResult.failed();
		}

		int maxTargetCount = Math.min(targetSlot.getMaxStackSize(), sourceStack.getMaxStackSize());
		int currentTargetCount = targetStack.isEmpty() ? 0 : targetStack.getCount();
		boolean compatibleTarget = targetStack.isEmpty() || ItemStack.isSameItemSameComponents(sourceStack, targetStack);
		if (!compatibleTarget) {
			return WithdrawalMoveResult.failed();
		}

		int roomInTarget = maxTargetCount - currentTargetCount;
		int moveCount = Math.min(remaining, Math.min(sourceStack.getCount(), roomInTarget));
		if (moveCount <= 0) {
			return WithdrawalMoveResult.failed();
		}

		int sourceCount = sourceStack.getCount();
		int splitCarryCount = (sourceCount + 1) / 2;
		int splitRemainderClicks = Math.max(0, splitCarryCount - moveCount);
		int sourceRemainderAfterFullPickup = sourceCount - moveCount;

		if (moveCount == sourceCount) {
			pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			pickup(gameMode, player, menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			if (!player.containerMenu.getCarried().isEmpty()) {
				pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				return WithdrawalMoveResult.failed();
			}
			return new WithdrawalMoveResult(moveCount, WithdrawalExecutionMode.FULL_STACK);
		}

		boolean canUseSplitPickup = moveCount <= splitCarryCount;
		int splitClickCost = canUseSplitPickup ? 2 + splitRemainderClicks : Integer.MAX_VALUE;
		int remainderBackCost = 2 + sourceRemainderAfterFullPickup;
		int repeatedTargetCost = 1 + moveCount + (sourceRemainderAfterFullPickup > 0 ? 1 : 0);

		WithdrawalExecutionMode mode = selectWithdrawalExecutionMode(splitClickCost, remainderBackCost, repeatedTargetCost);
		return switch (mode) {
			case SPLIT_PICKUP -> executeSplitPickup(menu, sourceSlot, targetSlot, moveCount, splitCarryCount, player, gameMode);
			case REMAINDER_BACK_TO_SOURCE -> executeRemainderBack(menu, sourceSlot, targetSlot, moveCount, sourceRemainderAfterFullPickup, player, gameMode);
			case REPEATED_TARGET_PLACEMENT -> executeRepeatedTargetPlacement(menu, sourceSlot, targetSlot, moveCount, player, gameMode);
			case FULL_STACK, FAILED -> WithdrawalMoveResult.failed();
		};
	}

	private static WithdrawalExecutionMode selectWithdrawalExecutionMode(int splitClickCost, int remainderBackCost, int repeatedTargetCost) {
		int best = Math.min(splitClickCost, Math.min(remainderBackCost, repeatedTargetCost));
		if (best == splitClickCost) {
			return WithdrawalExecutionMode.SPLIT_PICKUP;
		}
		if (best == remainderBackCost) {
			return WithdrawalExecutionMode.REMAINDER_BACK_TO_SOURCE;
		}
		return WithdrawalExecutionMode.REPEATED_TARGET_PLACEMENT;
	}

	private static WithdrawalMoveResult executeSplitPickup(
		AbstractContainerMenu menu,
		Slot sourceSlot,
		Slot targetSlot,
		int moveCount,
		int splitCarryCount,
		LocalPlayer player,
		MultiPlayerGameMode gameMode
	) {
		pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		if (player.containerMenu.getCarried().isEmpty()) {
			return WithdrawalMoveResult.failed();
		}

		for (int i = 0; i < splitCarryCount - moveCount; i++) {
			pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		}
		pickup(gameMode, player, menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
		if (!player.containerMenu.getCarried().isEmpty()) {
			pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			return WithdrawalMoveResult.failed();
		}
		return new WithdrawalMoveResult(moveCount, WithdrawalExecutionMode.SPLIT_PICKUP);
	}

	private static WithdrawalMoveResult executeRemainderBack(
		AbstractContainerMenu menu,
		Slot sourceSlot,
		Slot targetSlot,
		int moveCount,
		int sourceRemainderAfterFullPickup,
		LocalPlayer player,
		MultiPlayerGameMode gameMode
	) {
		pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
		if (player.containerMenu.getCarried().isEmpty()) {
			return WithdrawalMoveResult.failed();
		}

		for (int i = 0; i < sourceRemainderAfterFullPickup; i++) {
			pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		}
		pickup(gameMode, player, menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);

		if (!player.containerMenu.getCarried().isEmpty()) {
			pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			return WithdrawalMoveResult.failed();
		}
		return new WithdrawalMoveResult(moveCount, WithdrawalExecutionMode.REMAINDER_BACK_TO_SOURCE);
	}

	private static WithdrawalMoveResult executeRepeatedTargetPlacement(
		AbstractContainerMenu menu,
		Slot sourceSlot,
		Slot targetSlot,
		int moveCount,
		LocalPlayer player,
		MultiPlayerGameMode gameMode
	) {
		pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
		if (player.containerMenu.getCarried().isEmpty()) {
			return WithdrawalMoveResult.failed();
		}

		int placed = 0;
		while (placed < moveCount && !player.containerMenu.getCarried().isEmpty()) {
			pickup(gameMode, player, menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			placed++;
		}

		if (!player.containerMenu.getCarried().isEmpty()) {
			pickup(gameMode, player, menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
		}
		if (!player.containerMenu.getCarried().isEmpty()) {
			return WithdrawalMoveResult.failed();
		}
		return new WithdrawalMoveResult(placed, WithdrawalExecutionMode.REPEATED_TARGET_PLACEMENT);
	}

	static boolean moveGridSlotBackToInventory(AbstractContainerMenu menu, Slot sourceGridSlot, LocalPlayer player, MultiPlayerGameMode gameMode) {
		if (!sourceGridSlot.hasItem() || !sourceGridSlot.mayPickup(player)) {
			return false;
		}

		String itemId = BuiltInRegistries.ITEM.getKey(sourceGridSlot.getItem().getItem()).toString();
		pickup(gameMode, player, menu, sourceGridSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
		if (player.containerMenu.getCarried().isEmpty()) {
			return false;
		}

		while (!player.containerMenu.getCarried().isEmpty()) {
			Slot destinationSlot = findPlayerDestinationSlot(player, menu, itemId);
			if (destinationSlot == null) {
				gameMode.handleContainerInput(menu.containerId, -999, 0, ContainerInput.PICKUP, player);
				return true;
			}
			int carriedBefore = player.containerMenu.getCarried().getCount();
			pickup(gameMode, player, menu, destinationSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			int carriedAfter = player.containerMenu.getCarried().getCount();
			if (carriedAfter >= carriedBefore) {
				pickup(gameMode, player, menu, sourceGridSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				return false;
			}
		}

		return true;
	}

	static Slot findInventoryMenuSlot(AbstractContainerMenu menu, int inventorySlot) {
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory && slot.getContainerSlot() == inventorySlot) {
				return slot;
			}
		}
		return null;
	}

	static void moveItem(AbstractContainerMenu menu, Slot source, Slot target, int count, LocalPlayer player, MultiPlayerGameMode gameMode) {
		ItemStack sourceStack = source.getItem();
		int sourceCount = sourceStack.getCount();
		if (count <= 0) {
			return;
		}

		ItemStack targetStack = target.getItem();
		int maxTargetCount;
		int roomInTarget;
		maxTargetCount = Math.min(target.getMaxStackSize(), sourceStack.getMaxStackSize());
		if (targetStack.isEmpty()) {
			roomInTarget = maxTargetCount;
		} else if (ItemStack.isSameItemSameComponents(targetStack, sourceStack)) {
			roomInTarget = maxTargetCount - targetStack.getCount();
		} else {
			roomInTarget = 0;
		}

		if (count == sourceCount) {
			gameMode.handleContainerInput(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP, player);
			gameMode.handleContainerInput(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP, player);
		} else if (count == (sourceCount + 1) / 2 && roomInTarget >= count) {
			gameMode.handleContainerInput(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ContainerInput.PICKUP, player);
			gameMode.handleContainerInput(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP, player);
		} else if (roomInTarget >= count && (sourceCount - count) > 0 && (sourceCount - count) < count) {
			gameMode.handleContainerInput(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP, player);
			for (int i = 0; i < sourceCount - count; i++) {
				gameMode.handleContainerInput(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ContainerInput.PICKUP, player);
			}
			gameMode.handleContainerInput(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP, player);
			if (!player.containerMenu.getCarried().isEmpty()) {
				gameMode.handleContainerInput(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP, player);
			}
		} else {
			gameMode.handleContainerInput(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP, player);
			for (int i = 0; i < count; i++) {
				gameMode.handleContainerInput(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ContainerInput.PICKUP, player);
			}
			if (!player.containerMenu.getCarried().isEmpty()) {
				gameMode.handleContainerInput(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ContainerInput.PICKUP, player);
			}
		}
	}
}
