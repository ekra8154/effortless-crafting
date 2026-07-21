package com.reachcrafting.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

final class AutoCraftController {
	private static final int HOLD_RELEASE_GRACE_TICKS = 3;
	private static boolean autoCraftKeyHeld = false;
	private static boolean autoCraftTogglePending = false;
	private static long lastAutoCraftToggleTime = 0;
	private static boolean holdSessionActive = false;
	private static boolean holdStickyNormalLatched = false;
	private static boolean holdStickyBulkLatched = false;
	private static boolean holdStickyNormalAltOverride = false;
	private static boolean holdStickyBulkAltOverride = false;
	private static boolean holdQuickCraftCancelled = false;
	private static boolean holdQuickCraftConsumed = false;
	private static int holdReleaseGraceTicks = 0;

	private AutoCraftController() {
	}

	static void handleKeyPress() {
		if (autoCraftKeyHeld) {
			// Pre-1.21.9 input delivers GLFW key-repeat events to keyPressed while alt is held. Without
			// this guard, a repeat arriving after bulk latches (e.g. alt held through the toggle click)
			// would arm the "alt re-held" cancel gesture and kill bulk on release.
			return;
		}
		autoCraftKeyHeld = true;
		holdQuickCraftCancelled = false;
		holdQuickCraftConsumed = false;
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			autoCraftTogglePending = true;
			return;
		}
		if (holdStickyBulkLatched) {
			holdStickyBulkAltOverride = true;
		} else if (holdStickyNormalLatched) {
			holdStickyNormalAltOverride = true;
		}
	}

	static void handleKeyReleased() {
		if (!autoCraftKeyHeld && !autoCraftTogglePending) {
			return;
		}

		autoCraftKeyHeld = false;

		// While a bulk or chain session is actively crafting, Alt is INERT: an
		// Alt tap, or an alt-tab (which delivers an Alt release), must not stop
		// the run, toggle the mode, or schedule a stray quick-craft. Esc is the
		// abort key. We still clear the transient Alt/quick-craft state so the
		// gesture leaves no residue for after the session ends.
		if (isBulkOrChainSessionRunning()) {
			holdStickyBulkAltOverride = false;
			holdStickyNormalAltOverride = false;
			holdQuickCraftCancelled = false;
			holdQuickCraftConsumed = false;
			autoCraftTogglePending = false;
			return;
		}

		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			if (autoCraftTogglePending) {
				autoCraftTogglePending = false;
				toggleAutoCraftMode();
			}
			return;
		}

		if ((holdStickyBulkLatched && holdStickyBulkAltOverride) || (holdStickyNormalLatched && holdStickyNormalAltOverride)) {
			BulkAutoCraftController.stop(true, "alt_reheld_release");
			return;
		}

		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (!holdQuickCraftCancelled && !holdQuickCraftConsumed) {
				Minecraft client = Minecraft.getInstance();
				if (client.player != null && client.player.containerMenu != null && (client.screen instanceof CraftingScreen || client.screen instanceof InventoryScreen)) {
					Slot resultSlot = client.player.containerMenu.getSlot(0);
					if (resultSlot != null && resultSlot.hasItem()) {
						AutoMoveController.scheduleAutoMove(ItemStack.EMPTY);
					}
				}
			}
			holdQuickCraftCancelled = false;
			holdQuickCraftConsumed = false;
		}

		autoCraftTogglePending = false;
		holdReleaseGraceTicks = HOLD_RELEASE_GRACE_TICKS;
	}

	/** True while a bulk / bulk-chain / chain craft is actually running, so Alt
	 *  gestures (tap, alt-tab release) must not disturb it — Esc aborts. */
	private static boolean isBulkOrChainSessionRunning() {
		return BulkAutoCraftController.isActive()
			|| BulkChainCraftController.isActive()
			|| ChainCraftController.isActive();
	}

	static void cancelToggle() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			autoCraftTogglePending = false;
		} else if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			// With a mode latched, another key while Alt is held (e.g. Ctrl
			// joining an Alt+Ctrl request) only cancels the pending Alt-tap
			// quick craft; it must not kill the latch or a running session.
			if (holdStickyBulkLatched || holdStickyNormalLatched) {
				holdQuickCraftConsumed = true;
				return;
			}
			if (!holdQuickCraftCancelled) {
				holdQuickCraftCancelled = true;
				BulkAutoCraftController.stop(true, "hold_cancel_on_key");
			}
		}
	}

	static void consumeQuickCraft() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			autoCraftTogglePending = false;
		} else if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			holdQuickCraftConsumed = true;
			// Every quick-craft consumption means Alt is participating in a
			// recipe request (click queue, scroll accumulation, or release).
			// Its eventual release must fire the request, not read as the
			// re-held cancel gesture and kill the sticky latch — the click
			// paths already disarm via armHoldSessionForCurrentRequest, but
			// scroll accumulation reaches Alt release before that runs.
			holdStickyBulkAltOverride = false;
			holdStickyNormalAltOverride = false;
		}
	}

	static boolean isTogglePending() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.TOGGLE) {
			return autoCraftTogglePending;
		}
		return autoCraftKeyHeld;
	}

	static boolean isEnabled() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			return isHoldRuntimeEnabled();
		}
		return config.autoCraftEnabled() || holdSessionActive || RecipeBookInputController.getInstance().isAltRequestActive();
	}

	static boolean isHoldRuntimeEnabled() {
		return !holdQuickCraftCancelled && (isPhysicalAltHeld() || holdSessionActive || holdStickyNormalLatched || holdStickyBulkLatched);
	}

	static ReachCraftingConfig.AutoCraftMode enabledMode() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			return holdStickyBulkLatched ? ReachCraftingConfig.AutoCraftMode.BULK : ReachCraftingConfig.AutoCraftMode.NORMAL;
		}
		return ReachCraftingConfig.get().autoCraftEnabledMode();
	}

	/** Compact state dump for debugging bulk/hold gating decisions. */
	static String describeHoldState() {
		return "enabled=" + isEnabled()
			+ " bulk=" + isBulkModeEnabled()
			+ " handling=" + ReachCraftingConfig.get().autoCraftHandling()
			+ " capability=" + ReachCraftingConfig.get().autoCraftCapability()
			+ " stickyBulk=" + holdStickyBulkLatched
			+ " stickyNormal=" + holdStickyNormalLatched
			+ " bulkAltOverride=" + holdStickyBulkAltOverride
			+ " sessionActive=" + holdSessionActive
			+ " quickCraftCancelled=" + holdQuickCraftCancelled
			+ " quickCraftConsumed=" + holdQuickCraftConsumed
			+ " altHeld=" + isPhysicalAltHeld()
			+ " keyHeld=" + autoCraftKeyHeld
			+ " graceTicks=" + holdReleaseGraceTicks;
	}

	static boolean isBulkModeEnabled() {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			return isEnabled()
				&& holdStickyBulkLatched
				&& ReachCraftingConfig.get().autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.BULK;
		}
		return ReachCraftingConfig.get().isBulkAutoCraftMode();
	}

	static void setEnabled(boolean enabled) {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (!enabled) {
				clearHoldRuntimeState();
			} else if (ReachCraftingConfig.get().autoCraftCapability() != ReachCraftingConfig.AutoCraftCapability.NONE) {
				holdSessionActive = true;
			}
			return;
		}
		ReachCraftingConfig.get().setAutoCraftEnabled(enabled);
		if (!enabled) {
			BulkAutoCraftController.clear();
		} else {
			AutoMoveController.scheduleAutoMove(net.minecraft.world.item.ItemStack.EMPTY);
		}
		ReachCraftingConfig.save();
	}

	static void setEnabledMode(ReachCraftingConfig.AutoCraftMode mode) {
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (mode != ReachCraftingConfig.AutoCraftMode.BULK) {
				holdStickyBulkLatched = false;
				holdStickyBulkAltOverride = false;
				holdStickyNormalLatched = false;
				BulkAutoCraftController.clear();
			} else if (ReachCraftingConfig.get().autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.BULK) {
				holdStickyBulkLatched = true;
				holdStickyNormalLatched = false;
				holdSessionActive = true;
			}
			return;
		}
		ReachCraftingConfig.get().setAutoCraftEnabledMode(mode);
		if (!ReachCraftingConfig.get().autoCraftEnabled()) {
			ReachCraftingConfig.save();
			return;
		}
		if (mode != ReachCraftingConfig.AutoCraftMode.BULK) {
			BulkAutoCraftController.clear();
		}
		ReachCraftingConfig.save();
	}

	static void toggleEnabledModeViaArrow() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftCapability() != ReachCraftingConfig.AutoCraftCapability.BULK) {
			return;
		}
		if (config.autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (!isHoldRuntimeEnabled()) {
				return;
			}
			if (holdStickyBulkLatched) {
				demoteBulkToPhysicalHoldMode();
			} else {
				holdStickyBulkLatched = true;
				holdStickyNormalLatched = false;
				holdStickyBulkAltOverride = false;
				holdSessionActive = true;
			}
			return;
		}
		if (!config.autoCraftEnabled()) {
			return;
		}
		if (config.autoCraftEnabledMode() == ReachCraftingConfig.AutoCraftMode.BULK) {
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
			BulkAutoCraftController.clear();
		} else {
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.BULK);
		}
		ReachCraftingConfig.save();
	}

	/** Shared end-of-bulk-session teardown for flat bulk and bulk chain. */
	static void finishBulkSessionTeardown() {
		Minecraft client = Minecraft.getInstance();
		if (BulkChainCraftController.isActive()) {
			// A flat sub-session ending mid-bulk-chain must not reset the
			// mode latch the CHAIN still owns — that killed a healthy
			// 500-craft run at 358 ("bulk_mode_disabled"). The chain's own
			// stop() calls back here after it clears its active flag.
			com.reachcrafting.ReachCraftingMod.LOGGER.info(
				"[bulk_craft] teardown deferred: bulk chain session still active");
			return;
		}
		boolean preserveAutoCraft = (client.isWindowActive() || ReproHarness.suppressFocusGuard())
			&& (client.screen instanceof CraftingScreen || client.screen instanceof InventoryScreen)
			&& isEnabled();
		resetBulkModeAfterSession(preserveAutoCraft);
		if (ReachCraftingConfig.get().autoCraftOffAfterBulk()) {
			setEnabled(false);
			ReachCraftingModClient.sendDebugChat("Auto Crafting disabled after bulk craft.");
		} else {
			ReachCraftingModClient.sendDebugChat("Auto Crafting mode reset to normal.");
		}
		OffhandConsolidationController.swapBack(client);
	}

	static void resetBulkModeAfterSession(boolean preserveAutoCraft) {
		com.reachcrafting.ReachCraftingMod.LOGGER.debug("[hold_state] resetBulkModeAfterSession preserve={} {}", preserveAutoCraft, describeHoldState());
		if (ReachCraftingConfig.get().autoCraftHandling() == ReachCraftingConfig.AutoCraftHandling.HOLD) {
			if (!preserveAutoCraft || !isPhysicalAltHeld()) {
				clearHoldRuntimeState();
				return;
			}
			demoteBulkToPhysicalHoldMode();
			return;
		}
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftEnabledMode() == ReachCraftingConfig.AutoCraftMode.BULK) {
			config.setAutoCraftEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
			ReachCraftingConfig.save();
		}
		BulkAutoCraftController.clear();
	}

	static void armHoldSessionForCurrentRequest(boolean altTriggered) {
		if (ReachCraftingConfig.get().autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.NONE) {
			return;
		}
		if (altTriggered || holdStickyNormalLatched || holdStickyBulkLatched || isPhysicalAltHeld() || holdReleaseGraceTicks > 0) {
			holdSessionActive = true;
		}
		if (altTriggered) {
			// Alt participated in a recipe request; releasing it afterward
			// must not read as the re-held cancel gesture and kill the latch.
			holdStickyBulkAltOverride = false;
			holdStickyNormalAltOverride = false;
		}
	}

	static void clearHoldSession() {
		clearHoldRuntimeState();
	}

	static void tick() {
		if (ReachCraftingConfig.get().autoCraftHandling() != ReachCraftingConfig.AutoCraftHandling.HOLD && !holdSessionActive) {
			return;
		}

		if (!isPhysicalAltHeld() && holdReleaseGraceTicks > 0) {
			holdReleaseGraceTicks--;
		}

		if (!Minecraft.getInstance().isWindowActive() && !ReproHarness.suppressFocusGuard()) {
			logLatchWipe("tick_window_inactive");
			holdReleaseGraceTicks = 0;
			holdStickyNormalLatched = false;
			holdStickyBulkLatched = false;
			holdStickyNormalAltOverride = false;
			holdStickyBulkAltOverride = false;
			holdSessionActive = false;
		}

		if (holdSessionActive
			&& !holdStickyNormalLatched
			&& !holdStickyBulkLatched
			&& !isPhysicalAltHeld()
			&& !BulkAutoCraftController.isActive()
			&& !AutoMoveController.isAutomatedInteractionRunning()
			&& !RecipeBookInputController.getInstance().isInputQueueActive()) {
			holdSessionActive = false;
		}

		if (!holdStickyBulkLatched) {
			holdStickyBulkAltOverride = false;
		}
		if (!holdStickyNormalLatched) {
			holdStickyNormalAltOverride = false;
		}
	}

	private static void toggleAutoCraftMode() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		if (config.autoCraftCapability() == ReachCraftingConfig.AutoCraftCapability.NONE) {
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastAutoCraftToggleTime < 50) {
			return;
		}
		lastAutoCraftToggleTime = now;

		boolean current = config.autoCraftEnabled();
		boolean next = !current;
		config.setAutoCraftEnabled(next);
		if (!next) {
			BulkAutoCraftController.clear();
		}
		if (next) {
			AutoMoveController.scheduleAutoMove(net.minecraft.world.item.ItemStack.EMPTY);
		}
		ReachCraftingConfig.save();
	}

	private static boolean isPhysicalAltHeld() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null && RecipeBookFocusManager.isAltKeyDown(minecraft);
	}

	private static void logLatchWipe(String site) {
		if (holdStickyBulkLatched || holdStickyNormalLatched) {
			com.reachcrafting.ReachCraftingMod.LOGGER.debug("[hold_state] latch_wipe site=" + site, new Exception("latch wipe call site"));
		}
	}

	private static void clearHoldRuntimeState() {
		logLatchWipe("clearHoldRuntimeState");
		autoCraftKeyHeld = false;
		autoCraftTogglePending = false;
		holdSessionActive = false;
		holdStickyNormalLatched = false;
		holdStickyBulkLatched = false;
		holdStickyNormalAltOverride = false;
		holdStickyBulkAltOverride = false;
		holdQuickCraftCancelled = false;
		holdQuickCraftConsumed = false;
		holdReleaseGraceTicks = 0;
		BulkAutoCraftController.clear();
	}

	private static void demoteBulkToNormalHoldMode() {
		logLatchWipe("demoteBulkToNormalHoldMode");
		holdStickyBulkLatched = false;
		holdStickyBulkAltOverride = false;
		holdStickyNormalLatched = true;
		holdStickyNormalAltOverride = false;
		holdSessionActive = true;
		BulkAutoCraftController.clear();
	}

	private static void demoteBulkToPhysicalHoldMode() {
		logLatchWipe("demoteBulkToPhysicalHoldMode");
		holdStickyBulkLatched = false;
		holdStickyBulkAltOverride = false;
		holdStickyNormalLatched = false;
		holdStickyNormalAltOverride = false;
		holdSessionActive = isPhysicalAltHeld() || holdReleaseGraceTicks > 0;
		BulkAutoCraftController.clear();
	}
}
