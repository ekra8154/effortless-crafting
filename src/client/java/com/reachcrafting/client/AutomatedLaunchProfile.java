package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Dev only: a client the automated scripts launch (versions.py, ec.py: the
 * quiet repro launch) keeps its own copies of the files a player changes by
 * hand, the mod config and vanilla's options.txt. The suite pins settings
 * for each scenario and the harness's "in memory only" pins still reach the
 * disk whenever anything else saves the config, so sharing one file meant
 * every manual launch came back with the suite's settings (Dynamic Auto Craft
 * off, Only Prompt When Different off, master volume 0). A manual launch now
 * reads and writes the normal files only.
 *
 * <p>The automated copy is seeded from the player's file the first time, so
 * a new tree starts from the same settings. Inert outside a development
 * environment.</p>
 */
public final class AutomatedLaunchProfile {
	private AutomatedLaunchProfile() {
	}

	public static boolean active() {
		return FabricLoader.getInstance().isDevelopmentEnvironment() && ReproHarness.isQuietLaunch();
	}

	/**
	 * The file to use in place of {@code fileName}: itself on a manual launch,
	 * "{@code <name>-automated.<ext>}" next to it on an automated one.
	 */
	public static String fileName(Path directory, String fileName) {
		if (!active()) {
			return fileName;
		}
		int dot = fileName.lastIndexOf('.');
		String automated = dot > 0
			? fileName.substring(0, dot) + "-automated" + fileName.substring(dot)
			: fileName + "-automated";
		Path manualFile = directory.resolve(fileName);
		Path automatedFile = directory.resolve(automated);
		if (Files.notExists(automatedFile) && Files.exists(manualFile)) {
			try {
				Files.copy(manualFile, automatedFile);
				ReachCraftingMod.LOGGER.info("[automated_launch] seeded {} from {}", automated, fileName);
			} catch (IOException e) {
				ReachCraftingMod.LOGGER.warn("[automated_launch] could not seed {}: {}", automated, e.toString());
			}
		}
		return automated;
	}
}
