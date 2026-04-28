package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.Container;
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
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class NearbyContainerDryRun {
	private static SearchSession activeSession;
	private static int interactionBlockTicks;
	private static boolean suppressSecondaryUse;

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
		int requestedSingleClicks
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
			requestedSingleClicks
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
		int requestedSingleClicks
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
			requestedSingleClicks
		);
		return session.expandReservedGridInPlace();
	}

	public static void cancelCurrent() {
		if (activeSession != null) {
			activeSession.stop(true);
			activeSession = null;
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
			activeSession.onContainerContentsInitialized(menu);
		}
	}

	private static final class SearchSession {
		private static final int OPEN_TIMEOUT_TICKS = 40;
		private static final int RESUME_DELAY_TICKS = 2;
		private static final int REOPEN_TIMEOUT_TICKS = 20;
		private static final int RESTORE_TIMEOUT_TICKS = 10;
		private static final int SEED_WAIT_TICKS = 20;
		private static final int MAX_REOPEN_ATTEMPTS = 3;

		private final Minecraft client;
		private final LocalPlayer player;
		private final Level level;
		private final MultiPlayerGameMode gameMode;
		private final Entity cameraEntity;
		private final RecipeCollection recipeCollection;
		private final boolean explicitVariantSelection;
		private final int recipeIndex;
		private final RecipeDisplayId originalRecipeId;
		private final String originalOutputLabel;
		private final RecipeIngredientSummary originalIngredientSummary;
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
		private final Map<String, Integer> cachedNearbyCounts;
		private final List<BlockPos> candidates;
		private final Set<BlockPos> visited = new HashSet<>();
		private final Map<String, Integer> discoveredNearby = new LinkedHashMap<>();
		private final Map<String, Integer> withdrawnItems = new LinkedHashMap<>();
		private List<IngredientPlanning.SlotTarget> plannedTargets = List.of();

		private int nextCandidateIndex;
		private int timeoutTicks;
		private int restoreTicksRemaining;
		private int reopenAttemptsRemaining;
		private int scannedContainers;
		private int targetCopiesPerSlot;
		private BlockPos pendingContainerPos;
		private boolean inventorySpaceBlocked;
		private boolean redistributeThisRun;
		private boolean usedCachedFastPath;
		private boolean attemptedLiveDiscoveryFallback;
		private boolean seededVanillaBatchLayout;
		private int seedWaitTicksRemaining;
		private String blockedCommittedLayoutMissingSummary;
		private String lastRestoreFailure = "<none>";
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
			int requestedSingleClicks
		) {
			this.client = client;
			this.player = player;
			this.level = level;
			this.gameMode = gameMode;
			this.cameraEntity = cameraEntity;
			this.recipeCollection = recipeCollection;
			this.explicitVariantSelection = explicitVariantSelection;
			this.originalRecipeId = recipeId;
			this.originalOutputLabel = outputLabel;
			this.originalIngredientSummary = ingredientSummary;
			this.recipeId = recipeId;
			this.recipeIndex = recipeIndex;
			this.outputLabel = outputLabel;
			this.ingredientSummary = ingredientSummary;
			this.localItems = localItems;
			this.craftAll = craftAll;
			this.requestedSingleClicks = Math.max(requestedSingleClicks, 1);
			this.originalContext = ScreenContext.capture(client, cameraEntity, player.blockInteractionRange(), localItems);
			this.planningPolicy = ReachCraftingConfig.get().toPlanningPolicy();
			this.scanAcceptedItemIds = computeScanAcceptedItemIds(recipeCollection, ingredientSummary, explicitVariantSelection, originalContext.hasReservedGrid());
			this.initialDeficit = RecipeDeficitReport.from(
				ingredientSummary,
				localItems.inventoryCounts(),
				originalContext.gridStacks(),
				craftAll
			);
			this.remainingItemIds = new ArrayList<>(initialDeficit.missingItemIds());
			this.cachedReachableView = NearbyContainerCache.getReachableView(level, cameraEntity, player.blockInteractionRange());
			this.cachedNearbyCounts = cachedReachableView.countsFor(scanAcceptedItemIds);
			this.candidates = NearbyContainerCache.prioritizeCandidates(
				findCandidates(level, cameraEntity, player.blockInteractionRange()),
				cachedReachableView,
				scanAcceptedItemIds
			);
			this.targetCopiesPerSlot = craftAll ? 0 : this.requestedSingleClicks;
			this.redistributeThisRun = false;
			this.usedCachedFastPath = false;
			this.attemptedLiveDiscoveryFallback = false;
			this.seededVanillaBatchLayout = false;
			this.seedWaitTicksRemaining = SEED_WAIT_TICKS;
			this.blockedCommittedLayoutMissingSummary = null;
			this.phase = SearchPhase.DISCOVERY;
		}

		private boolean canStart() {
			return !player.isSpectator() && !player.isShiftKeyDown() && !player.isHandsBusy() && player.containerMenu.getCarried().isEmpty();
		}

		private void start() {
			ReachCraftingMod.LOGGER.info(
				"[nearby_scan] idx={} start missing={} candidates={} craft_all={} phase={} planned={}",
				recipeIndex,
				initialDeficit.compactMissingSummary(),
				candidates.size(),
				craftAll,
				phase.name().toLowerCase(),
				summarizeRemainingItems(remainingItemIds)
			);

			sendChat("Scanning nearby containers...");
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

		private void tick() {
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

		private void onContainerContentsInitialized(AbstractContainerMenu menu) {
			if (state != SearchState.WAITING_FOR_CONTAINER) {
				return;
			}
			if (client.screen instanceof InventoryScreen || client.screen instanceof CraftingScreen) {
				return;
			}
			if (menu.containerId == player.inventoryMenu.containerId) {
				return;
			}

			Map<String, Integer> allItems = collectAllItems(menu);
			NearbyContainerCache.recordObservedContents(level, pendingContainerPos, allItems);
			Map<String, Integer> usefulItems = collectUsefulItems(menu, scanAcceptedItemIds);
			if (!usefulItems.isEmpty()) {
				usefulItems.forEach((itemId, count) -> discoveredNearby.merge(itemId, count, Integer::sum));
				ReachCraftingMod.LOGGER.info(
					"[nearby_container] idx={} pos={} found={}",
					recipeIndex,
					formatPos(pendingContainerPos),
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

		private void onOpenFailed(String reason) {
			ReachCraftingMod.LOGGER.info(
				"[nearby_container] idx={} pos={} skipped={}",
				recipeIndex,
				formatPos(pendingContainerPos),
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
				if (!isSupportedContainer(blockState)) {
					continue;
				}
				if (!canAttemptOpen(level, pos, blockState)) {
					continue;
				}
				if (squaredDistanceToBlock(eyePos, pos) > Mth.square(player.blockInteractionRange())) {
					continue;
				}

				markVisited(pos);
				Vec3 hitPos = closestPointOnUnitBlock(eyePos, pos);
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

					Slot targetSlot = findPlayerDestinationSlot(menu, itemId);
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
					formatPos(pendingContainerPos),
					AvailableItemSnapshot.formatCounts(withdrawnItems),
					summarizeRemainingItems(remainingItemIds)
				);
			}
		}

		private boolean withdrawOneItem(AbstractContainerMenu menu, Slot sourceSlot, Slot targetSlot) {
			pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			if (player.containerMenu.getCarried().isEmpty()) {
				return false;
			}

			pickup(menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			return player.containerMenu.getCarried().isEmpty();
		}

		private void beginResume() {
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
			Map<String, Integer> committedMissingCounts = committedReservedGrid ? computeReservedGridNeededCounts() : Map.of();
			Map<String, Integer> missingForCommittedLayout = committedReservedGrid
				? subtractAvailableCounts(committedMissingCounts, inventoryAndNearby)
				: Map.of();
			boolean committedLayoutCanContinue = committedReservedGrid && missingForCommittedLayout.isEmpty();
			boolean rotatingFamilyRedistribute = planningPolicy.redistributeToCraftWhenNeeded()
				&& reservedGridMatchesCollection
				&& recipeCollection != null
				&& recipeCollection.getRecipes().size() > 1;
			boolean allowReservedGridVariantSwitch = rotatingFamilyRedistribute
				|| (planningPolicy.redistributeToCraftWhenNeeded()
					&& reservedGridMatchesCollection
					&& (craftAll || !committedLayoutCanContinue));
			boolean redistributeReservedGrid = planningPolicy.redistributeToCraftWhenNeeded()
				&& committedReservedGrid
				&& (craftAll || !committedLayoutCanContinue || rotatingFamilyRedistribute);
			this.redistributeThisRun = redistributeReservedGrid;
			Map<String, Integer> gridCounts = countStacks(originalContext.gridStacks());
			Map<String, Integer> totalAvailable = redistributeReservedGrid
				? AvailableItemSnapshot.mergeCounts(inventoryAndNearby, gridCounts)
				: inventoryAndNearby;
			Map<String, Integer> planningInventoryCounts = redistributeReservedGrid ? totalAvailable : inventoryCounts;
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
			RecipeVariantResolver.Selection resolvedSelection = RecipeVariantResolver.resolve(
				client,
				player,
				recipeId,
				recipeCollection,
				ItemStack.EMPTY,
				explicitVariantSelection,
				true,
				resolverItems,
				totalAvailable,
				totalAvailable,
				craftAll,
				allowReservedGridVariantSwitch,
				desiredVariantCopies
			);
			if (resolvedSelection != null && !resolvedSelection.recipeId().equals(recipeId)) {
				ReachCraftingMod.LOGGER.info(
					"[recipe_variant] clicked_idx={} selected_idx={} mode={} output={}",
					recipeIndex,
					resolvedSelection.recipeId().index(),
					ReachCraftingConfig.get().revolvingCraftHandling().name().toLowerCase(),
					resolvedSelection.outputLabel()
				);
				recipeId = resolvedSelection.recipeId();
				outputLabel = resolvedSelection.outputLabel();
				ingredientSummary = resolvedSelection.ingredientSummary();
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
			plannedTargets = redistributeReservedGrid && !craftAll
				? computeMinimalRedistributedTargets(totalAvailable, targetCopiesPerSlot)
				: plannedResult.slotTargets();
			if (plannedTargets.isEmpty()) {
				plannedTargets = plannedResult.slotTargets();
			}
			remainingItemIds.clear();
			remainingItemIds.addAll(redistributeReservedGrid ? computeRedistributedFetchItemIds(plannedTargets) : computeFetchItemIds(plannedTargets));

			if (!planningPolicy.redistributeToCraftWhenNeeded()
				&& committedReservedGrid) {
				if (!missingForCommittedLayout.isEmpty()) {
					blockedCommittedLayoutMissingSummary = AvailableItemSnapshot.formatCounts(missingForCommittedLayout);
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

		private Set<String> computeScanAcceptedItemIds(
			RecipeCollection recipeCollection,
			RecipeIngredientSummary baseSummary,
			boolean explicitVariantSelection,
			boolean hasReservedGrid
		) {
			if (recipeCollection == null
				|| recipeCollection.getRecipes().size() <= 1
				|| explicitVariantSelection
				|| (hasReservedGrid && !planningPolicy.redistributeToCraftWhenNeeded())
				|| ReachCraftingConfig.get().revolvingCraftHandling() == ReachCraftingConfig.RevolvingCraftHandling.SPECIFIC_VARIANT_ONLY
				|| client.level == null) {
				return baseSummary.acceptedItemIds();
			}

			ContextMap context = SlotDisplayContext.fromLevel(client.level);
			Set<String> acceptedItemIds = new HashSet<>(baseSummary.acceptedItemIds());
			for (var entry : recipeCollection.getRecipes()) {
				acceptedItemIds.addAll(RecipeIngredientSummary.fromDisplay(entry.display(), context).acceptedItemIds());
			}
			return Set.copyOf(acceptedItemIds);
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

		private void resetForLiveDiscovery() {
			recipeId = originalRecipeId;
			outputLabel = originalOutputLabel;
			ingredientSummary = originalIngredientSummary;
			remainingItemIds.clear();
			remainingItemIds.addAll(initialDeficit.missingItemIds());
			plannedTargets = List.of();
			nextCandidateIndex = 0;
			timeoutTicks = 0;
			restoreTicksRemaining = 0;
			reopenAttemptsRemaining = 0;
			targetCopiesPerSlot = craftAll ? 0 : requestedSingleClicks;
			pendingContainerPos = null;
			inventorySpaceBlocked = false;
			redistributeThisRun = false;
			seededVanillaBatchLayout = false;
			seedWaitTicksRemaining = SEED_WAIT_TICKS;
			blockedCommittedLayoutMissingSummary = null;
			lastRestoreFailure = "<none>";
			visited.clear();
			discoveredNearby.clear();
			withdrawnItems.clear();
			phase = SearchPhase.DISCOVERY;
			state = SearchState.OPEN_NEXT;
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
			int minCount = Integer.MAX_VALUE;
			for (ItemStack stack : originalContext.gridStacks()) {
				if (stack.isEmpty()) {
					continue;
				}
				minCount = Math.min(minCount, stack.getCount());
			}
			return minCount == Integer.MAX_VALUE ? 0 : minCount;
		}

		private List<IngredientPlanning.SlotTarget> computeMinimalRedistributedTargets(Map<String, Integer> totalAvailable, int targetCopies) {
			if (targetCopies <= 0) {
				return List.of();
			}

			Map<String, Integer> remaining = new LinkedHashMap<>(totalAvailable);
			Map<List<String>, List<ReservedSlotState>> groupedSlots = new LinkedHashMap<>();
			List<RecipeIngredientSummary.IngredientSlot> recipeSlots = ingredientSummary.slots();
			List<ItemStack> gridStacks = originalContext.gridStacks();

			for (int index = 0; index < recipeSlots.size(); index++) {
				RecipeIngredientSummary.IngredientSlot ingredientSlot = recipeSlots.get(index);
				if (ingredientSlot.isEmpty()) {
					continue;
				}

				ItemStack currentStack = index < gridStacks.size() ? gridStacks.get(index) : ItemStack.EMPTY;
				String currentItemId = null;
				int currentCount = 0;
				if (!currentStack.isEmpty()) {
					String gridItemId = BuiltInRegistries.ITEM.getKey(currentStack.getItem()).toString();
					if (ingredientSlot.itemIds().contains(gridItemId)) {
						currentItemId = gridItemId;
						currentCount = currentStack.getCount();
					}
				}

				ReservedSlotState slotState = new ReservedSlotState(index + 1, ingredientSlot, currentItemId, currentCount);
				groupedSlots.computeIfAbsent(ingredientSlot.itemIds(), ignored -> new ArrayList<>()).add(slotState);
			}

			List<IngredientPlanning.SlotTarget> targets = new ArrayList<>();
			for (List<ReservedSlotState> groupSlots : groupedSlots.values()) {
				if (!planMinimalRedistributedGroup(groupSlots, remaining, totalAvailable, targetCopies, targets)) {
					return List.of();
				}
			}
			return List.copyOf(targets);
		}

		private boolean planMinimalRedistributedGroup(
			List<ReservedSlotState> groupSlots,
			Map<String, Integer> remaining,
			Map<String, Integer> preferenceTotals,
			int targetCopies,
			List<IngredientPlanning.SlotTarget> targets
		) {
			if (groupSlots.isEmpty()) {
				return true;
			}

			RecipeIngredientSummary.IngredientSlot ingredientSlot = groupSlots.getFirst().ingredientSlot();
			if (ingredientSlot.isExact()) {
				String itemId = ingredientSlot.itemIds().getFirst();
				for (ReservedSlotState slotState : groupSlots) {
					int targetCount = Math.max(slotState.currentCount(), targetCopies);
					if (remaining.getOrDefault(itemId, 0) < targetCount) {
						return false;
					}
					remaining.put(itemId, remaining.get(itemId) - targetCount);
					targets.add(new IngredientPlanning.SlotTarget(slotState.slotIndex(), ingredientSlot, itemId, targetCount));
				}
				return true;
			}

			List<ReservedSlotState> occupied = new ArrayList<>();
			List<ReservedSlotState> unassigned = new ArrayList<>();
			for (ReservedSlotState slotState : groupSlots) {
				if (slotState.currentItemId() == null) {
					unassigned.add(slotState);
				} else {
					occupied.add(slotState);
				}
			}

			occupied.sort(Comparator
				.comparingInt(ReservedSlotState::currentCount).reversed()
				.thenComparingInt(ReservedSlotState::slotIndex));

			Set<String> committedVariants = new HashSet<>();
			for (ReservedSlotState slotState : occupied) {
				committedVariants.add(slotState.currentItemId());
			}

			for (ReservedSlotState slotState : occupied) {
				String currentItemId = slotState.currentItemId();
				int targetCount = Math.max(slotState.currentCount(), targetCopies);
				if (remaining.getOrDefault(currentItemId, 0) >= targetCount) {
					remaining.put(currentItemId, remaining.get(currentItemId) - targetCount);
					targets.add(new IngredientPlanning.SlotTarget(slotState.slotIndex(), ingredientSlot, currentItemId, targetCount));
				} else {
					unassigned.add(slotState);
				}
			}

			List<String> orderedVariants = orderRedistributedVariants(ingredientSlot.itemIds(), committedVariants, currentInventoryCounts(), preferenceTotals);
			for (String variant : orderedVariants) {
				while (!unassigned.isEmpty() && remaining.getOrDefault(variant, 0) >= targetCopies) {
					ReservedSlotState slotState = unassigned.removeFirst();
					remaining.put(variant, remaining.get(variant) - targetCopies);
					targets.add(new IngredientPlanning.SlotTarget(slotState.slotIndex(), ingredientSlot, variant, targetCopies));
				}
			}

			return unassigned.isEmpty();
		}

		private List<String> orderRedistributedVariants(
			List<String> acceptedVariants,
			Set<String> committedVariants,
			Map<String, Integer> inventoryCounts,
			Map<String, Integer> preferenceTotals
		) {
			LinkedHashSet<String> ordered = new LinkedHashSet<>();
			acceptedVariants.stream()
				.filter(committedVariants::contains)
				.sorted(compareVariantPreference(preferenceTotals))
				.forEach(ordered::add);
			acceptedVariants.stream()
				.filter(itemId -> inventoryCounts.getOrDefault(itemId, 0) > 0)
				.sorted(compareVariantPreference(preferenceTotals))
				.forEach(ordered::add);
			acceptedVariants.stream()
				.sorted(compareVariantPreference(preferenceTotals))
				.forEach(ordered::add);
			return List.copyOf(ordered);
		}

		private Comparator<String> compareVariantPreference(Map<String, Integer> preferenceTotals) {
			Comparator<String> byCount = Comparator.comparingInt((String itemId) -> preferenceTotals.getOrDefault(itemId, 0));
			if (planningPolicy.countPreference() == IngredientPlanning.CountPreference.HIGHEST_TOTAL) {
				byCount = byCount.reversed();
			}
			return byCount.thenComparing(Comparator.naturalOrder());
		}

		private void resumeOriginalContext() {
			if (originalContext.kind() == ScreenKind.INVENTORY_2X2) {
				client.setScreen(new InventoryScreen(player));
				return;
			}
			if (originalContext.kind() == ScreenKind.CRAFTING_TABLE_3X3 && originalContext.craftingTablePos() != null) {
				Vec3 eyePos = cameraEntity.getEyePosition(0);
				BlockPos pos = originalContext.craftingTablePos();
				if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE) && squaredDistanceToBlock(eyePos, pos) <= Mth.square(player.blockInteractionRange())) {
					Vec3 hitPos = closestPointOnUnitBlock(eyePos, pos);
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

		private boolean isOriginalContextReady() {
			if (originalContext.kind() == ScreenKind.NONE) {
				return true;
			}
			if (originalContext.kind() == ScreenKind.INVENTORY_2X2) {
				return client.screen instanceof InventoryScreen;
			}
			return client.screen instanceof CraftingScreen;
		}

		private boolean tryFinishAfterResume() {
			boolean contextReady = isOriginalContextReady();
			boolean gridRestored = false;
			boolean reservedGridMatchesRecipe = false;
			boolean reservedGridExpanded = false;
			boolean placedPlannedGrid = false;
			lastRestoreFailure = contextReady ? "<none>" : "context_not_ready";
			if (contextReady) {
				restoreRecipeBookState();
				restoreMousePosition();
				gridRestored = restoreReservedGrid();
				if (!gridRestored && originalContext.hasReservedGrid() && restoreTicksRemaining > 0) {
					restoreTicksRemaining--;
					ReachCraftingMod.LOGGER.info(
						"[nearby_restore] idx={} waiting_for_grid_restore remaining_ticks={}",
						recipeIndex,
						restoreTicksRemaining
					);
					return false;
				}
				reservedGridMatchesRecipe = redistributeThisRun
					? originalContext.hasReservedGrid() && originalContext.reservedGridMatchesCollection(client.level, recipeCollection)
					: originalContext.reservedGridMatches(ingredientSummary);
				if (gridRestored && reservedGridMatchesRecipe && targetCopiesPerSlot > 0) {
					reservedGridExpanded = redistributeThisRun
						? applyPlannedTargetsToGrid()
						: expandReservedGrid();
				}
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
				} else if (targetCopiesPerSlot > 0 && !updatedDeficit.hasMissingIngredients() && contextReady && reservedGridMatchesRecipe && reservedGridExpanded) {
					sendChat("Updated grid: " + outputLabel);
				} else if (updatedDeficit.hasMissingIngredients()) {
					sendChat("Fetched what I could. Missing now: " + updatedDeficit.compactMissingSummary());
				} else {
					sendChat("Fetched ingredients for the next craft.");
				}
			} else if (placedPlannedGrid) {
				sendChat("Updated grid: " + outputLabel);
			} else if (targetCopiesPerSlot > 0 && !updatedDeficit.hasMissingIngredients() && contextReady && player.containerMenu != null) {
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
			stop(false);
			NearbyContainerDryRun.activeSession = null;
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
						formatStack(desiredStack),
						formatStack(menu.getSlot(slotIndex).getItem()),
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
						formatStack(currentStack),
						formatStack(desiredStack),
						formatStack(menu.getSlot(slotIndex).getItem()),
						lastRestoreFailure
					);
					return false;
				}
			}

			ReachCraftingMod.LOGGER.info("[nearby_restore] idx={} expanded_grid={}", recipeIndex, summarizeGrid(menu, originalGridStacks.size()));
			return true;
		}

		private boolean applyPlannedTargetsToGrid() {
			return applyPlannedTargetsToGrid(originalOccupiedGridSlotIndices());
		}

		private PlacementAttempt placePlannedGridWithVanillaShape() {
			if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
				lastRestoreFailure = "screen_not_container";
				return PlacementAttempt.FAILURE;
			}
			AbstractContainerMenu menu = containerScreen.getMenu();
			if (!seededVanillaBatchLayout) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, recipeId, false);
				seededVanillaBatchLayout = true;
			}
			List<Integer> occupiedSlots = currentOccupiedGridSlotIndices(menu, originalContext.gridStacks().size());
			if (occupiedSlots.size() < plannedTargets.size()) {
				lastRestoreFailure = "vanilla_shape_seed_waiting";
				return PlacementAttempt.WAITING_FOR_SEED;
			}
			return topUpSeededGridToPlannedTargets(menu, occupiedSlots) ? PlacementAttempt.SUCCESS : PlacementAttempt.FAILURE;
		}

		private boolean topUpSeededGridToPlannedTargets(AbstractContainerMenu menu, List<Integer> occupiedAnchorSlots) {
			Map<Integer, IngredientPlanning.SlotTarget> targetsBySlot = remapPlannedTargetsToAnchors(menu, occupiedAnchorSlots);
			for (int slotIndex = 1; slotIndex <= originalContext.gridStacks().size(); slotIndex++) {
				IngredientPlanning.SlotTarget desiredTarget = targetsBySlot.get(slotIndex);
				if (desiredTarget == null || desiredTarget.itemId() == null || desiredTarget.targetCount() <= 0) {
					continue;
				}

				ItemStack desiredStack = BuiltInRegistries.ITEM.getOptional(Identifier.parse(desiredTarget.itemId()))
					.map(item -> new ItemStack(item, desiredTarget.targetCount()))
					.orElse(ItemStack.EMPTY);
				if (desiredStack.isEmpty()) {
					lastRestoreFailure = "missing_item_for_target=" + desiredTarget.itemId();
					return false;
				}

				if (!restoreGridSlot(menu, slotIndex, desiredStack)) {
					ReachCraftingMod.LOGGER.warn(
						"[nearby_restore] idx={} failed_to_top_up_seeded grid_slot={} desired={} actual={} reason={}",
						recipeIndex,
						slotIndex,
						formatStack(desiredStack),
						formatStack(menu.getSlot(slotIndex).getItem()),
						lastRestoreFailure
					);
					return false;
				}
			}

			ReachCraftingMod.LOGGER.info("[nearby_restore] idx={} seeded_grid={}", recipeIndex, summarizeGrid(menu, originalContext.gridStacks().size()));
			return true;
		}

		private boolean applyPlannedTargetsToGrid(List<Integer> occupiedAnchorSlots) {
			if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
				return false;
			}

			AbstractContainerMenu menu = containerScreen.getMenu();
			Map<Integer, IngredientPlanning.SlotTarget> targetsBySlot = remapPlannedTargetsToAnchors(menu, occupiedAnchorSlots);

			if (!clearGridForRedistribute(menu, targetsBySlot)) {
				return false;
			}

			for (int slotIndex = 1; slotIndex <= originalContext.gridStacks().size(); slotIndex++) {
				IngredientPlanning.SlotTarget desiredTarget = targetsBySlot.get(slotIndex);
				if (desiredTarget == null || desiredTarget.itemId() == null || desiredTarget.targetCount() <= 0) {
					continue;
				}

				ItemStack desiredStack = BuiltInRegistries.ITEM.getOptional(Identifier.parse(desiredTarget.itemId()))
					.map(item -> new ItemStack(item, desiredTarget.targetCount()))
					.orElse(ItemStack.EMPTY);
				if (desiredStack.isEmpty()) {
					lastRestoreFailure = "missing_item_for_target=" + desiredTarget.itemId();
					return false;
				}

				if (!restoreGridSlot(menu, slotIndex, desiredStack)) {
					ReachCraftingMod.LOGGER.warn(
						"[nearby_restore] idx={} failed_to_redistribute grid_slot={} desired={} actual={} reason={}",
						recipeIndex,
						slotIndex,
						formatStack(desiredStack),
						formatStack(menu.getSlot(slotIndex).getItem()),
						lastRestoreFailure
					);
					return false;
				}
			}

			ReachCraftingMod.LOGGER.info("[nearby_restore] idx={} redistributed_grid={}", recipeIndex, summarizeGrid(menu, originalContext.gridStacks().size()));
			return true;
		}

		private Map<Integer, IngredientPlanning.SlotTarget> remapPlannedTargetsToAnchors(AbstractContainerMenu menu, List<Integer> occupiedAnchorSlots) {
			Map<Integer, IngredientPlanning.SlotTarget> targetsBySlot = new LinkedHashMap<>();
			List<IngredientPlanning.SlotTarget> unassignedTargets = new ArrayList<>(plannedTargets);

			for (int slotIndex : occupiedAnchorSlots) {
				ItemStack stack = menu.getSlot(slotIndex).getItem();
				if (stack.isEmpty()) {
					continue;
				}

				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				IngredientPlanning.SlotTarget bestMatch = null;
				for (IngredientPlanning.SlotTarget target : unassignedTargets) {
					if (itemId.equals(target.itemId())) {
						bestMatch = target;
						break;
					}
				}

				if (bestMatch != null) {
					unassignedTargets.remove(bestMatch);
					targetsBySlot.put(
						slotIndex,
						new IngredientPlanning.SlotTarget(slotIndex, bestMatch.ingredientSlot(), bestMatch.itemId(), bestMatch.targetCount())
					);
				}
			}

			// Add any remaining unassigned targets using their original indices (e.g. for slots we expect to fill but are currently empty)
			for (IngredientPlanning.SlotTarget target : unassignedTargets) {
				if (!targetsBySlot.containsKey(target.slotIndex())) {
					targetsBySlot.put(target.slotIndex(), target);
				}
			}

			return targetsBySlot;
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

		private List<Integer> currentOccupiedGridSlotIndices(AbstractContainerMenu menu, int gridSlotCount) {
			List<Integer> occupiedSlots = new ArrayList<>();
			for (int index = 1; index <= gridSlotCount; index++) {
				if (menu.getSlot(index).hasItem()) {
					occupiedSlots.add(index);
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

		private record ReservedSlotState(
			int slotIndex,
			RecipeIngredientSummary.IngredientSlot ingredientSlot,
			String currentItemId,
			int currentCount
		) {
		}

		private enum PlacementAttempt {
			SUCCESS,
			WAITING_FOR_SEED,
			FAILURE
		}

		private void stop(boolean closeContainer) {
			if (closeContainer && player.containerMenu != player.inventoryMenu) {
				player.closeContainer();
			}
		}

		private void sendChat(String message) {
			player.displayClientMessage(
				Component.literal("[Reach Crafting] " + message).withStyle(ChatFormatting.GOLD),
				false
			);
		}

		private static Slot findPlayerDestinationSlot(AbstractContainerMenu menu, String itemId) {
			Slot emptySlot = null;
			for (Slot slot : menu.slots) {
				if (!(slot.container instanceof Inventory)) {
					continue;
				}
				if (!slot.hasItem()) {
					if (emptySlot == null) {
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
				lastRestoreFailure = "carried_stack=" + formatStack(player.containerMenu.getCarried());
				return false;
			}

			Slot targetSlot = menu.getSlot(targetSlotIndex);
			ItemStack currentStack = targetSlot.getItem();
			if (!currentStack.isEmpty() && !ItemStack.isSameItemSameComponents(currentStack, desiredStack)) {
				if (!moveGridSlotBackToInventory(menu, targetSlot)) {
					lastRestoreFailure = "wrong_item_in_grid actual=" + formatStack(currentStack) + " desired=" + formatStack(desiredStack);
					return false;
				}
				currentStack = targetSlot.getItem();
			}

			int remaining = desiredStack.getCount() - currentStack.getCount();
			while (remaining > 0) {
				Slot sourceSlot = findMatchingInventorySourceSlot(menu, desiredStack);
				if (sourceSlot == null) {
					lastRestoreFailure = "missing_source_for=" + formatStack(desiredStack) + " remaining=" + remaining;
					return false;
				}

				int moved = moveExactCount(menu, sourceSlot, targetSlot, remaining);
				if (moved <= 0) {
					lastRestoreFailure = "move_failed source=" + formatStack(sourceSlot.getItem()) + " target=" + formatStack(targetSlot.getItem()) + " remaining=" + remaining;
					return false;
				}
				remaining -= moved;
			}

			ItemStack restoredStack = targetSlot.getItem();
			boolean restored = ItemStack.isSameItemSameComponents(restoredStack, desiredStack) && restoredStack.getCount() >= desiredStack.getCount();
			if (!restored) {
				lastRestoreFailure = "post_check actual=" + formatStack(restoredStack) + " desired=" + formatStack(desiredStack);
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
				Slot destinationSlot = findPlayerDestinationSlot(menu, itemId);
				if (destinationSlot == null) {
					pickup(menu, sourceGridSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
					lastRestoreFailure = "no_inventory_space_to_clear_grid item=" + itemId;
					return false;
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
				if (ItemStack.isSameItemSameComponents(slot.getItem(), desiredStack)) {
					return slot;
				}
			}
			return null;
		}

		private int moveExactCount(AbstractContainerMenu menu, Slot sourceSlot, Slot targetSlot, int remaining) {
			ItemStack sourceStack = sourceSlot.getItem();
			if (sourceStack.isEmpty()) {
				return 0;
			}

			int moveCount = Math.min(remaining, sourceStack.getCount());
			pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			ItemStack carriedStack = player.containerMenu.getCarried();
			if (carriedStack.isEmpty()) {
				return 0;
			}

			if (targetSlot.getItem().isEmpty() && moveCount == carriedStack.getCount()) {
				pickup(menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				return moveCount;
			}

			int placed = 0;
			while (placed < moveCount && !player.containerMenu.getCarried().isEmpty()) {
				pickup(menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
				placed++;
			}

			if (!player.containerMenu.getCarried().isEmpty()) {
				pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
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
				if (isSupportedContainer(state)) {
					candidates.add(pos.immutable());
				}
			}

			candidates.sort(Comparator.comparingDouble(pos -> squaredDistanceToBlock(eyePos, pos)));
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

		private static Map<String, Integer> collectAllItems(AbstractContainerMenu menu) {
			Map<String, Integer> allItems = new LinkedHashMap<>();
			for (Slot slot : menu.slots) {
				if (slot.container instanceof Inventory || !slot.hasItem()) {
					continue;
				}

				String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				allItems.merge(itemId, slot.getItem().getCount(), Integer::sum);
			}
			return allItems;
		}

		private static boolean isSupportedContainer(BlockState state) {
			Block block = state.getBlock();
			return block instanceof ChestBlock
				|| block instanceof BarrelBlock
				|| block instanceof ShulkerBoxBlock
				|| block instanceof EnderChestBlock;
		}

		private static boolean canAttemptOpen(Level level, BlockPos pos, BlockState state) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof Container) && !(state.getBlock() instanceof EnderChestBlock)) {
				return false;
			}
			if (state.getBlock() instanceof ChestBlock || state.getBlock() instanceof EnderChestBlock) {
				if (ChestBlock.isChestBlockedAt(level, pos)) {
					return false;
				}
				return getOtherHalfOfLargeChest(level, pos)
					.map(otherHalf -> !ChestBlock.isChestBlockedAt(level, otherHalf))
					.orElse(true);
			}
			return true;
		}

		private void markVisited(BlockPos pos) {
			visited.add(pos);
			getOtherHalfOfLargeChest(level, pos).ifPresent(visited::add);
		}

		private static Optional<BlockPos> getOtherHalfOfLargeChest(Level level, BlockPos pos) {
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
				return Optional.empty();
			}

			BlockPos otherHalfPos = pos.relative(ChestBlock.getConnectedDirection(state));
			BlockState otherHalf = level.getBlockState(otherHalfPos);
			if (!(otherHalf.getBlock() instanceof ChestBlock)) {
				return Optional.empty();
			}
			if (state.getValue(ChestBlock.FACING) != otherHalf.getValue(ChestBlock.FACING)) {
				return Optional.empty();
			}
			if (ChestBlock.getConnectedDirection(state) != ChestBlock.getConnectedDirection(otherHalf).getOpposite()) {
				return Optional.empty();
			}
			return Optional.of(otherHalfPos.immutable());
		}

		private static String formatPos(BlockPos pos) {
			if (pos == null) {
				return "<none>";
			}
			return pos.getX() + "," + pos.getY() + "," + pos.getZ();
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

				double distance = squaredDistanceToBlock(eyePos, pos);
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
			return new RecipeBookState(
				component.isVisible(),
				filterButton != null && Boolean.TRUE.equals(filterButton.getValue()),
				searchBox != null ? searchBox.getValue() : "",
				selectedTab != null ? selectedTab.getCategory() : null,
				pageAccessor.getCurrentPage(),
				overlay != null && overlay.isVisible(),
				overlay != null ? overlay.getRecipeCollection() : null
			);
		}

		private static RecipeBookState empty() {
			return new RecipeBookState(false, false, "", null, 0, false, null);
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
			if (searchBox != null && !searchText.equals(searchBox.getValue())) {
				searchBox.setValue(searchText);
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

	private static String formatStack(ItemStack stack) {
		if (stack.isEmpty()) {
			return "<empty>";
		}
		return stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem());
	}

	private static double squaredDistanceToBlock(Vec3 eyePos, BlockPos pos) {
		return closestPointOnUnitBlock(eyePos, pos).distanceToSqr(eyePos);
	}

	private static Vec3 closestPointOnUnitBlock(Vec3 origin, BlockPos pos) {
		double x = Mth.clamp(origin.x, pos.getX(), pos.getX() + 1.0D);
		double y = Mth.clamp(origin.y, pos.getY(), pos.getY() + 1.0D);
		double z = Mth.clamp(origin.z, pos.getZ(), pos.getZ() + 1.0D);
		return new Vec3(x, y, z);
	}
}
