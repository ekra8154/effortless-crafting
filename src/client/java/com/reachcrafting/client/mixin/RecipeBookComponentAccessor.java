package com.reachcrafting.client.mixin;

import java.util.List;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentAccessor {
	@Accessor("tabButtons")
	List<RecipeBookTabButton> getTabButtons();

	@Accessor("selectedTab")
	RecipeBookTabButton getSelectedTab();

	@Accessor("filterButton")
	CycleButton<Boolean> getFilterButton();

	@Accessor("searchBox")
	EditBox getSearchBox();

	@Accessor("recipeBookPage")
	RecipeBookPage getRecipeBookPage();

	@Invoker("replaceSelected")
	void invokeReplaceSelected(RecipeBookTabButton button);
}
