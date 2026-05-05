package com.reachcrafting.client.mixin;

import com.reachcrafting.client.NearbyContainerDryRun;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftInteractionBlockMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$blockWorldUse(CallbackInfo ci) {
		if (!com.reachcrafting.client.ReachCraftingConfig.get().enabled()) return;
		if (NearbyContainerDryRun.shouldBlockWorldInteraction()) {
			ci.cancel();
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$blockWorldAttack(CallbackInfoReturnable<Boolean> cir) {
		if (!com.reachcrafting.client.ReachCraftingConfig.get().enabled()) return;
		if (NearbyContainerDryRun.shouldBlockWorldInteraction()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$blockHeldAttack(boolean interacting, CallbackInfo ci) {
		if (!com.reachcrafting.client.ReachCraftingConfig.get().enabled()) return;
		if (NearbyContainerDryRun.shouldBlockWorldInteraction()) {
			ci.cancel();
		}
	}
}
