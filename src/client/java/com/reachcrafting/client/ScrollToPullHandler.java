package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public final class ScrollToPullHandler {
    private ScrollToPullHandler() {
    }

    public static boolean handleScroll(Screen screen, double mouseX, double mouseY, double amount) {
        if (!ReachCraftingConfig.get().scrollToPull()) {
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

        Slot hoveredSlot = ((AbstractContainerScreenAccessor) containerScreen).getHoveredSlot();
        if (!(hoveredSlot instanceof ResultSlot)) {
            return false;
        }

        if (!hoveredSlot.hasItem() || !hoveredSlot.mayPickup(minecraft.player)) {
            return false;
        }

        boolean isSpaceHeld = isSpaceKeyDown(minecraft);
        int multiplier = isSpaceHeld ? 16 : 1;
        boolean up = amount > 0;

        // Use a virtual view of the cursor and inventory to handle multiple packets in one frame
        ItemStack virtualCarried = minecraft.player.containerMenu.getCarried().copy();
        AbstractContainerMenu menu = containerScreen.getMenu();
        java.util.Map<Integer, Integer> virtualSlotCounts = new java.util.HashMap<>();
        
        // Cache the result item for virtual updates since the slot may appear empty after the first click
        ItemStack resultStack = hoveredSlot.getItem().copy();
        int countToMove = resultStack.getCount();

        int craftsAttempted = 0;
        for (int i = 0; i < multiplier; i++) {
            if (up) {
                // Wheel Up: To Cursor (stops at stack size)
                if (virtualCarried.isEmpty() || (ItemStack.isSameItemSameComponents(virtualCarried, resultStack) && virtualCarried.getCount() < resultStack.getMaxStackSize())) {
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, hoveredSlot.index, 0, ClickType.PICKUP, minecraft.player);
                    
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
                // 1. Find a slot that can take the WHOLE craft (Priority: Stacking -> Empty)
                int targetSlotIndex = findBestSlotForWholeCraft(menu, resultStack, virtualSlotCounts);
                
                if (targetSlotIndex != -1) {
                    int virtualCountAfter = virtualSlotCounts.getOrDefault(targetSlotIndex, (menu.getSlot(targetSlotIndex).hasItem() ? menu.getSlot(targetSlotIndex).getItem().getCount() : 0)) + countToMove;
                    ReachCraftingMod.LOGGER.info("ScrollToPull: Granular craft {}/{} -> Slot {}, New Virtual Count: {}", i + 1, multiplier, targetSlotIndex, virtualCountAfter);

                    // 2. Click Output
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, hoveredSlot.index, 0, ClickType.PICKUP, minecraft.player);
                    // 3. Click Target
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, targetSlotIndex, 0, ClickType.PICKUP, minecraft.player);
                    
                    // 4. Update virtual state
                    virtualSlotCounts.put(targetSlotIndex, virtualCountAfter);
                    
                    craftsAttempted++;
                } else {
                    break;
                }
            }
        }

        return craftsAttempted > 0;
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
                if (!s.hasItem() || ItemStack.isSameItemSameComponents(s.getItem(), result)) {
                    return s.index;
                }
            }
        }

        // Priority 2: Stacking in existing stacks in Hotbar
        for (int i = 0; i < 9; i++) {
            Slot s = findInventorySlot(menu, i);
            if (s != null && s.hasItem() && !virtualSlotCounts.containsKey(s.index) && ItemStack.isSameItemSameComponents(s.getItem(), result)) {
                if (s.getItem().getCount() + craftCount <= maxStack) {
                    return s.index;
                }
            }
        }
        
        // Priority 3: Stacking in existing stacks in Main
        for (int i = 9; i < 36; i++) {
            Slot s = findInventorySlot(menu, i);
            if (s != null && s.hasItem() && !virtualSlotCounts.containsKey(s.index) && ItemStack.isSameItemSameComponents(s.getItem(), result)) {
                if (s.getItem().getCount() + craftCount <= maxStack) {
                    return s.index;
                }
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
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.getContainerSlot() == inventoryIndex) {
                return slot;
            }
        }
        return null;
    }

    public static boolean isHoveringOutput(Minecraft minecraft) {
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return false;
        }
        Slot hoveredSlot = ((AbstractContainerScreenAccessor) containerScreen).getHoveredSlot();
        return hoveredSlot instanceof ResultSlot;
    }

    private static boolean isShiftKeyDown(Minecraft minecraft) {
        return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static boolean isSpaceKeyDown(Minecraft minecraft) {
        return InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_SPACE);
    }
}
