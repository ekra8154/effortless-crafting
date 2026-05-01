package com.reachcrafting.client;

// import com.reachcrafting.ReachCraftingMod;

import java.util.HashMap;
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

	private static int autoMoveWaitingTicks = 0;
	private static boolean pendingAutoMove = false;
	private static ItemStack autoMoveTargetStack = ItemStack.EMPTY;
	private static Map<Integer, Integer> autoMoveSnapshotCounts = new HashMap<>();
	private static boolean autoMoveOrganizing = false;

	public static void autoMoveResult(AbstractContainerMenu menu) {
	}

	public static void scheduleAutoMove() {
		if (ReachCraftingConfig.get().autoCraftMode()) {
			pendingAutoMove = true;
			autoMoveWaitingTicks = 0;
		}
	}

	public static boolean isAutoMovePending() {
		return pendingAutoMove;
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
		if (!ReachCraftingConfig.get().autoCraftMode()) {
			return;
		}
		if (client.player == null || client.player.containerMenu == null || client.screen == null) {
			return;
		}
		
		// Only auto-move in Crafting or Inventory screens where slot 0 is the result
		if (!(client.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen) 
			&& !(client.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
			return;
		}
		
		AbstractContainerMenu menu = client.player.containerMenu;
		if (menu.slots.isEmpty()) {
			return;
		}
		
		Slot resultSlot = menu.getSlot(0);
		
		// Phase 1: Initiation
		if (!autoMoveOrganizing) {
			if (resultSlot.hasItem() && resultSlot.mayPickup(client.player)) {
				autoMoveTargetStack = resultSlot.getItem().copy();
				autoMoveWaitingTicks = 0;
				autoMoveOrganizing = true;
				
				// Take snapshot of current counts
				autoMoveSnapshotCounts.clear();
				for (int i = 0; i < 36; i++) {
					Slot s = findInventorySlot(menu, i);
					if (s != null && s.hasItem()) {
						autoMoveSnapshotCounts.put(i, s.getItem().getCount());
					}
				}
				
				// Perform the initial Shift-click (synchronous client-side update)
				client.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot.index, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, client.player);
			} else {
				autoMoveWaitingTicks++;
				if (autoMoveWaitingTicks > 10) pendingAutoMove = false;
				return;
			}
		}
		
		// Phase 2: Single-Pass Reorganize
		autoMoveWaitingTicks++;
		int movesThisTick = 0;
		int maxMovesPerTick = 20;
		
		// We only scan if we have reason to believe items have landed 
		// (Either because we just clicked, or enough time has passed)
		for (int i = 0; i < 36 && movesThisTick < maxMovesPerTick; i++) {
			Slot sourceSlot = findInventorySlot(menu, i);
			if (sourceSlot != null && sourceSlot.hasItem() && ItemStack.isSameItemSameComponents(sourceSlot.getItem(), autoMoveTargetStack)) {
				int currentCount = sourceSlot.getItem().getCount();
				int oldCount = autoMoveSnapshotCounts.getOrDefault(i, 0);
				
				if (currentCount > oldCount) {
					// Found newly landed items. Try to consolidate them leftward into the hotbar.
					for (int h = 0; h < i && h < 9; h++) {
						Slot targetSlot = findInventorySlot(menu, h);
						if (targetSlot == null) continue;
						
						boolean canMove = false;
						if (!targetSlot.hasItem()) {
							canMove = true;
						} else if (ItemStack.isSameItemSameComponents(targetSlot.getItem(), autoMoveTargetStack)) {
							if (targetSlot.getItem().getCount() < targetSlot.getItem().getMaxStackSize()) {
								canMove = true;
							}
						}
						
						if (canMove) {
							client.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
							client.gameMode.handleInventoryMouseClick(menu.containerId, targetSlot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
							
							if (!client.player.containerMenu.getCarried().isEmpty()) {
								client.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
							}
							
							movesThisTick++;
							
							// CRITICAL: Update snapshot for BOTH slots to prevent collisions
							autoMoveSnapshotCounts.put(i, 0); // Source is now empty (or reduced)
							autoMoveSnapshotCounts.put(h, targetSlot.getItem().getCount() + currentCount); // Target is now fuller
							break;
						}
					}
				}
			}
		}
		
		// Cleanup: Stop if we've moved everything or timed out
		if (movesThisTick == 0) {
			if (autoMoveWaitingTicks > 1) {
				// FINAL SAFETY: If anything is still on the cursor, put it away
				if (!client.player.containerMenu.getCarried().isEmpty()) {
					for (int i = 0; i < 36; i++) {
						Slot s = findInventorySlot(menu, i);
						if (s != null && !s.hasItem()) {
							client.gameMode.handleInventoryMouseClick(menu.containerId, s.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, client.player);
							break;
						}
					}
				}
				pendingAutoMove = false;
				autoMoveOrganizing = false;
				autoMoveTargetStack = ItemStack.EMPTY;
			}
		}
	}

	private static Slot findInventorySlot(AbstractContainerMenu menu, int inventoryIndex) {
		for (Slot slot : menu.slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory && slot.getContainerSlot() == inventoryIndex) {
				return slot;
			}
		}
		return null;
	}
}
