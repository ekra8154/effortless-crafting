package com.reachcrafting.client.mixin;

import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.NearbyContainerCache;
import com.reachcrafting.client.NearbyContainerDryRun;
import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	@Inject(method = "initializeContents(ILjava/util/List;Lnet/minecraft/world/item/ItemStack;)V", at = @At("TAIL"))
	private void reachcrafting$onInitializeContents(int revision, List<ItemStack> stacks, ItemStack cursorStack, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		NearbyContainerDryRun.onContainerContentsInitialized((AbstractContainerMenu) (Object) this);
		NearbyContainerCache.onContainerContentsInitialized((AbstractContainerMenu) (Object) this);
	}
}
