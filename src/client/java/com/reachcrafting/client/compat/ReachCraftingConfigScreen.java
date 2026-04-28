package com.reachcrafting.client.compat;

import com.reachcrafting.client.IngredientPlanning;
import com.reachcrafting.client.ReachCraftingConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ReachCraftingConfigScreen {
	private ReachCraftingConfigScreen() {
	}

	public static Screen create(Screen parent) {
		ReachCraftingConfig config = ReachCraftingConfig.get();
		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.translatable("title.reachcrafting.config"));

		ConfigCategory general = builder.getOrCreateCategory(Component.translatable("category.reachcrafting.general"));
		ConfigEntryBuilder entries = builder.entryBuilder();

		general.addEntry(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.count_preference"),
				IngredientPlanning.CountPreference.class,
				config.countPreference()
			)
			.setDefaultValue(IngredientPlanning.CountPreference.HIGHEST_TOTAL)
			.setTooltip(Component.translatable("tooltip.reachcrafting.count_preference"))
			.setSaveConsumer(config::setCountPreference)
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.count_preference." + value.name().toLowerCase()))
			.build());

		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.redistribute_to_craft_when_needed"),
				config.redistributeToCraftWhenNeeded()
			)
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.reachcrafting.redistribute_to_craft_when_needed"))
			.setSaveConsumer(config::setRedistributeToCraftWhenNeeded)
			.build());

		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.show_nearby_craftable_indicator"),
				config.showNearbyCraftableIndicator()
			)
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.reachcrafting.show_nearby_craftable_indicator"))
			.setSaveConsumer(config::setShowNearbyCraftableIndicator)
			.build());

		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.cache_containers_for_faster_search"),
				config.cacheContainersForFasterSearch()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.cache_containers_for_faster_search"))
			.setSaveConsumer(config::setCacheContainersForFasterSearch)
			.build());

		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.reach_craft_hold_and_release"),
				config.reachCraftHoldAndRelease()
			)
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.reachcrafting.reach_craft_hold_and_release"))
			.setSaveConsumer(config::setReachCraftHoldAndRelease)
			.build());

		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.reach_craft_close_overlay_after_release"),
				config.reachCraftCloseOverlayAfterRelease()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.reach_craft_close_overlay_after_release"))
			.setSaveConsumer(config::setReachCraftCloseOverlayAfterRelease)
			.build());

		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.reach_craft_prefer_inventory"),
				config.reachCraftPreferInventory()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.reach_craft_prefer_inventory"))
			.setSaveConsumer(config::setReachCraftPreferInventory)
			.build());

		general.addEntry(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.revolving_craft_handling"),
				ReachCraftingConfig.RevolvingCraftHandling.class,
				config.revolvingCraftHandling()
			)
			.setDefaultValue(ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK)
			.setTooltip(Component.translatable("tooltip.reachcrafting.revolving_craft_handling"))
			.setSaveConsumer(config::setRevolvingCraftHandling)
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.revolving_craft_handling." + value.name().toLowerCase()))
			.build());

		builder.setSavingRunnable(ReachCraftingConfig::save);
		return builder.build();
	}
}
