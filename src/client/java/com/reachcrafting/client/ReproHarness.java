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
 *   set revolving <specific|prefer|always>
 *                                 - revolvingCraftHandling in memory only
 *   set variantswitch on|off      - outputVariantSwitching in memory only
 *   set nearby always|ctrl|off    - nearby container usage mode in memory only
 *   waitidle                      - log "[repro_harness] idle" once nothing is running
 * Every consumed command is echoed to repro-cmd.ack, so the driver can
 * wait for consumption instead of sleeping.
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
	/** retrieve only: click the item's entry on the retrieval-mode variant menu (overlay) instead of the page button. */
	private static boolean pendingOverlay;
	// A scripted craft has always behaved as though Alt were held, so the
	// remainder of a retrieve-then-craft was collected rather than left
	// staged in the grid. "noalt" drives the plain path a player gets
	// without auto craft.
	private static boolean pendingNoAlt;
	// Existing Output Handling as it was before a bulk/chain run forced
	// Craft Only, so the run can hand it back. The setter itself does not
	// persist, but auto craft toggles during the run call save(), which
	// writes the forced value to disk and leaves the worktree on Craft
	// Only for whatever is launched next.
	private static ReachCraftingConfig.ExistingOutputHandling handlingBeforeRun;
	private static boolean autoConfirmYes = true;

	private static Path cmdFile;
	/**
	 * Written with the command text the moment a command is consumed, so the
	 * driver can wait for that instead of sleeping a fixed 1.5 s per command.
	 */
	private static Path ackFile;
	/** `waitidle`: log "[repro_harness] idle" once the mod has been quiet for IDLE_QUIET_TICKS. */
	private static boolean idleWatch;
	private static int idleQuietTicks;
	private static final int IDLE_QUIET_TICKS = 10;
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

	// Dev-only client QoL: -Dreachcrafting.repro.quiet=true (set by the launch
	// scripts via -PecReproQuiet) makes the from-source client non-intrusive
	// (no focus steal / taskbar flash, free cursor). F6 toggles the cursor live.
	private static final boolean QUIET_LAUNCH =
		"true".equalsIgnoreCase(System.getProperty("reachcrafting.repro.quiet", ""));
	private static boolean freeMouse = QUIET_LAUNCH;
	private static boolean freeMouseKeyWasDown;

	public static boolean suppressWindowFocus() {
		return QUIET_LAUNCH;
	}

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
		ackFile = FabricLoader.getInstance().getGameDir().resolve("repro-cmd.ack");
		ReachCraftingMod.diag("[repro_harness] armed cmd_file={} quiet_launch={} free_mouse={} (F6 toggles)", cmdFile, QUIET_LAUNCH, freeMouse);
		ClientTickEvents.END_CLIENT_TICK.register(ReproHarness::tick);
	}

	private static void pollFreeMouseToggle(Minecraft client) {
		boolean down = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
			client.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_F6);
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
		pollIdle(client);
		if (++pollCounter < 2) {
			return;
		}
		pollCounter = 0;
		String command = readAndClearCommand();
		if (command == null || command.isBlank()) {
			return;
		}
		ReachCraftingMod.diag("[repro_harness] command={}", command);
		writeAck(command);
		String[] parts = command.trim().split("\\s+");
		switch (parts[0]) {
			case "waitidle" -> {
				idleWatch = true;
				idleQuietTicks = 0;
			}
			case "open" -> openNearestCraftingTable(client);
			case "close" -> {
				// Close the crafting screen so grid contents return to the
				// inventory where the external ledger audit can see them.
				if (client.screen != null) {
					client.player.closeContainer();
				}
				// Hand back the handling a bulk/chain run borrowed, and rewrite the
				// file so the worktree is not left on Craft Only. Every scenario
				// ends with `close`, so this is the reliable place for it.
				if (handlingBeforeRun != null) {
					ReachCraftingConfig.get().setExistingOutputHandling(handlingBeforeRun);
					ReachCraftingMod.diag("[repro_harness] restored existing_output_handling={}", handlingBeforeRun);
					handlingBeforeRun = null;
					ReachCraftingConfig.save();
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
				if (handlingBeforeRun == null) {
					handlingBeforeRun = ReachCraftingConfig.get().existingOutputHandling();
				}
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
				if (client.screen != null) {
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
				pendingOverlay = false;
				pendingNoAlt = false;
				for (int i = 2; i < parts.length; i++) {
					if (parts[i].startsWith("count=")) {
						pendingRetrieveCount = Integer.parseInt(parts[i].substring("count=".length()));
					} else if (parts[i].equals("ctrl")) {
						pendingCtrl = true;
					} else if (parts[i].equals("shift")) {
						pendingShift = true;
					} else if (parts[i].equals("overlay")) {
						pendingOverlay = true;
					} else if (parts[i].equals("noalt")) {
						pendingNoAlt = true;
					}
				}
				pendingBulkLatch = false;
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
			case "cmd" -> {
				// The /effortlesscrafting help pages, driven the way the command
				// handlers are: "cmd help bulk", "cmd tips", "cmd settings".
				String rest = parts.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)) : "";
				HelpCommand.harnessRun(rest);
				ReachCraftingMod.diag("[repro_harness] cmd executed args='{}'", rest);
			}
			case "screen" -> ReachCraftingMod.diag("[repro_harness] screen={}",
				client.screen == null ? "null" : client.screen.getClass().getName());
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
				} else if (parts.length == 3 && parts[1].equals("revolving")) {
					ReachCraftingConfig.RevolvingCraftHandling handling = switch (parts[2]) {
						case "always" -> ReachCraftingConfig.RevolvingCraftHandling.ALWAYS_PREFER_BASED_ON_COUNT;
						case "prefer" -> ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK;
						default -> ReachCraftingConfig.RevolvingCraftHandling.SPECIFIC_VARIANT_ONLY;
					};
					ReachCraftingConfig.get().setRevolvingCraftHandling(handling);
					ReachCraftingMod.diag("[repro_harness] set revolving_craft_handling={}", handling);
				} else if (parts.length == 3 && parts[1].equals("variantswitch")) {
					boolean on = parts[2].equals("on");
					ReachCraftingConfig.get().setOutputVariantSwitching(on);
					ReachCraftingMod.diag("[repro_harness] set output_variant_switching={}", on);
				} else if (parts.length == 3 && parts[1].equals("striplogs")) {
					boolean on = parts[2].equals("on");
					ReachCraftingConfig.get().setPreferNonStrippedLogs(on);
					ReachCraftingMod.diag("[repro_harness] set prefer_non_stripped_logs={}", on);
				} else if (parts.length == 3 && parts[1].equals("nearby")) {
					// Revolving-variant resolution on a plain craft only runs when
					// nearby chests are allowed, so scenarios that assert on it pin
					// the mode instead of inheriting a worktree's run config.
					ReachCraftingConfig.NearbyContainerUsageMode mode = switch (parts[2]) {
						case "always" -> ReachCraftingConfig.NearbyContainerUsageMode.ALWAYS;
						case "off", "disabled" -> ReachCraftingConfig.NearbyContainerUsageMode.DISABLED;
						default -> ReachCraftingConfig.NearbyContainerUsageMode.CTRL_HELD;
					};
					ReachCraftingConfig.get().setNearbyContainerUsageMode(mode);
					ReachCraftingMod.diag("[repro_harness] set nearby_container_usage={}", mode);
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
				if (client.screen != null) {
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
			if (client.screen instanceof net.minecraft.client.gui.components.PopupScreen popup
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
		if (!(client.screen instanceof CraftingScreen)) {
			return;
		}
		String itemId = pendingBulkItem;
		boolean ctrl = pendingCtrl;
		boolean bulkLatch = pendingBulkLatch;
		boolean noAlt = pendingNoAlt;
		pendingBulkItem = null;
		if (pendingKind == PendingKind.RETRIEVE) {
			if (pendingOverlay) {
				retrieveFromVariantOverlay(client, itemId, pendingRetrieveCount);
			} else {
				retrieveRecipeByItemId(client, itemId, pendingRetrieveCount);
			}
			return;
		}
		if (pendingKind == PendingKind.CRAFT_PLAIN) {
			craftRecipeByItemId(client, itemId, ctrl, pendingShift, pendingRetrieveCount, noAlt);
			return;
		}
		if (pendingKind == PendingKind.INDICATOR) {
			indicatorForItemId(client, itemId);
			return;
		}
		clickRecipeByItemId(client, itemId, ctrl, bulkLatch);
	}

	/** A plain Alt-style auto-craft click, no bulk latch, honoring existingOutputHandling. */
	private static void craftRecipeByItemId(Minecraft client, String itemId, boolean ctrl, boolean shift, int count, boolean noAlt) {
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				ItemStack stack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
				if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().location().toString())) {
					continue;
				}
				ExistingOutputRetrievalController.setEnabled(false);
				AutoCraftController.setEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
				autoConfirmTicks = 400;
				ReachCraftingMod.diag(
					"[repro_harness] craft armed recipe id={} item={} ctrl={} shift={} alt={} count={} handling={}",
					entry.id(), itemId, ctrl, shift, !noAlt, count < 0 ? "1" : String.valueOf(count),
					ReachCraftingConfig.get().existingOutputHandling());
				if (count < 0) {
					RecipeBookClickCapture.onRecipeButtonClicked(entry.id(), collection, stack, 0, shift, ctrl, !noAlt, false);
				} else {
					RecipeBookInputController.getInstance().harnessQueueAndRelease(entry.id(), collection, stack, count, ctrl, !noAlt);
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
				if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().location().toString())) {
					continue;
				}
				ExistingOutputRetrievalController.setEnabled(false);
				RecipeButtonNearbyIndicator.clearCaches();
				// The probe reads the cache the way a click does: settled, not
				// whatever an in-flight tick recompute last published.
				ChainCraftabilityCache.refreshNow(client);
				ReachCraftingMod.diag("[repro_harness] indicator_state item={} recipe={} {}",
					itemId, entry.id(), RecipeButtonNearbyIndicator.describe(entry.id(), collection));
				return;
			}
		}
		ReachCraftingMod.LOGGER.warn("[repro_harness] no recipe collection found for {}", itemId);
	}

	/**
	 * The retrieval-mode variant menu: one synthetic entry per colour. A
	 * click there arrives with NO display stack (the overlay button hover
	 * lookup only knows the entry id), so the drive passes EMPTY exactly as
	 * the real path does and the input controller must resolve the clicked
	 * entry's own output from the collection.
	 */
	private static void retrieveFromVariantOverlay(Minecraft client, String itemId, int count) {
		RecipeCollection grouped = RetrievalOutputVariantOverlay.harnessGroupedCollection(itemId);
		if (grouped == null || grouped.getRecipes().size() <= 1) {
			ReachCraftingMod.LOGGER.warn("[repro_harness] no retrieval variant menu for {} (entries={})",
				itemId, grouped == null ? "none" : grouped.getRecipes().size());
			return;
		}
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		for (RecipeDisplayEntry entry : grouped.getRecipes()) {
			ItemStack stack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
			if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().location().toString())) {
				continue;
			}
			ExistingOutputRetrievalController.setEnabled(true);
			ReachCraftingMod.diag(
				"[repro_harness] retrieve armed OVERLAY id={} item={} count={} menu_entries={} synthetic={}",
				entry.id(), itemId, count < 0 ? "all" : String.valueOf(count), grouped.getRecipes().size(),
				VirtualRetrievalRecipeBookEntries.isSyntheticRecipeId(entry.id()));
			driveRetrieveClick(entry.id(), grouped, ItemStack.EMPTY, count);
			return;
		}
		ReachCraftingMod.LOGGER.warn("[repro_harness] variant menu for {} has no entry for it", itemId);
	}

	private static void retrieveRecipeByItemId(Minecraft client, String itemId, int count) {
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				ItemStack stack = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
				if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().location().toString())) {
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
				if (stack.isEmpty() || !itemId.equals(stack.getItem().builtInRegistryHolder().key().location().toString())) {
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

	private static void writeAck(String command) {
		try {
			if (ackFile != null) {
				Files.writeString(ackFile, command);
			}
		} catch (IOException e) {
			ReachCraftingMod.LOGGER.warn("[repro_harness] ack file error", e);
		}
	}

	/**
	 * Everything the mod can still be doing after a scenario's "done" line:
	 * container sessions (withdrawal, retrieval, return), the click queue,
	 * a pending auto-move, the extractor, every controller that schedules
	 * further work, a prompt waiting for an answer, and the harness's own
	 * pending trigger.
	 */
	private static boolean modBusy(Minecraft client) {
		return pendingBulkItem != null
			|| NearbyContainerDryRun.isActiveSessionRunning()
			|| ContainerUtils.isInputQueueActive()
			|| ContainerUtils.isAutoMovePending()
			|| ChainCraftController.isActive()
			|| BulkAutoCraftController.isActive()
			|| BulkChainCraftController.isActive()
			|| GridExtractor.isActive()
			|| OutputVariantContinuationController.isActive()
			|| RetrieveThenCraftController.isActive()
			|| (client.screen instanceof net.minecraft.client.gui.components.PopupScreen popup
				&& ChainCraftPopupController.isChainCraftPopup(popup));
	}

	private static void pollIdle(Minecraft client) {
		if (!idleWatch) {
			return;
		}
		if (modBusy(client)) {
			idleQuietTicks = 0;
			return;
		}
		if (++idleQuietTicks >= IDLE_QUIET_TICKS) {
			idleWatch = false;
			ReachCraftingMod.diag("[repro_harness] idle");
		}
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
