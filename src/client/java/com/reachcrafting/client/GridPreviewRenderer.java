package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Draws GridPreview's plan over the crafting grid, styled like vanilla's
 * ghost recipe (washed-out item) but with the batch count and a yellow
 * nearby dot on items that come from containers.
 *
 * <p>Backport: the only GUI-API surface of the queue preview. Pre-1.21.2
 * trees draw with GuiGraphics (renderFakeItem / renderItemDecorations);
 * 26.x with GuiGraphicsExtractor (fakeItem / itemDecorations).</p>
 */
public final class GridPreviewRenderer {
	/** Vanilla's slot interior, drawn first to hide whatever the release will flush. */
	private static final int SLOT_BACKGROUND = 0xFF8B8B8B;
	/** Vanilla GhostSlots' wash over the item. */
	private static final int GHOST_WASH = 0x30FFFFFF;

	private GridPreviewRenderer() {
	}

	/** Called per slot, inside the container's translated pose (slot.x/y are container-local). */
	public static void render(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
		// Only the result and crafting grid slots can carry a ghost; skip the
		// inventory slots before asking for the plan.
		if (!(slot instanceof ResultSlot) && (slot.container instanceof Inventory || slot.index > 9)) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		GridPreview.Plan plan = GridPreview.current(client);
		if (plan == null) {
			return;
		}
		ItemStack ghost;
		boolean fromNearby = false;
		if (slot instanceof ResultSlot) {
			ghost = plan.result();
		} else {
			GridPreview.PreviewSlot previewSlot = plan.slotAt(slot.index);
			if (previewSlot == null) {
				return;
			}
			ghost = previewSlot.stack();
			fromNearby = previewSlot.fromNearby();
		}
		if (ghost.isEmpty()) {
			return;
		}
		int x = slot.x;
		int y = slot.y;
		graphics.fill(x, y, x + 16, y + 16, SLOT_BACKGROUND);
		graphics.fakeItem(ghost, x, y);
		graphics.fill(x, y, x + 16, y + 16, GHOST_WASH);
		graphics.itemDecorations(client.font, ghost, x, y);
		if (fromNearby) {
			RecipeButtonNearbyIndicator.renderDot(graphics, x - 1, y - 1);
		}
	}
}
