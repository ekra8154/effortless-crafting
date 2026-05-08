package com.reachcrafting.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/**
 * Shared utility methods for container interaction, block distance,
 * and formatting used by NearbyContainerDryRun, NearbyContainerCache,
 * and other container-related classes.
 */
public final class ContainerUtils {
	private ContainerUtils() {
	}

	public static void handleAutoCraftKeyPress() {
		AutoCraftController.handleKeyPress();
	}

	public static void handleAutoCraftKeyReleased() {
		AutoCraftController.handleKeyReleased();
	}

	public static void cancelAutoCraftToggle() {
		AutoCraftController.cancelToggle();
	}

	public static boolean isAutoCraftTogglePending() {
		return AutoCraftController.isTogglePending();
	}

	public static boolean isAutoCraftEnabled() {
		return AutoCraftController.isEnabled();
	}

	public static boolean isBulkAutoCraftModeEnabled() {
		return AutoCraftController.isBulkModeEnabled();
	}

	public static void toggleAutoCraftEnabledModeViaArrow() {
		AutoCraftController.toggleEnabledModeViaArrow();
	}

	public static void clearBulkAutoCraft() {
		BulkAutoCraftController.clear();
	}

	public static void scheduleAutoMove(ItemStack expectedStack) {
		AutoMoveController.scheduleAutoMove(expectedStack);
	}

	public static boolean isAutoMovePending() {
		return AutoMoveController.isAutoMovePending();
	}

	public static boolean isAutomatedInteractionRunning() {
		return AutoMoveController.isAutomatedInteractionRunning();
	}

	public static boolean isSupportedContainer(BlockState state) {
		String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		if (ReachCraftingConfig.get().blacklistedContainerIds().contains(blockId)) {
			return false;
		}
		return isPotentiallySupportedContainer(state);
	}

	public static boolean isPotentiallySupportedContainer(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock || block instanceof EnderChestBlock || block instanceof HopperBlock) {
			return true;
		}
		
		String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
		return blockId.endsWith("chest") || blockId.endsWith("barrel") || blockId.endsWith("shulker_box") || blockId.endsWith("hopper") || blockId.contains("container");
	}

	public static boolean canAttemptOpen(Level level, BlockPos pos, BlockState state) {
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

	public static Optional<BlockPos> getOtherHalfOfLargeChest(Level level, BlockPos pos) {
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

	public static Map<String, Integer> collectAllItems(AbstractContainerMenu menu) {
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

	public static double squaredDistanceToBlock(Vec3 eyePos, BlockPos pos) {
		return closestPointOnUnitBlock(eyePos, pos).distanceToSqr(eyePos);
	}

	public static Vec3 closestPointOnUnitBlock(Vec3 origin, BlockPos pos) {
		double x = Mth.clamp(origin.x, pos.getX(), pos.getX() + 1.0D);
		double y = Mth.clamp(origin.y, pos.getY(), pos.getY() + 1.0D);
		double z = Mth.clamp(origin.z, pos.getZ(), pos.getZ() + 1.0D);
		return new Vec3(x, y, z);
	}

	public static BlockPos findNearestCraftingTable(Level level, Vec3 eyePos, double reach) {
		double minSqDist = reach * reach;
		BlockPos nearest = null;
		int radius = Mth.ceil(reach);
		BlockPos center = BlockPos.containing(eyePos);

		for (BlockPos pos : BlockPos.betweenClosed(
			center.offset(-radius, -radius, -radius),
			center.offset(radius, radius, radius)
		)) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Blocks.CRAFTING_TABLE)) {
				double distSq = squaredDistanceToBlock(eyePos, pos);
				if (distSq <= minSqDist) {
					minSqDist = distSq;
					nearest = pos.immutable();
				}
			}
		}
		return nearest;
	}

	public static String formatPos(BlockPos pos) {
		if (pos == null) {
			return "<none>";
		}
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	public static String formatStack(ItemStack stack) {
		if (stack.isEmpty()) {
			return "<empty>";
		}
		return stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem());
	}

	/**
	 * Computes the minimum count across non-empty stacks in a grid.
	 * Returns 0 if all stacks are empty (no reserved items).
	 */
	public static int currentReservedCraftCopies(java.util.List<ItemStack> gridStacks) {
		int minCount = Integer.MAX_VALUE;
		for (ItemStack stack : gridStacks) {
			if (stack.isEmpty()) {
				continue;
			}
			minCount = Math.min(minCount, stack.getCount());
		}
		return minCount == Integer.MAX_VALUE ? 0 : minCount;
	}

	public static BlockPos canonicalizeContainerPos(Level level, BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof ChestBlock) {
			Optional<BlockPos> otherHalf = ContainerUtils.getOtherHalfOfLargeChest(level, pos);
			if (otherHalf.isPresent()) {
				BlockPos other = otherHalf.get();
				return compareBlockPos(pos, other) <= 0 ? pos.immutable() : other.immutable();
			}
		}
		return pos.immutable();
	}

	public static int compareBlockPos(BlockPos left, BlockPos right) {
		if (left.getX() != right.getX()) {
			return Integer.compare(left.getX(), right.getX());
		}
		if (left.getY() != right.getY()) {
			return Integer.compare(left.getY(), right.getY());
		}
		return Integer.compare(left.getZ(), right.getZ());
	}

	public static void autoMoveResult(net.minecraft.client.Minecraft client) {
		AutoMoveController.autoMoveResult(client);
	}

	public static boolean isGridEmpty(AbstractContainerMenu menu) {
		for (int i = 0; i < menu.slots.size(); i++) {
			if (InventoryGridRestoreTracker.isGridSlot(menu, i)) {
				if (menu.getSlot(i).hasItem()) {
					return false;
				}
			}
		}
		return true;
	}

	public static void flushCraftingGrid(net.minecraft.client.Minecraft client, boolean allowScreenChange, boolean isStartingNewCraft) {
		CraftingGridCleaner.flushCraftingGrid(client, allowScreenChange, isStartingNewCraft);
	}

	public static boolean isInputQueueActive() {
		return RecipeBookInputController.getInstance().isInputQueueActive();
	}

	public static void clearInputQueue() {
		RecipeBookInputController.getInstance().clearInputQueue();
	}

	public static void abortAllSessions() {
		boolean wasActive = RecipeBookInputController.getInstance().isInputQueueActive() 
			|| AutoMoveController.isAutomatedInteractionRunning() 
			|| NearbyContainerDryRun.isActiveSessionRunning()
			|| InventoryGridRestoreTracker.isRestoring();

		clearInputQueue();
		AutoMoveController.abort();
		AutoCraftController.setEnabledMode(ReachCraftingConfig.AutoCraftMode.NORMAL);
		BulkAutoCraftController.clear();
		NearbyContainerDryRun.abortActiveSession();
		InventoryGridRestoreTracker.clear();
		OffhandConsolidationController.swapBack(net.minecraft.client.Minecraft.getInstance());

		if (wasActive) {
			ReachCraftingModClient.sendChat("Crafting session aborted.");
		}
	}

	public static String formatStackBreakdown(int count) {
		if (count <= 64) {
			return String.valueOf(count);
		}
		int stacks = count / 64;
		int remainder = count % 64;
		String breakdown = (stacks == 1 ? "64" : (stacks + "x64"));
		if (remainder > 0) {
			return count + " (" + breakdown + " +" + remainder + ")";
		} else {
			return count + " (" + breakdown + ")";
		}
	}
}
