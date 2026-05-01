package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

public final class RecipeOutputCounter {
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int SHADOW_COLOR = 0xFF000000;

	private RecipeOutputCounter() {
	}

	public static void render(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen, Slot slot) {
		if (!ReachCraftingConfig.get().showTotalOutputCounts()) {
			return;
		}

		if (!(slot instanceof ResultSlot)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}

		// 1. Calculate Grid Output
		List<ItemStack> gridStacks = getGridStacks(screen);
		int gridCrafts = ContainerUtils.currentReservedCraftCopies(gridStacks);
		int gridOutputCount = 0;
		ItemStack gridResultStack = slot.getItem();
		
		if (!gridResultStack.isEmpty()) {
			gridOutputCount = gridCrafts * gridResultStack.getCount();
		}

		// 2. Calculate Queued Output
		int queuedOutputCount = 0;
		String queuedItemId = null;
		
		var pending = RecipeBookClickCapture.getPendingHeldRecipe();
		if (pending != null) {
			int queuedClicks = pending.clickCount();
			if (queuedClicks > 0) {
				ItemStack queuedResultStack = resolveQueuedOutputStack(minecraft, pending);
				if (!queuedResultStack.isEmpty()) {
					queuedOutputCount = queuedClicks * queuedResultStack.getCount();
					queuedItemId = BuiltInRegistries.ITEM.getKey(queuedResultStack.getItem()).toString();
				}
			}
		}

		// 3. Resolve Total Count and Logic
		int displayCount = 0;
		boolean includesQueued = false;
		if (!gridResultStack.isEmpty()) {
			displayCount = gridOutputCount;
			
			// If queued item is the same, add it
			if (queuedItemId != null) {
				String gridItemId = BuiltInRegistries.ITEM.getKey(gridResultStack.getItem()).toString();
				if (gridItemId.equals(queuedItemId)) {
					displayCount += queuedOutputCount;
					includesQueued = true;
				}
			}
		} else if (queuedOutputCount > 0) {
			// Grid is empty, show queued output
			displayCount = queuedOutputCount;
			includesQueued = true;
		}

		if (displayCount <= 0 || (displayCount == 1 && gridOutputCount == 1 && !includesQueued)) {
			// Don't show "1" if it's just the normal result stack count and no queuing
			if (displayCount == 1 && !gridResultStack.isEmpty() && gridResultStack.getCount() == 1 && !includesQueued) {
				return;
			}
			if (displayCount <= 0) return;
		}

		// 4. Render
		int color = includesQueued ? 0xFFF2D15B : TEXT_COLOR; // Gold if queued
		renderCount(guiGraphics, minecraft.font, displayCount, slot.x, slot.y, color);
	}

	private static void renderCount(GuiGraphics guiGraphics, Font font, int count, int slotX, int slotY, int color) {
		String text = count > 999 ? (count / 1000) + "k" : String.valueOf(count);
		float scale = 0.8f;
		
		// Position further up and to the right, mostly outside the 16x16 slot
		float x = slotX + 14;
		float y = slotY - 4;

		guiGraphics.pose().pushMatrix();
		guiGraphics.pose().translate(x, y);
		guiGraphics.pose().scale(scale, scale);
		
		// Draw at (0,0) relative to translated/scaled position
		guiGraphics.drawString(font, text, 1, 1, SHADOW_COLOR, false);
		guiGraphics.drawString(font, text, 0, 0, color, false);
		
		guiGraphics.pose().popMatrix();
	}

	private static List<ItemStack> getGridStacks(AbstractContainerScreen<?> screen) {
		List<ItemStack> stacks = new ArrayList<>();
		// Slot 0 is result, slots 1-9 are usually the grid in CraftingScreen
		// In InventoryScreen, slots 1-4 are the grid.
		int gridStart = 1;
		int gridSize = (screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen) ? 9 : 4;
		
		for (int i = 0; i < gridSize; i++) {
			stacks.add(screen.getMenu().getSlot(gridStart + i).getItem());
		}
		return stacks;
	}

	private static ItemStack resolveQueuedOutputStack(Minecraft minecraft, RecipeBookClickCapture.PendingHeldRecipe pending) {
		return RecipeBookClickCapture.resolvePendingOutputStack(minecraft);
	}
}
