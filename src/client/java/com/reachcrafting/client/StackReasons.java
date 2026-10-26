package com.reachcrafting.client;

import net.minecraft.nbt.CompoundTag;
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
 * Inventory.findSlotMatchingUnusedItem's test); PRISTINE allows nothing, for chain steps.
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

	/**
	 * 1.20.1 keeps all of this in the stack's NBT. A stack whose tag matches
	 * its item's default instance (a fresh tool carries Damage:0) has no
	 * reasons; past that, Damage, Enchantments and display.Name map to their
	 * own bits and any other difference is OTHER_COMPONENTS.
	 */
	static int of(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		CompoundTag defaults = stack.getItem().getDefaultInstance().getTag();
		if (emptyToNull(tag) == null ? emptyToNull(defaults) == null : tag.equals(defaults)) {
			return 0;
		}
		int mask = 0;
		if (stack.isDamaged()) {
			mask |= DAMAGED;
		}
		if (stack.isEnchanted()) {
			mask |= ENCHANTED;
		}
		if (stack.hasCustomHoverName()) {
			mask |= RENAMED;
		}
		if (!withoutNamedReasons(tag).equals(withoutNamedReasons(defaults))) {
			mask |= OTHER_COMPONENTS;
		}
		return mask;
	}

	private static CompoundTag emptyToNull(CompoundTag tag) {
		return tag == null || tag.isEmpty() ? null : tag;
	}

	/** The tag minus what DAMAGED, ENCHANTED and RENAMED already cover. */
	private static CompoundTag withoutNamedReasons(CompoundTag tag) {
		CompoundTag rest = tag == null ? new CompoundTag() : tag.copy();
		rest.remove("Damage");
		rest.remove("Enchantments");
		if (rest.contains("display", 10)) {
			CompoundTag display = rest.getCompound("display").copy();
			display.remove("Name");
			if (display.isEmpty()) {
				rest.remove("display");
			} else {
				rest.put("display", display);
			}
		}
		return rest;
	}

	static boolean allows(int rule, int mask) {
		return (mask & ~rule) == 0;
	}
}
