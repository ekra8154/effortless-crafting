package com.reachcrafting.client;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Manages the "offhand swap" state for 3x3 crafting where the offhand slot
 * is not natively accessible in the GUI.
 */
public final class OffhandConsolidationController {
	private static int swapInventoryIndex = -1; // 0-35
	private static int warmupDelay = 0;
	private static int idleTimeout = 0;
	private static boolean isSwapped = false;
	private static ItemStack swappedItem = ItemStack.EMPTY;
	private static double accumulatedScroll = 0.0D;

	private OffhandConsolidationController() {
	}

	public static void tick(Minecraft client) {
		if (warmupDelay > 0) {
			warmupDelay--;
			if (warmupDelay == 0 && accumulatedScroll != 0.0D) {
				flushAccumulatedScroll(client);
			}
		}

		if (isSwapped) {
			if (idleTimeout > 0) {
				idleTimeout--;
			}

			// Immediate swap back if the slot becomes full
			if (client.player != null && client.player.containerMenu != null) {
				Slot s = findInventorySlot(client.player.containerMenu, swapInventoryIndex);
				if (s != null) {
					if (s.hasItem()) {
						if (s.getItem().getCount() >= s.getItem().getMaxStackSize()) {
							// Stack is full, swap it back now
							swapBack(client);
							return;
						}
						// If item in slot changed, we only swap back if NOT in a bulk session.
						// During bulk, byproducts might briefly touch this slot or server sync might be weird.
						if (!AutoMoveController.isAutomatedInteractionRunning() && !ItemStack.isSameItemSameTags(s.getItem(), swappedItem)) {
							swapBack(client);
							return;
						}
					}
				} else {
					// Slot not found (menu changed?), force reset
					isSwapped = false;
					swapInventoryIndex = -1;
				}
			}

			// Idle timeout swap back (only if no automated interaction is currently running)
			if (idleTimeout == 0 && !AutoMoveController.isAutomatedInteractionRunning() && warmupDelay == 0) {
				swapBack(client);
			}
		}
	}

	public static boolean isWarmupDelayActive() {
		return warmupDelay > 0;
	}

	public static boolean isSwapped() {
		return isSwapped;
	}

	public static int getSwapSlotIndex(AbstractContainerMenu menu) {
		if (!isSwapped || swapInventoryIndex == -1) return -1;
		Slot s = findInventorySlot(menu, swapInventoryIndex);
		return s != null ? s.index : -1;
	}

	public static void addScrollAmount(double amount) {
		accumulatedScroll += amount;
	}

	public static double getAccumulatedScroll() {
		return accumulatedScroll;
	}

	public static double consumeAccumulatedScroll() {
		double amt = accumulatedScroll;
		accumulatedScroll = 0.0D;
		return amt;
	}

	private static void flushAccumulatedScroll(Minecraft client) {
		if (client.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen) {
			double mouseX = client.mouseHandler.xpos() * (double)client.getWindow().getGuiScaledWidth() / (double)client.getWindow().getScreenWidth();
			double mouseY = client.mouseHandler.ypos() * (double)client.getWindow().getGuiScaledHeight() / (double)client.getWindow().getScreenHeight();
			ScrollToPullHandler.handleScroll(containerScreen, mouseX, mouseY, 0.0D);
		}
	}

	public static boolean prepareSwapIfNeeded(Minecraft client, ItemStack resultStack, int slotsNeeded) {
		if (!ReachCraftingConfig.get().enabled() || !ReachCraftingConfig.get().inventory2x2OffhandConsolidation()) {
			return false;
		}

		if (client.player == null || client.player.containerMenu == null) {
			return false;
		}

		AbstractContainerMenu menu = client.player.containerMenu;
		
		// Only apply to 3x3 (non-InventoryMenu screens)
		if (menu instanceof InventoryMenu) {
			return false;
		}

		ItemStack offhand = client.player.getOffhandItem();
		if (offhand.isEmpty() || !ItemStack.isSameItemSameTags(offhand, resultStack)) {
			return false;
		}

		if (offhand.getCount() >= offhand.getMaxStackSize()) {
			return false;
		}

		// Already swapped? Just reset timeout
		if (isSwapped) {
			idleTimeout = 10;
			return false;
		}

		// Count empty slots first
		int emptyCount = 0;
		for (int i = 0; i < 36; i++) {
			Slot s = findInventorySlot(menu, i);
			if (s != null && !s.hasItem()) {
				emptyCount++;
			}
		}

		Set<String> acceptedItemIds = BulkAutoCraftController.getAcceptedItemIds();

		// Find a slot to swap into (0-35)
		int targetInvIndex = -1;
		int safeOccupiedFallback = -1;
		
		// If space is available, preserve empty slots first.
		if (emptyCount > slotsNeeded) {
			// Priority 1: Search Main Inventory (9-35)
			for (int i = 9; i < 36; i++) {
				Slot s = findInventorySlot(menu, i);
				if (s != null && !s.hasItem()) {
					targetInvIndex = i;
					break;
				}
			}

			// Priority 2: Search Hotbar (0-8)
			if (targetInvIndex == -1) {
				for (int i = 0; i < 9; i++) {
					Slot s = findInventorySlot(menu, i);
					if (s != null && !s.hasItem()) {
						targetInvIndex = i;
						break;
					}
				}
			}
		}

		// If we couldn't use an empty slot, prefer a non-ingredient occupied slot.
		if (targetInvIndex == -1) {
			for (int i = 9; i < 36; i++) {
				Slot s = findInventorySlot(menu, i);
				if (isSafeOccupiedSwapTarget(s, acceptedItemIds)) {
					safeOccupiedFallback = i;
					break;
				}
			}

			if (safeOccupiedFallback == -1) {
				for (int i = 0; i < 9; i++) {
					Slot s = findInventorySlot(menu, i);
					if (isSafeOccupiedSwapTarget(s, acceptedItemIds)) {
						safeOccupiedFallback = i;
						break;
					}
				}
			}
		}

		if (safeOccupiedFallback != -1) {
			targetInvIndex = safeOccupiedFallback;
		}

		// Final fallback: Slot 9, even if it holds a needed ingredient.
		if (targetInvIndex == -1) {
			targetInvIndex = 9;
		}

		Slot targetSlot = findInventorySlot(menu, targetInvIndex);
		if (targetSlot == null) {
			return false;
		}

		// Perform swap via ClickType.SWAP with button 40 (offhand)
		client.gameMode.handleInventoryMouseClick(menu.containerId, targetSlot.index, 40, ClickType.SWAP, client.player);
		
		swapInventoryIndex = targetInvIndex;
		isSwapped = true;
		swappedItem = offhand.copy();
		warmupDelay = 1;
		idleTimeout = 10;
		
		return true;
	}

	public static void resetIdleTimeout() {
		if (isSwapped) {
			idleTimeout = 10;
		}
	}

	public static void swapBack(Minecraft client) {
		if (!isSwapped) return;
		
		if (client.player != null \u0026\u0026 client.player.containerMenu != null) {
			AbstractContainerMenu menu = client.player.containerMenu;
			Slot targetSlot = findInventorySlot(menu, swapInventoryIndex);
			if (targetSlot != null) {
				// Swap back
				client.gameMode.handleInventoryMouseClick(menu.containerId, targetSlot.index, 40, ClickType.SWAP, client.player);
			}
		}
		
		isSwapped = false;
		swapInventoryIndex = -1;
		swappedItem = ItemStack.EMPTY;
		warmupDelay = 0;
		idleTimeout = 0;
	}

	private static Slot findInventorySlot(AbstractContainerMenu menu, int inventoryIndex) {
		for (Slot slot : menu.slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory \u0026\u0026 slot.getContainerSlot() == inventoryIndex) {
				return slot;
			}
		}
		return null;
	}

	private static boolean isSafeOccupiedSwapTarget(Slot slot, Set<String> acceptedItemIds) {
		if (slot == null || !slot.hasItem()) {
			return false;
		}
		if (acceptedItemIds == null || acceptedItemIds.isEmpty()) {
			return true;
		}
		String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
		return !acceptedItemIds.contains(itemId);
	}
}

