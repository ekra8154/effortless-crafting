package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Iterator;
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
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
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
		int recipeIndex,
		String outputLabel,
		RecipeIngredientSummary ingredientSummary,
		AvailableItemSnapshot localItems,
		boolean craftAll
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
		SearchSession session = new SearchSession(client, player, level, gameMode, cameraEntity, recipeId, recipeIndex, outputLabel, ingredientSummary, localItems, craftAll);
		if (!session.canStart()) {
			return;
		}

		activeSession = session;
		session.start();
	}

	public static void cancelCurrent() {
		if (activeSession != null) {
			activeSession.stop(true);
			activeSession = null;
		}
	}

	public static boolean shouldBlockWorldInteraction() {
		return activeSession != null || interactionBlockTicks > 0;
	}

	private static void armInteractionBlock() {
		interactionBlockTicks = Math.max(interactionBlockTicks, 4);
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
		private static final int MAX_REOPEN_ATTEMPTS = 3;

		private final Minecraft client;
		private final LocalPlayer player;
		private final Level level;
		private final MultiPlayerGameMode gameMode;
		private final Entity cameraEntity;
		private final RecipeDisplayId recipeId;
		private final int recipeIndex;
		private final String outputLabel;
		private final RecipeIngredientSummary ingredientSummary;
		private final AvailableItemSnapshot localItems;
		private final boolean craftAll;
		private final RecipeDeficitReport initialDeficit;
		private final List<RecipeIngredientSummary.IngredientSlot> remainingSlots;
		private final ScreenContext originalContext;
		private final List<BlockPos> candidates;
		private final Set<BlockPos> visited = new HashSet<>();
		private final Map<String, Integer> discoveredNearby = new LinkedHashMap<>();
		private final Map<String, Integer> withdrawnItems = new LinkedHashMap<>();

		private int nextCandidateIndex;
		private int timeoutTicks;
		private int restoreTicksRemaining;
		private int reopenAttemptsRemaining;
		private int scannedContainers;
		private int targetCopiesPerSlot;
		private BlockPos pendingContainerPos;
		private boolean inventorySpaceBlocked;
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
			int recipeIndex,
			String outputLabel,
			RecipeIngredientSummary ingredientSummary,
			AvailableItemSnapshot localItems,
			boolean craftAll
		) {
			this.client = client;
			this.player = player;
			this.level = level;
			this.gameMode = gameMode;
			this.cameraEntity = cameraEntity;
			this.recipeId = recipeId;
			this.recipeIndex = recipeIndex;
			this.outputLabel = outputLabel;
			this.ingredientSummary = ingredientSummary;
			this.localItems = localItems;
			this.craftAll = craftAll;
			this.initialDeficit = RecipeDeficitReport.from(ingredientSummary, localItems.inventoryCounts(), craftAll);
			this.remainingSlots = new ArrayList<>(computeRemainingSlots(ingredientSummary, localItems.inventoryCounts(), craftAll));
			this.originalContext = ScreenContext.capture(client, cameraEntity, player.blockInteractionRange(), localItems);
			this.candidates = findCandidates(level, cameraEntity, player.blockInteractionRange());
			this.targetCopiesPerSlot = craftAll ? 0 : 1;
			this.phase = craftAll ? SearchPhase.DISCOVERY : SearchPhase.WITHDRAW;
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
				summarizeRemainingSlots(remainingSlots)
			);

			if (phase == SearchPhase.DISCOVERY) {
				sendChat("Scanning nearby containers...");
			} else {
				sendChat("Fetching: " + summarizeRemainingSlots(remainingSlots));
			}
		}

		private void tick() {
			if (client.player != player || client.level != level) {
				stop(false);
				NearbyContainerDryRun.activeSession = null;
				return;
			}

			if (state == SearchState.OPEN_NEXT) {
				if (phase == SearchPhase.WITHDRAW && remainingSlots.isEmpty()) {
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

			Map<String, Integer> usefulItems = collectUsefulItems(menu, ingredientSummary.acceptedItemIds());
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

				gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
				pendingContainerPos = pos;
				timeoutTicks = OPEN_TIMEOUT_TICKS;
				state = SearchState.WAITING_FOR_CONTAINER;
				return true;
			}

			return false;
		}

		private void withdrawItemsFromContainer(AbstractContainerMenu menu) {
			if (remainingSlots.isEmpty()) {
				return;
			}

			for (Slot slot : menu.slots) {
				if (slot.container instanceof Inventory || !slot.hasItem()) {
					continue;
				}

				String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				if (findMatchingRemainingSlotIndex(itemId) < 0 || !slot.mayPickup(player)) {
					continue;
				}

				while (slot.hasItem()) {
					int remainingSlotIndex = findMatchingRemainingSlotIndex(itemId);
					if (remainingSlotIndex < 0) {
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

					remainingSlots.remove(remainingSlotIndex);
					withdrawnItems.merge(itemId, 1, Integer::sum);
				}
			}

			if (!withdrawnItems.isEmpty()) {
				ReachCraftingMod.LOGGER.info(
					"[nearby_withdraw] idx={} pos={} total_withdrawn={} remaining={}",
					recipeIndex,
					formatPos(pendingContainerPos),
					AvailableItemSnapshot.formatCounts(withdrawnItems),
					summarizeRemainingSlots(remainingSlots)
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
			Map<String, Integer> totalAvailable = AvailableItemSnapshot.mergeCounts(localItems.inventoryCounts(), discoveredNearby);
			targetCopiesPerSlot = computeMaxCraftCopies(ingredientSummary, totalAvailable);
			remainingSlots.clear();
			remainingSlots.addAll(computeRemainingSlots(ingredientSummary, localItems.inventoryCounts(), targetCopiesPerSlot));
			ReachCraftingMod.LOGGER.info(
				"[nearby_plan] idx={} total_available={} target_copies={} planned={}",
				recipeIndex,
				AvailableItemSnapshot.formatCounts(totalAvailable),
				targetCopiesPerSlot,
				summarizeRemainingSlots(remainingSlots)
			);

			if (targetCopiesPerSlot <= 0 || remainingSlots.isEmpty()) {
				beginResume();
				return;
			}

			visited.clear();
			nextCandidateIndex = 0;
			pendingContainerPos = null;
			phase = SearchPhase.WITHDRAW;
			state = SearchState.OPEN_NEXT;
			sendChat("Fetching: " + summarizeRemainingSlots(remainingSlots));
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
					boolean wasSneaking = player.isShiftKeyDown();
					if (wasSneaking) {
						player.setShiftKeyDown(false);
					}
					gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
					if (wasSneaking) {
						player.setShiftKeyDown(true);
					}
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
			lastRestoreFailure = contextReady ? "<none>" : "context_not_ready";
			if (contextReady) {
				restoreRecipeBookState();
				restoreMousePosition();
				gridRestored = restoreReservedGrid();
				if (!gridRestored && originalContext.hasReservedGrid() && restoreTicksRemaining > 0) {
					restoreTicksRemaining--;
					timeoutTicks = 1;
					ReachCraftingMod.LOGGER.info(
						"[nearby_restore] idx={} waiting_for_grid_restore remaining_ticks={}",
						recipeIndex,
						restoreTicksRemaining
					);
					return false;
				}
				reservedGridMatchesRecipe = originalContext.reservedGridMatches(ingredientSummary);
				if (gridRestored && reservedGridMatchesRecipe && targetCopiesPerSlot > 0) {
					if (craftAll && player.containerMenu != null) {
						gameMode.handlePlaceRecipe(player.containerMenu.containerId, recipeId, craftAll);
					}
					reservedGridExpanded = expandReservedGrid();
				}
			}

			Map<String, Integer> updatedCounts = AvailableItemSnapshot.mergeCounts(localItems.inventoryCounts(), withdrawnItems);
			RecipeDeficitReport updatedDeficit = RecipeDeficitReport.from(ingredientSummary, updatedCounts, targetCopiesPerSlot);
			ReachCraftingMod.LOGGER.info(
				"[nearby_scan] idx={} scanned={} discovered={} withdrawn={} remaining={}",
				recipeIndex,
				scannedContainers,
				AvailableItemSnapshot.formatCounts(discoveredNearby),
				AvailableItemSnapshot.formatCounts(withdrawnItems),
				updatedDeficit.compactMissingSummary()
			);

			if (originalContext.hasReservedGrid()) {
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
			} else if (targetCopiesPerSlot > 0 && !updatedDeficit.hasMissingIngredients() && contextReady && player.containerMenu != null) {
				gameMode.handlePlaceRecipe(player.containerMenu.containerId, recipeId, craftAll);
				sendChat("Placed recipe: " + outputLabel);
			} else if (!remainingSlots.isEmpty() || inventorySpaceBlocked) {
				sendChat("Fetched what I could. Missing now: " + updatedDeficit.compactMissingSummary());
			} else if (updatedDeficit.hasMissingIngredients()) {
				sendChat("Items fetched. Remaining: " + updatedDeficit.compactMissingSummary());
			} else {
				sendChat("Ready to place: " + outputLabel);
			}

			NearbyContainerDryRun.armInteractionBlock();
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

				int targetCount = craftAll
					? Math.min(currentStack.getMaxStackSize(), Math.max(targetCopiesPerSlot, currentStack.getCount()))
					: Math.min(currentStack.getMaxStackSize(), currentStack.getCount() + 1);
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
			Slot destinationSlot = findPlayerDestinationSlot(menu, itemId);
			if (destinationSlot == null) {
				lastRestoreFailure = "no_inventory_space_to_clear_grid item=" + itemId;
				return false;
			}

			pickup(menu, sourceGridSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			if (player.containerMenu.getCarried().isEmpty()) {
				lastRestoreFailure = "pickup_failed_when_clearing_grid item=" + itemId;
				return false;
			}

			pickup(menu, destinationSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			if (!player.containerMenu.getCarried().isEmpty()) {
				pickup(menu, sourceGridSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
				lastRestoreFailure = "inventory_reject_clear item=" + itemId + " destination=" + destinationSlot.index;
				return false;
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

		private int findMatchingRemainingSlotIndex(String itemId) {
			for (int i = 0; i < remainingSlots.size(); i++) {
				if (remainingSlots.get(i).itemIds().contains(itemId)) {
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

		private static List<RecipeIngredientSummary.IngredientSlot> computeRemainingSlots(
			RecipeIngredientSummary ingredientSummary,
			Map<String, Integer> availableCounts,
			boolean craftAll
		) {
			return computeRemainingSlots(ingredientSummary, availableCounts, craftAll ? minSlotMaxStackSize(ingredientSummary) : 1);
		}

		private static List<RecipeIngredientSummary.IngredientSlot> computeRemainingSlots(
			RecipeIngredientSummary ingredientSummary,
			Map<String, Integer> availableCounts,
			int copiesPerSlot
		) {
			Map<String, Integer> remainingCounts = new LinkedHashMap<>(availableCounts);
			List<RecipeIngredientSummary.IngredientSlot> remainingSlots = new ArrayList<>();

			for (RecipeIngredientSummary.IngredientSlot slot : ingredientSummary.slots()) {
				if (slot.isEmpty()) {
					continue;
				}

				int desiredCopies = copiesPerSlot;
				for (int i = 0; i < desiredCopies; i++) {
					String matchedItemId = firstAvailableOption(slot.itemIds(), remainingCounts);
					if (matchedItemId == null) {
						remainingSlots.add(slot);
						continue;
					}

					consume(remainingCounts, matchedItemId);
				}
			}

			return remainingSlots;
		}

		private static int computeMaxCraftCopies(RecipeIngredientSummary ingredientSummary, Map<String, Integer> availableCounts) {
			int upperBound = minSlotMaxStackSize(ingredientSummary);
			if (upperBound <= 0) {
				return 0;
			}

			int low = 0;
			int high = upperBound;
			while (low < high) {
				int mid = (low + high + 1) / 2;
				if (computeRemainingSlots(ingredientSummary, availableCounts, mid).isEmpty()) {
					low = mid;
				} else {
					high = mid - 1;
				}
			}
			return low;
		}

		private static int minSlotMaxStackSize(RecipeIngredientSummary ingredientSummary) {
			int minStackSize = Integer.MAX_VALUE;
			for (RecipeIngredientSummary.IngredientSlot slot : ingredientSummary.slots()) {
				if (slot.isEmpty()) {
					continue;
				}
				minStackSize = Math.min(minStackSize, Math.max(slot.maxStackSize(), 1));
			}
			return minStackSize == Integer.MAX_VALUE ? 0 : minStackSize;
		}

		private static String summarizeRemainingSlots(List<RecipeIngredientSummary.IngredientSlot> remainingSlots) {
			if (remainingSlots.isEmpty()) {
				return "<none>";
			}

			Map<String, Integer> aggregate = new LinkedHashMap<>();
			for (RecipeIngredientSummary.IngredientSlot slot : remainingSlots) {
				String key = slot.display().startsWith("[") ? "any of " + slot.display() : slot.display();
				aggregate.merge(key, 1, Integer::sum);
			}
			return AvailableItemSnapshot.formatCounts(aggregate);
		}

		private static String firstAvailableOption(List<String> itemIds, Map<String, Integer> remainingCounts) {
			for (String itemId : itemIds) {
				if (remainingCounts.getOrDefault(itemId, 0) > 0) {
					return itemId;
				}
			}
			return null;
		}

		private static void consume(Map<String, Integer> remainingCounts, String itemId) {
			int available = remainingCounts.getOrDefault(itemId, 0);
			if (available <= 0) {
				return;
			}
			if (available == 1) {
				remainingCounts.remove(itemId);
			} else {
				remainingCounts.put(itemId, available - 1);
			}
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
