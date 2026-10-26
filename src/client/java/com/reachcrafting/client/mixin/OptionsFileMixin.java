package com.reachcrafting.client.mixin;

import com.reachcrafting.client.AutomatedLaunchProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Dev-only: an automated (quiet) repro launch reads and writes its own
 * options file, so a muted or resized suite client never changes the options
 * a manual launch comes back to. See AutomatedLaunchProfile.
 */
@Mixin(Options.class)
public class OptionsFileMixin {
	@ModifyConstant(method = "<init>", constant = @Constant(stringValue = "options.txt"), require = 1)
	private String reachcrafting$automatedOptionsFile(String fileName) {
		return AutomatedLaunchProfile.fileName(FabricLoader.getInstance().getGameDir(), fileName);
	}
}
