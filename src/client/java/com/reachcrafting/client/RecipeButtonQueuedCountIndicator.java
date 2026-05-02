package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

public final class RecipeButtonQueuedCountIndicator {
	private static final int BADGE_WIDTH = 9;
	private static final int BADGE_HEIGHT = 6;
	private static final int TEXT_COLOR = 0xFFF2D15B;
	private static final int SHADOW_COLOR = 0xFF000000;
	private static final int BACKGROUND_COLOR = 0x00000000;

	private RecipeButtonQueuedCountIndicator() {
	}

	public static void render(GuiGraphics guiGraphics, RecipeButton button) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		int queuedCount = RecipeBookClickCapture.getHeldQueuedCount(button);
		if (queuedCount < 1) {
			return;
		}

		AbstractWidget widget = (AbstractWidget) (Object) button;
		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		String text = Integer.toString(Math.min(queuedCount, 64));

		int badgeX = widget.getX() + widget.getWidth() - BADGE_WIDTH - 2;
		int badgeY = widget.getY() + 2;
		int textWidth = font.width(text);
		int textX = badgeX + (BADGE_WIDTH - textWidth) / 2;
		int textY = badgeY;

		// Transparent placeholder area so we have a stable badge region to skin later.
		guiGraphics.fill(badgeX, badgeY, badgeX + BADGE_WIDTH, badgeY + BADGE_HEIGHT, BACKGROUND_COLOR);

		guiGraphics.drawString(font, text, textX + 1, textY + 1, SHADOW_COLOR, false);
		guiGraphics.drawString(font, text, textX, textY, TEXT_COLOR, false);
	}

	public static void renderOverlayButton(
		GuiGraphics guiGraphics,
		int x,
		int y,
		int width,
		RecipeDisplayId recipeId,
		RecipeCollection collection
	) {
		if (!ReachCraftingConfig.get().enabled()) {
			return;
		}
		int queuedCount = RecipeBookClickCapture.getHeldQueuedCount(recipeId, collection, true);
		if (queuedCount < 1) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		String text = Integer.toString(Math.min(queuedCount, 64));

		int badgeX = x + width - BADGE_WIDTH - 2;
		int badgeY = y + 2;
		int textWidth = font.width(text);
		int textX = badgeX + (BADGE_WIDTH - textWidth) / 2;
		int textY = badgeY;

		guiGraphics.fill(badgeX, badgeY, badgeX + BADGE_WIDTH, badgeY + BADGE_HEIGHT, BACKGROUND_COLOR);
		guiGraphics.drawString(font, text, textX + 1, textY + 1, SHADOW_COLOR, false);
		guiGraphics.drawString(font, text, textX, textY, TEXT_COLOR, false);
	}
}
