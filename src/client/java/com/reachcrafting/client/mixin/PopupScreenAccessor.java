package com.reachcrafting.client.mixin;

import net.minecraft.client.gui.components.PopupScreen;
import net.minecraft.client.gui.layouts.LinearLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PopupScreen.class)
public interface PopupScreenAccessor {
	@Accessor("layout")
	LinearLayout reachcrafting$getLayout();
}
