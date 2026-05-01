package com.reachcrafting.client.compat;

import com.reachcrafting.client.IngredientPlanning;
import com.reachcrafting.client.ReachCraftingConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
		
		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.put_pulled_resources_back"),
				config.putPulledResourcesBack()
			)
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.reachcrafting.put_pulled_resources_back"))
			.setSaveConsumer(config::setPutPulledResourcesBack)
			.build());

		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.restore_inventory_item_positions"),
				config.restoreInventoryItemPositions()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.restore_inventory_item_positions"))
			.setSaveConsumer(config::setRestoreInventoryItemPositions)
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

		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.remember_previous_search"),
				config.rememberPreviousSearch()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.remember_previous_search"))
			.setSaveConsumer(config::setRememberPreviousSearch)
			.build());

		general.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.show_total_output_counts"),
				config.showTotalOutputCounts()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.show_total_output_counts"))
			.setSaveConsumer(config::setShowTotalOutputCounts)
			.build());

		ConfigCategory containers = builder.getOrCreateCategory(Component.translatable("category.reachcrafting.containers"));
		
		containers.addEntry(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.in_world_filter_mode"),
				ReachCraftingConfig.InWorldFilterMode.class,
				config.inWorldFilterMode()
			)
			.setDefaultValue(ReachCraftingConfig.InWorldFilterMode.NONE)
			.setTooltip(Component.translatable("tooltip.reachcrafting.in_world_filter_mode"))
			.setSaveConsumer(config::setInWorldFilterMode)
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.in_world_filter_mode." + value.name().toLowerCase()))
			.build());

		containers.addEntry(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.show_filter_outlines"),
				ReachCraftingConfig.OutlineDisplayMode.class,
				config.showFilterOutlines()
			)
			.setDefaultValue(ReachCraftingConfig.OutlineDisplayMode.OFF)
			.setTooltip(Component.translatable("tooltip.reachcrafting.show_filter_outlines"))
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.outline_display_mode." + value.name().toLowerCase()))
			.setSaveConsumer(config::setShowFilterOutlines)
			.build());

		containers.addEntry(entries.startStrList(
				Component.translatable("option.reachcrafting.blacklisted_container_ids"),
				new ArrayList<>(config.blacklistedContainerIds())
			)
			.setDefaultValue(List.of())
			.setTooltip(Component.translatable("tooltip.reachcrafting.blacklisted_container_ids"))
			.setSaveConsumer(list -> config.setBlacklistedContainerIds(new HashSet<>(list)))
			.build());

		builder.setSavingRunnable(ReachCraftingConfig::save);
		return builder.build();
	}
}
