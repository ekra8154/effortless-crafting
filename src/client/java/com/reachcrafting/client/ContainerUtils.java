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

	public static boolean isSupportedContainer(BlockState state) {
		String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		if (ReachCraftingConfig.get().blacklistedContainerIds().contains(blockId)) {
			return false;
		}
		return isPotentiallySupportedContainer(state);
	}

	public static boolean isPotentiallySupportedContainer(BlockState state) {
		String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		Block block = state.getBlock();
		return block instanceof ChestBlock
			|| block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock
			|| block instanceof EnderChestBlock
			|| block instanceof HopperBlock
			|| (blockId.startsWith("minecraft:") && blockId.endsWith("copper_chest"));
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
}
