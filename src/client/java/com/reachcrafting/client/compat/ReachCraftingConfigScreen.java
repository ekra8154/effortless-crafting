package com.reachcrafting.client.compat;

import com.reachcrafting.client.IngredientPlanning;
import com.reachcrafting.client.ReachCraftingConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import java.util.ArrayList;
import java.util.HashSet;
// import java.util.List;
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

		ConfigEntryBuilder entries = builder.entryBuilder();

		// TAB 1: Crafting & UI
		ConfigCategory craftingUi = builder.getOrCreateCategory(Component.translatable("category.reachcrafting.crafting_ui"));

		craftingUi.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.enabled"),
				config.enabled()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.enabled"))
			.setSaveConsumer(config::setEnabled)
			.build());

		craftingUi.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.show_total_output_counts"),
				config.showTotalOutputCounts()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.show_total_output_counts"))
			.setSaveConsumer(config::setShowTotalOutputCounts)
			.build());

		craftingUi.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.restore_inventory_item_positions"),
				config.restoreInventoryItemPositions()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.restore_inventory_item_positions"))
			.setSaveConsumer(config::setRestoreInventoryItemPositions)
			.build());

		craftingUi.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.type_to_focus_search"),
				config.typeToFocusSearch()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.type_to_focus_search"))
			.setSaveConsumer(config::setTypeToFocusSearch)
			.build());

		craftingUi.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.remember_previous_search"),
				config.rememberPreviousSearch()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.remember_previous_search"))
			.setSaveConsumer(config::setRememberPreviousSearch)
			.build());

		craftingUi.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.reach_craft_close_overlay_after_release"),
				config.reachCraftCloseOverlayAfterRelease()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.reach_craft_close_overlay_after_release"))
			.setSaveConsumer(config::setReachCraftCloseOverlayAfterRelease)
			.build());

		craftingUi.addEntry(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.scroll_to_pull"),
				ReachCraftingConfig.ScrollToPullMode.class,
				config.scrollToPullMode()
			)
			.setDefaultValue(ReachCraftingConfig.ScrollToPullMode.WHILE_RESULT_OR_INVENTORY_SLOT_HOVERED)
			.setTooltip(Component.translatable("tooltip.reachcrafting.scroll_to_pull"))
			.setSaveConsumer(config::setScrollToPullMode)
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.scroll_to_pull." + value.name().toLowerCase()))
			.build());

		// Sub-Category: Planning & Logic
		var logicGroup = entries.startSubCategory(Component.translatable("category.reachcrafting.logic_planning"));
		logicGroup.setExpanded(true);

		logicGroup.add(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.count_preference"),
				IngredientPlanning.CountPreference.class,
				config.countPreference()
			)
			.setDefaultValue(IngredientPlanning.CountPreference.HIGHEST_TOTAL)
			.setTooltip(Component.translatable("tooltip.reachcrafting.count_preference"))
			.setSaveConsumer(config::setCountPreference)
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.count_preference." + value.name().toLowerCase()))
			.build());

		logicGroup.add(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.revolving_craft_handling"),
				ReachCraftingConfig.RevolvingCraftHandling.class,
				config.revolvingCraftHandling()
			)
			.setDefaultValue(ReachCraftingConfig.RevolvingCraftHandling.PREFER_CLICKED_TYPE_WITH_COUNT_FALLBACK)
			.setTooltip(Component.translatable("tooltip.reachcrafting.revolving_craft_handling"))
			.setSaveConsumer(config::setRevolvingCraftHandling)
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.revolving_craft_handling." + value.name().toLowerCase()))
			.build());

		logicGroup.add(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.input_counter_visibility"),
				ReachCraftingConfig.InputCounterVisibility.class,
				config.inputCounterVisibility()
			)
			.setDefaultValue(ReachCraftingConfig.InputCounterVisibility.SHOW_WHILE_QUEUEING)
			.setTooltip(Component.translatable("tooltip.reachcrafting.input_counter_visibility"))
			.setSaveConsumer(config::setInputCounterVisibility)
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.input_counter_visibility." + value.name().toLowerCase()))
			.build());

		logicGroup.add(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.redistribute_to_craft_when_needed"),
				config.redistributeToCraftWhenNeeded()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.redistribute_to_craft_when_needed"))
			.setSaveConsumer(config::setRedistributeToCraftWhenNeeded)
			.build());

		logicGroup.add(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.inventory_2x2_offhand_consolidation"),
				config.inventory2x2OffhandConsolidation()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.inventory_2x2_offhand_consolidation"))
			.setSaveConsumer(config::setInventory2x2OffhandConsolidation)
			.build());

		logicGroup.add(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.eject_items_when_full"),
				config.ejectItemsWhenFull()
			)
			.setDefaultValue(false)
			.setTooltip(Component.translatable("tooltip.reachcrafting.eject_items_when_full"))
			.setSaveConsumer(config::setEjectItemsWhenFull)
			.build());

		logicGroup.add(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.auto_craft_capability"),
				ReachCraftingConfig.AutoCraftCapability.class,
				config.autoCraftCapability()
			)
			.setDefaultValue(ReachCraftingConfig.AutoCraftCapability.NONE)
			.setTooltip(Component.translatable("tooltip.reachcrafting.auto_craft_capability"))
			.setSaveConsumer(config::setAutoCraftCapability)
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.auto_craft_capability." + value.name().toLowerCase()))
			.build());


		craftingUi.addEntry(logicGroup.build());


		// TAB 2: Nearby Containers
		ConfigCategory containers = builder.getOrCreateCategory(Component.translatable("category.reachcrafting.nearby_containers"));

		containers.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.enable_nearby_container_usage"),
				config.enableNearbyContainerUsage()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.enable_nearby_container_usage"))
			.setSaveConsumer(config::setEnableNearbyContainerUsage)
			.build());

		containers.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.reach_craft_prefer_inventory"),
				config.reachCraftPreferInventory()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.reach_craft_prefer_inventory"))
			.setSaveConsumer(config::setReachCraftPreferInventory)
			.build());

		containers.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.show_nearby_craftable_indicator"),
				config.showNearbyCraftableIndicator()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.show_nearby_craftable_indicator"))
			.setSaveConsumer(config::setShowNearbyCraftableIndicator)
			.build());

		containers.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.cache_containers_for_faster_search"),
				config.cacheContainersForFasterSearch()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.cache_containers_for_faster_search"))
			.setSaveConsumer(config::setCacheContainersForFasterSearch)
			.build());

		containers.addEntry(entries.startBooleanToggle(
				Component.translatable("option.reachcrafting.put_pulled_resources_back"),
				config.putPulledResourcesBack()
			)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("tooltip.reachcrafting.put_pulled_resources_back"))
			.setSaveConsumer(config::setPutPulledResourcesBack)
			.build());


		// Sub-Category: Filtering & Highlighting
		var filterGroup = entries.startSubCategory(Component.translatable("category.reachcrafting.filtering_highlighting"));
		
		filterGroup.add(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.in_world_filter_mode"),
				ReachCraftingConfig.InWorldFilterMode.class,
				config.inWorldFilterMode()
			)
			.setDefaultValue(ReachCraftingConfig.InWorldFilterMode.NONE)
			.setTooltip(Component.translatable("tooltip.reachcrafting.in_world_filter_mode"))
			.setSaveConsumer(config::setInWorldFilterMode)
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.in_world_filter_mode." + value.name().toLowerCase()))
			.build());

		filterGroup.add(entries.startEnumSelector(
				Component.translatable("option.reachcrafting.show_filter_outlines"),
				ReachCraftingConfig.OutlineDisplayMode.class,
				config.showFilterOutlines()
			)
			.setDefaultValue(ReachCraftingConfig.OutlineDisplayMode.KEYBIND)
			.setTooltip(Component.translatable("tooltip.reachcrafting.show_filter_outlines"))
			.setEnumNameProvider(value -> Component.translatable("enum.reachcrafting.outline_display_mode." + value.name().toLowerCase()))
			.setSaveConsumer(config::setShowFilterOutlines)
			.build());

		filterGroup.add(entries.startStrList(
				Component.translatable("option.reachcrafting.blacklisted_container_ids"),
				new ArrayList<>(config.blacklistedContainerIds())
			)
			.setExpanded(true)
			.setDefaultValue(new ArrayList<>(ReachCraftingConfig.DEFAULT_BLACKLIST))
			.setTooltip(Component.translatable("tooltip.reachcrafting.blacklisted_container_ids"))
			.setSaveConsumer(list -> config.setBlacklistedContainerIds(new HashSet<>(list)))
			.build());

		containers.addEntry(filterGroup.build());

		builder.setSavingRunnable(ReachCraftingConfig::save);
		return builder.build();
	}
}
