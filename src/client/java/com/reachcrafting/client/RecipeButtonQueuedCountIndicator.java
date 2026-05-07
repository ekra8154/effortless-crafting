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
		QueuedRecipeCountState countState = RecipeBookClickCapture.getQueuedCountState(button);
		if (!countState.visible()) {
			return;
		}

		AbstractWidget widget = (AbstractWidget) (Object) button;
		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		String text = Integer.toString(Math.max(countState.displayedCount(), 0));

		int badgeX = widget.getX() + widget.getWidth() - BADGE_WIDTH + 1;
		int badgeY = widget.getY() + 2;
		int textWidth = font.width(text);
		int textX = badgeX + (BADGE_WIDTH - textWidth) / 2;
		int textY = badgeY;

		// Transparent placeholder area so we have a stable badge region to skin later.
		guiGraphics.fill(badgeX, badgeY, badgeX + BADGE_WIDTH, badgeY + BADGE_HEIGHT, BACKGROUND_COLOR);

		guiGraphics.drawString(font, text, textX + 1, textY + 1, SHADOW_COLOR, false);
		guiGraphics.drawString(font, text, textX, textY, TEXT_COLOR, false);

		if (countState.displayedCount() > 64) {
			renderBreakdown(guiGraphics, font, countState.displayedCount(), textX + textWidth + 1, textY - 1, TEXT_COLOR);
		}
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
		QueuedRecipeCountState countState = RecipeBookClickCapture.getQueuedCountState(recipeId, collection, true);
		if (!countState.visible()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		String text = Integer.toString(Math.max(countState.displayedCount(), 0));

		int badgeX = x + width - BADGE_WIDTH + 1;
		int badgeY = y + 2;
		int textWidth = font.width(text);
		int textX = badgeX + (BADGE_WIDTH - textWidth) / 2;
		int textY = badgeY;

		guiGraphics.fill(badgeX, badgeY, badgeX + BADGE_WIDTH, badgeY + BADGE_HEIGHT, BACKGROUND_COLOR);
		guiGraphics.drawString(font, text, textX + 1, textY + 1, SHADOW_COLOR, false);
		guiGraphics.drawString(font, text, textX, textY, TEXT_COLOR, false);

		if (countState.displayedCount() > 64) {
			renderBreakdown(guiGraphics, font, countState.displayedCount(), textX + textWidth + 1, textY - 1, TEXT_COLOR);
		}
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
			String line2 = " +" + remainder + ")";
			
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
}
