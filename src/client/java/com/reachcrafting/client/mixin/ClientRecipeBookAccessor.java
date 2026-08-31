package com.reachcrafting.client.mixin;

import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.RecipeBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the known-recipe set, which ChainCraftPlanner sizes its index cache
 * against.
 *
 * <p>Targets RecipeBook, not ClientRecipeBook. On this version "known" is a
 * protected field of the SUPERCLASS, and an @Accessor only looks at the class
 * it is mixed into -- pointed at ClientRecipeBook it fails to apply with
 * "No candidates were found matching known:Ljava/util/Set;" and takes the
 * client down mid-configuration. From 1.21.2 the field moved down and the
 * newer branches can target ClientRecipeBook directly.</p>
 */
@Mixin(RecipeBook.class)
public interface ClientRecipeBookAccessor {
	@Accessor("known")
	Set<ResourceLocation> getKnown();
}
