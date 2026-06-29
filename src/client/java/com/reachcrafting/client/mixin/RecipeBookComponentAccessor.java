package com.reachcrafting.client.mixin;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.StateSwitchingButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.world.inventory.RecipeBookMenu;
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

	@Accessor("menu")
	RecipeBookMenu getMenu();

	// Mojang left this method unnamed in 1.21.10 and below; it is named "replaceSelected" only from 1.21.11.
	@Invoker("method_2582")
	boolean invokeReplaceSelected(RecipeBookTabButton button);

	@Invoker("getXOrigin")
	int invokeGetXOrigin();

	@Invoker("getYOrigin")
	int invokeGetYOrigin();

	@Invoker("updateCollections")
	void invokeUpdateCollections(boolean resetPage, boolean filtering);

	@Invoker("sendUpdateSettings")
	void invokeSendUpdateSettings();
}
