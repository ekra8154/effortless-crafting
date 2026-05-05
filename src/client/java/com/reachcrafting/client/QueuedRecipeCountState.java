package com.reachcrafting.client;

import net.minecraft.world.item.ItemStack;

/**
 * Single source of truth for a recipe button's current count state.
 * Renderers should consume this object rather than re-deriving queue semantics.
 */
record QueuedRecipeCountState(int displayedCount, boolean visible, boolean queuedState, ItemStack queuedOutputStack) {
	static QueuedRecipeCountState hidden() {
		return new QueuedRecipeCountState(0, false, false, ItemStack.EMPTY);
	}

	static QueuedRecipeCountState visible(int displayedCount, boolean queuedState, ItemStack queuedOutputStack) {
		ItemStack outputStack = queuedOutputStack == null ? ItemStack.EMPTY : queuedOutputStack;
		return new QueuedRecipeCountState(displayedCount, true, queuedState, outputStack);
	}
}
