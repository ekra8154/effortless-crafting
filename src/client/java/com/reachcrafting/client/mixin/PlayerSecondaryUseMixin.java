package com.reachcrafting.client.mixin;

import com.reachcrafting.client.NearbyContainerDryRun;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerSecondaryUseMixin {
	@Inject(method = "isSecondaryUseActive", at = @At("HEAD"), cancellable = true)
	private void reachcrafting$suppressSecondaryUseDuringAutomation(CallbackInfoReturnable<Boolean> cir) {
		if (NearbyContainerDryRun.shouldSuppressSecondaryUse() && (Object) this instanceof LocalPlayer) {
			cir.setReturnValue(false);
		}
	}
}
