package com.reachcrafting.client;

import net.minecraft.world.inventory.AbstractContainerMenu;

interface CraftSession {
	void tick();

	void start();

	void stop(boolean closeContainer);

	void onOpenFailed(String reason);

	void onContainerContentsInitialized(AbstractContainerMenu menu);
}
