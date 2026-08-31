package com.reachcrafting;

import java.util.function.BooleanSupplier;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReachCraftingMod implements ModInitializer {
	public static final String MOD_ID = "reachcrafting";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static volatile BooleanSupplier diagnosticLogging = () -> false;

	/**
	 * Point the diagnostic gate at the client config. Called during client
	 * init; the mod's common entrypoint has no config, so until then (and on a
	 * dedicated server) diagnostics stay off.
	 */
	public static void setDiagnosticLoggingGate(BooleanSupplier gate) {
		diagnosticLogging = gate != null ? gate : () -> false;
	}

	/**
	 * Per-craft diagnostics: the placement branch taken, chain step
	 * scheduling, variant resolution and so on. Silent unless the player turns
	 * Diagnostic Logging on.
	 *
	 * <p>These are logged at INFO when enabled because that is the level a
	 * player can actually capture in latest.log without editing a log4j
	 * config, which is the whole point of asking them for one. Genuine
	 * problems stay on {@link #LOGGER} warn/error and are never gated.</p>
	 */
	public static void diag(String format, Object... args) {
		if (diagnosticLogging.getAsBoolean()) {
			LOGGER.info(format, args);
		}
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Reach Crafting initialized.");
	}
}
