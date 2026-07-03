package com.reachcrafting.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import org.lwjgl.glfw.GLFW;

/**
 * Scroll-wheel recipe-book page navigation for the inventory / crafting-table screens.
 *
 * <p>1.20.1 does not override {@code mouseScrolled} on {@code Screen} / {@code AbstractContainerScreen}
 * (it inherits the {@code ContainerEventHandler} default), so a Mixin {@code @Inject} has no target.
 * We use Fabric's {@link ScreenMouseEvents#allowMouseScroll} instead, which normalizes the 1.20.1
 * single-axis scroll into (horizontal, vertical) amounts. The book origin is computed inline from the
 * component's width/height/xOffset (1.20.1's {@code RecipeBookComponent} exposes no getXOrigin).</p>
 */
public final class RecipeBookScrollController {
	private RecipeBookScrollController() {
	}

	public static void init() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof CraftingScreen) && !(screen instanceof InventoryScreen)) {
				return;
			}
			ScreenMouseEvents.allowMouseScroll(screen).register(
				(currentScreen, mouseX, mouseY, horizontalAmount, verticalAmount) ->
					!handleScroll(currentScreen, mouseX, mouseY, verticalAmount)
			);
		});
	}

	private static boolean handleScroll(Screen screen, double mouseX, double mouseY, double verticalAmount) {
		if (!ReachCraftingConfig.get().enabled()) return false;
		if (!ReachCraftingConfig.get().recipeBookPageNavigation()) return false;
		if (verticalAmount == 0.0D) return false;
		if (!(screen instanceof RecipeUpdateListener recipeBookScreen)) return false;

		Minecraft client = Minecraft.getInstance();
		if (client == null) return false;
		long window = client.getWindow().getWindow();
		if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
			|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)) {
			return false;
		}

		RecipeBookComponent recipeBookComponent = recipeBookScreen.getRecipeBookComponent();
		if (recipeBookComponent == null || !recipeBookComponent.isVisible()) return false;
		RecipeBookComponentAccessor componentAccessor = (RecipeBookComponentAccessor) recipeBookComponent;

		RecipeBookPage page = componentAccessor.getRecipeBookPage();
		if (page == null) return false;
		RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) page;

		int bookX = (componentAccessor.getWidth() - 147) / 2 - componentAccessor.getXOffset();
		int bookY = (componentAccessor.getHeight() - 166) / 2;
		boolean hoveringRecipeBookUi = mouseX >= bookX - 30 && mouseX <= bookX + 147
			&& mouseY >= bookY && mouseY <= bookY + 166;
		if (!hoveringRecipeBookUi) return false;

		int currentPage = pageAccessor.getCurrentPage();
		int totalPages = Math.max(1, pageAccessor.getTotalPages());
		int targetPage = currentPage;
		if (verticalAmount < 0.0D) {
			targetPage = Math.min(currentPage + 1, totalPages - 1);
		} else if (verticalAmount > 0.0D) {
			targetPage = Math.max(currentPage - 1, 0);
		}
		if (targetPage == currentPage) return false;

		pageAccessor.setCurrentPage(targetPage);
		pageAccessor.invokeUpdateButtonsForPage();
		RecipeBookChunkedScheduler.noteVisiblePageIndex(targetPage);
		return true;
	}
}
