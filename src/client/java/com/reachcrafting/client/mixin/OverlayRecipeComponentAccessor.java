package com.reachcrafting.client.mixin;

import java.util.List;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OverlayRecipeComponent.class)
public interface OverlayRecipeComponentAccessor {
	@Accessor("recipeButtons")
	List<Object> getRecipeButtons();
}
