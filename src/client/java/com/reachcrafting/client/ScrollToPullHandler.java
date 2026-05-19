package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public final class ScrollToPullHandler {
    private ScrollToPullHandler() {
    }

    public static boolean handleScroll(Screen screen, double mouseX, double mouseY, double amount) {
        ReachCraftingConfig.ScrollToPullMode mode = ReachCraftingConfig.get().scrollToPullMode();
        double accumulated = OffhandConsolidationController.getAccumulatedScroll();
        if (!ReachCraftingConfig.get().enabled() || mode == ReachCraftingConfig.ScrollToPullMode.NONE || (amount == 0.0D && accumulated == 0.0D)) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) {
            return false;
        }

        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }

        // Must be holding Shift
        if (!isShiftKeyDown(minecraft)) {
            return false;
        }

        AbstractContainerMenu menu = containerScreen.getMenu();
        Slot hoveredSlot = ((AbstractContainerScreenAccessor) containerScreen).getHoveredSlot();
        Slot resultSlot = findResultSlot(menu);
        if (!(resultSlot instanceof ResultSlot) || !resultSlot.hasItem() || !resultSlot.mayPickup(minecraft.player)) {
            return false;
        }

        boolean up = amount > 0.0D;
        if (mode == ReachCraftingConfig.ScrollToPullMode.WHILE_RESULT_SLOT_HOVERED && hoveredSlot != resultSlot) {
            return false;
        }

        boolean isSpaceHeld = isSpaceKeyDown(minecraft);
        int multiplier = isSpaceHeld ? 16 : 1;

        // Use a virtual view of the cursor and inventory to handle multiple packets in one frame
        ItemStack virtualCarried = minecraft.player.containerMenu.getCarried().copy();
        java.util.Map<Integer, Integer> virtualSlotCounts = new java.util.HashMap<>();
        
        // Cache the result item for virtual updates since the slot may appear empty after the first click
        ItemStack resultStack = resultSlot.getItem().copy();
        
        // Handle offhand swap accumulation and delay - ONLY if hovering the result slot and scrolling down
        if (hoveredSlot == resultSlot) {
            if (amount < 0.0D && OffhandConsolidationController.prepareSwapIfNeeded(minecraft, resultStack, 9)) {
                OffhandConsolidationController.addScrollAmount(amount);
                return true;
            }
            
            if (OffhandConsolidationController.isWarmupDelayActive()) {
                OffhandConsolidationController.addScrollAmount(amount);
                return true;
            }
        }
        
        double currentAmount = amount + OffhandConsolidationController.consumeAccumulatedScroll();
        if (currentAmount == 0.0D) {
            return false;
        }

        up = currentAmount > 0.0D;
        int scrollMultiplier = (int) Math.abs(currentAmount);
        int totalAttempts = multiplier * scrollMultiplier;
        int countToMove = resultStack.getCount();

        int craftsAttempted = 0;
        for (int i = 0; i < totalAttempts; i++) {
            if (up) {
                // Wheel Up: To Cursor (stops at stack size)
                if (virtualCarried.isEmpty() || (ItemStack.isSameItemSameTags(virtualCarried, resultStack) && virtualCarried.getCount() < resultStack.getMaxStackSize())) {
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 0, ClickType.PICKUP, minecraft.player);
                    
                    // Update virtual carried
                    if (virtualCarried.isEmpty()) {
                        virtualCarried = resultStack.copy();
                        virtualCarried.setCount(countToMove);
                    } else {
                        virtualCarried.setCount(Math.min(virtualCarried.getMaxStackSize(), virtualCarried.getCount() + countToMove));
                    }
                    craftsAttempted++;
                } else {
                    break;
                }
            } else {
                // Wheel Down: To Inventory (Granular pull-then-place)
                int targetSlotIndex = resolveWheelDownTargetSlot(mode, menu, hoveredSlot, resultStack, virtualSlotCounts);
                
                if (targetSlotIndex != -1) {
                    int virtualCountAfter = virtualSlotCounts.getOrDefault(targetSlotIndex, (menu.getSlot(targetSlotIndex).hasItem() ? menu.getSlot(targetSlotIndex).getItem().getCount() : 0)) + countToMove;
                    ReachCraftingMod.LOGGER.debug("ScrollToPull: Granular craft {}/{} -> Slot {}, New Virtual Count: {}", i + 1, totalAttempts, targetSlotIndex, virtualCountAfter);

                    // 2. Click Output
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 0, ClickType.PICKUP, minecraft.player);
                    // 3. Click Target
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, targetSlotIndex, 0, ClickType.PICKUP, minecraft.player);
                    
                    // 4. Update virtual state
                    virtualSlotCounts.put(targetSlotIndex, virtualCountAfter);
                    
                    craftsAttempted++;
                } else if (hoveredSlot == resultSlot && ReachCraftingConfig.get().ejectItemsWhenFull()) {
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 0, ClickType.THROW, minecraft.player);
                    craftsAttempted++;
                } else {
                    break;
                }
            }
        }

        if (craftsAttempted == 0) {
            OffhandConsolidationController.swapBack(minecraft);
        }

        return craftsAttempted > 0;
    }

    private static int resolveWheelDownTargetSlot(
        ReachCraftingConfig.ScrollToPullMode mode,
        AbstractContainerMenu menu,
        Slot hoveredSlot,
        ItemStack resultStack,
        java.util.Map<Integer, Integer> virtualSlotCounts
    ) {
        if (mode == ReachCraftingConfig.ScrollToPullMode.WHILE_RESULT_SLOT_HOVERED) {
            return hoveredSlot instanceof ResultSlot ? findBestSlotForWholeCraft(menu, resultStack, virtualSlotCounts) : -1;
        }
        if (hoveredSlot instanceof ResultSlot) {
            return findBestSlotForWholeCraft(menu, resultStack, virtualSlotCounts);
        }
        if (!isAppendTargetSlot(menu, hoveredSlot)) {
            return -1;
        }
        return canAppendWholeCraft(hoveredSlot, resultStack, virtualSlotCounts) ? hoveredSlot.index : -1;
    }

    private static int findBestSlotForWholeCraft(AbstractContainerMenu menu, ItemStack result, java.util.Map<Integer, Integer> virtualSlotCounts) {
        int craftCount = result.getCount();
        int maxStack = result.getMaxStackSize();
        
        // Priority 1: Stacking in slots we already touched in this burst (Keep filling the same slot)
        for (Integer slotIndex : virtualSlotCounts.keySet()) {
            Slot s = menu.getSlot(slotIndex);
            // Must be an inventory slot
            if (!(s.container instanceof net.minecraft.world.entity.player.Inventory)) continue;

            int virtualCount = virtualSlotCounts.get(slotIndex);
            if (virtualCount + craftCount <= maxStack) {
                // If it has a real item, must match. 
                if (!s.hasItem() || ItemStack.isSameItemSameTags(s.getItem(), result)) {
                    return s.index;
                }
            }
        }

        // Priority 2: Stacking in existing stacks in Hotbar
        for (int i = 0; i < 9; i++) {
            Slot s = findInventorySlot(menu, i);
            if (s != null && s.hasItem() && !virtualSlotCounts.containsKey(s.index) && ItemStack.isSameItemSameTags(s.getItem(), result)) {
                if (s.getItem().getCount() + craftCount <= maxStack) {
                    return s.index;
                }
            }
        }
        
        // Priority 3: Stacking in existing stacks in Main
        for (int i = 9; i < 36; i++) {
            Slot s = findInventorySlot(menu, i);
            if (s != null && s.hasItem() && !virtualSlotCounts.containsKey(s.index) && ItemStack.isSameItemSameTags(s.getItem(), result)) {
                if (s.getItem().getCount() + craftCount <= maxStack) {
                    return s.index;
                }
            }
        }

        // Priority 3.5: Refill matching offhand stack, but never use an empty offhand slot
        Slot offhandSlot = findVisibleOffhandSlot(menu);
        if (offhandSlot != null
            && offhandSlot.hasItem()
            && !virtualSlotCounts.containsKey(offhandSlot.index)
            && ItemStack.isSameItemSameTags(offhandSlot.getItem(), result)
            && offhandSlot.getItem().getCount() + craftCount <= maxStack) {
            return offhandSlot.index;
        }

        // Priority 3.6: Refill matching swapped-in offhand stack (3x3)
        int swappedSlotIndex = OffhandConsolidationController.getSwapSlotIndex(menu);
        if (swappedSlotIndex != -1) {
            Slot s = menu.getSlot(swappedSlotIndex);
            if (s.hasItem() && ItemStack.isSameItemSameTags(s.getItem(), result) && s.getItem().getCount() + craftCount <= maxStack) {
                return s.index;
            }
        }

        // Priority 4: Empty Hotbar
        for (int i = 0; i < 9; i++) {
            Slot s = findInventorySlot(menu, i);
            if (s != null && !s.hasItem() && !virtualSlotCounts.containsKey(s.index)) {
                return s.index;
            }
        }

        // Priority 5: Empty Main
        for (int i = 9; i < 36; i++) {
            Slot s = findInventorySlot(menu, i);
            if (s != null && !s.hasItem() && !virtualSlotCounts.containsKey(s.index)) {
                return s.index;
            }
        }
        return -1;
    }


    private static Slot findInventorySlot(AbstractContainerMenu menu, int inventoryIndex) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory && slot.getContainerSlot() == inventoryIndex) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findVisibleOffhandSlot(AbstractContainerMenu menu) {
        if (!ReachCraftingConfig.get().inventory2x2OffhandConsolidation()
            || !(menu instanceof InventoryMenu)
            || menu.slots.size() <= InventoryMenu.SHIELD_SLOT) {
            return null;
        }
        return menu.getSlot(InventoryMenu.SHIELD_SLOT);
    }

    private static Slot findResultSlot(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot instanceof ResultSlot) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isAppendTargetSlot(AbstractContainerMenu menu, Slot slot) {
        if (slot == null) {
            return false;
        }
        if (slot.index == InventoryMenu.SHIELD_SLOT
            && ReachCraftingConfig.get().inventory2x2OffhandConsolidation()
            && menu instanceof InventoryMenu
            && menu.slots.size() > InventoryMenu.SHIELD_SLOT) {
            return true;
        }
        if (slot.container instanceof Inventory) {
            int containerSlot = slot.getContainerSlot();
            return containerSlot >= 0 && containerSlot < 36;
        }
        return false;
    }

    private static boolean canAppendWholeCraft(Slot slot, ItemStack result, java.util.Map<Integer, Integer> virtualSlotCounts) {
        int currentCount = virtualSlotCounts.getOrDefault(slot.index, slot.hasItem() ? slot.getItem().getCount() : 0);
        if (slot.hasItem() && !ItemStack.isSameItemSameTags(slot.getItem(), result)) {
            return false;
        }
        return currentCount + result.getCount() <= result.getMaxStackSize();
    }

    public static boolean isHoveringOutput(Minecraft minecraft) {
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }
        Slot hoveredSlot = ((AbstractContainerScreenAccessor) containerScreen).getHoveredSlot();
        return hoveredSlot instanceof ResultSlot;
    }

    private static boolean isShiftKeyDown(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static boolean isSpaceKeyDown(Minecraft minecraft) {
        return InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE);
    }
}

