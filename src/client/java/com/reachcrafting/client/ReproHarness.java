package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-only, file-driven repro harness for the server packet-limit investigation.
 *
 * Poll loop reads one command per line from {@code <gamedir>/repro-cmd.txt},
 * then truncates the file. Commands:
 *   open                          - open the nearest crafting table
 *   bulk <item_id> [ctrl]         - open table if needed, then shift-click
 *                                   (craft-max) the recipe producing item_id;
 *                                   "ctrl" also enables nearby containers
 * Progress is logged with the [repro_harness] tag so external scripts can
 * follow the run in logs/latest.log.
 */
public final class ReproHarness {
	private static Path cmdFile;
	private static int pollCounter;
	private static String pendingBulkItem;
	private static boolean pendingCtrl;
	private static int pendingTimeoutTicks;
	private static int autoConfirmTicks;
	// While a scripted run is in flight, suppress the window-focus guard so a
	// backgrounded dev client (the normal case for automated tests) does not
	// abort bulk-chain automation. Dev-only; armed per command, self-expiring.
	private static int focusBypassTicks;

	/** Dev-only: true while the harness is driving a run, so focus guards
	 * (which abort automation when the window is backgrounded) should be
	 * skipped. Always false outside a development environment. */
	public static boolean suppressFocusGuard() {
		return focusBypassTicks > 0;
	}

	private ReproHarness() {
	}

	public static void init() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		cmdFile = FabricLoader.getInstance().getGameDir().resolve("repro-cmd.txt");
		ReachCraftingMod.LOGGER.info("[repro_harness] armed cmd_file={}", cmdFile);
		ClientTickEvents.END_CLIENT_TICK.register(ReproHarness::tick);
	}

	private static void tick(Minecraft client) {
		if (client.player == null || client.level == null) {
			return;
		}
		if (focusBypassTicks > 0) {
			focusBypassTicks--;
		}
		drivePending(client);
		if (++pollCounter < 10) {
			return;
		}
		pollCounter = 0;
		String command = readAndClearCommand();
		if (command == null || command.isBlank()) {
			return;
		}
		ReachCraftingMod.LOGGER.info("[repro_harness] command={}", command);
		String[] parts = command.trim().split("\\s+");
		switch (parts[0]) {
			case "open" -> openNearestCraftingTable(client);
			case "close" -> {
				// Close the crafting screen so grid contents return to the
				// inventory where the external ledger audit can see them.
				if (client.screen != null) {
					client.player.closeContainer();
				}
				ReachCraftingMod.LOGGER.info("[repro_harness] closed container");
			}
			case "clearcache" -> {
				// The repro reuses the same chest positions every run with
				// different contents; setblock does not invalidate the mod's
				// nearby-container cache, so it would withdraw against stale
				// data. Clear it so the next craft rescans the real contents.
				NearbyContainerCache.clear();
				ReachCraftingMod.LOGGER.info("[repro_harness] cleared nearby container cache");
			}
			case "bulk" -> {
				if (parts.length < 2) {
					ReachCraftingMod.LOGGER.warn("[repro_harness] bulk requires an item id");
					return;
				}
				pendingBulkItem = parts[1];
				pendingCtrl = parts.length > 2 && parts[2].equals("ctrl");
				pendingTimeoutTicks = 100;
				// Keep automation alive while the window is backgrounded for
				// the duration of this run (dev-only focus-guard bypass).
				focusBypassTicks = 6000;
				// Always start from a fresh container session so leftover
				// screens/grids from a previous run can't contaminate the test.
				if (client.screen != null) {
					client.player.closeContainer();
				}
				openNearestCraftingTable(client);
			}
			default -> ReachCraftingMod.LOGGER.warn("[repro_harness] unknown command {}", parts[0]);
		}
	}

	private static void drivePending(Minecraft client) {
		// Auto-accept the chain-craft CONFIRM popup for a short window after a
		// scripted bulk request, so chain repro runs need no manual click.
		if (autoConfirmTicks > 0) {
			autoConfirmTicks--;
			if (client.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen) {
				// Pre-1.21.2 presents the chain-craft confirm as a ConfirmScreen
				// (no PopupScreen); its keyPressed accepts Enter to confirm.
				ReachCraftingMod.LOGGER.info("[repro_harness] auto-confirming chain popup");
				client.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
				autoConfirmTicks = 0;
			}
		}
		if (pendingBulkItem == null) {
			return;
		}
		if (--pendingTimeoutTicks <= 0) {
			ReachCraftingMod.LOGGER.warn("[repro_harness] bulk timed out waiting for crafting screen item={}", pendingBulkItem);
			pendingBulkItem = null;
			return;
		}
		if (!(client.screen instanceof CraftingScreen)) {
			return;
		}
		String itemId = pendingBulkItem;
		boolean ctrl = pendingCtrl;
		pendingBulkItem = null;
		clickRecipeByItemId(client, itemId, ctrl);
	}

	private static void clickRecipeByItemId(Minecraft client, String itemId, boolean ctrl) {
		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeHolder<?> recipe : collection.getRecipes()) {
				ItemStack stack = RecipeVariantResolver.resolveDisplayStack(recipe, client);
				if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().location().toString())) {
					continue;
				}
				ReachCraftingMod.LOGGER.info(
					"[repro_harness] clicking recipe id={} item={} shift=true ctrl={}", recipe.id(), itemId, ctrl);
				// Arm the sticky bulk latch the same way a user's physical
				// alt-hold does, so the click runs a refillable bulk session.
				AutoCraftController.setEnabledMode(ReachCraftingConfig.AutoCraftMode.BULK);
				autoConfirmTicks = 200;
				RecipeBookClickCapture.onRecipeButtonClicked(
					recipe, collection, stack, 0, true, ctrl, false, false);
				return;
			}
		}
		ReachCraftingMod.LOGGER.warn("[repro_harness] no recipe collection found for {}", itemId);
	}

	private static void openNearestCraftingTable(Minecraft client) {
		Vec3 eyePos = client.player.getEyePosition(0);
		double reach = client.player.blockInteractionRange();
		BlockPos tablePos = ContainerUtils.findNearestCraftingTable(client.level, eyePos, reach);
		if (tablePos == null) {
			ReachCraftingMod.LOGGER.warn("[repro_harness] no crafting table in reach");
			return;
		}
		Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, tablePos);
		Direction face = Direction.getNearest(hitPos.subtract(eyePos).x, hitPos.subtract(eyePos).y, hitPos.subtract(eyePos).z).getOpposite();
		client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitPos, face, tablePos, false));
	}

	private static String readAndClearCommand() {
		try {
			if (cmdFile == null || !Files.exists(cmdFile)) {
				return null;
			}
			String content = Files.readString(cmdFile).trim();
			if (!content.isEmpty()) {
				Files.writeString(cmdFile, "");
			}
			return content;
		} catch (IOException e) {
			ReachCraftingMod.LOGGER.warn("[repro_harness] command file error", e);
			return null;
		}
	}
}
