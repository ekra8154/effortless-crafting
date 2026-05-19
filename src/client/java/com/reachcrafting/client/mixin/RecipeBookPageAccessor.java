package com.reachcrafting.client.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
// import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RecipeBookPage.class)
public interface RecipeBookPageAccessor {
	@Accessor("overlay")
	OverlayRecipeComponent getOverlay();

	@Accessor("buttons")
	List<RecipeButton> getButtons();

	@Accessor("currentPage")
	int getCurrentPage();

	@Accessor("currentPage")
	void setCurrentPage(int currentPage);

	@Accessor("totalPages")
	int getTotalPages();

	@Invoker("updateButtonsForPage")
	void invokeUpdateButtonsForPage();

	@Invoker("updateArrowButtons")
	void invokeUpdateArrowButtons();
}
