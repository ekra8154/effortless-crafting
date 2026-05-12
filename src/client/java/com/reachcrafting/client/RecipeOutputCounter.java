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
		QueuedRecipeCountState countState = resolveQueuedCountState(minecraft);
		int queuedOutputCount = 0;
		String queuedItemId = null;
		boolean hasQueuedState = countState.queuedState();
		if (hasQueuedState && !countState.queuedOutputStack().isEmpty()) {
			queuedItemId = BuiltInRegistries.ITEM.getKey(countState.queuedOutputStack().getItem()).toString();
			queuedOutputCount = countState.displayedCount() * countState.queuedOutputStack().getCount();
		}

		// 3. Resolve Total Count and Logic
		int displayCount = 0;
		boolean includesQueued = false;
		if (!gridResultStack.isEmpty()) {
			displayCount = gridOutputCount;
			
			// If queued item is the same, the queued amount is the intended total.
			if (queuedItemId != null) {
				String gridItemId = BuiltInRegistries.ITEM.getKey(gridResultStack.getItem()).toString();
				if (gridItemId.equals(queuedItemId)) {
					displayCount = queuedOutputCount;
					includesQueued = true;
				}
			}
		} else if (hasQueuedState && queuedOutputCount >= 0) {
			// Grid is empty, show queued output
			displayCount = queuedOutputCount;
			includesQueued = true;
		}

		if ((!includesQueued && displayCount <= 0) || (displayCount == 1 && gridOutputCount == 1 && !includesQueued)) {
			// Don't show "1" if it's just the normal result stack count and no queuing
			if (displayCount == 1 && !gridResultStack.isEmpty() && gridResultStack.getCount() == 1 && !includesQueued) {
				return;
			}
			if (!includesQueued && displayCount <= 0) return;
		}

		// 4. Render
		int color = includesQueued ? 0xFFF2D15B : TEXT_COLOR; // Gold if queued
		renderCount(guiGraphics, minecraft.font, displayCount, slot.x, slot.y, color);
	}

	private static void renderCount(GuiGraphics guiGraphics, Font font, int count, int slotX, int slotY, int color) {
		String text = count > 999 ? (count / 1000) + "k" : String.valueOf(count);
		float scale = 0.8f;
		
		// Position further up and to the right, mostly outside the 16x16 slot
		float x = slotX + 17;
		float y = slotY - 4;

		guiGraphics.pose().pushMatrix();
		guiGraphics.pose().translate(x, y);
		guiGraphics.pose().scale(scale, scale);
		
		// Draw at (0,0) relative to translated/scaled position
		guiGraphics.drawString(font, text, 1, 1, SHADOW_COLOR, false);
		guiGraphics.drawString(font, text, 0, 0, color, false);

		if (count > 64) {
			int textWidth = font.width(text);
			renderBreakdown(guiGraphics, font, count, textWidth + 1, -1, color);
		}
		
		guiGraphics.pose().popMatrix();
	}

	private static void renderBreakdown(GuiGraphics guiGraphics, Font font, int count, int x, int y, int color) {
		int stacks = count / 64;
		int remainder = count % 64;
		String line1 = (stacks == 1 ? "64" : (stacks + "x64"));
		
		guiGraphics.pose().pushMatrix();
		guiGraphics.pose().translate(x, y);
		guiGraphics.pose().scale(0.75f, 0.75f);
		
		if (remainder > 0) {
			String line1WithParen = "(" + line1;
			String line2 = "+" + remainder + ")";
			
			guiGraphics.drawString(font, line1WithParen, 1, 1, SHADOW_COLOR, false);
			guiGraphics.drawString(font, line1WithParen, 0, 0, color, false);
			
			guiGraphics.drawString(font, line2, 1, font.lineHeight + 1, SHADOW_COLOR, false);
			guiGraphics.drawString(font, line2, 0, font.lineHeight, color, false);
		} else {
			String text = "(" + line1 + ")";
			guiGraphics.drawString(font, text, 1, 1, SHADOW_COLOR, false);
			guiGraphics.drawString(font, text, 0, 0, color, false);
		}
		
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

	private static QueuedRecipeCountState resolveQueuedCountState(Minecraft minecraft) {
		var pending = RecipeBookClickCapture.getPendingHeldRecipe();
		if (pending == null) {
			return QueuedRecipeCountState.hidden();
		}
		return QueuedRecipeCountState.visible(
			pending.clickCount(),
			true,
			RecipeBookClickCapture.resolvePendingOutputStack(minecraft)
		);
	}
}
