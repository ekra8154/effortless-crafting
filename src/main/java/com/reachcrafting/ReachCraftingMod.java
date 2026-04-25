package com.reachcrafting;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReachCraftingMod implements ModInitializer {
	public static final String MOD_ID = "reachcrafting";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Reach Crafting initialized.");
	}
}
