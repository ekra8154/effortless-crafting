package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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

	private NearbyContainerDryRun() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
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

	public static void start(int recipeIndex, String outputLabel, RecipeIngredientSummary ingredientSummary, AvailableItemSnapshot localItems) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		Level level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;
		Entity cameraEntity = client.getCameraEntity();
		if (player == null || level == null || gameMode == null || cameraEntity == null) {
			return;
		}

		cancelCurrent();
		SearchSession session = new SearchSession(client, player, level, gameMode, cameraEntity, recipeIndex, outputLabel, ingredientSummary, localItems);
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

	public static void onContainerContentsInitialized(AbstractContainerMenu menu) {
		if (activeSession != null) {
			activeSession.onContainerContentsInitialized(menu);
		}
	}

	private static final class SearchSession {
		private static final int OPEN_TIMEOUT_TICKS = 40;
		private static final int RESUME_DELAY_TICKS = 2;

		private final Minecraft client;
		private final LocalPlayer player;
		private final Level level;
		private final MultiPlayerGameMode gameMode;
		private final Entity cameraEntity;
		private final int recipeIndex;
		private final String outputLabel;
		private final RecipeIngredientSummary ingredientSummary;
		private final AvailableItemSnapshot localItems;
		private final RecipeDeficitReport initialDeficit;
		private final String targetExactItemId;
		private final ScreenContext originalContext;
		private final List<BlockPos> candidates;
		private final Set<BlockPos> visited = new HashSet<>();
		private final Map<String, Integer> nearbyMatches = new LinkedHashMap<>();

		private int nextCandidateIndex;
		private int timeoutTicks;
		private int scannedContainers;
		private int withdrawnCount;
		private BlockPos pendingContainerPos;
		private SearchState state = SearchState.OPEN_NEXT;

		private SearchSession(
			Minecraft client,
			LocalPlayer player,
			Level level,
			MultiPlayerGameMode gameMode,
			Entity cameraEntity,
			int recipeIndex,
			String outputLabel,
			RecipeIngredientSummary ingredientSummary,
			AvailableItemSnapshot localItems
		) {
			this.client = client;
			this.player = player;
			this.level = level;
			this.gameMode = gameMode;
			this.cameraEntity = cameraEntity;
			this.recipeIndex = recipeIndex;
			this.outputLabel = outputLabel;
			this.ingredientSummary = ingredientSummary;
			this.localItems = localItems;
			this.initialDeficit = RecipeDeficitReport.from(ingredientSummary, localItems);
			this.targetExactItemId = initialDeficit.firstExactMissingItemId().orElse(null);
			this.originalContext = ScreenContext.capture(client, cameraEntity, player.blockInteractionRange());
			this.candidates = findCandidates(level, cameraEntity, player.blockInteractionRange());
		}

		private boolean canStart() {
			return !player.isSpectator() && !player.isShiftKeyDown() && !player.isHandsBusy() && player.containerMenu.getCarried().isEmpty();
		}

		private void start() {
			ReachCraftingMod.LOGGER.info(
				"[nearby_scan] idx={} start missing={} candidates={} target={}",
				recipeIndex,
				initialDeficit.compactMissingSummary(),
				candidates.size(),
				targetExactItemId == null ? "<none>" : targetExactItemId
			);

			if (targetExactItemId == null) {
				sendChat("Searching nearby containers...");
				return;
			}

			sendChat("Searching for 1x " + targetExactItemId + "...");
		}

		private void tick() {
			if (client.player != player || client.level != level) {
				stop(false);
				NearbyContainerDryRun.activeSession = null;
				return;
			}

			if (state == SearchState.OPEN_NEXT) {
				if (!openNextContainer()) {
					finishDryRun();
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
					finishAfterWithdrawal();
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
				usefulItems.forEach((itemId, count) -> nearbyMatches.merge(itemId, count, Integer::sum));
				ReachCraftingMod.LOGGER.info(
					"[nearby_container] idx={} pos={} found={}",
					recipeIndex,
					formatPos(pendingContainerPos),
					AvailableItemSnapshot.formatCounts(usefulItems)
				);
			}

			scannedContainers++;
			if (targetExactItemId != null && tryWithdrawOneExactItem(menu, targetExactItemId)) {
				withdrawnCount = 1;
				ReachCraftingMod.LOGGER.info(
					"[nearby_withdraw] idx={} pos={} item={} count=1",
					recipeIndex,
					formatPos(pendingContainerPos),
					targetExactItemId
				);
				player.closeContainer();
				pendingContainerPos = null;
				timeoutTicks = RESUME_DELAY_TICKS;
				state = SearchState.RESUME_CONTEXT;
				return;
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
				if (distanceToBlock(eyePos, pos) > Mth.square(player.blockInteractionRange())) {
					continue;
				}

				markVisited(pos, blockState);
				Vec3 hitPos = closestPointOnBlock(eyePos, pos);
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

		private void finishDryRun() {
			Map<String, Integer> totalWithNearby = AvailableItemSnapshot.mergeCounts(localItems.totalCounts(), nearbyMatches);
			RecipeDeficitReport remainingDeficit = RecipeDeficitReport.from(ingredientSummary, totalWithNearby);
			ReachCraftingMod.LOGGER.info(
				"[nearby_scan] idx={} scanned={} nearby={} remaining={}",
				recipeIndex,
				scannedContainers,
				AvailableItemSnapshot.formatCounts(nearbyMatches),
				remainingDeficit.compactMissingSummary()
			);

			if (remainingDeficit.hasMissingIngredients()) {
				sendChat("Nearby still missing: " + remainingDeficit.compactMissingSummary());
			} else {
				sendChat("Nearby can cover: " + outputLabel);
			}

			stop(false);
			NearbyContainerDryRun.activeSession = null;
		}

		private void finishAfterWithdrawal() {
			Map<String, Integer> updatedCounts = new LinkedHashMap<>(localItems.totalCounts());
			updatedCounts.merge(targetExactItemId, withdrawnCount, Integer::sum);
			RecipeDeficitReport updatedDeficit = RecipeDeficitReport.from(ingredientSummary, updatedCounts);
			ReachCraftingMod.LOGGER.info(
				"[nearby_withdraw] idx={} resumed={} remaining={}",
				recipeIndex,
				originalContext.kind(),
				updatedDeficit.compactMissingSummary()
			);

			sendChat("Fetched 1x " + targetExactItemId + ". Missing now: " + updatedDeficit.compactMissingSummary());
			stop(false);
			NearbyContainerDryRun.activeSession = null;
		}

		private boolean tryWithdrawOneExactItem(AbstractContainerMenu menu, String itemId) {
			Slot sourceSlot = findContainerSourceSlot(menu, itemId);
			Slot targetSlot = findPlayerDestinationSlot(menu, itemId);
			if (sourceSlot == null) {
				return false;
			}
			if (targetSlot == null) {
				sendChat("No inventory space for 1x " + itemId);
				return false;
			}

			pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			pickup(menu, targetSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			pickup(menu, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			return player.containerMenu.getCarried().isEmpty();
		}

		private void resumeOriginalContext() {
			if (originalContext.kind() == ScreenKind.INVENTORY_2X2) {
				client.setScreen(new InventoryScreen(player));
				return;
			}
			if (originalContext.kind() == ScreenKind.CRAFTING_TABLE_3X3 && originalContext.craftingTablePos() != null) {
				Vec3 eyePos = cameraEntity.getEyePosition(0);
				BlockPos pos = originalContext.craftingTablePos();
				if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE) && distanceToBlock(eyePos, pos) <= Mth.square(player.blockInteractionRange())) {
					Vec3 hitPos = closestPointOnBlock(eyePos, pos);
					Direction face = Direction.getApproximateNearest(hitPos.subtract(eyePos)).getOpposite();
					BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);
					gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
				}
			}
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

		private static Slot findContainerSourceSlot(AbstractContainerMenu menu, String itemId) {
			for (Slot slot : menu.slots) {
				if (slot.container instanceof Inventory || !slot.hasItem()) {
					continue;
				}
				String slotItemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
				if (itemId.equals(slotItemId) && slot.mayPickup(Minecraft.getInstance().player)) {
					return slot;
				}
			}
			return null;
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

			candidates.sort(Comparator.comparingDouble(pos -> distanceToBlock(eyePos, pos)));
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

		private void markVisited(BlockPos pos, BlockState state) {
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

		private static double distanceToBlock(Vec3 eyePos, BlockPos pos) {
			return closestPointOnBlock(eyePos, pos).distanceToSqr(eyePos);
		}

		private static Vec3 closestPointOnBlock(Vec3 origin, BlockPos pos) {
			double x = Mth.clamp(origin.x, pos.getX(), pos.getX() + 1.0D);
			double y = Mth.clamp(origin.y, pos.getY(), pos.getY() + 1.0D);
			double z = Mth.clamp(origin.z, pos.getZ(), pos.getZ() + 1.0D);
			return new Vec3(x, y, z);
		}

		private static String formatPos(BlockPos pos) {
			if (pos == null) {
				return "<none>";
			}
			return pos.getX() + "," + pos.getY() + "," + pos.getZ();
		}
	}

	private record ScreenContext(ScreenKind kind, BlockPos craftingTablePos) {
		private static ScreenContext capture(Minecraft client, Entity cameraEntity, double reachDistance) {
			if (client.screen instanceof CraftingScreen) {
				return new ScreenContext(ScreenKind.CRAFTING_TABLE_3X3, findNearestCraftingTable(client.level, cameraEntity, reachDistance));
			}
			if (client.screen instanceof InventoryScreen) {
				return new ScreenContext(ScreenKind.INVENTORY_2X2, null);
			}
			return new ScreenContext(ScreenKind.NONE, null);
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

	private enum ScreenKind {
		NONE,
		INVENTORY_2X2,
		CRAFTING_TABLE_3X3
	}

	private enum SearchState {
		OPEN_NEXT,
		WAITING_FOR_CONTAINER,
		RESUME_CONTEXT
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
