package com.reachcrafting.client.mixin;

import java.util.Set;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientRecipeBook.class)
public interface ClientRecipeBookAccessor {
	@Accessor
	Set<ResourceLocation> getKnown();
}
