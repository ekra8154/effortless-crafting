package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Draws GridPreview's plan in the crafting grid, styled like vanilla's ghost
 * recipe (washed-out item) but with the batch count, a yellow dot on items
 * pulled from containers and an orange one on items a chain crafts.
 *
 * <p>It replaces the grid and result slots' own item drawing rather than
 * painting over it: the release flushes the grid, so whatever is staged there
 * now must not show, and covering it with a slot-colored square leaked the
 * staged counts' text shadows and clashed with resource packs that retexture
 * the slots. Skipping the item draw leaves the GUI texture's own slot.</p>
 *
 * <p>Backport: the only GUI-API surface of the queue preview. 1.21.x trees
 * draw with GuiGraphics (renderFakeItem / drawString) and hook renderSlot;
 * 26.x uses GuiGraphicsExtractor (fakeItem / text) and extractSlot. 1.21.5
 * and older have a 3D GUI pose, so the overlay needs a z lift.</p>
 */
public final class GridPreviewRenderer {
	/**
	 * Fades the item halfway into vanilla's slot gray, so a preview reads as
	 * "not placed yet" next to real stacks. (Vanilla GhostSlots' 0x30FFFFFF
	 * looked too much like staged items.) Translucent, so a resource pack's
	 * slot still shows through.
	 */
	private static final int GHOST_WASH = 0x608B8B8B;
	/** Preview counts: muted, unlike a real stack's white count. */
	private static final int GHOST_COUNT_COLOR = 0xFFC0C0C0;

	private GridPreviewRenderer() {
	}

	/**
	 * Draws the preview for a grid or result slot in place of its contents.
	 * Called at the head of the slot draw, inside the container's translated
	 * pose (slot.x/y are container-local).
	 *
	 * @return true when the preview owns this slot and vanilla must skip
	 *     drawing its real item
	 */
	public static boolean renderInPlaceOfSlot(GuiGraphics graphics, AbstractContainerScreen<?> screen, Slot slot) {
		boolean resultSlot = slot instanceof ResultSlot;
		if (!resultSlot && !(slot.container instanceof CraftingContainer)) {
			return false;
		}
		Minecraft client = Minecraft.getInstance();
		GridPreview.Plan plan = GridPreview.current(client);
		if (plan == null) {
			return false;
		}
		ItemStack ghost;
		GridPreview.Source source = GridPreview.Source.INVENTORY;
		if (resultSlot) {
			ghost = plan.result();
			if (plan.retrievedItems() > 0) {
				// Some of the result comes out of containers, not the craft.
				source = GridPreview.Source.NEARBY;
			}
		} else {
			GridPreview.PreviewSlot previewSlot = plan.slotAt(slot.index);
			// A grid cell outside the new pattern will be empty after the
			// release's flush: draw nothing.
			ghost = previewSlot == null ? ItemStack.EMPTY : previewSlot.stack();
			source = previewSlot == null ? GridPreview.Source.INVENTORY : previewSlot.source();
		}
		if (ghost.isEmpty()) {
			return true;
		}
		int x = slot.x;
		int y = slot.y;
		graphics.renderFakeItem(ghost, x, y);
		// The GUI is 3D here and renderItem draws at z 150: lift the wash,
		// count and dots above it (vanilla draws decorations at 200).
		graphics.pose().pushPose();
		graphics.pose().translate(0.0F, 0.0F, 200.0F);
		graphics.fill(x, y, x + 16, y + 16, GHOST_WASH);
		if (ghost.getCount() > 1) {
			// Vanilla's count placement (ItemStack decorations), muted.
			String count = String.valueOf(ghost.getCount());
			graphics.drawString(client.font, count, x + 17 - client.font.width(count), y + 9, GHOST_COUNT_COLOR, true);
		}
		// Same colors as the recipe book's indicators: yellow for nearby
		// containers, orange for what the chain crafts on the way.
		if (source == GridPreview.Source.NEARBY) {
			RecipeButtonNearbyIndicator.renderDot(graphics, x - 1, y - 1);
		} else if (source == GridPreview.Source.CHAIN) {
			RecipeButtonNearbyIndicator.renderChainDot(graphics, x - 1, y - 1);
		}
		graphics.pose().popPose();
		return true;
	}
}
