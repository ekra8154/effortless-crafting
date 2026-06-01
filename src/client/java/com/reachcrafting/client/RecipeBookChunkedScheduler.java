package com.reachcrafting.client;

import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

public final class RecipeBookChunkedScheduler {
	private static final int COLLECTIONS_PER_TICK = 12;

	private static Pass currentPass;

	private RecipeBookChunkedScheduler() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(RecipeBookChunkedScheduler::tick);
	}

	public static PassSnapshot beginOrReusePass(List<RecipeCollection> collections, Map<Integer, Integer> originalOrder) {
		StateKey stateKey = currentStateKey();
		if (stateKey == null || collections == null || collections.isEmpty()) {
			clear();
			return PassSnapshot.empty();
		}

		int collectionSignature = collectionSignature(collections);
		if (currentPass == null || !currentPass.matches(stateKey, collectionSignature)) {
			currentPass = new Pass(stateKey, collectionSignature, collections, originalOrder);
		} else {
			currentPass.refreshOriginalOrder(originalOrder);
		}

		return currentPass.snapshot();
	}

	public static void noteVisibleButtons(List<RecipeButton> buttons) {
		if (currentPass == null || buttons == null || buttons.isEmpty()) {
			return;
		}

		LinkedHashSet<RecipeCollection> visibleCollections = new LinkedHashSet<>();
		for (RecipeButton button : buttons) {
			if (button != null && button.visible && button.getCollection() != null) {
				visibleCollections.add(button.getCollection());
			}
		}
		currentPass.setVisiblePriority(visibleCollections);
	}

	public static void clear() {
		currentPass = null;
	}

	private static void tick(Minecraft client) {
		if (currentPass == null) {
			return;
		}

		StateKey stateKey = currentStateKey();
		if (stateKey == null || !stateKey.equals(currentPass.stateKey)) {
			clear();
			return;
		}
		if (currentPass.isComplete()) {
			return;
		}

		long startNanos = PerformanceProfiler.start();
		int processed = currentPass.processChunk(COLLECTIONS_PER_TICK);
		if (processed <= 0) {
			return;
		}

		PerformanceProfiler.record(
			"recipe_book.chunk_tick",
			startNanos,
			"processed=" + processed
				+ " settled=" + currentPass.settledCount()
				+ " pending=" + currentPass.pendingCount()
		);
		requestRefresh(client);
	}

	private static void requestRefresh(Minecraft client) {
		if (!(client.screen instanceof AbstractRecipeBookScreen<?> recipeBookScreen) || client.player == null) {
			return;
		}

		RecipeBookComponent<?> component = ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (component == null || !component.isVisible()) {
			return;
		}

		RecipeBookComponentAccessor accessor = (RecipeBookComponentAccessor) component;
		boolean filtering = client.player.getRecipeBook().isFiltering(accessor.getMenu().getRecipeBookType());
		accessor.invokeUpdateCollections(false, filtering);
	}

	private static StateKey currentStateKey() {
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.screen;
		if (!(screen instanceof InventoryScreen) && !(screen instanceof CraftingScreen)) {
			return null;
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null || minecraft.getCameraEntity() == null) {
			return null;
		}

		NearbyContainerCache.ReachableView reachableView = NearbyContainerCache.getReachableView(
			minecraft.level,
			minecraft.getCameraEntity(),
			player.blockInteractionRange()
		);
		long inventoryHash = computeInventoryHash(player, screen);
		long nearbyRevision = reachableView.revision();
		int reachableSignature = reachableSignature(reachableView);
		return new StateKey(screen.getClass(), inventoryHash, nearbyRevision, reachableSignature);
	}

	private static long computeInventoryHash(LocalPlayer player, Screen screen) {
		long hash = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				hash = hash * 31 + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).hashCode();
				hash = hash * 31 + stack.getCount();
			} else {
				hash = hash * 31;
			}
		}
		if (screen instanceof AbstractContainerScreen<?> containerScreen) {
			int gridSlotCount = screen instanceof InventoryScreen ? 4 : screen instanceof CraftingScreen ? 9 : 0;
			for (int slotIndex = 1; slotIndex <= gridSlotCount; slotIndex++) {
				ItemStack stack = containerScreen.getMenu().getSlot(slotIndex).getItem();
				if (!stack.isEmpty()) {
					hash = hash * 31 + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).hashCode();
					hash = hash * 31 + stack.getCount();
				} else {
					hash = hash * 31;
				}
			}
		}
		return hash;
	}

	private static int reachableSignature(NearbyContainerCache.ReachableView view) {
		int signature = 1;
		signature = (31 * signature) + view.aggregateCounts().hashCode();
		signature = (31 * signature) + view.snapshotsByKey().keySet().hashCode();
		return signature;
	}

	private static int collectionSignature(List<RecipeCollection> collections) {
		int signature = 1;
		for (RecipeCollection collection : collections) {
			signature = (31 * signature) + System.identityHashCode(collection);
		}
		return signature;
	}

	public record PassSnapshot(
		Map<RecipeCollection, RecipeBookSmartSorter.SortScore> settledScores,
		Map<Integer, Integer> recentRanks,
		int settledCount,
		int pendingCount
	) {
		private static PassSnapshot empty() {
			return new PassSnapshot(Map.of(), Map.of(), 0, 0);
		}

		public RecipeBookSmartSorter.SortScore scoreFor(RecipeCollection collection, int originalIndex) {
			RecipeBookSmartSorter.SortScore settled = settledScores.get(collection);
			return settled != null
				? settled
				: RecipeBookSmartSorter.fallbackScore(collection, recentRanks, originalIndex);
		}
	}

	private record StateKey(Class<?> screenClass, long inventoryHash, long nearbyRevision, int reachableSignature) {
	}

	private static final class Pass {
		private final StateKey stateKey;
		private final int collectionSignature;
		private final List<RecipeCollection> collections;
		private final Map<RecipeCollection, Integer> originalOrder;
		private final Map<RecipeCollection, RecipeBookSmartSorter.SortScore> settledScores = new IdentityHashMap<>();
		private final RecipeBookSmartSorter.SortPassContext sortContext = new RecipeBookSmartSorter.SortPassContext(RecipeBookSmartSorter.recentRanks());
		private final LinkedHashSet<RecipeCollection> visiblePriority = new LinkedHashSet<>();
		private final long startedAtNanos = PerformanceProfiler.start();
		private int cursor = 0;

		private Pass(
			StateKey stateKey,
			int collectionSignature,
			List<RecipeCollection> collections,
			Map<Integer, Integer> originalOrder
		) {
			this.stateKey = stateKey;
			this.collectionSignature = collectionSignature;
			this.collections = List.copyOf(collections);
			this.originalOrder = new IdentityHashMap<>();
			refreshOriginalOrder(originalOrder);
		}

		private boolean matches(StateKey stateKey, int collectionSignature) {
			return this.stateKey.equals(stateKey) && this.collectionSignature == collectionSignature;
		}

		private void refreshOriginalOrder(Map<Integer, Integer> originalOrder) {
			this.originalOrder.clear();
			for (RecipeCollection collection : collections) {
				this.originalOrder.put(
					collection,
					originalOrder.getOrDefault(System.identityHashCode(collection), Integer.MAX_VALUE)
				);
			}
		}

		private PassSnapshot snapshot() {
			return new PassSnapshot(
				Map.copyOf(settledScores),
				Map.copyOf(sortContext.recentRanks),
				settledCount(),
				pendingCount()
			);
		}

		private void setVisiblePriority(Set<RecipeCollection> visibleCollections) {
			visiblePriority.clear();
			for (RecipeCollection collection : visibleCollections) {
				if (originalOrder.containsKey(collection) && !settledScores.containsKey(collection)) {
					visiblePriority.add(collection);
				}
			}
		}

		private int processChunk(int budget) {
			int processed = 0;
			while (processed < budget) {
				RecipeCollection collection = nextCollection();
				if (collection == null) {
					break;
				}
				int originalIndex = originalOrder.getOrDefault(collection, Integer.MAX_VALUE);
				settledScores.put(collection, RecipeBookSmartSorter.fullScore(collection, sortContext, originalIndex));
				processed++;
			}
			if (isComplete()) {
				PerformanceProfiler.record(
					"recipe_book.chunk_pass_complete",
					startedAtNanos,
					"collections=" + collections.size()
						+ " chain_memo=" + sortContext.chainCraftableByRecipe.size()
						+ " nearby_memo=" + sortContext.nearbyCraftabilityByRecipe.size()
				);
			}
			return processed;
		}

		private RecipeCollection nextCollection() {
			var iterator = visiblePriority.iterator();
			while (iterator.hasNext()) {
				RecipeCollection collection = iterator.next();
				if (settledScores.containsKey(collection)) {
					iterator.remove();
					continue;
				}
				iterator.remove();
				return collection;
			}

			while (cursor < collections.size()) {
				RecipeCollection collection = collections.get(cursor++);
				if (!settledScores.containsKey(collection)) {
					return collection;
				}
			}
			return null;
		}

		private boolean isComplete() {
			return settledScores.size() >= collections.size();
		}

		private int settledCount() {
			return settledScores.size();
		}

		private int pendingCount() {
			return Math.max(0, collections.size() - settledScores.size());
		}
	}
}
