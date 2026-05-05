package com.reachcrafting.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

final class MenuTransferHelper {
	private MenuTransferHelper() {
	}

	static void pickup(MultiPlayerGameMode gameMode, LocalPlayer player, AbstractContainerMenu menu, Slot slot, int mouseButton) {
		gameMode.handleInventoryMouseClick(menu.containerId, slot.index, mouseButton, ClickType.PICKUP, player);
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
				gameMode.handleInventoryMouseClick(menu.containerId, -999, 0, ClickType.PICKUP, player);
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
			gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
			gameMode.handleInventoryMouseClick(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
		} else if (count == (sourceCount + 1) / 2 && roomInTarget >= count) {
			gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
			gameMode.handleInventoryMouseClick(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
		} else if (roomInTarget >= count && (sourceCount - count) > 0 && (sourceCount - count) < count) {
			gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
			for (int i = 0; i < sourceCount - count; i++) {
				gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
			}
			gameMode.handleInventoryMouseClick(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
			if (!player.containerMenu.getCarried().isEmpty()) {
				gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
			}
		} else {
			gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
			for (int i = 0; i < count; i++) {
				gameMode.handleInventoryMouseClick(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
			}
			if (!player.containerMenu.getCarried().isEmpty()) {
				gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
			}
		}
	}
}
