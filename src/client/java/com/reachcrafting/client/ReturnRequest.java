package com.reachcrafting.client;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;

record ReturnRequest(
	AbstractContainerMenu closingMenu,
	List<PulledResourcesTracker.WithdrawnItem> items,
	ScreenContextSnapshot originalContext,
	boolean reopenScreen
) {
}
