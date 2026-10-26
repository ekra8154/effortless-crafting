package com.reachcrafting.client;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * Why a stack might be one a craft must not take, as bits of a reason mask.
 * A stack can carry several at once (a skeleton's bow is often damaged AND
 * enchanted), so each reason is its own bit and counts are kept per exact
 * combination, never per reason.
 *
 * <p>A rule is the mask of reasons it allows: a stack passes when every bit
 * it carries is allowed. CRAFTING is vanilla's rule for automatic placement
 * (its recipe book and the server's place-recipe handler use the same test,
 * Inventory.isUsableForCrafting); PRISTINE allows nothing, for chain steps.
 * The container cache stores masks rather than verdicts, so a future
 * exemption (damaged bows for dispensers, say) is a change to a rule here
 * and every remembered chest follows without being reopened.</p>
 */
final class StackReasons {
	static final int DAMAGED = 1;
	static final int ENCHANTED = 1 << 1;
	static final int RENAMED = 1 << 2;
	/** Any other component change: contents, dye, lore, repair cost... */
	static final int OTHER_COMPONENTS = 1 << 3;

	/** Ordinary crafts: anything but damage, enchantments or a custom name. */
	static final int CRAFTING = OTHER_COMPONENTS;
	/** Chain steps: an item exactly as it comes by default. */
	static final int PRISTINE = 0;

	private StackReasons() {
	}

	static int of(ItemStack stack) {
		DataComponentPatch patch = stack.getComponentsPatch();
		if (patch.isEmpty()) {
			return 0;
		}
		int mask = 0;
		if (stack.isDamaged()) {
			mask |= DAMAGED;
		}
		if (stack.isEnchanted()) {
			mask |= ENCHANTED;
		}
		if (stack.has(DataComponents.CUSTOM_NAME)) {
			mask |= RENAMED;
		}
		if (!patch.forget(type -> type == DataComponents.DAMAGE
			|| type == DataComponents.ENCHANTMENTS
			|| type == DataComponents.CUSTOM_NAME).isEmpty()) {
			mask |= OTHER_COMPONENTS;
		}
		return mask;
	}

	static boolean allows(int rule, int mask) {
		return (mask & ~rule) == 0;
	}
}
