package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import net.minecraft.client.Minecraft;

/**
 * Dynamic Auto Craft: an auto craft (Alt, or a latched auto craft mode)
 * chooses its own sources instead of needing Ctrl for nearby containers.
 *
 * <p>It takes the strictest way that covers the request: finished items
 * already in storage (the green dot, per Existing Output Handling), then the
 * inventory, then nearby containers, then a chain craft from the inventory,
 * then a chain craft with nearby containers. A max (Shift) request has no
 * count to cover, so its ceiling is the recipe's own indicator. Everything
 * outside an auto craft keeps the old gestures, and Ctrl still forces nearby
 * containers on.</p>
 *
 * <p>The decision is only "may this request use nearby containers": with
 * them allowed, a request the inventory covers still crafts from the
 * inventory, and a chain is offered once the direct craft falls short. The
 * queue preview asks the same question, so it shows what the release does.</p>
 */
final class DynamicAutoCraft {
	private DynamicAutoCraft() {
	}

	/**
	 * Whether dynamic decides this request's sources. Ctrl held, Nearby
	 * Container Usage set to Always or Disabled, and Retrieval Mode all keep
	 * their own meaning.
	 */
	static boolean applies(boolean autoCraftRequested, boolean ctrlHeld) {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		return config.enabled()
			&& config.dynamicAutoCraft()
			&& !ctrlHeld
			&& config.enableNearbyContainerUsage()
			&& !config.nearbyAlwaysAllowed()
			&& !ExistingOutputRetrievalController.isEnabled()
			&& (autoCraftRequested || AutoCraftController.isEnabled());
	}

	/**
	 * Chain gate: a latched auto craft mode may chain without Alt held. Chain
	 * crafting is always an auto craft, so under dynamic the mode itself is
	 * the request for one.
	 */
	static boolean chainsWithoutAlt() {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		return config.enabled() && config.dynamicAutoCraft() && AutoCraftController.isEnabled();
	}

	/**
	 * Whether a plain click in a latched auto craft mode must take the mod's
	 * path. Vanilla's own placement only reaches the inventory, which is
	 * enough when the indicator says the inventory makes it.
	 */
	static boolean needsModPathForPlainClick(RecipeBookClickCapture.HeldRecipeAction action) {
		if (!applies(false, false) || action == null || action.collection() == null) {
			return false;
		}
		if (retrievable(action)) {
			return true;
		}
		RecipeButtonNearbyIndicator.IndicatorState state = RecipeButtonNearbyIndicator.stateFor(
			action.recipeId(), action.collection(), action.displayStack());
		return state != RecipeButtonNearbyIndicator.IndicatorState.LOCAL;
	}

	/**
	 * Whether this auto craft may use nearby containers.
	 *
	 * @param copies crafts requested (ignored for a max request)
	 * @param max a Shift request: as many as the indicator's way allows
	 */
	static boolean allowNearby(Minecraft client, RecipeBookClickCapture.HeldRecipeAction action, int copies, boolean max) {
		String way;
		boolean allow;
		if (retrievable(action)) {
			way = "retrieve";
			allow = true;
		} else if (max) {
			RecipeButtonNearbyIndicator.IndicatorState state = RecipeButtonNearbyIndicator.stateFor(
				action.recipeId(), action.collection(), action.displayStack());
			allow = state != RecipeButtonNearbyIndicator.IndicatorState.LOCAL
				&& state != RecipeButtonNearbyIndicator.IndicatorState.CHAIN_LOCAL;
			way = "max_" + state;
		} else {
			int request = Math.max(copies, 1);
			GridPreview.Plan local = GridPreview.planFor(client, action, request, false, true);
			if (covers(local) && !local.chain()) {
				way = "inventory";
				allow = false;
			} else {
				GridPreview.Plan nearby = GridPreview.planFor(client, action, request, true, true);
				if (covers(nearby) && !nearby.chain()) {
					way = "nearby";
					allow = true;
				} else if (covers(local)) {
					way = "chain_inventory";
					allow = false;
				} else {
					// A chain with nearby containers, or nothing covers the
					// request: the widest sources make the most of it.
					way = covers(nearby) ? "chain_nearby" : "widest";
					allow = true;
				}
			}
		}
		ReachCraftingMod.diag(
			"[dynamic_craft] recipe={} copies={} max={} way={} allow_nearby={}",
			action.recipeId(),
			copies,
			max,
			way,
			allow
		);
		return allow;
	}

	/** The green dot: finished items nearby that a retrieve-first step would take. */
	private static boolean retrievable(RecipeBookClickCapture.HeldRecipeAction action) {
		if (ReachCraftingConfig.get().existingOutputHandling() == ReachCraftingConfig.ExistingOutputHandling.CRAFT_ONLY) {
			return false;
		}
		return RecipeButtonNearbyIndicator.hasRetrievableOutput(
			action.recipeId(),
			action.collection(),
			action.displayStack() != null ? action.displayStack().copy() : net.minecraft.world.item.ItemStack.EMPTY,
			action.explicitVariantSelection()
		);
	}

	private static boolean covers(GridPreview.Plan plan) {
		return plan != null && !plan.shortOfRequest();
	}
}
