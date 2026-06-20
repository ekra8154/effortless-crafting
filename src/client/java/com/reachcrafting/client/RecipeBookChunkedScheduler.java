package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.AbstractRecipeBookScreenAccessor;
import com.reachcrafting.client.mixin.RecipeBookComponentAccessor;
import com.reachcrafting.client.mixin.RecipeBookPageAccessor;
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
	private static final long CHUNK_TIME_BUDGET_NANOS = 5_000_000L; // 5ms

	private static Pass currentPass;
	private static int lastObservedPageIndex = 0;
	private static int frozenPageIndex = 0;
	private static boolean freezeResortUntilManualReopen = false;
	private static boolean pendingAutomatedRecipeBookReopen = false;
	private static boolean forceEagerNextSort = false;

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
			currentPass.refreshOriginalOrder(collections, originalOrder);
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

	public static void markPendingAutomatedRecipeBookReopen() {
		pendingAutomatedRecipeBookReopen = true;
	}

	public static boolean consumePendingAutomatedRecipeBookReopen() {
		boolean pending = pendingAutomatedRecipeBookReopen;
		pendingAutomatedRecipeBookReopen = false;
		return pending;
	}

	public static void markForceEagerNextSort() {
		forceEagerNextSort = true;
	}

	public static boolean consumeForceEagerNextSort() {
		boolean eager = forceEagerNextSort;
		forceEagerNextSort = false;
		return eager;
	}

	public static void resetFrozenPageState(String reason) {
		// com.reachcrafting.ReachCraftingMod.LOGGER.info(
		// 	"[recipe_sort] reset_frozen_state reason={} previous_page={} frozen_page={} freeze_before_clear={}",
		// 	reason,
		// 	lastObservedPageIndex,
		// 	frozenPageIndex,
		// 	freezeResortUntilManualReopen
		// );
		freezeResortUntilManualReopen = false;
		lastObservedPageIndex = 0;
		frozenPageIndex = 0;
	}

	public static void noteVisiblePageIndex(int pageIndex) {
		int previousPageIndex = lastObservedPageIndex;
		lastObservedPageIndex = Math.max(pageIndex, 0);
		if (lastObservedPageIndex > 0) {
			freezeResortUntilManualReopen = true;
			frozenPageIndex = lastObservedPageIndex;
		} else if (!freezeResortUntilManualReopen) {
			lastObservedPageIndex = 0;
			frozenPageIndex = 0;
		}
		if (previousPageIndex != lastObservedPageIndex) {
			// com.reachcrafting.ReachCraftingMod.LOGGER.info(
			// 	"[recipe_sort] visible_page changed previous={} current={} frozen_page={} freeze={}",
			// 	previousPageIndex,
			// 	lastObservedPageIndex,
			// 	frozenPageIndex,
			// 	freezeResortUntilManualReopen
			// );
		}
	}

	public static void onRecipeBookVisibilityChanged(boolean visible) {
		// com.reachcrafting.ReachCraftingMod.LOGGER.info(
		// 	"[recipe_sort] recipe_book visibility={} page={} frozen_page={} freeze_before_clear={}",
		// 	visible,
		// 	lastObservedPageIndex,
		// 	frozenPageIndex,
		// 	freezeResortUntilManualReopen
		// );
		if (!visible) {
			resetFrozenPageState("recipe_book_hidden");
		}
	}

	public static void onRecentRecipesChanged() {
		Minecraft client = Minecraft.getInstance();
		if (ContainerUtils.isAnySessionActiveExcludingRetrievalMode()) {
			ReachCraftingMod.LOGGER.info("[recipe_sort] recent_change skipped reason=active_session");
			return;
		}
		if (shouldFreezeResort()) {
			ReachCraftingMod.LOGGER.info(
				"[recipe_sort] recent_change skipped reason=frozen page={} freeze={}",
				frozenPageIndex,
				freezeResortUntilManualReopen
			);
			return;
		}
		ReachCraftingMod.LOGGER.info("[recipe_sort] recent_change requesting refresh");
		requestRefresh(client);
	}

	public static void forceVisibleRecipeBookRefresh() {
		ReachCraftingMod.LOGGER.info("[retrieval_virtual] forceVisibleRecipeBookRefresh");
		requestRefresh(Minecraft.getInstance(), true);
	}

	public static boolean isCurrentRecipeBookOnFirstPage() {
		return !shouldFreezeResort() && isRecipeBookOnFirstPage(Minecraft.getInstance());
	}

	public static boolean shouldFreezeResort() {
		return freezeResortUntilManualReopen;
	}

	public static int frozenPageIndex() {
		return frozenPageIndex;
	}

	private static void tick(Minecraft client) {
		if (currentPass == null) {
			return;
		}

		StateKey stateKey = currentStateKey();
		if (stateKey == null) {
			return;
		}
		if (!stateKey.equals(currentPass.stateKey)) {
			clear();
			return;
		}
		if (currentPass.isComplete()) {
			return;
		}

		long startNanos = PerformanceProfiler.start();
		int processed = currentPass.processChunk(CHUNK_TIME_BUDGET_NANOS);
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
		requestRefresh(client, false);
	}

	private static void requestRefresh(Minecraft client, boolean force) {
		if (ContainerUtils.isAnySessionActiveExcludingRetrievalMode()) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] refresh skipped reason=active_session_excluding_retrieval force={}", force);
			return;
		}
		if (!force && shouldFreezeResort()) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] refresh skipped reason=frozen force={} page={} freeze={}", force, frozenPageIndex, freezeResortUntilManualReopen);
			return;
		}
		if (!(client.gui.screen() instanceof AbstractRecipeBookScreen<?> recipeBookScreen) || client.player == null) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] refresh skipped reason=unsupported_screen force={} screen={} player={}", force, client.gui.screen() != null ? client.gui.screen().getClass().getSimpleName() : "null", client.player != null);
			return;
		}

		RecipeBookComponent<?> component = ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (component == null || !component.isVisible()) {
			ReachCraftingMod.LOGGER.info("[retrieval_virtual] refresh skipped reason=recipe_book_hidden force={} component={} visible={}", force, component != null, component != null && component.isVisible());
			return;
		}

		RecipeBookComponentAccessor accessor = (RecipeBookComponentAccessor) component;
		boolean filtering = client.player.getRecipeBook().isFiltering(accessor.getMenu().getRecipeBookType());
		ReachCraftingMod.LOGGER.info("[retrieval_virtual] refresh invokeUpdateCollections force={} filtering={} page={} frozen_page={} freeze={}", force, filtering, lastObservedPageIndex, frozenPageIndex, freezeResortUntilManualReopen);
		accessor.invokeUpdateCollections(force, filtering);
	}

	private static boolean isRecipeBookOnFirstPage(Minecraft client) {
		if (!(client.gui.screen() instanceof AbstractRecipeBookScreen<?> recipeBookScreen)) {
			return false;
		}

		RecipeBookComponent<?> component = ((AbstractRecipeBookScreenAccessor) recipeBookScreen).getRecipeBookComponent();
		if (component == null || !component.isVisible()) {
			return false;
		}

		var page = ((RecipeBookComponentAccessor) component).getRecipeBookPage();
		return page != null && ((RecipeBookPageAccessor) page).getCurrentPage() == 0;
	}

	private static StateKey currentStateKey() {
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = minecraft.gui.screen();
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
		return new StateKey(screen.getClass(), inventoryHash, nearbyRevision, reachableSignature, ContainerUtils.isExistingOutputRetrievalEnabled());
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
		Map<RecipeCollection, RecipeBookSmartSorter.SortScore> fastScores,
		Map<RecipeCollection, RecipeBookSmartSorter.SortScore> settledScores,
		Map<Integer, Integer> recentRanks,
		int settledCount,
		int pendingCount
	) {
		public static PassSnapshot empty() {
			return new PassSnapshot(Map.of(), Map.of(), Map.of(), 0, 0);
		}

		public RecipeBookSmartSorter.SortScore scoreFor(RecipeCollection collection, int originalIndex) {
			RecipeBookSmartSorter.SortScore settled = settledScores.get(collection);
			if (settled != null) {
				return settled;
			}
			RecipeBookSmartSorter.SortScore fast = fastScores.get(collection);
			if (fast != null) {
				return fast;
			}
			return new RecipeBookSmartSorter.SortScore(5, Integer.MAX_VALUE, originalIndex);
		}
	}

	private record StateKey(Class<?> screenClass, long inventoryHash, long nearbyRevision, int reachableSignature, boolean retrievalModeEnabled) {
	}

	private static final class Pass {
		private final StateKey stateKey;
		private final int collectionSignature;
		private final List<RecipeCollection> collections;
		private final Map<RecipeCollection, Integer> originalOrder;
		private final Map<RecipeCollection, RecipeBookSmartSorter.SortScore> fastScores = new IdentityHashMap<>();
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
			this.originalOrder = new IdentityHashMap<>();
			
			refreshOriginalOrder(collections, originalOrder);
			populateFastScores(collections);

			// Now sort the internal processing list by fastScore to evaluate the most likely items first
			List<RecipeCollection> sortedForChunking = new java.util.ArrayList<>(collections);
			sortedForChunking.sort(java.util.Comparator.comparing(fastScores::get));
			this.collections = List.copyOf(sortedForChunking);
		}

		private boolean matches(StateKey stateKey, int collectionSignature) {
			return this.stateKey.equals(stateKey) && this.collectionSignature == collectionSignature;
		}

		private void refreshOriginalOrder(List<RecipeCollection> rawCollections, Map<Integer, Integer> originalOrder) {
			this.originalOrder.clear();
			for (RecipeCollection collection : rawCollections) {
				this.originalOrder.put(
					collection,
					originalOrder.getOrDefault(System.identityHashCode(collection), Integer.MAX_VALUE)
				);
			}
		}

		private void populateFastScores(List<RecipeCollection> rawCollections) {
			for (RecipeCollection collection : rawCollections) {
				int originalIndex = originalOrder.getOrDefault(collection, Integer.MAX_VALUE);
				fastScores.put(collection, RecipeBookSmartSorter.fastScore(collection, sortContext, originalIndex));
			}
		}

		private PassSnapshot snapshot() {
			return new PassSnapshot(
				Map.copyOf(fastScores),
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

		private int processChunk(long timeBudgetNanos) {
			int processed = 0;
			long startNanos = System.nanoTime();
			while (System.nanoTime() - startNanos < timeBudgetNanos) {
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
