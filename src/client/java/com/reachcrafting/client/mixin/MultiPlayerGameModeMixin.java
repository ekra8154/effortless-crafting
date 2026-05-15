package com.reachcrafting.client.mixin;
import com.reachcrafting.client.ReachCraftingConfig;
import com.reachcrafting.client.InventoryGridRestoreTracker;
import com.reachcrafting.client.NearbyContainerCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(
		method = "useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
		at = @At("HEAD")
	)
	private void reachcrafting$trackPotentialContainerOpen(
		LocalPlayer player,
		InteractionHand hand,
		BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		if (!ReachCraftingConfig.get().enabled()) return;
		if (player == null || player.level() == null || hitResult == null) {
			return;
		}
		NearbyContainerCache.notePotentialContainerInteraction(player.level(), hitResult.getBlockPos());
	}

	@Inject(method = "handleContainerInput", at = @At("HEAD"))
	private void reachcrafting$onHandleInventoryMouseClick(int containerId, int slotId, int button, ContainerInput clickType, net.minecraft.world.entity.player.Player player, CallbackInfo ci) {
		if (!ReachCraftingConfig.get().enabled()) return;
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.player.containerMenu == null) return;
		
		AbstractContainerMenu menu = client.player.containerMenu;
		if (menu.containerId != containerId) return;

		// DIAGNOSTIC: Log every click on the result slot (slot 0) — crafting only triggers here
		if (slotId == 0 && com.reachcrafting.client.BulkAutoCraftController.isActive()) {
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[RESULT_SLOT_CLICK] slot=0 button={} clickType={} caller={}",
				button, clickType,
				new Throwable().getStackTrace()[2].getMethodName()
			);
		}
		
		// Update the inventory snapshot high-water mark after every click to capture movements
		// BUT only if we aren't in an automated session, otherwise we'll "capture" the withdrawn items as initial state!
		if (!com.reachcrafting.client.ContainerUtils.isAutomatedInteractionRunning()) {
			com.reachcrafting.client.PulledResourcesTracker.updateSnapshot(client.player);

			if (clickType == ContainerInput.PICKUP) {
				if (menu.getCarried().isEmpty()) {
					InventoryGridRestoreTracker.recordPotentialSource(slotId, clickType, menu);
				} else {
					InventoryGridRestoreTracker.recordPotentialDestination(slotId, button, clickType, menu);
				}
			} else if (clickType == ContainerInput.QUICK_CRAFT) {
				InventoryGridRestoreTracker.recordPotentialDestination(slotId, button, clickType, menu);
			}
		}
	}
}
