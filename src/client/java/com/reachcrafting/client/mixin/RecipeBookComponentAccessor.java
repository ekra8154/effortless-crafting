package com.reachcrafting.client.mixin;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StateSwitchingButton;
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

	@Accessor("selectedTab")
	void setSelectedTab(RecipeBookTabButton button);

	@Accessor("filterButton")
	StateSwitchingButton getFilterButton();

	@Accessor("searchBox")
	EditBox getSearchBox();

	@Accessor("recipeBookPage")
	RecipeBookPage getRecipeBookPage();

	@Accessor("width")
	int getWidth();

	@Accessor("height")
	int getHeight();

	@Accessor("minecraft")
	Minecraft getMinecraft();

	@Accessor("xOffset")
	int getXOffset();

	@Invoker("updateCollections")
	void invokeUpdateCollections(boolean resetPage);

	@Invoker("sendUpdateSettings")
	void invokeSendUpdateSettings();
}
