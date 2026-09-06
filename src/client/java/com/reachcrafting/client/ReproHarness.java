package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
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
 *   retrieve <item_id> [count=N]  - open table, switch retrieval mode on, then
 *                                   shift-click the recipe (retrieve all) or,
 *                                   with count, queue N and release the way a
 *                                   Ctrl+scroll accumulation would
 *   craft <item_id> [ctrl] [shift] [count=N]
 *                                 - a plain auto-craft click (Alt semantics, no
 *                                   bulk latch): Ctrl allows nearby containers,
 *                                   shift = craft-all, count queues N; goes
 *                                   through the retrieve-first step per the
 *                                   existingOutputHandling setting
 *   indicator <item_id>           - log the recipe-book indicator state for the
 *                                   recipe producing item_id (needs the table)
 *   set retrieval <craft_only|ask|craft|only>
 *                                 - existingOutputHandling in memory only
 *   set autoconfirm yes|no        - answer for auto-handled popups (default yes)
 *   set eject on|off              - flip ejectItemsWhenFull in memory only
 *   set budget <n>                - clickBudgetPerWindow in memory only
 *   warmcache                     - scan uncached containers (logs "warmup finish")
 *   abort                         - what Esc does: abort all sessions, close screen
 * Progress is logged with the [repro_harness] tag so external scripts can
 * follow the run in logs/latest.log.
 */
public final class ReproHarness {
	private enum PendingKind { CRAFT, RETRIEVE, CRAFT_PLAIN, INDICATOR }
	private static boolean pendingShift;
	private static boolean autoConfirmYes = true;

	private static Path cmdFile;
	private static int pollCounter;
	private static String pendingBulkItem;
	private static PendingKind pendingKind = PendingKind.CRAFT;
	private static int pendingRetrieveCount = -1;
	private static boolean pendingCtrl;
	private static boolean pendingBulkLatch = true;
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

	// Dev-only client QoL. -Dreachcrafting.repro.quiet=true (set by the automated
	// launch scripts via EC_REPRO_QUIET, NOT by a plain `gradlew runClientRepro`)
	// makes the from-source client non-intrusive: no focus steal / taskbar flash
	// on launch, and a free cursor so the dev PC stays usable. F6 toggles the free
	// cursor live, so you can also launch normally, play as ReproBot manually, and
	// hand the mouse to automated runs without relaunching. Inert outside a dev env.
	private static final boolean QUIET_LAUNCH =
		"true".equalsIgnoreCase(System.getProperty("reachcrafting.repro.quiet", ""));
	private static boolean freeMouse = QUIET_LAUNCH;
	private static boolean freeMouseKeyWasDown;

	/** Launch-time: suppress GLFW focus-on-show so the client does not steal
	 * foreground / flash the taskbar. Read by WindowMixin at window creation. */
	public static boolean suppressWindowFocus() {
		return QUIET_LAUNCH;
	}

	/** Runtime: keep the cursor ungrabbed so the developer's mouse stays usable.
	 * Read by MouseHandlerMixin; toggled live with F6. */
	public static boolean freeMouseActive() {
		return freeMouse;
	}

	private ReproHarness() {
	}

	public static void init() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		// The functional suite asserts on per-craft diagnostics (post_place
		// counts, slow-path detection, harness state). Those are gated off for
		// players now, so the dev environment force-enables them rather than
		// making all ten version worktrees carry a config override.
		ReachCraftingMod.setDiagnosticLoggingGate(() -> true);
		cmdFile = FabricLoader.getInstance().getGameDir().resolve("repro-cmd.txt");
		ReachCraftingMod.diag("[repro_harness] armed cmd_file={} quiet_launch={} free_mouse={} (F6 toggles)",
			cmdFile, QUIET_LAUNCH, freeMouse);
		ClientTickEvents.END_CLIENT_TICK.register(ReproHarness::tick);
	}

	/** F6 toggles free-mouse mode live (release the cursor for the desktop, or
	 * grab it back to play as ReproBot) — no relaunch needed. Edge-triggered. */
	private static void pollFreeMouseToggle(Minecraft client) {
		boolean down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
			client.getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_F6);
		if (down && !freeMouseKeyWasDown) {
			freeMouse = !freeMouse;
			if (freeMouse) {
				client.mouseHandler.releaseMouse();
			} else {
				client.mouseHandler.grabMouse();
			}
			ReachCraftingMod.diag("[repro_harness] free-mouse {} (F6)", freeMouse ? "ON" : "OFF");
		}
		freeMouseKeyWasDown = down;
	}

	private static void tick(Minecraft client) {
		pollFreeMouseToggle(client);
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
		ReachCraftingMod.diag("[repro_harness] command={}", command);
		String[] parts = command.trim().split("\\s+");
		switch (parts[0]) {
			case "open" -> openNearestCraftingTable(client);
			case "close" -> {
				// Close the crafting screen so grid contents return to the
				// inventory where the external ledger audit can see them.
				if (client.gui.screen() != null) {
					client.player.closeContainer();
				}
				ReachCraftingMod.diag("[repro_harness] closed container");
			}
			case "clearcache" -> {
				// The repro reuses the same chest positions every run with
				// different contents; setblock does not invalidate the mod's
				// nearby-container cache, so it would withdraw against stale
				// data. Clear it so the next craft rescans the real contents.
				NearbyContainerCache.clear();
				ReachCraftingMod.diag("[repro_harness] cleared nearby container cache");
			}
			case "bulk", "chain" -> {
				if (parts.length < 2) {
					ReachCraftingMod.LOGGER.warn("[repro_harness] {} requires an item id", parts[0]);
					return;
				}
				pendingBulkItem = parts[1];
				pendingKind = PendingKind.CRAFT;
				// A retrieve run leaves retrieval mode latched (it is a mode,
				// not a session); a craft run after it must not be rerouted.
				ExistingOutputRetrievalController.setEnabled(false);
				// bulk/chain are pure craft primitives; the retrieve-first
				// step is exercised through `craft` with `set retrieval`.
				ReachCraftingConfig.get().setExistingOutputHandling(ReachCraftingConfig.ExistingOutputHandling.CRAFT_ONLY);
				// "chain" drives the NON-bulk request path: alt held for the
				// craft, no sticky bulk latch. That combination is its own
				// execution path (bulk_mode=false) and had no coverage, which
				// is how a final step with no fast placement branch shipped.
				pendingBulkLatch = parts[0].equals("bulk");
				pendingCtrl = parts.length > 2 && parts[2].equals("ctrl");
				pendingTimeoutTicks = 100;
				// Keep automation alive while the window is backgrounded for
				// the duration of this run (dev-only focus-guard bypass).
				focusBypassTicks = 6000;
				// Always start from a fresh container session so leftover
				// screens/grids from a previous run can't contaminate the test.
				if (client.gui.screen() != null) {
					client.player.closeContainer();
				}
				openNearestCraftingTable(client);
			}
			case "retrieve", "craft", "indicator" -> {
				if (parts.length < 2) {
					ReachCraftingMod.LOGGER.warn("[repro_harness] {} requires an item id", parts[0]);
					return;
				}
				pendingBulkItem = parts[1];
				pendingKind = switch (parts[0]) {
					case "retrieve" -> PendingKind.RETRIEVE;
					case "craft" -> PendingKind.CRAFT_PLAIN;
					default -> PendingKind.INDICATOR;
				};
				pendingRetrieveCount = -1;
				pendingCtrl = false;
				pendingShift = false;
				for (int i = 2; i < parts.length; i++) {
					if (parts[i].startsWith("count=")) {
						pendingRetrieveCount = Integer.parseInt(parts[i].substring("count=".length()));
					} else if (parts[i].equals("ctrl")) {
						pendingCtrl = true;
					} else if (parts[i].equals("shift")) {
						pendingShift = true;
					}
				}
				pendingBulkLatch = false;
				pendingTimeoutTicks = 100;
				// Keep automation alive while the window is backgrounded for
				// the duration of this run (dev-only focus-guard bypass).
				focusBypassTicks = 6000;
				// Always start from a fresh container session so leftover
				// screens/grids from a previous run can't contaminate the test.
				if (client.gui.screen() != null) {
					client.player.closeContainer();
				}
				openNearestCraftingTable(client);
			}
			case "warmcache" -> {
				// Scan every uncached container in reach so a following
				// retrieve runs against a warm cache. Logs "warmup finish"
				// when done (see CacheWarmupSession).
				NearbyContainerDryRun.startCacheWarmup("harness");
				ReachCraftingMod.diag("[repro_harness] cache warmup started");
			}
			case "set" -> {
				if (parts.length == 3 && parts[1].equals("eject")) {
					boolean on = parts[2].equals("on");
					ReachCraftingConfig.get().setEjectItemsWhenFull(on);
					ReachCraftingMod.diag("[repro_harness] set eject_items_when_full={}", on);
				} else if (parts.length == 3 && parts[1].equals("budget")) {
					int budget = Integer.parseInt(parts[2]);
					ReachCraftingConfig.get().setClickBudgetPerWindow(budget);
					ReachCraftingMod.diag("[repro_harness] set click_budget_per_window={}", budget);
				} else if (parts.length == 3 && parts[1].equals("retrieval")) {
					ReachCraftingConfig.ExistingOutputHandling handling = switch (parts[2]) {
						case "ask" -> ReachCraftingConfig.ExistingOutputHandling.RETRIEVE_THEN_ASK;
						case "craft" -> ReachCraftingConfig.ExistingOutputHandling.RETRIEVE_THEN_CRAFT;
						case "only" -> ReachCraftingConfig.ExistingOutputHandling.RETRIEVE_ONLY;
						default -> ReachCraftingConfig.ExistingOutputHandling.CRAFT_ONLY;
					};
					ReachCraftingConfig.get().setExistingOutputHandling(handling);
					ReachCraftingMod.diag("[repro_harness] set existing_output_handling={}", handling);
				} else if (parts.length == 3 && parts[1].equals("autoconfirm")) {
					autoConfirmYes = parts[2].equals("yes");
					ReachCraftingMod.diag("[repro_harness] set autoconfirm_yes={}", autoConfirmYes);
				} else {
					ReachCraftingMod.LOGGER.warn("[repro_harness] unknown set target {}", command);
				}
			}
			case "abort" -> {
				// What Esc does mid-session (AbstractContainerScreenMixin):
				// abort every session, then close whatever screen is up.
				ContainerUtils.abortAllSessions();
				if (client.gui.screen() != null) {
					client.player.closeContainer();
				}
				ReachCraftingMod.diag("[repro_harness] aborted sessions");
			}
			default -> ReachCraftingMod.LOGGER.warn("[repro_harness] unknown command {}", parts[0]);
		}
	}

	private static void drivePending(Minecraft client) {
		// Auto-accept the chain-craft CONFIRM popup for a short window after a
		// scripted bulk request, so chain repro runs need no manual click.
		if (autoConfirmTicks > 0) {
			autoConfirmTicks--;
			// Every mod popup in the window gets the scripted answer: a
			// retrieve-then-craft prompt may be followed by a chain prompt.
			if (client.gui.screen() instanceof net.minecraft.client.gui.components.PopupScreen popup
				&& ChainCraftPopupController.isChainCraftPopup(popup)) {
				ReachCraftingMod.diag("[repro_harness] auto-{} popup title={}", autoConfirmYes ? "confirming" : "declining", popup.getTitle().getString());
				if (autoConfirmYes) {
					ChainCraftPopupController.confirm(popup);
				} else {
					ChainCraftPopupController.cancel(popup);
				}
			}
		}
		if (pendingBulkItem == null) {
			return;
		}
		if (--pendingTimeoutTicks <= 0) {
			ReachCraftingMod.LOGGER.warn("[repro_harness] {} timed out waiting for crafting screen item={}",
				pendingKind == PendingKind.RETRIEVE ? "retrieve" : "bulk", pendingBulkItem);
			pendingBulkItem = null;
			return;
		}
		if (!(client.gui.screen() instanceof CraftingScreen)) {
			return;
		}
		String itemId = pendingBulkItem;
		boolean ctrl = pendingCtrl;
		boolean bulkLatch = pendingBulkLatch;
		pendingBulkItem = null;
		if (pendingKind == PendingKind.RETRIEVE) {
			retrieveRecipeByItemId(client, itemId, pendingRetrieveCount);
			return;
		}
		if (pendingKind == PendingKind.CRAFT_PLAIN) {
			craftRecipeByItemId(client, itemId, ctrl, pendingShift, pendingRetrieveCount);
			return;
		}
		if (pendingKind == PendingKind.INDICATOR) {
			indicatorForItemId(client, itemId);
			return;
		}
		clickRecipeByItemId(client, itemId, ctrl, bulkLatch);
	}

	/** A plain Alt-style auto-craft click, no bulk latch, honoring existingOutputHandling. */
	private static void craftRecipeByItemId(Minecraft client, String itemId, boolean ctrl, boolean shift, int count) {
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				ItemStack stack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
				if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().identifier().toString())) {
					continue;
				}
				ExistingOutputRetrievalController.setEnabled(false);
				AutoCraftController.setEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
				autoConfirmTicks = 400;
				ReachCraftingMod.diag(
					"[repro_harness] craft armed recipe id={} item={} ctrl={} shift={} count={} handling={}",
					entry.id(), itemId, ctrl, shift, count < 0 ? "1" : String.valueOf(count),
					ReachCraftingConfig.get().existingOutputHandling());
				if (count < 0) {
					RecipeBookClickCapture.onRecipeButtonClicked(entry.id(), collection, stack, 0, shift, ctrl, true, false);
				} else {
					RecipeBookInputController.getInstance().harnessQueueAndRelease(entry.id(), collection, stack, count, ctrl, true);
				}
				return;
			}
		}
		ReachCraftingMod.LOGGER.warn("[repro_harness] no recipe collection found for {}", itemId);
	}

	private static void indicatorForItemId(Minecraft client, String itemId) {
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				ItemStack stack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
				if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().identifier().toString())) {
					continue;
				}
				ExistingOutputRetrievalController.setEnabled(false);
				RecipeButtonNearbyIndicator.clearCaches();
				ReachCraftingMod.diag("[repro_harness] indicator_state item={} recipe={} {}",
					itemId, entry.id(), RecipeButtonNearbyIndicator.describe(entry.id(), collection));
				return;
			}
		}
		ReachCraftingMod.LOGGER.warn("[repro_harness] no recipe collection found for {}", itemId);
	}

	private static void retrieveRecipeByItemId(Minecraft client, String itemId, int count) {
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				ItemStack stack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
				if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().identifier().toString())) {
					continue;
				}
				// Retrieval is a mode the player toggles with Ctrl double-tap;
				// arm it directly, then drive the same click paths a user would.
				ExistingOutputRetrievalController.setEnabled(true);
				ReachCraftingMod.diag(
					"[repro_harness] retrieve armed recipe id={} item={} count={} retrieval_enabled={}",
					entry.id(), itemId, count < 0 ? "all" : String.valueOf(count),
					ExistingOutputRetrievalController.isEnabled());
				driveRetrieveClick(entry.id(), collection, stack, count);
				return;
			}
		}
		// No recipe makes this item (eggs, ender pearls...). Retrieval mode
		// injects a synthetic entry for such items; click that instead, the
		// way the player would from the retrieval-mode book.
		RecipeCollection synthetic = VirtualRetrievalRecipeBookEntries.harnessSyntheticCollection(itemId);
		if (synthetic != null && !synthetic.getRecipes().isEmpty()) {
			ExistingOutputRetrievalController.setEnabled(true);
			RecipeDisplayEntry entry = synthetic.getRecipes().getFirst();
			ItemStack stack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
			ReachCraftingMod.diag(
				"[repro_harness] retrieve armed SYNTHETIC id={} item={} count={} display={}",
				entry.id(), itemId, count < 0 ? "all" : String.valueOf(count), ContainerUtils.formatStack(stack));
			driveRetrieveClick(entry.id(), synthetic, stack, count);
			return;
		}
		ReachCraftingMod.LOGGER.warn("[repro_harness] no recipe collection found for {}", itemId);
	}

	private static void driveRetrieveClick(
		net.minecraft.world.item.crafting.display.RecipeDisplayId recipeId,
		RecipeCollection collection,
		ItemStack stack,
		int count
	) {
		if (count < 0) {
			RecipeBookClickCapture.onRecipeButtonClicked(recipeId, collection, stack, 0, true, false, false, false);
		} else {
			RecipeBookInputController.getInstance().harnessQueueAndRelease(recipeId, collection, stack, count);
		}
	}

	private static void clickRecipeByItemId(Minecraft client, String itemId, boolean ctrl, boolean bulkLatch) {
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				ItemStack stack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
				if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().identifier().toString())) {
					continue;
				}
				ReachCraftingMod.diag(
					"[repro_harness] clicking recipe id={} item={} shift=true ctrl={} bulk_latch={}",
					entry.id(), itemId, ctrl, bulkLatch);
				// Arm the sticky bulk latch the same way a user's physical
				// alt-hold does, so the click runs a refillable bulk session.
				// Without it the request stays on the NORMAL path and alt has
				// to be pressed for the click to count as a craft request.
				AutoCraftController.setEnabledMode(bulkLatch
					? ReachCraftingConfig.AutoCraftMode.BULK
					: ReachCraftingConfig.AutoCraftMode.NORMAL);
				autoConfirmTicks = 200;
				RecipeBookClickCapture.onRecipeButtonClicked(
					entry.id(), collection, stack, 0, true, ctrl, !bulkLatch, false);
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
		Direction face = Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
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
