package com.reachcrafting.client;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What the player holds, as a fingerprint of per-item totals over the main
 * inventory plus the stack on the cursor. Slot positions do not enter it.
 *
 * <p>The craftability indicators, the recipe book's smart sort and the chain
 * cache all refresh when this changes. They used to hash the inventory slot
 * by slot without the cursor, so picking a stack up and putting it down
 * somewhere else read as two inventory changes: with the recipe book open,
 * moving a few sticks around restarted the smart sort every tick (about
 * 1,500 sort passes in one short session, 100+ fps down to 50-70) though
 * nothing craftable had changed. Only real changes (crafting, picking up,
 * tossing) move this fingerprint.</p>
 */
final class InventoryFingerprint {
	private static boolean hasCached = false;
	private static long cachedLayout = 0;
	private static long cachedTotals = 0;

	private InventoryFingerprint() {
	}

	static long totals(LocalPlayer player) {
		ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
		// Cheap slot-by-slot pre-check: callers ask once per visible button
		// per frame, and the inventory is unchanged on almost every one.
		long layout = 1;
		for (ItemStack stack : player.getInventory().items) {
			layout = layout * 31 + stackHash(stack);
		}
		layout = layout * 31 + stackHash(carried);
		if (hasCached && layout == cachedLayout) {
			return cachedTotals;
		}

		Map<Item, Integer> totals = new IdentityHashMap<>();
		for (ItemStack stack : player.getInventory().items) {
			if (!stack.isEmpty()) {
				totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		if (!carried.isEmpty()) {
			totals.merge(carried.getItem(), carried.getCount(), Integer::sum);
		}
		// Summed, so the order the map yields entries in does not matter.
		long fingerprint = totals.size();
		for (Map.Entry<Item, Integer> entry : totals.entrySet()) {
			fingerprint += mix(((long) System.identityHashCode(entry.getKey()) << 32) ^ entry.getValue());
		}

		hasCached = true;
		cachedLayout = layout;
		cachedTotals = fingerprint;
		return fingerprint;
	}

	private static long stackHash(ItemStack stack) {
		return stack.isEmpty() ? 0 : System.identityHashCode(stack.getItem()) * 31L + stack.getCount();
	}

	/** 64-bit finalizer (MurmurHash3 fmix64), so summed entries do not cancel. */
	private static long mix(long z) {
		z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
		z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return z ^ (z >>> 33);
	}
}
