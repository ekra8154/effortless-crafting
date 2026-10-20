package com.reachcrafting.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Harness only: parks the cursor over a widget so a screenshot shows its hover state. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("xpos")
    void reachcrafting$setXpos(double xpos);

    @Accessor("ypos")
    void reachcrafting$setYpos(double ypos);
}
