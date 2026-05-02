package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.ExtendedRecipeBookCategory;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class NearbyContainerDryRun {
	private static int interactionBlockTicks;
	private static boolean suppressSecondaryUse;
	private static Set<String> pendingPostReturnCompactionItemIds = Set.of();

	private NearbyContainerDryRun() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (interactionBlockTicks > 0) {
				interactionBlockTicks--;
			}
			if (activeSession != null) {
				activeSession.tick();
			}
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (activeSession == null) {
				return;
			}
			if (message.getContents() instanceof TranslatableContents translatable
				&& "container.isLocked".equals(translatable.getKey())) {
				activeSession.onOpenFailed("locked");
			}
		});
	}

	public static void start(
		RecipeDisplayId recipeId,
		RecipeCollection recipeCollection,
		boolean explicitVariantSelection,
		int recipeIndex,
		String outputLabel,
		RecipeIngredientSummary ingredientSummary,
		AvailableItemSnapshot localItems,
		boolean craftAll,
		int requestedSingleClicks,
		boolean allowNearby
	) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		Level level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;
		Entity cameraEntity = client.getCameraEntity();
		if (player == null || level == null || gameMode == null || cameraEntity == null) {
			return;
		}

		cancelCurrent();
		SearchSession session = new SearchSession(
			client,
			player,
			level,
			gameMode,
			cameraEntity,
			recipeId,
			recipeCollection,
			explicitVariantSelection,
			recipeIndex,
			outputLabel,
			ingredientSummary,
			localItems,
			craftAll,
			requestedSingleClicks,
			allowNearby
		);
		if (!session.canStart()) {
			return;
		}

		activeSession = session;
		session.start();
	}

	public static boolean tryExpandReservedGrid(
		RecipeDisplayId recipeId,
		RecipeCollection recipeCollection,
		boolean explicitVariantSelection,
		int recipeIndex,
		String outputLabel,
		RecipeIngredientSummary ingredientSummary,
		AvailableItemSnapshot localItems,
		boolean craftAll,
		int requestedSingleClicks,
		boolean allowNearby
	) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		Level level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;
		Entity cameraEntity = client.getCameraEntity();
		if (player == null || level == null || gameMode == null || cameraEntity == null) {
			return false;
		}

		cancelCurrent();
		SearchSession session = new SearchSession(
			client,
			player,
			level,
			gameMode,
			cameraEntity,
			recipeId,
			recipeCollection,
			explicitVariantSelection,
			recipeIndex,
			outputLabel,
			ingredientSummary,
			localItems,
			craftAll,
			requestedSingleClicks,
			allowNearby
		);
		return session.expandReservedGridInPlace();
	}

	public static void startReturn(AbstractContainerMenu closingMenu, List<PulledResourcesTracker.WithdrawnItem> items, boolean reopenScreen) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		Level level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;
		Entity cameraEntity = client.getCameraEntity();

		if (client == null || player == null || level == null || gameMode == null || cameraEntity == null || items.isEmpty()) {
			return;
		}

		cancelCurrent();
		AvailableItemSnapshot localItems = AvailableItemSnapshot.capture(player, client.screen);
		ScreenContext context = ScreenContext.capture(client, cameraEntity, player.blockInteractionRange(), localItems);
		if (reopenScreen) {
			context = context.withClearedGrid();
		}
		activeSession = new ReturnSession(client, player, level, gameMode, cameraEntity, closingMenu, items, context, reopenScreen);
		activeSession.start();
	}

	public static void cancelCurrent() {
		if (activeSession != null) {
			activeSession.stop(true);
			activeSession = null;
			RecipeBookClickCapture.refocusRecipeBookSearch(Minecraft.getInstance());
		}
	}

	public static void abortActiveSession() {
		if (activeSession != null) {
			activeSession.stop(false);
			activeSession = null;
			RecipeBookClickCapture.refocusRecipeBookSearch(Minecraft.getInstance());
		}
	}

	public static boolean isActiveSessionRunning() {
		return activeSession != null;
	}

	public static boolean shouldBlockWorldInteraction() {
		return activeSession != null || interactionBlockTicks > 0;
	}

	public static boolean shouldSuppressSecondaryUse() {
		return suppressSecondaryUse;
	}

	public static void runPendingPostReturnCompaction(Minecraft client) {
		if (pendingPostReturnCompactionItemIds.isEmpty() || client == null || client.player == null || client.gameMode == null) {
			return;
		}
		InventoryGridRestoreTracker.compactTrackedInventoryStacks(client.player.containerMenu, client.gameMode, pendingPostReturnCompactionItemIds);
		ReachCraftingMod.LOGGER.info(
			"[nearby_return] Deferred compaction applied: {}",
			AvailableItemSnapshot.formatInventorySlots(client.player, pendingPostReturnCompactionItemIds)
		);
		pendingPostReturnCompactionItemIds = Set.of();
	}

	private static void armInteractionBlock() {
		interactionBlockTicks = Math.max(interactionBlockTicks, 4);
	}

	private static void withSuppressedSecondaryUse(Runnable action) {
		boolean previous = suppressSecondaryUse;
		suppressSecondaryUse = true;
		try {
			action.run();
		} finally {
			suppressSecondaryUse = previous;
		}
	}

	private static void sendShiftOverride(Minecraft client, LocalPlayer player, boolean shiftDown) {
		if (client.getConnection() == null || player.input == null) {
			return;
		}
		Input keyPresses = player.input.keyPresses;
		if (keyPresses == null) {
			return;
		}
		client.getConnection().send(new ServerboundPlayerInputPacket(new Input(
			keyPresses.forward(),
			keyPresses.backward(),
			keyPresses.left(),
			keyPresses.right(),
			keyPresses.jump(),
			shiftDown,
			keyPresses.sprint()
		)));
	}

	public static void onContainerContentsInitialized(AbstractContainerMenu menu) {
		if (activeSession != null) {
			if (activeSession instanceof SearchSession searchSession) {
				searchSession.onContainerContentsInitialized(menu);
			} else if (activeSession instanceof ReturnSession returnSession) {
				returnSession.onContainerContentsInitialized(menu);
			}
		}
	}

	private abstract static class BaseSession {
		protected final Minecraft client;
		protected final LocalPlayer player;
		protected final Level level;
		protected final MultiPlayerGameMode gameMode;
		protected final Entity cameraEntity;

		protected BaseSession(Minecraft client, LocalPlayer player, Level level, MultiPlayerGameMode gameMode, Entity cameraEntity) {
			this.client = client;
			this.player = player;
			this.level = level;
			this.gameMode = gameMode;
			this.cameraEntity = cameraEntity;
		}

		public abstract void tick();

		public abstract void start();

		public abstract void stop(boolean closeContainer);

		public abstract void onOpenFailed(String reason);

		public abstract void onContainerContentsInitialized(AbstractContainerMenu menu);

		protected void sendChat(String message) {
			player.displayClientMessage(
				Component.literal("[Reach Crafting] " + message).withStyle(ChatFormatting.GOLD),
				false
			);
		}

		protected void resumeOriginalContext(ScreenContext context) {
			Map<String, Integer> reservedGridCounts = new LinkedHashMap<>();
			for (ItemStack stack : context.gridStacks()) {
				if (stack.isEmpty()) {
					continue;
				}
				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				reservedGridCounts.merge(itemId, stack.getCount(), Integer::sum);
			}
			ReachCraftingMod.LOGGER.info(
				"[nearby_resume] kind={} grid={} mouse=({}, {})",
				context.kind(),
				AvailableItemSnapshot.formatCounts(reservedGridCounts),
				String.format(java.util.Locale.ROOT, "%.1f", context.mouseX()),
				String.format(java.util.Locale.ROOT, "%.1f", context.mouseY())
			);
			if (context.kind() == ScreenKind.INVENTORY_2X2) {
				client.setScreen(new InventoryScreen(player));
				return;
			}
			if (context.kind() == ScreenKind.CRAFTING_TABLE_3X3 && context.craftingTablePos() != null) {
				Vec3 eyePos = cameraEntity.getEyePosition(0);
				BlockPos pos = context.craftingTablePos();
				if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE) && ContainerUtils.squaredDistanceToBlock(eyePos, pos) <= Mth.square(player.blockInteractionRange())) {
					Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, pos);
					Direction face = Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
					BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);
					boolean wasSneaking = player.isShiftKeyDown() || (player.input != null && player.input.keyPresses != null && player.input.keyPresses.shift());
					withSuppressedSecondaryUse(() -> {
						if (wasSneaking) {
							sendShiftOverride(client, player, false);
							player.setShiftKeyDown(false);
						}
						gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
						if (wasSneaking) {
							player.setShiftKeyDown(true);
							sendShiftOverride(client, player, true);
						}
					});
				}
			}
		}

		protected boolean isOriginalContextReady(ScreenContext context) {
			if (context.kind() == ScreenKind.NONE) {
				return true;
			}
			if (context.kind() == ScreenKind.INVENTORY_2X2) {
				return client.screen instanceof InventoryScreen;
			}
			return client.screen instanceof CraftingScreen;
		}
	}

	private static BaseSession activeSession;

	private static final class SearchSession extends BaseSession {
		private static final int OPEN_TIMEOUT_TICKS = 40;
		private static final int RESUME_DELAY_TICKS = 10; // Increased from 2 to 10 to prevent race conditions
		private static final int REOPEN_TIMEOUT_TICKS = 20;
		private static final int RESTORE_TIMEOUT_TICKS = 10;
		private static final int MAX_REOPEN_ATTEMPTS = 3;

		private final RecipeCollection recipeCollection;
		private final boolean explicitVariantSelection;
		private final int recipeIndex;
		private RecipeDisplayId recipeId;
		private String outputLabel;
		private RecipeIngredientSummary ingredientSummary;
		private final AvailableItemSnapshot localItems;
		private final boolean craftAll;
		private final int requestedSingleClicks;
		private final IngredientPlanning.Policy planningPolicy;
		private final RecipeDeficitReport initialDeficit;
		private final List<String> remainingItemIds;
		private final ScreenContext originalContext;
		private final Set<String> scanAcceptedItemIds;
		private final NearbyContainerCache.ReachableView cachedReachableView;
		private final List<BlockPos> candidates;
		private final Set<BlockPos> visited = new HashSet<>();
		private final Map<String, Integer> discoveredNearby = new LinkedHashMap<>();
		private final Map<String, Integer> withdrawnItems = new LinkedHashMap<>();
		private final boolean allowNearby;
		private List<IngredientPlanning.SlotTarget> plannedTargets = List.of();

		private int nextCandidateIndex;
		private int timeoutTicks;
		private int restoreTicksRemaining;
		private int reopenAttemptsRemaining;
		private int seedWaitTicksRemaining;
		private int scannedContainers;
		private String lastRestoreFailure = "<none>";
		private int targetCopiesPerSlot;
		private BlockPos pendingContainerPos;
		private boolean inventorySpaceBlocked;
		private boolean redistributeThisRun;
		private String blockedCommittedLayoutMissingSummary;
		private SearchPhase phase;
		private SearchState state = SearchState.OPEN_NEXT;

		private SearchSession(
			Minecraft client,
			LocalPlayer player,
			Level level,
			MultiPlayerGameMode gameMode,
			Entity cameraEntity,
			RecipeDisplayId recipeId,
			RecipeCollection recipeCollection,
			boolean explicitVariantSelection,
			int recipeIndex,
			String outputLabel,
			RecipeIngredientSummary ingredientSummary,
			AvailableItemSnapshot localItems,
			boolean craftAll,
			int requestedSingleClicks,
			boolean allowNearby
		) {
			super(client, player, level, gameMode, cameraEntity);
			this.recipeCollection = recipeCollection;
			this.explicitVariantSelection = explicitVariantSelection;
			this.recipeId = recipeId;
			this.recipeIndex = recipeIndex;
			this.outputLabel = outputLabel;
			this.ingredientSummary = ingredientSummary;
			this.localItems = localItems;
			this.craftAll = craftAll;
			this.requestedSingleClicks = Math.max(requestedSingleClicks, 1);
			this.allowNearby = allowNearby;
			this.originalContext = ScreenContext.capture(client, cameraEntity, player.blockInteractionRange(), localItems);
			this.planningPolicy = ReachCraftingConfig.get().toPlanningPolicy();
			Set<String> accepted = new HashSet<>(ingredientSummary.acceptedItemIds());
			if (recipeCollection != null && (planningPolicy.redistributeToCraftWhenNeeded() || ReachCraftingConfig.get().revolvingCraftHandling() != ReachCraftingConfig.RevolvingCraftHandling.SPECIFIC_VARIANT_ONLY)) {
				ContextMap context = SlotDisplayContext.fromLevel(client.level);
				for (var entry : recipeCollection.getRecipes()) {
					accepted.addAll(RecipeIngredientSummary.fromDisplay(entry.display(), context).acceptedItemIds());
				}
			}
			this.scanAcceptedItemIds = Set.copyOf(accepted);
			this.initialDeficit = RecipeDeficitReport.from(
				ingredientSummary,
				localItems.inventoryCounts(),
				originalContext.gridStacks(),
				craftAll
			);
			this.remainingItemIds = new ArrayList<>(initialDeficit.missingItemIds());
			this.cachedReachableView = NearbyContainerCache.getReachableView(level, cameraEntity, player.blockInteractionRange());
			this.candidates = allowNearby ? NearbyContainerCache.prioritizeCandidates(
				findCandidates(level, cameraEntity, player.blockInteractionRange()),
				cachedReachableView,
				scanAcceptedItemIds
			) : List.of();
			this.targetCopiesPerSlot = craftAll ? 0 : this.requestedSingleClicks;
			this.reopenAttemptsRemaining = 3;
			this.restoreTicksRemaining = 10;
			this.seedWaitTicksRemaining = 20;
			this.redistributeThisRun = false;
			this.blockedCommittedLayoutMissingSummary = null;
			this.phase = SearchPhase.DISCOVERY;
		}

		private boolean canStart() {
			return !player.isSpectator() && !player.isHandsBusy() && player.containerMenu.getCarried().isEmpty();
		}

		@Override
		public void start() {
			ReachCraftingMod.LOGGER.info(
				"[nearby_scan] idx={} start missing={} candidates={} craft_all={} phase={} planned={}",
				recipeIndex,
				initialDeficit.compactMissingSummary(),
				candidates.size(),
				craftAll,
				phase.name().toLowerCase(),
				summarizeRemainingItems(remainingItemIds)
			);

			PulledResourcesTracker.clearWithdrawals(); // Safety: clear stale withdrawals, but keep the baseline inventory snapshot for return planning

			if (allowNearby) {
				sendChat("Scanning nearby containers...");
			}
		}

		private boolean expandReservedGridInPlace() {
		if (!originalContext.hasReservedGrid() || !originalContext.reservedGridMatches(ingredientSummary)) {
			return false;
		}

			Map<String, Integer> availableCounts = localItems.inventoryCounts();
			int maxReachableCopies = IngredientPlanning.computeMaxCraftCopies(
				ingredientSummary,
				localItems.inventoryCounts(),
				originalContext.gridStacks(),
				availableCounts,
				availableCounts,
				planningPolicy
			);
			targetCopiesPerSlot = craftAll
				? maxReachableCopies
				: Math.min(currentReservedCraftCopies() + requestedSingleClicks, maxReachableCopies);
			if (targetCopiesPerSlot <= 0) {
				return false;
			}
			if (!hasInventoryForReservedGridExpansion()) {
				return false;
			}

			lastRestoreFailure = "<none>";
			boolean expanded = expandReservedGrid();
			if (!expanded) {
				ReachCraftingMod.LOGGER.warn(
					"[nearby_restore] idx={} in_place_expand_failed reason={}",
					recipeIndex,
					lastRestoreFailure
				);
				return false;
			}

			sendChat("Updated grid: " + outputLabel);
			return true;
		}

		private boolean hasInventoryForReservedGridExpansion() {
			Map<String, Integer> neededCounts = new LinkedHashMap<>();
			Map<String, Integer> inventoryCounts = currentInventoryCounts();
			for (ItemStack stack : originalContext.gridStacks()) {
				if (stack.isEmpty()) {
					continue;
				}

				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				int currentCount = stack.getCount();
				int desiredCount = Math.min(stack.getMaxStackSize(), Math.max(targetCopiesPerSlot, currentCount));
				int missing = Math.max(desiredCount - currentCount, 0);
				if (missing > 0) {
					neededCounts.merge(itemId, missing, Integer::sum);
				}
			}

			for (Map.Entry<String, Integer> entry : neededCounts.entrySet()) {
				if (inventoryCounts.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
					return false;
				}
			}
			return true;
		}

		@Override
		public void tick() {
			if (client.player != player || client.level != level) {
				stop(false);
				NearbyContainerDryRun.activeSession = null;
				return;
			}

			if (state == SearchState.OPEN_NEXT) {
				if (phase == SearchPhase.WITHDRAW && remainingItemIds.isEmpty()) {
					beginResume();
					return;
				}
				if (!openNextContainer()) {
					if (phase == SearchPhase.DISCOVERY) {
						beginWithdrawPhaseOrResume();
					} else {
						beginResume();
					}
				}
				return;
			}

			if (state == SearchState.WAITING_FOR_CONTAINER) {
				timeoutTicks--;
				if (timeoutTicks <= 0) {
					onOpenFailed("timeout");
				}
				return;
			}

			if (state == SearchState.RESUME_CONTEXT) {
				timeoutTicks--;
				if (timeoutTicks <= 0) {
					resumeOriginalContext();
					timeoutTicks = REOPEN_TIMEOUT_TICKS;
					state = SearchState.WAITING_FOR_REOPEN;
				}
				return;
			}

			if (state == SearchState.WAITING_FOR_REOPEN) {
				if (isOriginalContextReady()) {
					if (tryFinishAfterResume()) {
						return;
					}
					return;
				}

				timeoutTicks--;
				if (timeoutTicks <= 0) {
					if (originalContext.kind() != ScreenKind.NONE && reopenAttemptsRemaining > 0) {
						reopenAttemptsRemaining--;
						ReachCraftingMod.LOGGER.info(
							"[nearby_restore] idx={} reopen_retry kind={} attempts_left={}",
							recipeIndex,
							originalContext.kind().name().toLowerCase(),
							reopenAttemptsRemaining
						);
						resumeOriginalContext();
						timeoutTicks = REOPEN_TIMEOUT_TICKS;
						return;
					}

					ReachCraftingMod.LOGGER.warn(
						"[nearby_restore] idx={} reopen_failed kind={} screen={} attempts_exhausted",
						recipeIndex,
						originalContext.kind().name().toLowerCase(),
						client.screen == null ? "<none>" : client.screen.getClass().getSimpleName()
					);
					tryFinishAfterResume();
				}
			}
		}

		@Override
		public void onContainerContentsInitialized(AbstractContainerMenu menu) {
			if (state != SearchState.WAITING_FOR_CONTAINER) {
				return;
			}
			if (client.screen instanceof InventoryScreen || client.screen instanceof CraftingScreen) {
				return;
			}
			if (menu.containerId == player.inventoryMenu.containerId) {
				return;
			}

			Map<String, Integer> allItems = ContainerUtils.collectAllItems(menu);
			NearbyContainerCache.recordObservedContents(level, pendingContainerPos, allItems);
			Map<String, Integer> usefulItems = collectUsefulItems(menu, scanAcceptedItemIds);
			if (!usefulItems.isEmpty()) {
				usefulItems.forEach((itemId, count) -> discoveredNearby.merge(itemId, count, Integer::sum));
				ReachCraftingMod.LOGGER.info(
					"[nearby_container] idx={} pos={} found={}",
					recipeIndex,
					ContainerUtils.formatPos(pendingContainerPos),
					AvailableItemSnapshot.formatCounts(usefulItems)
				);
			}

			scannedContainers++;
			if (phase == SearchPhase.WITHDRAW) {
				withdrawItemsFromContainer(menu);
			}
			player.closeContainer();
			pendingContainerPos = null;
			timeoutTicks = 0;
			state = SearchState.OPEN_NEXT;
		}

		@Override
		public void onOpenFailed(String reason) {
			ReachCraftingMod.LOGGER.info(
				"[nearby_container] idx={} pos={} skipped={}",
				recipeIndex,
				ContainerUtils.formatPos(pendingContainerPos),
				reason
			);
			pendingContainerPos = null;
			timeoutTicks = 0;
			state = SearchState.OPEN_NEXT;
		}

		private boolean openNextContainer() {
			Vec3 eyePos = cameraEntity.getEyePosition(0);
			while (nextCandidateIndex < candidates.size()) {
				BlockPos pos = candidates.get(nextCandidateIndex++);
				if (visited.contains(pos)) {
					continue;
				}

				BlockState blockState = level.getBlockState(pos);
				if (!InWorldFilterManager.isContainerActive(level, pos, blockState)) {
					continue;
				}
				if (!ContainerUtils.canAttemptOpen(level, pos, blockState)) {
					continue;
				}
				if (ContainerUtils.squaredDistanceToBlock(eyePos, pos) > Mth.square(player.blockInteractionRange())) {
					continue;
				}

				markVisited(pos);
				Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, pos);
				Direction face = Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
				BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);
				boolean wasSneaking = player.isShiftKeyDown() || (player.input != null && player.input.keyPresses != null && player.input.keyPresses.shift());
				withSuppressedSecondaryUse(() -> {
					if (wasSneaking) {
						sendShiftOverride(client, player, false);
						player.setShiftKeyDown(false);
					}
					gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
					if (wasSneaking) {
						player.setShiftKeyDown(true);
						sendShiftOverride(client, player, true);
					}
				});
				pendingContainerPos = pos;
				timeoutTicks = OPEN_TIMEOUT_TICKS;
				state = SearchState.WAITING_FOR_CONTAINER;
				return true;
			}

			return false;
		}

		private void withdrawItemsFromContainer(AbstractContainerMenu menu) {
			if (remainingItemIds.isEmpty()) {
				return;
			}

			Map<String, Integer> containerWithdrawnItems = new LinkedHashMap<>();
			for (Slot slot : menu.slots) {
				if (slot.container instanceof Inventory || !slot.hasItem()) {
					continue;
				}

				String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				if (findMatchingRemainingItemIndex(itemId) < 0 || !slot.mayPickup(player)) {
					continue;
				}

				while (slot.hasItem()) {
					int remainingItemIndex = findMatchingRemainingItemIndex(itemId);
					if (remainingItemIndex < 0) {
						break;
					}

					Slot targetSlot = findPlayerDestinationSlot(player, menu, itemId);
					if (targetSlot == null) {
						inventorySpaceBlocked = true;
						sendChat("No inventory space for " + itemId);
						return;
					}
					if (!withdrawOneItem(menu, slot, targetSlot)) {
						return;
					}

					remainingItemIds.remove(remainingItemIndex);
					withdrawnItems.merge(itemId, 1, Integer::sum);
					containerWithdrawnItems.merge(itemId, 1, Integer::sum);
				}
			}

			subtractCounts(discoveredNearby, containerWithdrawnItems);
			NearbyContainerCache.applyWithdrawals(level, pendingContainerPos, containerWithdrawnItems);
			if (!withdrawnItems.isEmpty()) {
				ReachCraftingMod.LOGGER.info(
					"[nearby_withdraw] idx={} pos={} total_withdrawn={} remaining={}",
					recipeIndex,
					ContainerUtils.formatPos(pendingContainerPos),
					AvailableItemSnapshot.formatCounts(withdrawnItems),
					summarizeRemainingItems(remainingItemIds)
				);
			}
		}

		private void markVisited(BlockPos pos) {
			visited.add(pos);
			ContainerUtils.getOtherHalfOfLargeChest(level, pos).ifPresent(visited::add);
		}

		private boolean withdrawOneItem(AbstractContainerMenu menu, Slot sourceSlot, Slot targetSlot) {
			ItemStack withdrawnStack = sourceSlot.getItem().copy();
			withdrawnStack.setCount(1);
			com.reachcrafting.client.PulledResourcesTracker.recordWithdrawal(pendingContainerPos, sourceSlot.getContainerSlot(), withdrawnStack);
			
			pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			if (player.containerMenu.getCarried().isEmpty()) {
				return false;
			}

			pickup(menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			
			boolean success = player.containerMenu.getCarried().isEmpty();
			if (success) {
				PulledResourcesTracker.recordWithdrawal(pendingContainerPos, sourceSlot.index, withdrawnStack);
			}
			return success;
		}

		private void beginResume() {
			if (!allowNearby && isOriginalContextReady()) {
				state = SearchState.WAITING_FOR_REOPEN;
				timeoutTicks = 0;
				return;
			}
			player.closeContainer();
			timeoutTicks = RESUME_DELAY_TICKS;
			restoreTicksRemaining = RESTORE_TIMEOUT_TICKS;
			reopenAttemptsRemaining = MAX_REOPEN_ATTEMPTS;
			state = SearchState.RESUME_CONTEXT;
		}

		private void beginWithdrawPhaseOrResume() {
			Map<String, Integer> inventoryCounts = currentInventoryCounts();
			Map<String, Integer> inventoryAndNearby = AvailableItemSnapshot.mergeCounts(inventoryCounts, discoveredNearby);
			boolean reservedGridMatchesCollection = originalContext.hasReservedGrid() && originalContext.reservedGridMatchesCollection(client.level, recipeCollection);
			boolean committedReservedGrid = originalContext.hasReservedGrid()
				&& (originalContext.reservedGridMatches(ingredientSummary) || reservedGridMatchesCollection);
			// Initial check to see if we can continue with the current layout at the requested count
			Map<String, Integer> initialMissing = committedReservedGrid ? subtractAvailableCounts(computeReservedGridNeededCounts(), inventoryAndNearby) : Map.of();
			boolean committedLayoutCanContinue = committedReservedGrid && initialMissing.isEmpty();
			boolean rotatingFamilyRedistribute = planningPolicy.redistributeToCraftWhenNeeded()
				&& reservedGridMatchesCollection
				&& recipeCollection != null
				&& recipeCollection.getRecipes().size() > 1;
			boolean allowReservedGridVariantSwitch = rotatingFamilyRedistribute
				|| (planningPolicy.redistributeToCraftWhenNeeded()
					&& reservedGridMatchesCollection
					&& (craftAll || !committedLayoutCanContinue));
			boolean redistributeReservedGrid = planningPolicy.redistributeToCraftWhenNeeded() && (craftAll || requestedSingleClicks > 1 || rotatingFamilyRedistribute);
			this.redistributeThisRun = redistributeReservedGrid;
			Map<String, Integer> gridCounts = countStacks(originalContext.gridStacks());
			Map<String, Integer> totalAvailable = redistributeReservedGrid
				? AvailableItemSnapshot.mergeCounts(inventoryAndNearby, gridCounts)
				: inventoryAndNearby;
			Map<String, Integer> planningInventoryCounts = redistributeReservedGrid 
				? AvailableItemSnapshot.mergeCounts(inventoryCounts, gridCounts) 
				: inventoryCounts;
			List<ItemStack> planningGridStacks = redistributeReservedGrid ? List.of() : originalContext.gridStacks();
			AvailableItemSnapshot resolverItems = redistributeReservedGrid
				? new AvailableItemSnapshot(Map.copyOf(totalAvailable), Map.of(), Map.copyOf(totalAvailable), List.of())
				: new AvailableItemSnapshot(
					Map.copyOf(inventoryCounts),
					localItems.gridCounts(),
					AvailableItemSnapshot.mergeCounts(inventoryCounts, localItems.gridCounts()),
					localItems.gridStacks()
				);
			int desiredVariantCopies = craftAll
				? 1
				: allowReservedGridVariantSwitch
					? currentReservedCraftCopies() + requestedSingleClicks
					: requestedSingleClicks;
			RecipeVariantResolver.Selection resolvedSelection = null;
			if (!redistributeReservedGrid && committedReservedGrid) {
				// Lock to current grid variant
				resolvedSelection = RecipeVariantResolver.resolveMatchForGrid(
					client,
					player,
					recipeCollection,
					originalContext.gridStacks(),
					resolverItems,
					totalAvailable,
					totalAvailable,
					craftAll,
					desiredVariantCopies
				);
				ReachCraftingMod.LOGGER.info("[recipe_variant] locked_to_grid_variant idx={} mode=locked_expansion match={}", recipeIndex, resolvedSelection != null);
			} else {
				resolvedSelection = RecipeVariantResolver.resolve(
					client,
					player,
					recipeId,
					recipeCollection,
					ItemStack.EMPTY,
					explicitVariantSelection,
					allowNearby,
					resolverItems,
					totalAvailable,
					totalAvailable,
					craftAll,
					allowReservedGridVariantSwitch,
					desiredVariantCopies
				);
			}

			if (resolvedSelection != null) {
				boolean variantChanged = !resolvedSelection.recipeId().equals(recipeId);
				if (variantChanged || (!redistributeReservedGrid && committedReservedGrid)) {
					ReachCraftingMod.LOGGER.info(
						"[recipe_variant] clicked_idx={} selected_idx={} mode={} output={}",
						recipeIndex,
						resolvedSelection.recipeId().index(),
						redistributeReservedGrid ? ReachCraftingConfig.get().revolvingCraftHandling().name().toLowerCase() : "locked_expansion",
						resolvedSelection.outputLabel()
					);
					recipeId = resolvedSelection.recipeId();
					outputLabel = resolvedSelection.outputLabel();
					ingredientSummary = resolvedSelection.ingredientSummary();
				}
			}
			int desiredTargetCopies = committedReservedGrid
				? currentReservedCraftCopies() + requestedSingleClicks
				: requestedSingleClicks;
			int redistributedMaxCopies = redistributeReservedGrid
				? IngredientPlanning.computeMaxCraftCopies(
					ingredientSummary,
					planningInventoryCounts,
					planningGridStacks,
					totalAvailable,
					totalAvailable,
					planningPolicy
				)
				: 0;
			int maxReachableCopies = IngredientPlanning.computeMaxCraftCopies(
				ingredientSummary,
				planningInventoryCounts,
				planningGridStacks,
				totalAvailable,
				totalAvailable,
				planningPolicy
			);
			targetCopiesPerSlot = craftAll
				? maxReachableCopies
				: redistributeReservedGrid
					? Math.min(desiredTargetCopies, redistributedMaxCopies)
					: Math.min(desiredTargetCopies, maxReachableCopies);
			IngredientPlanning.PlanResult plannedResult = IngredientPlanning.plan(
				ingredientSummary,
				planningInventoryCounts,
				planningGridStacks,
				totalAvailable,
				totalAvailable,
				targetCopiesPerSlot,
				planningPolicy
			);
			plannedTargets = plannedResult.slotTargets();
			remainingItemIds.clear();
			remainingItemIds.addAll(redistributeReservedGrid ? computeRedistributedFetchItemIds(plannedTargets) : computeFetchItemIds(plannedTargets));

			if (!planningPolicy.redistributeToCraftWhenNeeded() && committedReservedGrid) {
				Map<String, Integer> finalMissing = subtractAvailableCounts(computeReservedGridNeededCounts(), inventoryAndNearby);
				if (!finalMissing.isEmpty()) {
					blockedCommittedLayoutMissingSummary = AvailableItemSnapshot.formatCounts(finalMissing);
					remainingItemIds.clear();
					ReachCraftingMod.LOGGER.info(
						"[nearby_plan] idx={} committed_layout_blocked missing={}",
						recipeIndex,
						blockedCommittedLayoutMissingSummary
					);
					beginResume();
					return;
				}
			}

			ReachCraftingMod.LOGGER.info(
				"[nearby_plan] idx={} total_available={} target_copies={} planned={}",
				recipeIndex,
				AvailableItemSnapshot.formatCounts(totalAvailable),
				targetCopiesPerSlot,
				summarizeRemainingItems(remainingItemIds)
			);

			if (targetCopiesPerSlot <= 0 || plannedResult.hasMissingIngredients() || remainingItemIds.isEmpty()) {
				beginResume();
				return;
			}

			visited.clear();
			nextCandidateIndex = 0;
			pendingContainerPos = null;
			phase = SearchPhase.WITHDRAW;
			state = SearchState.OPEN_NEXT;
			sendChat("Fetching: " + summarizeRemainingItems(remainingItemIds));
		}

		private List<String> computeFetchItemIds(List<IngredientPlanning.SlotTarget> slotTargets) {
			if (originalContext.hasReservedGrid() && originalContext.reservedGridMatches(ingredientSummary)) {
				return computeReservedGridFetchItemIds();
			}

			Map<String, Integer> desiredCounts = new LinkedHashMap<>();
			Map<String, Integer> alreadyCovered = new LinkedHashMap<>(currentInventoryCounts());

			for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
				if (slotTarget.itemId() == null || slotTarget.targetCount() <= 0) {
					continue;
				}
				desiredCounts.merge(slotTarget.itemId(), slotTarget.targetCount(), Integer::sum);
			}

			List<ItemStack> reservedGrid = originalContext.gridStacks();
			for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
				int slotIndex = slotTarget.slotIndex() - 1;
				if (slotIndex < 0 || slotIndex >= reservedGrid.size()) {
					continue;
				}
				ItemStack stack = reservedGrid.get(slotIndex);
				if (stack.isEmpty() || slotTarget.itemId() == null) {
					continue;
				}
				String stackItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				if (slotTarget.itemId().equals(stackItemId)) {
					alreadyCovered.merge(stackItemId, stack.getCount(), Integer::sum);
				}
			}

			List<String> fetchItems = new ArrayList<>();
			for (Map.Entry<String, Integer> entry : desiredCounts.entrySet()) {
				String itemId = entry.getKey();
				int missing = entry.getValue() - alreadyCovered.getOrDefault(itemId, 0);
				for (int i = 0; i < Math.max(missing, 0); i++) {
					fetchItems.add(itemId);
				}
			}
			return fetchItems;
		}


		private List<String> computeReservedGridFetchItemIds() {
			Map<String, Integer> neededCounts = computeReservedGridNeededCounts();

			Map<String, Integer> availableInventory = new LinkedHashMap<>(currentInventoryCounts());
			List<String> fetchItems = new ArrayList<>();
			for (Map.Entry<String, Integer> entry : neededCounts.entrySet()) {
				String itemId = entry.getKey();
				int stillNeeded = entry.getValue();
				int inventoryCount = availableInventory.getOrDefault(itemId, 0);
				int coveredByInventory = Math.min(inventoryCount, stillNeeded);
				stillNeeded -= coveredByInventory;
				if (coveredByInventory > 0) {
					if (inventoryCount == coveredByInventory) {
						availableInventory.remove(itemId);
					} else {
						availableInventory.put(itemId, inventoryCount - coveredByInventory);
					}
				}

				for (int i = 0; i < stillNeeded; i++) {
					fetchItems.add(itemId);
				}
			}
			return fetchItems;
		}

		private List<String> computeRedistributedFetchItemIds(List<IngredientPlanning.SlotTarget> slotTargets) {
			Map<String, Integer> desiredCounts = new LinkedHashMap<>();
			for (IngredientPlanning.SlotTarget slotTarget : slotTargets) {
				if (slotTarget.itemId() == null || slotTarget.targetCount() <= 0) {
					continue;
				}
				desiredCounts.merge(slotTarget.itemId(), slotTarget.targetCount(), Integer::sum);
			}

			Map<String, Integer> movableCounts = AvailableItemSnapshot.mergeCounts(currentInventoryCounts(), countStacks(originalContext.gridStacks()));
			List<String> fetchItems = new ArrayList<>();
			for (Map.Entry<String, Integer> entry : desiredCounts.entrySet()) {
				String itemId = entry.getKey();
				int missing = entry.getValue() - movableCounts.getOrDefault(itemId, 0);
				for (int i = 0; i < Math.max(missing, 0); i++) {
					fetchItems.add(itemId);
				}
			}
			return fetchItems;
		}

		private Map<String, Integer> computeReservedGridNeededCounts() {
			Map<String, Integer> neededCounts = new LinkedHashMap<>();
			for (ItemStack stack : originalContext.gridStacks()) {
				if (stack.isEmpty()) {
					continue;
				}

				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				int currentCount = stack.getCount();
				int desiredCount = Math.min(stack.getMaxStackSize(), Math.max(targetCopiesPerSlot, currentCount));
				int missing = Math.max(desiredCount - currentCount, 0);
				if (missing > 0) {
					neededCounts.merge(itemId, missing, Integer::sum);
				}
			}
			return neededCounts;
		}

		private static Map<String, Integer> subtractAvailableCounts(Map<String, Integer> neededCounts, Map<String, Integer> availableCounts) {
			Map<String, Integer> remaining = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> entry : neededCounts.entrySet()) {
				int missing = entry.getValue() - availableCounts.getOrDefault(entry.getKey(), 0);
				if (missing > 0) {
					remaining.put(entry.getKey(), missing);
				}
			}
			return remaining;
		}

		private Map<String, Integer> currentInventoryCounts() {
			return AvailableItemSnapshot.mergeCounts(localItems.inventoryCounts(), withdrawnItems);
		}

		private static void subtractCounts(Map<String, Integer> counts, Map<String, Integer> subtract) {
			for (Map.Entry<String, Integer> entry : subtract.entrySet()) {
				int remaining = counts.getOrDefault(entry.getKey(), 0) - entry.getValue();
				if (remaining > 0) {
					counts.put(entry.getKey(), remaining);
				} else {
					counts.remove(entry.getKey());
				}
			}
		}


		private static Map<String, Integer> countStacks(List<ItemStack> stacks) {
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (ItemStack stack : stacks) {
				if (stack.isEmpty()) {
					continue;
				}
				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				counts.merge(itemId, stack.getCount(), Integer::sum);
			}
			return counts;
		}

		private int currentReservedCraftCopies() {
			return ContainerUtils.currentReservedCraftCopies(originalContext.gridStacks());
		}


		private void resumeOriginalContext() {
			super.resumeOriginalContext(originalContext);
		}

		private boolean isOriginalContextReady() {
			return super.isOriginalContextReady(originalContext);
		}

		private boolean tryFinishAfterResume() {
			if (!isOriginalContextReady()) {
				lastRestoreFailure = "context_not_ready";
				return false;
			}

			boolean gridRestored = false;
			restoreRecipeBookState();
			restoreMousePosition();
			gridRestored = redistributeThisRun || restoreReservedGrid();
			
			if (!gridRestored && originalContext.hasReservedGrid() && restoreTicksRemaining > 0) {
				restoreTicksRemaining--;
				ReachCraftingMod.LOGGER.info(
					"[nearby_restore] idx={} waiting_for_grid_restore remaining_ticks={}",
					recipeIndex,
					restoreTicksRemaining
				);
				return false;
			}

			boolean reservedGridMatchesRecipe = redistributeThisRun
				? originalContext.hasReservedGrid() && originalContext.reservedGridMatchesCollection(client.level, recipeCollection)
				: originalContext.reservedGridMatches(ingredientSummary);

			boolean reservedGridExpanded = false;
			if (gridRestored && reservedGridMatchesRecipe && targetCopiesPerSlot > 0) {
				reservedGridExpanded = redistributeThisRun
					? applyPlannedTargetsToGrid(originalOccupiedGridSlotIndices())
					: expandReservedGrid();
			}

			boolean placedPlannedGrid = false;
			if (!originalContext.hasReservedGrid()
				&& !craftAll
				&& requestedSingleClicks > 1
				&& targetCopiesPerSlot > 0
				&& !plannedTargets.isEmpty()) {
				PlacementAttempt seededPlacement = placePlannedGridWithVanillaShape();
				if (seededPlacement == PlacementAttempt.WAITING_FOR_SEED) {
					if (seedWaitTicksRemaining > 0) {
						seedWaitTicksRemaining--;
						ReachCraftingMod.LOGGER.info(
							"[nearby_restore] idx={} waiting_for_seeded_shape remaining_ticks={}",
							recipeIndex,
							seedWaitTicksRemaining
						);
						return false;
					}
					lastRestoreFailure = "vanilla_shape_seed_timeout";
				} else {
					placedPlannedGrid = seededPlacement == PlacementAttempt.SUCCESS;
				}
			}

			Map<String, Integer> updatedCounts = AvailableItemSnapshot.mergeCounts(localItems.inventoryCounts(), withdrawnItems);
			RecipeDeficitReport updatedDeficit = RecipeDeficitReport.from(
				ingredientSummary,
				updatedCounts,
				originalContext.gridStacks(),
				targetCopiesPerSlot
			);
			if (originalContext.hasReservedGrid()
				&& gridRestored
				&& reservedGridMatchesRecipe
				&& reservedGridExpanded
				&& remainingItemIds.isEmpty()
				&& !inventorySpaceBlocked) {
				updatedDeficit = new RecipeDeficitReport(
					Map.of(),
					Map.of(),
					List.of(),
					plannedTargets,
					"<none>",
					false
				);
			} else if (!originalContext.hasReservedGrid()
				&& placedPlannedGrid
				&& remainingItemIds.isEmpty()
				&& !inventorySpaceBlocked) {
				updatedDeficit = new RecipeDeficitReport(
					Map.of(),
					Map.of(),
					List.of(),
					plannedTargets,
					"<none>",
					false
				);
			}
			ReachCraftingMod.LOGGER.info(
				"[nearby_scan] idx={} scanned={} discovered={} withdrawn={} remaining={}",
				recipeIndex,
				scannedContainers,
				AvailableItemSnapshot.formatCounts(discoveredNearby),
				AvailableItemSnapshot.formatCounts(withdrawnItems),
				blockedCommittedLayoutMissingSummary != null ? blockedCommittedLayoutMissingSummary : updatedDeficit.compactMissingSummary()
			);

			if (originalContext.hasReservedGrid()) {
				if (blockedCommittedLayoutMissingSummary != null) {
					sendChat("Committed layout is out of materials: " + blockedCommittedLayoutMissingSummary);
				} else
				if (!gridRestored) {
					ReachCraftingMod.LOGGER.warn(
						"[nearby_restore] idx={} final_failure reason={}",
						recipeIndex,
						lastRestoreFailure
					);
					sendChat("Fetched items, but couldn't fully restore the crafting grid.");
				} else if (targetCopiesPerSlot > 0 && !updatedDeficit.hasMissingIngredients() && reservedGridMatchesRecipe && reservedGridExpanded) {
					sendChat("Updated grid: " + outputLabel);
				} else if (updatedDeficit.hasMissingIngredients()) {
					sendChat("Fetched what I could. Missing now: " + updatedDeficit.compactMissingSummary());
				} else {
					sendChat("Fetched ingredients for the next craft.");
				}
			} else if (placedPlannedGrid) {
				sendChat("Updated grid: " + outputLabel);
			} else if (targetCopiesPerSlot > 0 && !updatedDeficit.hasMissingIngredients() && player.containerMenu != null) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, recipeId, craftAll);
				sendChat("Placed recipe: " + outputLabel);
			} else if (!remainingItemIds.isEmpty() || inventorySpaceBlocked) {
				sendChat("Fetched what I could. Missing now: " + updatedDeficit.compactMissingSummary());
			} else if (updatedDeficit.hasMissingIngredients()) {
				sendChat("Items fetched. Remaining: " + updatedDeficit.compactMissingSummary());
			} else {
				sendChat("Ready to place: " + outputLabel);
			}

			NearbyContainerDryRun.armInteractionBlock();
			if (explicitVariantSelection) {
				RecipeBookClickCapture.tryCloseOverlayAfterRelease();
			}
			if (ReachCraftingConfig.get().autoCraftMode()) {
				ItemStack expectedStack = ItemStack.EMPTY;
				var knownRecipes = ((com.reachcrafting.client.mixin.ClientRecipeBookAccessor) player.getRecipeBook()).getKnown();
				var entry = knownRecipes.get(recipeId);
				if (entry != null) {
					expectedStack = RecipeVariantResolver.resolveDisplayStack(entry.display(), net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(level));
				}
				ContainerUtils.scheduleAutoMove(expectedStack);
			}
			stop(false);
			NearbyContainerDryRun.activeSession = null;
			RecipeBookClickCapture.refocusRecipeBookSearch(client);
			return true;
		}

		private void restoreRecipeBookState() {
			if (!(client.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
				return;
			}
			originalContext.recipeBookState().restore(recipeBookScreen);
		}

		private void restoreMousePosition() {
			if (client.mouseHandler.isMouseGrabbed()) {
				return;
			}

			double clampedX = Mth.clamp(originalContext.mouseX(), 0.0D, Math.max(0.0D, client.getWindow().getScreenWidth() - 1.0D));
			double clampedY = Mth.clamp(originalContext.mouseY(), 0.0D, Math.max(0.0D, client.getWindow().getScreenHeight() - 1.0D));
			client.mouseHandler.setIgnoreFirstMove();
			GLFW.glfwSetCursorPos(client.getWindow().handle(), clampedX, clampedY);
		}

		private boolean restoreReservedGrid() {
			if (!originalContext.hasReservedGrid()) {
				return true;
			}
			if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
				lastRestoreFailure = "screen_not_container";
				return false;
			}

			AbstractContainerMenu menu = containerScreen.getMenu();
			List<ItemStack> desiredGridStacks = originalContext.gridStacks();
			lastRestoreFailure = "<none>";
			for (int slotIndex = 1; slotIndex <= desiredGridStacks.size(); slotIndex++) {
				ItemStack desiredStack = desiredGridStacks.get(slotIndex - 1);
				if (desiredStack.isEmpty()) {
					continue;
				}
				if (!restoreGridSlot(menu, slotIndex, desiredStack)) {
					ReachCraftingMod.LOGGER.warn(
						"[nearby_restore] idx={} failed_to_restore grid_slot={} desired={} actual={} reason={}",
						recipeIndex,
						slotIndex,
						ContainerUtils.formatStack(desiredStack),
						ContainerUtils.formatStack(menu.getSlot(slotIndex).getItem()),
						lastRestoreFailure
					);
					return false;
				}
			}

			ReachCraftingMod.LOGGER.info("[nearby_restore] idx={} restored_grid={}", recipeIndex, originalContext.gridSummary());
			return true;
		}

		private boolean expandReservedGrid() {
			if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
				return false;
			}

			AbstractContainerMenu menu = containerScreen.getMenu();
			List<ItemStack> originalGridStacks = originalContext.gridStacks();
			for (int slotIndex = 1; slotIndex <= originalGridStacks.size(); slotIndex++) {
				ItemStack originalStack = originalGridStacks.get(slotIndex - 1);
				if (originalStack.isEmpty()) {
					continue;
				}

				Slot targetSlot = menu.getSlot(slotIndex);
				ItemStack currentStack = targetSlot.getItem();
				if (currentStack.isEmpty() || !ItemStack.isSameItemSameComponents(currentStack, originalStack)) {
					return false;
				}

				int targetCount = Math.min(currentStack.getMaxStackSize(), Math.max(targetCopiesPerSlot, currentStack.getCount()));
				if (currentStack.getCount() >= targetCount) {
					continue;
				}

				ItemStack desiredStack = currentStack.copy();
				desiredStack.setCount(targetCount);
				if (!restoreGridSlot(menu, slotIndex, desiredStack)) {
					ReachCraftingMod.LOGGER.warn(
						"[nearby_restore] idx={} failed_to_expand grid_slot={} from={} to={} actual={} reason={}",
						recipeIndex,
						slotIndex,
						ContainerUtils.formatStack(currentStack),
						ContainerUtils.formatStack(desiredStack),
						ContainerUtils.formatStack(menu.getSlot(slotIndex).getItem()),
						lastRestoreFailure
					);
					return false;
				}
			}

			ReachCraftingMod.LOGGER.info("[nearby_restore] idx={} expanded_grid={}", recipeIndex, summarizeGrid(menu, originalGridStacks.size()));
			return true;
		}

		private PlacementAttempt placePlannedGridWithVanillaShape() {
			if (targetCopiesPerSlot <= 0 || recipeId == null) {
				return PlacementAttempt.SUCCESS;
			}

			int totalFound = 0;
			for (int i = 0; i < targetCopiesPerSlot; i++) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, recipeId, false);
				totalFound++;
			}

			ReachCraftingMod.LOGGER.info("[nearby_restore] idx={} placed_via_vanilla_calls count={} target={}", recipeIndex, totalFound, targetCopiesPerSlot);
			return PlacementAttempt.SUCCESS;
		}



		private boolean applyPlannedTargetsToGrid(List<Integer> occupiedAnchorSlots) {
			if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
				return false;
			}

			AbstractContainerMenu menu = containerScreen.getMenu();
			// Clear the entire grid to ensure a clean slate for vanilla placement
			if (!clearGridForRedistribute(menu, Map.of())) {
				return false;
			}

			// Ensure cursor is empty before starting vanilla placement
			if (!player.containerMenu.getCarried().isEmpty()) {
				// Try to drop the item into the inventory
				for (Slot slot : menu.slots) {
					if (slot.container instanceof Inventory && !slot.hasItem()) {
						pickup(menu, slot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
						break;
					}
				}
				// If still not empty, we might need to wait or fail
				if (!player.containerMenu.getCarried().isEmpty()) {
					lastRestoreFailure = "cursor_not_empty_after_clear item=" + BuiltInRegistries.ITEM.getKey(player.containerMenu.getCarried().getItem());
					return false;
				}
			}

			return placePlannedGridWithVanillaShape() == PlacementAttempt.SUCCESS;
		}

		private List<Integer> originalOccupiedGridSlotIndices() {
			List<Integer> occupiedSlots = new ArrayList<>();
			List<ItemStack> gridStacks = originalContext.gridStacks();
			for (int index = 0; index < gridStacks.size(); index++) {
				if (!gridStacks.get(index).isEmpty()) {
					occupiedSlots.add(index + 1);
				}
			}
			return occupiedSlots;
		}

		private boolean clearGridForRedistribute(AbstractContainerMenu menu, Map<Integer, IngredientPlanning.SlotTarget> targetsBySlot) {
			for (int slotIndex = 1; slotIndex <= originalContext.gridStacks().size(); slotIndex++) {
				Slot slot = menu.getSlot(slotIndex);
				ItemStack current = slot.getItem();
				if (current.isEmpty()) {
					continue;
				}
				IngredientPlanning.SlotTarget desiredTarget = targetsBySlot.get(slotIndex);
				boolean mustClear = craftAll || desiredTarget == null || desiredTarget.itemId() == null;
				if (!mustClear) {
					String desiredItemId = desiredTarget.itemId();
					String currentItemId = BuiltInRegistries.ITEM.getKey(current.getItem()).toString();
					mustClear = !desiredItemId.equals(currentItemId) || current.getCount() > desiredTarget.targetCount();
				}
				if (mustClear && !moveGridSlotBackToInventory(menu, slot)) {
					lastRestoreFailure = "failed_to_clear_for_redistribute slot=" + slotIndex;
					return false;
				}
			}
			return true;
		}

		private enum PlacementAttempt {
			SUCCESS,
			WAITING_FOR_SEED,
			FAILURE
		}

		@Override
		public void stop(boolean closeContainer) {
			if (closeContainer && player.containerMenu != player.inventoryMenu) {
				player.closeContainer();
			}
		}


		private static Slot findPlayerDestinationSlot(LocalPlayer player, AbstractContainerMenu menu, String itemId) {
			Slot emptySlot = null;
			for (Slot slot : menu.slots) {
				if (!(slot.container instanceof Inventory)) {
					continue;
				}
				if (!slot.hasItem()) {
					if (emptySlot == null && slot.mayPlace(player.containerMenu.getCarried())) {
						emptySlot = slot;
					}
					continue;
				}

				ItemStack stack = slot.getItem();
				String slotItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				if (itemId.equals(slotItemId) && stack.getCount() < stack.getMaxStackSize()) {
					return slot;
				}
			}
			return emptySlot;
		}

		private void pickup(AbstractContainerMenu menu, Slot slot, int mouseButton) {
			gameMode.handleInventoryMouseClick(menu.containerId, slot.index, mouseButton, ClickType.PICKUP, player);
		}

		private boolean restoreGridSlot(AbstractContainerMenu menu, int targetSlotIndex, ItemStack desiredStack) {
			if (!player.containerMenu.getCarried().isEmpty()) {
				lastRestoreFailure = "carried_stack=" + ContainerUtils.formatStack(player.containerMenu.getCarried());
				return false;
			}

			Slot targetSlot = menu.getSlot(targetSlotIndex);
			ItemStack currentStack = targetSlot.getItem();
			if (!currentStack.isEmpty() && !ItemStack.isSameItemSameComponents(currentStack, desiredStack)) {
				if (!moveGridSlotBackToInventory(menu, targetSlot)) {
					lastRestoreFailure = "wrong_item_in_grid actual=" + ContainerUtils.formatStack(currentStack) + " desired=" + ContainerUtils.formatStack(desiredStack);
					return false;
				}
				currentStack = targetSlot.getItem();
			}

			int remaining = desiredStack.getCount() - currentStack.getCount();
			while (remaining > 0) {
				Slot sourceSlot = findMatchingInventorySourceSlot(menu, desiredStack);
				if (sourceSlot == null) {
					lastRestoreFailure = "missing_source_for=" + ContainerUtils.formatStack(desiredStack) + " remaining=" + remaining;
					return false;
				}

				int moved = moveExactCount(menu, sourceSlot, targetSlot, remaining);
				if (moved <= 0) {
					lastRestoreFailure = "move_failed source=" + ContainerUtils.formatStack(sourceSlot.getItem()) + " target=" + ContainerUtils.formatStack(targetSlot.getItem()) + " remaining=" + remaining;
					return false;
				}
				InventoryGridRestoreTracker.recordModMove(sourceSlot.index, targetSlot.index);
				remaining -= moved;
			}

			ItemStack restoredStack = targetSlot.getItem();
			boolean restored = ItemStack.isSameItemSameComponents(restoredStack, desiredStack) && restoredStack.getCount() >= desiredStack.getCount();
			if (!restored) {
				lastRestoreFailure = "post_check actual=" + ContainerUtils.formatStack(restoredStack) + " desired=" + ContainerUtils.formatStack(desiredStack);
			}
			return restored;
		}

		private boolean moveGridSlotBackToInventory(AbstractContainerMenu menu, Slot sourceGridSlot) {
			if (!sourceGridSlot.hasItem() || !sourceGridSlot.mayPickup(player)) {
				return false;
			}

			String itemId = BuiltInRegistries.ITEM.getKey(sourceGridSlot.getItem().getItem()).toString();
			pickup(menu, sourceGridSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			if (player.containerMenu.getCarried().isEmpty()) {
				lastRestoreFailure = "pickup_failed_when_clearing_grid item=" + itemId;
				return false;
			}

			while (!player.containerMenu.getCarried().isEmpty()) {
				Slot destinationSlot = findPlayerDestinationSlot(player, menu, itemId);
				if (destinationSlot == null) {
					// Inventory is full, drop it on the ground to clear the cursor
					gameMode.handleInventoryMouseClick(menu.containerId, -999, 0, ClickType.PICKUP, player);
					return true; // Cursor is now clear
				}
				int carriedBefore = player.containerMenu.getCarried().getCount();
				pickup(menu, destinationSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				int carriedAfter = player.containerMenu.getCarried().getCount();
				if (carriedAfter >= carriedBefore) {
					pickup(menu, sourceGridSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
					lastRestoreFailure = "inventory_reject_clear item=" + itemId + " destination=" + destinationSlot.index;
					return false;
				}
			}

			return true;
		}

		private String summarizeGrid(AbstractContainerMenu menu, int gridSlotCount) {
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (int slotIndex = 1; slotIndex <= gridSlotCount; slotIndex++) {
				ItemStack stack = menu.getSlot(slotIndex).getItem();
				if (stack.isEmpty()) {
					continue;
				}
				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				counts.merge(itemId, stack.getCount(), Integer::sum);
			}
			return AvailableItemSnapshot.formatCounts(counts);
		}

		private Slot findMatchingInventorySourceSlot(AbstractContainerMenu menu, ItemStack desiredStack) {
			for (Slot slot : menu.slots) {
				if (!(slot.container instanceof Inventory) || !slot.hasItem() || !slot.mayPickup(player)) {
					continue;
				}
				if (ItemStack.isSameItemSameComponents(slot.getItem(), desiredStack) && slot.mayPlace(desiredStack)) {
					return slot;
				}
			}
			return null;
		}

		private int moveExactCount(AbstractContainerMenu menu, Slot sourceSlot, Slot targetSlot, int remaining) {
			ItemStack sourceStack = sourceSlot.getItem();
			ItemStack targetStack = targetSlot.getItem();
			if (sourceStack.isEmpty()) {
				return 0;
			}

			int moveCount = Math.min(remaining, sourceStack.getCount());
			boolean canLeftClickMerge = targetStack.isEmpty() || (ItemStack.isSameItemSameComponents(sourceStack, targetStack) && targetStack.getCount() + moveCount <= targetStack.getMaxStackSize());

			if (canLeftClickMerge && moveCount == sourceStack.getCount()) {
				pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				pickup(menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				if (!player.containerMenu.getCarried().isEmpty()) {
					pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				}
				return moveCount;
			}

			// Fallback to manual placement if we need a partial stack or can't simple-merge
			pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			ItemStack carriedStack = player.containerMenu.getCarried();
			if (carriedStack.isEmpty()) {
				return 0;
			}

			int placed = 0;
			if (canLeftClickMerge && moveCount == carriedStack.getCount()) {
				pickup(menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				placed = moveCount;
			} else {
				while (placed < moveCount && !player.containerMenu.getCarried().isEmpty()) {
					pickup(menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
					placed++;
				}
			}

			if (!player.containerMenu.getCarried().isEmpty()) {
				Slot returnSlot = findMatchingInventorySourceSlot(menu, player.containerMenu.getCarried());
				if (returnSlot != null) {
					pickup(menu, returnSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				} else {
					// Drop or find any empty slot? Let's try to find an empty one
					for (Slot slot : menu.slots) {
						if (slot.container instanceof Inventory && !slot.hasItem() && slot.mayPlace(player.containerMenu.getCarried())) {
							pickup(menu, slot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
							break;
						}
					}
				}
			}
			return placed;
		}

		private int findMatchingRemainingItemIndex(String itemId) {
			for (int i = 0; i < remainingItemIds.size(); i++) {
				if (itemId.equals(remainingItemIds.get(i))) {
					return i;
				}
			}
			return -1;
		}

		private static List<BlockPos> findCandidates(Level level, Entity cameraEntity, double reachDistance) {
			Vec3 eyePos = cameraEntity.getEyePosition(0);
			int radius = Mth.ceil(reachDistance);
			BlockPos center = BlockPos.containing(eyePos);
			List<BlockPos> candidates = new ArrayList<>();

			for (BlockPos pos : BlockPos.betweenClosed(
				center.offset(-radius, -radius, -radius),
				center.offset(radius, radius, radius)
			)) {
				BlockState state = level.getBlockState(pos);
				if (InWorldFilterManager.isContainerActive(level, pos, state)) {
					candidates.add(pos.immutable());
				}
			}

			candidates.sort(Comparator.comparingDouble(pos -> ContainerUtils.squaredDistanceToBlock(eyePos, pos)));
			return candidates;
		}

		private static Map<String, Integer> collectUsefulItems(AbstractContainerMenu menu, Set<String> acceptedItemIds) {
			Map<String, Integer> usefulItems = new LinkedHashMap<>();
			for (Slot slot : menu.slots) {
				if (slot.container instanceof Inventory || !slot.hasItem()) {
					continue;
				}

				String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				if (acceptedItemIds.contains(itemId)) {
					usefulItems.merge(itemId, slot.getItem().getCount(), Integer::sum);
				}
			}
			return usefulItems;
		}


	}


	private static String summarizeRemainingItems(List<String> remainingItemIds) {
		if (remainingItemIds.isEmpty()) {
			return "<none>";
		}

		Map<String, Integer> aggregate = new LinkedHashMap<>();
		for (String itemId : remainingItemIds) {
			aggregate.merge(itemId, 1, Integer::sum);
		}
		return AvailableItemSnapshot.formatCounts(aggregate);
	}

	private record ScreenContext(
		ScreenKind kind,
		BlockPos craftingTablePos,
		RecipeBookState recipeBookState,
		List<ItemStack> gridStacks,
		double mouseX,
		double mouseY
	) {
		private static ScreenContext capture(Minecraft client, Entity cameraEntity, double reachDistance, AvailableItemSnapshot localItems) {
			if (client.screen instanceof CraftingScreen craftingScreen) {
				return new ScreenContext(
					ScreenKind.CRAFTING_TABLE_3X3,
					findNearestCraftingTable(client.level, cameraEntity, reachDistance),
					RecipeBookState.capture(craftingScreen),
					copyStacks(localItems.gridStacks()),
					client.mouseHandler.xpos(),
					client.mouseHandler.ypos()
				);
			}
			if (client.screen instanceof InventoryScreen inventoryScreen) {
				return new ScreenContext(
					ScreenKind.INVENTORY_2X2,
					null,
					RecipeBookState.capture(inventoryScreen),
					copyStacks(localItems.gridStacks()),
					client.mouseHandler.xpos(),
					client.mouseHandler.ypos()
				);
			}
			return new ScreenContext(ScreenKind.NONE, null, RecipeBookState.empty(), List.of(), client.mouseHandler.xpos(), client.mouseHandler.ypos());
		}

		private boolean hasReservedGrid() {
			return gridStacks.stream().anyMatch(stack -> !stack.isEmpty());
		}

		private ScreenContext withClearedGrid() {
			List<ItemStack> cleared = new ArrayList<>(gridStacks.size());
			for (int i = 0; i < gridStacks.size(); i++) {
				cleared.add(ItemStack.EMPTY);
			}
			return new ScreenContext(kind, craftingTablePos, recipeBookState, List.copyOf(cleared), mouseX, mouseY);
		}

		private boolean reservedGridMatches(RecipeIngredientSummary ingredientSummary) {
			List<RecipeIngredientSummary.IngredientSlot> ingredientSlots = ingredientSummary.slots();
			List<ItemStack> occupiedGridStacks = gridStacks.stream()
				.filter(stack -> !stack.isEmpty())
				.toList();
			List<RecipeIngredientSummary.IngredientSlot> nonEmptyIngredientSlots = ingredientSlots.stream()
				.filter(slot -> !slot.isEmpty())
				.toList();

			if (occupiedGridStacks.size() != nonEmptyIngredientSlots.size()) {
				return false;
			}

			if (ingredientSlots.size() == gridStacks.size()) {
				for (int i = 0; i < gridStacks.size(); i++) {
					ItemStack stack = gridStacks.get(i);
					RecipeIngredientSummary.IngredientSlot ingredientSlot = ingredientSlots.get(i);
					if (stack.isEmpty()) {
						if (!ingredientSlot.isEmpty()) {
							continue;
						}
						continue;
					}
					if (ingredientSlot.isEmpty()) {
						return false;
					}

					String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
					if (!ingredientSlot.itemIds().contains(itemId)) {
						return false;
					}
				}
				return true;
			}

			List<RecipeIngredientSummary.IngredientSlot> unmatchedSlots = new ArrayList<>(nonEmptyIngredientSlots);
			for (ItemStack stack : occupiedGridStacks) {
				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				boolean matched = false;
				Iterator<RecipeIngredientSummary.IngredientSlot> iterator = unmatchedSlots.iterator();
				while (iterator.hasNext()) {
					RecipeIngredientSummary.IngredientSlot slot = iterator.next();
					if (slot.itemIds().contains(itemId)) {
						iterator.remove();
						matched = true;
						break;
					}
				}

				if (!matched) {
					return false;
				}
			}

			return unmatchedSlots.isEmpty();
		}

		private boolean reservedGridMatchesCollection(Level level, RecipeCollection recipeCollection) {
			if (recipeCollection == null || level == null) {
				return false;
			}

			ContextMap context = SlotDisplayContext.fromLevel(level);
			for (var entry : recipeCollection.getRecipes()) {
				if (reservedGridMatches(RecipeIngredientSummary.fromDisplay(entry.display(), context))) {
					return true;
				}
			}
			return false;
		}

		private String gridSummary() {
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (ItemStack stack : gridStacks) {
				if (!stack.isEmpty()) {
					String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
					counts.merge(itemId, stack.getCount(), Integer::sum);
				}
			}
			return AvailableItemSnapshot.formatCounts(counts);
		}

		private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
			List<ItemStack> copies = new ArrayList<>(stacks.size());
			for (ItemStack stack : stacks) {
				copies.add(stack.copy());
			}
			return List.copyOf(copies);
		}

		private static BlockPos findNearestCraftingTable(Level level, Entity cameraEntity, double reachDistance) {
			if (level == null) {
				return null;
			}

			Vec3 eyePos = cameraEntity.getEyePosition(0);
			int radius = Mth.ceil(reachDistance);
			BlockPos center = BlockPos.containing(eyePos);
			BlockPos bestPos = null;
			double bestDistance = Double.MAX_VALUE;

			for (BlockPos pos : BlockPos.betweenClosed(
				center.offset(-radius, -radius, -radius),
				center.offset(radius, radius, radius)
			)) {
				if (!level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
					continue;
				}

				double distance = ContainerUtils.squaredDistanceToBlock(eyePos, pos);
				if (distance < bestDistance) {
					bestDistance = distance;
					bestPos = pos.immutable();
				}
			}

			return bestPos;
		}
	}

	private record RecipeBookState(
		boolean visible,
		boolean filtering,
		String searchText,
		boolean focused,
		ExtendedRecipeBookCategory selectedCategory,
		int currentPage,
		boolean overlayVisible,
		RecipeCollection overlayCollection
	) {
		private static RecipeBookState capture(AbstractRecipeBookScreen<?> screen) {
			RecipeBookComponent<?> component = ((AbstractRecipeBookScreenAccessor) screen).getRecipeBookComponent();
			RecipeBookComponentAccessor accessor = (RecipeBookComponentAccessor) component;
			RecipeBookTabButton selectedTab = accessor.getSelectedTab();
			CycleButton<Boolean> filterButton = accessor.getFilterButton();
			EditBox searchBox = accessor.getSearchBox();
			RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) accessor.getRecipeBookPage();
			OverlayRecipeComponent overlay = pageAccessor.getOverlay();
			String text = searchBox != null ? searchBox.getValue() : "";
			ReachCraftingMod.LOGGER.info("[nearby_capture] Captured search text: '{}' (focused={})", text, searchBox != null && searchBox.isFocused());
			return new RecipeBookState(
				component.isVisible(),
				filterButton != null && Boolean.TRUE.equals(filterButton.getValue()),
				text,
				searchBox != null && searchBox.isFocused(),
				selectedTab != null ? selectedTab.getCategory() : null,
				pageAccessor.getCurrentPage(),
				overlay != null && overlay.isVisible(),
				overlay != null ? overlay.getRecipeCollection() : null
			);
		}

		private static RecipeBookState empty() {
			return new RecipeBookState(false, false, "", false, null, 0, false, null);
		}

		private void restore(AbstractRecipeBookScreen<?> screen) {
			RecipeBookComponent<?> component = ((AbstractRecipeBookScreenAccessor) screen).getRecipeBookComponent();
			RecipeBookComponentAccessor accessor = (RecipeBookComponentAccessor) component;
			if (component.isVisible() != visible) {
				component.toggleVisibility();
			}

			CycleButton<Boolean> filterButton = accessor.getFilterButton();
			if (filterButton != null && Boolean.TRUE.equals(filterButton.getValue()) != filtering) {
				filterButton.setValue(filtering);
			}

			EditBox searchBox = accessor.getSearchBox();
			if (searchBox != null) {
				if (!searchText.equals(searchBox.getValue())) {
					ReachCraftingMod.LOGGER.info("[nearby_restore] Restoring search text: '{}' (was: '{}')", searchText, searchBox.getValue());
					searchBox.setValue(searchText);
				}
				if (focused) {
					searchBox.setFocused(true);
					searchBox.setCursorPosition(searchBox.getValue().length());
					searchBox.setHighlightPos(0);
				}
			}

			if (selectedCategory != null) {
				for (RecipeBookTabButton tabButton : accessor.getTabButtons()) {
					if (selectedCategory.equals(tabButton.getCategory())) {
						accessor.invokeReplaceSelected(tabButton);
						break;
					}
				}
			}

			component.recipesUpdated();

			RecipeBookPage page = accessor.getRecipeBookPage();
			RecipeBookPageAccessor pageAccessor = (RecipeBookPageAccessor) page;
			int totalPages = Math.max(1, pageAccessor.getTotalPages());
			pageAccessor.setCurrentPage(Mth.clamp(currentPage, 0, totalPages - 1));
			pageAccessor.invokeUpdateButtonsForPage();
			pageAccessor.invokeUpdateArrowButtons();

			if (overlayVisible && overlayCollection != null) {
				restoreOverlay(accessor, pageAccessor);
			}
		}

		private void restoreOverlay(RecipeBookComponentAccessor accessor, RecipeBookPageAccessor pageAccessor) {
			RecipeButton matchingButton = null;
			for (RecipeButton button : pageAccessor.getButtons()) {
				if (button.getCollection() == overlayCollection || button.getCollection().equals(overlayCollection)) {
					matchingButton = button;
					break;
				}
			}
			if (matchingButton == null) {
				return;
			}

			OverlayRecipeComponent overlay = pageAccessor.getOverlay();
			Minecraft minecraft = accessor.getMinecraft();
			if (overlay == null || minecraft == null || minecraft.level == null) {
				return;
			}

			ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
			int left = accessor.invokeGetXOrigin();
			int top = accessor.invokeGetYOrigin();
			int width = accessor.getWidth();
			int height = accessor.getHeight();
			overlay.init(
				overlayCollection,
				context,
				pageAccessor.getIsFiltering(),
				matchingButton.getX(),
				matchingButton.getY(),
				left + width / 2,
				top + 13 + height / 2,
				matchingButton.getWidth()
			);
		}
	}

	private enum ScreenKind {
		NONE,
		INVENTORY_2X2,
		CRAFTING_TABLE_3X3
	}

	private enum SearchState {
		OPEN_NEXT,
		WAITING_FOR_CONTAINER,
		RESUME_CONTEXT,
		WAITING_FOR_REOPEN
	}

	private enum SearchPhase {
		DISCOVERY,
		WITHDRAW
	}


	private static final class ReturnSession extends BaseSession {
		private final List<PulledResourcesTracker.WithdrawnItem> itemsToReturn;
		private final List<BlockPos> uniquePositions;
		private final AbstractContainerMenu closingMenu;
		private final ScreenContext originalContext;
		private final boolean reopenScreen;
		private int nextPositionIndex = 0;
		private int timeoutTicks = 0;
		private BlockPos pendingContainerPos;
		private SearchState state = SearchState.OPEN_NEXT;
		private final Map<String, Integer> currentExcess = new HashMap<>();

		private ReturnSession(
			Minecraft client,
			LocalPlayer player,
			Level level,
			MultiPlayerGameMode gameMode,
			Entity cameraEntity,
			AbstractContainerMenu closingMenu,
			List<PulledResourcesTracker.WithdrawnItem> itemsToReturn,
			ScreenContext originalContext,
			boolean reopenScreen
		) {
			super(client, player, level, gameMode, cameraEntity);
			this.closingMenu = closingMenu;
			this.itemsToReturn = new ArrayList<>(itemsToReturn);
			this.originalContext = originalContext;
			this.reopenScreen = reopenScreen;
			LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
			for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
				positions.add(item.containerPos());
			}
			this.uniquePositions = new ArrayList<>(positions);
		}

		@Override
		public void start() {
			com.reachcrafting.ReachCraftingMod.LOGGER.info("[nearby_return] starting return session with menu {} ({} slots)", closingMenu.getClass().getSimpleName(), closingMenu.slots.size());
			sendChat("Returning items to containers...");
			
			Map<String, Integer> currentCounts = new HashMap<>();
			for (Slot slot : closingMenu.slots) {
				// Skip result slots (slot 0 in both 2x2 and 3x3)
				if (slot.index == 0 && (closingMenu instanceof net.minecraft.world.inventory.CraftingMenu || closingMenu instanceof net.minecraft.world.inventory.InventoryMenu)) {
					continue;
				}
				if (slot.hasItem()) {
					String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
					currentCounts.merge(itemId, slot.getItem().getCount(), Integer::sum);
				}
			}

			Set<String> trackedItemIds = new HashSet<>();
			for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
				trackedItemIds.add(BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString());
			}

			for (String itemId : trackedItemIds) {
				int current = currentCounts.getOrDefault(itemId, 0);
				int excess = PulledResourcesTracker.getExcessCount(itemId, current);
				
				com.reachcrafting.ReachCraftingMod.LOGGER.info("[nearby_return] item={} current={} excess={}", itemId, current, excess);
				
				if (excess > 0) {
					currentExcess.put(itemId, excess);
				}
			}
		}

		@Override
		public void tick() {
			if (client.player != player || client.level != level) {
				stop(false);
				NearbyContainerDryRun.activeSession = null;
				return;
			}

			if (state == SearchState.OPEN_NEXT) {
				if (!openNextContainer()) {
					if (reopenScreen && originalContext.kind() != ScreenKind.NONE) {
						state = SearchState.RESUME_CONTEXT;
						timeoutTicks = 2; // Short delay before re-opening
					} else {
						stop(false);
						NearbyContainerDryRun.activeSession = null;
					}
				}
				return;
			}

			if (state == SearchState.RESUME_CONTEXT) {
				timeoutTicks--;
				if (timeoutTicks <= 0) {
					resumeOriginalContext();
					stop(false);
					NearbyContainerDryRun.activeSession = null;
				}
				return;
			}

			if (state == SearchState.WAITING_FOR_CONTAINER) {
				timeoutTicks--;
				if (timeoutTicks <= 0) {
					onOpenFailed("timeout");
				}
				return;
			}
		}

		@Override
		public void onOpenFailed(String reason) {
			ReachCraftingMod.LOGGER.info("[nearby_return] pos={} skipped={}", ContainerUtils.formatPos(pendingContainerPos), reason);
			pendingContainerPos = null;
			timeoutTicks = 0;
			state = SearchState.OPEN_NEXT;
		}

		private boolean openNextContainer() {
			Vec3 eyePos = cameraEntity.getEyePosition(0);
			while (nextPositionIndex < uniquePositions.size()) {
				BlockPos pos = uniquePositions.get(nextPositionIndex++);
				BlockState blockState = level.getBlockState(pos);
				if (!InWorldFilterManager.isContainerActive(level, pos, blockState)) continue;
				if (!ContainerUtils.canAttemptOpen(level, pos, blockState)) continue;
				if (ContainerUtils.squaredDistanceToBlock(eyePos, pos) > net.minecraft.util.Mth.square(player.blockInteractionRange())) continue;

				Vec3 hitPos = ContainerUtils.closestPointOnUnitBlock(eyePos, pos);
				Direction face = Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
				BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);
				
				withSuppressedSecondaryUse(() -> {
					gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
				});
				
				pendingContainerPos = pos;
				timeoutTicks = 40;
				state = SearchState.WAITING_FOR_CONTAINER;
				return true;
			}
			return false;
		}

		@Override
		public void onContainerContentsInitialized(AbstractContainerMenu menu) {
			if (state != SearchState.WAITING_FOR_CONTAINER) return;

			if (client.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen || client.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen) {
				return;
			}
			if (menu.containerId == player.inventoryMenu.containerId) {
				return;
			}


			ReachCraftingMod.LOGGER.info("[nearby_return] Container opened at {}. Preparing return plan...", ContainerUtils.formatPos(pendingContainerPos));

			// 1. Consolidate what this specific container originally provided
			Map<String, Integer> providedByThisChest = new LinkedHashMap<>();
			for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
				if (item.containerPos().equals(pendingContainerPos)) {
					String itemId = BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString();
					providedByThisChest.merge(itemId, item.stack().getCount(), Integer::sum);
				}
			}

			// 2. Map out the current occupancy of ALL slots in the container by index
			Map<Integer, Integer> simulatedOccupancy = new HashMap<>();
			for (Slot slot : menu.slots) {
				if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) {
					simulatedOccupancy.put(slot.index, slot.hasItem() ? slot.getItem().getCount() : 0);
				}
			}

			// 3. Build the movement plan
			List<PlannedMove> plan = new ArrayList<>();
			for (Map.Entry<String, Integer> entry : providedByThisChest.entrySet()) {
				String itemId = entry.getKey();
				int amountToReturnToThisChest = Math.min(entry.getValue(), currentExcess.getOrDefault(itemId, 0));
				
				if (amountToReturnToThisChest <= 0) {
					ReachCraftingMod.LOGGER.info("[nearby_return]   Skipping {}: no excess available to return", itemId);
					continue;
				}

				int remaining = amountToReturnToThisChest;
				ReachCraftingMod.LOGGER.info("[nearby_return]   Planning {}x {} for this container...", remaining, itemId);
				
				while (remaining > 0) {
					Slot inventorySlot = findAvailableInventorySlot(menu, itemId, plan);
					if (inventorySlot == null) {
						ReachCraftingMod.LOGGER.warn("[nearby_return]   Plan break: no more {} in inventory", itemId);
						break;
					}

					int availableInInvSlot = getAvailableCountInSlot(inventorySlot, plan);
					Slot targetSlot = findBestTargetSlot(menu, itemId, inventorySlot.getItem(), simulatedOccupancy);
					
					if (targetSlot == null) {
						ReachCraftingMod.LOGGER.warn("[nearby_return]   Plan break: no more space for {} in container", itemId);
						break;
					}

					int currentInChest = simulatedOccupancy.get(targetSlot.index);
					int max = Math.min(targetSlot.getMaxStackSize(), inventorySlot.getItem().getMaxStackSize());
					int canAccept = max - currentInChest;
					
					int amountToMove = Math.min(Math.min(availableInInvSlot, remaining), canAccept);
					if (amountToMove <= 0) {
						ReachCraftingMod.LOGGER.warn("[nearby_return]   Plan break: target slot {} cannot accept more ({} / {})", targetSlot.index, currentInChest, max);
						break;
					}

					plan.add(new PlannedMove(inventorySlot, targetSlot, amountToMove));
					simulatedOccupancy.put(targetSlot.index, currentInChest + amountToMove);
					remaining -= amountToMove;
					currentExcess.put(itemId, currentExcess.get(itemId) - amountToMove);
					ReachCraftingMod.LOGGER.info("[nearby_return]   Added to plan: {}x from inv {} to chest {}", amountToMove, inventorySlot.index, targetSlot.index);
				}
			}

			// 4. Execute the plan
			ReachCraftingMod.LOGGER.info("[nearby_return] Plan complete. Executing {} moves.", plan.size());
			for (PlannedMove move : plan) {
				String itemId = BuiltInRegistries.ITEM.getKey(move.source.getItem().getItem()).toString();
				ReachCraftingMod.LOGGER.info("[nearby_return]   Executing: {}x {} (slot {} -> {})", move.count, itemId, move.source.index, move.target.index);
				moveItem(menu, move.source, move.target, move.count);
			}
			if (ReachCraftingConfig.get().restoreInventoryItemPositions()) {
				restoreProtectedInventoryLayout(menu);
			}

			player.closeContainer();
			pendingContainerPos = null;
			timeoutTicks = 0;
			state = SearchState.OPEN_NEXT;
		}

		private record PlannedMove(Slot source, Slot target, int count) {}

		private void resumeOriginalContext() {
			super.resumeOriginalContext(originalContext);
		}

		private int getAvailableCountInSlot(Slot slot, List<PlannedMove> plan) {
			int planned = 0;
			for (PlannedMove p : plan) if (p.source == slot) planned += p.count;
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
				return 0;
			}

			int protectedCount = PulledResourcesTracker.getProtectedSlotCount(slot.getContainerSlot(), slot.getItem());
			int returnableCount = Math.max(0, slot.getItem().getCount() - protectedCount);
			return Math.max(0, returnableCount - planned);
		}

		private Slot findAvailableInventorySlot(AbstractContainerMenu menu, String itemId, List<PlannedMove> plan) {
			Slot bestSlot = null;
			int bestAvailable = 0;
			int bestProtectedCount = Integer.MAX_VALUE;
			for (Slot slot : menu.slots) {
				if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.hasItem()) {
					String id = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
					if (!id.equals(itemId)) {
						continue;
					}

					int available = getAvailableCountInSlot(slot, plan);
					if (available <= 0) {
						continue;
					}

					int protectedCount = PulledResourcesTracker.getProtectedSlotCount(slot.getContainerSlot(), slot.getItem());
					if (bestSlot == null
						|| protectedCount < bestProtectedCount
						|| (protectedCount == bestProtectedCount && available > bestAvailable)) {
						bestSlot = slot;
						bestAvailable = available;
						bestProtectedCount = protectedCount;
					}
				}
			}
			return bestSlot;
		}

		private Slot findBestTargetSlot(AbstractContainerMenu menu, String itemId, ItemStack stack, Map<Integer, Integer> simulatedOccupancy) {
			// A. Try original slots first
			for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
				if (item.containerPos().equals(pendingContainerPos)) {
					String id = BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString();
					if (id.equals(itemId)) {
						Slot slot = menu.getSlot(item.slotIndex());
						if (canAcceptMore(slot, stack, simulatedOccupancy)) return slot;
					}
				}
			}
			
			// B. Try any matching slot
			for (Slot slot : menu.slots) {
				if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
				if (slot.hasItem() && canAcceptMore(slot, stack, simulatedOccupancy)) {
					String id = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
					if (id.equals(itemId)) return slot;
				}
			}
			
			// C. Fallback to empty slot
			for (Slot slot : menu.slots) {
				if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
				if (simulatedOccupancy.get(slot.index) == 0 && slot.mayPlace(stack)) return slot;
			}
			
			return null;
		}

		private void restoreProtectedInventoryLayout(AbstractContainerMenu menu) {
			Map<Integer, ItemStack> snapshots = PulledResourcesTracker.getInitialSlotSnapshots();
			if (snapshots.isEmpty()) {
				return;
			}

			for (Map.Entry<Integer, ItemStack> entry : snapshots.entrySet()) {
				int inventorySlot = entry.getKey();
				ItemStack snapshot = entry.getValue();
				if (snapshot.isEmpty()) {
					continue;
				}

				Slot targetSlot = findInventoryMenuSlot(menu, inventorySlot);
				if (targetSlot == null) {
					continue;
				}

				ItemStack current = targetSlot.getItem();
				if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, snapshot)) {
					continue;
				}

				int currentCount = current.isEmpty() ? 0 : current.getCount();
				int missing = snapshot.getCount() - currentCount;
				if (missing <= 0) {
					continue;
				}

				while (missing > 0) {
					Slot donor = findProtectedLayoutDonor(menu, inventorySlot, snapshot, missing);
					if (donor == null) {
						ReachCraftingMod.LOGGER.info(
							"[nearby_return]   Layout restore stalled for invSlot {}: missing {}x {}",
							inventorySlot,
							missing,
							BuiltInRegistries.ITEM.getKey(snapshot.getItem())
						);
						break;
					}

					int donorProtected = PulledResourcesTracker.getProtectedSlotCount(donor.getContainerSlot(), donor.getItem());
					int donorAvailable = donor.getItem().getCount() - donorProtected;
					int toMove = Math.min(missing, donorAvailable);
					if (toMove <= 0) {
						break;
					}

					ReachCraftingMod.LOGGER.info(
						"[nearby_return]   Restoring layout: {}x {} from inv {} to inv {}",
						toMove,
						BuiltInRegistries.ITEM.getKey(snapshot.getItem()),
						donor.getContainerSlot(),
						inventorySlot
					);
					moveItem(menu, donor, targetSlot, toMove);
					missing -= toMove;
				}
			}
		}

		private Slot findInventoryMenuSlot(AbstractContainerMenu menu, int inventorySlot) {
			for (Slot slot : menu.slots) {
				if (slot.container instanceof Inventory && slot.getContainerSlot() == inventorySlot) {
					return slot;
				}
			}
			return null;
		}

		private Slot findProtectedLayoutDonor(AbstractContainerMenu menu, int excludedInventorySlot, ItemStack desiredStack, int needed) {
			Slot bestSlot = null;
			int bestAvailable = 0;
			for (Slot slot : menu.slots) {
				if (!(slot.container instanceof Inventory) || !slot.hasItem()) {
					continue;
				}
				if (slot.getContainerSlot() == excludedInventorySlot) {
					continue;
				}
				if (!ItemStack.isSameItemSameComponents(slot.getItem(), desiredStack)) {
					continue;
				}

				int protectedCount = PulledResourcesTracker.getProtectedSlotCount(slot.getContainerSlot(), slot.getItem());
				int available = slot.getItem().getCount() - protectedCount;
				if (available <= 0) {
					continue;
				}
				if (bestSlot == null || available < bestAvailable || (available == bestAvailable && available >= needed)) {
					bestSlot = slot;
					bestAvailable = available;
				}
			}
			return bestSlot;
		}

		private boolean canAcceptMore(Slot slot, ItemStack stack, Map<Integer, Integer> simulatedOccupancy) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory) return false;
			if (!slot.mayPlace(stack)) return false;
			
			int count = simulatedOccupancy.getOrDefault(slot.index, 0);
			int max = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
			if (count == 0) return true;
			
			ItemStack current = slot.hasItem() ? slot.getItem() : ItemStack.EMPTY;
			boolean same = current.isEmpty() || ItemStack.isSameItemSameComponents(current, stack);
			return same && count < max;
		}



		private void moveItem(AbstractContainerMenu menu, Slot source, Slot target, int count) {
			ItemStack sourceStack = source.getItem();
			int sourceCount = sourceStack.getCount();
			
			if (count <= 0) return;

			ItemStack targetStack = target.getItem();
			int maxTargetCount = Math.min(target.getMaxStackSize(), sourceStack.getMaxStackSize());
			int roomInTarget;
			if (targetStack.isEmpty()) {
				roomInTarget = maxTargetCount;
			} else if (ItemStack.isSameItemSameComponents(targetStack, sourceStack)) {
				roomInTarget = maxTargetCount - targetStack.getCount();
			} else {
				roomInTarget = 0;
			}
			
			if (count == sourceCount) {
				// Move full stack: 2 clicks
				gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
				gameMode.handleInventoryMouseClick(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
			} else if (count == (sourceCount + 1) / 2 && roomInTarget >= count) {
				// Move exactly half: 2 clicks (right click picks up half rounded up)
				gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
				gameMode.handleInventoryMouseClick(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
			} else if (roomInTarget >= count && (sourceCount - count) > 0 && (sourceCount - count) < count) {
				// Cheaper exact partial move: leave the smaller remainder behind, then move the kept cursor stack in one click.
				gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
				for (int i = 0; i < sourceCount - count; i++) {
					gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
				}
				gameMode.handleInventoryMouseClick(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
				if (!player.containerMenu.getCarried().isEmpty()) {
					gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
				}
			} else {
				// Generic move: Pickup all, drop N, put back rest
				gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
				for (int i = 0; i < count; i++) {
					gameMode.handleInventoryMouseClick(menu.containerId, target.index, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
				}
				if (!player.containerMenu.getCarried().isEmpty()) {
					gameMode.handleInventoryMouseClick(menu.containerId, source.index, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
				}
			}
		}

		@Override
		public void stop(boolean closeContainer) {
			if (closeContainer) {
				player.closeContainer();
			}

			if (!ReachCraftingConfig.get().restoreInventoryItemPositions()) {
				Set<String> trackedItemIds = new LinkedHashSet<>();
				for (PulledResourcesTracker.WithdrawnItem item : itemsToReturn) {
					trackedItemIds.add(BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString());
				}
				if (reopenScreen) {
					pendingPostReturnCompactionItemIds = Set.copyOf(trackedItemIds);
					ReachCraftingMod.LOGGER.info("[nearby_return] Deferred compaction queued for {}", trackedItemIds);
				} else {
					InventoryGridRestoreTracker.compactTrackedInventoryStacks(player.containerMenu, gameMode, trackedItemIds);
					ReachCraftingMod.LOGGER.info(
						"[nearby_return] Post-stop tracked slots={}",
						AvailableItemSnapshot.formatInventorySlots(player, trackedItemIds)
					);
				}
			}

			Map<String, Integer> inventoryCounts = new LinkedHashMap<>();
			for (Slot slot : player.inventoryMenu.slots) {
				if (slot.hasItem()) {
					String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
					inventoryCounts.merge(itemId, slot.getItem().getCount(), Integer::sum);
				}
			}
			ReachCraftingMod.LOGGER.info(
				"[nearby_return] Post-stop inventory summary={} reopen_screen={}",
				AvailableItemSnapshot.formatCounts(inventoryCounts),
				reopenScreen
			);
			
			// Log final inventory state for debugging
			ReachCraftingMod.LOGGER.info("[nearby_return] Session stopped. Final inventory state:");
			for (Slot slot : player.inventoryMenu.slots) {
				if (slot.hasItem()) {
					ReachCraftingMod.LOGGER.info("  slot {}: {}x {}", slot.index, slot.getItem().getCount(), BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()));
				}
			}
			
			PulledResourcesTracker.clear();
		}
	}

}
