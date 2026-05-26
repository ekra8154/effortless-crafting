package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import com.reachcrafting.client.mixin.ClientRecipeBookAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import net.fabricmc.fabric.api.recipe.v1.FabricRecipeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

final class ChainCraftPlanner {
	private static final int UNCRAFTABLE_COST = 1_000_000;
	private final Minecraft minecraft;
	private final LocalPlayer player;
	private final boolean allowNearby;
	private final int gridSlotCount;
	private final ContextMap context;
	private final Map<String, List<Candidate>> recipesByOutput;

	private ChainCraftPlanner(Minecraft minecraft, LocalPlayer player, boolean allowNearby, int gridSlotCount) {
		this.minecraft = minecraft;
		this.player = player;
		this.allowNearby = allowNearby;
		this.gridSlotCount = gridSlotCount;
		this.context = SlotDisplayContext.fromLevel(minecraft.level);
		this.recipesByOutput = buildRecipeIndex();
		int candidateCount = this.recipesByOutput.values().stream().mapToInt(List::size).sum();
		ReachCraftingMod.LOGGER.info(
			"[chain_debug] index outputs={} candidates={} grid_slots={} allow_nearby={}",
			this.recipesByOutput.size(),
			candidateCount,
			gridSlotCount,
			allowNearby
		);
	}

	static Optional<ChainCraftPlan> plan(
		Minecraft minecraft,
		LocalPlayer player,
		RecipeVariantResolver.Selection finalSelection,
		Map<String, Integer> availableCounts,
		boolean allowNearby,
		int requestedRecipeCopies
		) {
		if (minecraft == null
			|| minecraft.level == null
			|| player == null
			|| finalSelection == null
			|| requestedRecipeCopies <= 0) {
			ReachCraftingMod.LOGGER.info(
				"[chain_debug] abort reason=invalid_inputs minecraft={} level={} player={} selection={} requested={}",
				minecraft != null,
				minecraft != null && minecraft.level != null,
				player != null,
				finalSelection != null,
				requestedRecipeCopies
			);
			return Optional.empty();
		}
		int gridSlotCount = minecraft.screen instanceof InventoryScreen ? 4 : minecraft.screen instanceof CraftingScreen ? 9 : 0;
		if (gridSlotCount <= 0) {
			ReachCraftingMod.LOGGER.info(
				"[chain_debug] abort reason=unsupported_screen screen={}",
				minecraft.screen != null ? minecraft.screen.getClass().getName() : "<none>"
			);
			return Optional.empty();
		}
		return new ChainCraftPlanner(minecraft, player, allowNearby, gridSlotCount)
			.plan(finalSelection, availableCounts, requestedRecipeCopies);
	}

	static Optional<ChainCraftPlan> planMax(
		Minecraft minecraft,
		LocalPlayer player,
		RecipeVariantResolver.Selection finalSelection,
		Map<String, Integer> availableCounts,
		boolean allowNearby,
		int upperBound
	) {
		int high = Math.max(upperBound, 0);
		if (high <= 0) {
			ReachCraftingMod.LOGGER.info("[chain_debug] plan_max abort reason=non_positive_upper_bound upper_bound={}", upperBound);
			return Optional.empty();
		}

		ChainCraftPlan bestPlan = null;
		int low = 0;
		while (low < high) {
			int mid = (low + high + 1) / 2;
			Optional<ChainCraftPlan> candidate = plan(minecraft, player, finalSelection, availableCounts, allowNearby, mid);
			if (candidate.isPresent()) {
				bestPlan = candidate.get();
				low = mid;
			} else {
				high = mid - 1;
			}
		}
		return Optional.ofNullable(bestPlan);
	}

	private Optional<ChainCraftPlan> plan(
		RecipeVariantResolver.Selection finalSelection,
		Map<String, Integer> availableCounts,
		int requestedRecipeCopies
	) {
		PlanningState state = new PlanningState(new LinkedHashMap<>(availableCounts), new LinkedHashMap<>());
		Candidate finalCandidate = new Candidate(
			finalSelection.recipeId(),
			resolveCollection(finalSelection.recipeId()),
			finalSelection.ingredientSummary(),
			finalSelection.displayStack().copy(),
			true
		);

		ReachCraftingMod.LOGGER.info(
			"[chain_debug] start final_recipe={} output={} requested_copies={} initial_counts={}",
			finalCandidate.recipeId(),
			ContainerUtils.formatStack(finalCandidate.displayStack()),
			requestedRecipeCopies,
			AvailableItemSnapshot.formatCounts(state.counts)
		);
		boolean planned = planRecipe(finalCandidate, requestedRecipeCopies, state, new HashSet<>(), true);
		List<ChainCraftPlan.Step> steps = state.toSteps(allowNearby);
		if (!planned || steps.size() <= 1) {
			ReachCraftingMod.LOGGER.info(
				"[chain_debug] unavailable final_recipe={} planned={} steps={} final_counts={}",
				finalCandidate.recipeId(),
				planned,
				steps.size(),
				AvailableItemSnapshot.formatCounts(state.counts)
			);
			return Optional.empty();
		}
		ReachCraftingMod.LOGGER.info(
			"[chain_debug] planned final_recipe={} steps={} final_counts={}",
			finalCandidate.recipeId(),
			formatSteps(steps),
			AvailableItemSnapshot.formatCounts(state.counts)
		);
		return Optional.of(new ChainCraftPlan(steps, finalSelection.displayStack(), requestedRecipeCopies));
	}

	private boolean planRecipe(
		Candidate candidate,
		int recipeCopies,
		PlanningState state,
		Set<String> resolvingItemIds,
		boolean finalStep
	) {
		ReachCraftingMod.LOGGER.info(
			"[chain_debug] recipe_enter recipe={} output={} copies={} final_step={} resolving={}",
			candidate.recipeId(),
			ContainerUtils.formatStack(candidate.displayStack()),
			recipeCopies,
			finalStep,
			resolvingItemIds
		);
		Map<String, Integer> required = new LinkedHashMap<>();
		List<RecipeIngredientSummary.IngredientSlot> flexibleSlots = new ArrayList<>();
		for (RecipeIngredientSummary.IngredientSlot slot : candidate.ingredientSummary().slots()) {
			if (slot.isEmpty()) {
				continue;
			}
			if (slot.isExact()) {
				required.merge(slot.itemIds().getFirst(), recipeCopies, Integer::sum);
			} else {
				flexibleSlots.add(slot);
			}
		}

		for (Map.Entry<String, Integer> entry : required.entrySet()) {
			if (!ensureItem(entry.getKey(), entry.getValue(), state, resolvingItemIds)) {
				ReachCraftingMod.LOGGER.info(
					"[chain_debug] recipe_fail reason=ensure_exact_failed recipe={} output={} ingredient={} required={} counts={}",
					candidate.recipeId(),
					ContainerUtils.formatStack(candidate.displayStack()),
					entry.getKey(),
					entry.getValue(),
					AvailableItemSnapshot.formatCounts(state.counts)
				);
				return false;
			}
		}

		if (!planIngredientSlots(candidate, recipeCopies, flexibleSlots, 0, state, resolvingItemIds, required)) {
			ReachCraftingMod.LOGGER.info(
				"[chain_debug] recipe_fail reason=ingredient_slots_failed recipe={} output={} counts={} resolving={}",
				candidate.recipeId(),
				ContainerUtils.formatStack(candidate.displayStack()),
				AvailableItemSnapshot.formatCounts(state.counts),
				resolvingItemIds
			);
			return false;
		}
		ReachCraftingMod.LOGGER.info(
			"[chain_debug] recipe_required recipe={} output={} required={}",
			candidate.recipeId(),
			ContainerUtils.formatStack(candidate.displayStack()),
			AvailableItemSnapshot.formatCounts(required)
		);

		for (Map.Entry<String, Integer> entry : required.entrySet()) {
			subtract(state.counts, entry.getKey(), entry.getValue());
		}
		String outputId = itemId(candidate.displayStack());
		state.counts.merge(outputId, Math.max(candidate.displayStack().getCount(), 1) * recipeCopies, Integer::sum);
		state.schedule(candidate, recipeCopies, finalStep, required);
		ReachCraftingMod.LOGGER.info(
			"[chain_debug] recipe_planned recipe={} output={} copies={} counts={}",
			candidate.recipeId(),
			ContainerUtils.formatStack(candidate.displayStack()),
			recipeCopies,
			AvailableItemSnapshot.formatCounts(state.counts)
		);
		return true;
	}

	private boolean planIngredientSlots(
		Candidate candidate,
		int recipeCopies,
		List<RecipeIngredientSummary.IngredientSlot> slots,
		int slotIndex,
		PlanningState state,
		Set<String> resolvingItemIds,
		Map<String, Integer> required
	) {
		if (slotIndex >= slots.size()) {
			return true;
		}

		RecipeIngredientSummary.IngredientSlot slot = slots.get(slotIndex);
		for (String itemId : orderedIngredientItems(slot, state.counts, resolvingItemIds)) {
			PlanningState trialState = state.copy();
			Map<String, Integer> trialRequired = new LinkedHashMap<>(required);
			int totalRequired = trialRequired.getOrDefault(itemId, 0) + recipeCopies;
			trialRequired.put(itemId, totalRequired);
			if (!ensureItem(itemId, totalRequired, trialState, resolvingItemIds)) {
				ReachCraftingMod.LOGGER.info(
					"[chain_debug] ingredient_choice_failed recipe={} output={} slot={} item={} required={} counts={}",
					candidate.recipeId(),
					ContainerUtils.formatStack(candidate.displayStack()),
					slotIndex,
					itemId,
					totalRequired,
					AvailableItemSnapshot.formatCounts(trialState.counts)
				);
				continue;
			}
			if (planIngredientSlots(candidate, recipeCopies, slots, slotIndex + 1, trialState, resolvingItemIds, trialRequired)) {
				state.replaceWith(trialState);
				required.clear();
				required.putAll(trialRequired);
				return true;
			}
		}

		ReachCraftingMod.LOGGER.info(
			"[chain_debug] recipe_fail reason=no_ingredient_choice recipe={} output={} slot={} slot_items={} counts={} resolving={}",
			candidate.recipeId(),
			ContainerUtils.formatStack(candidate.displayStack()),
			slotIndex,
			slot.itemIds(),
			AvailableItemSnapshot.formatCounts(state.counts),
			resolvingItemIds
		);
		return false;
	}

	private boolean ensureItem(
		String itemId,
		int requiredCount,
		PlanningState state,
		Set<String> resolvingItemIds
	) {
		int available = state.counts.getOrDefault(itemId, 0);
		List<Candidate> candidates = recipesByOutput.getOrDefault(itemId, List.of());
		ReachCraftingMod.LOGGER.info(
			"[chain_debug] ensure item={} required={} available={} candidates={} resolving={}",
			itemId,
			requiredCount,
			available,
			formatCandidates(candidates),
			resolvingItemIds
		);
		if (available >= requiredCount) {
			return true;
		}
		if (!resolvingItemIds.add(itemId)) {
			ReachCraftingMod.LOGGER.info("[chain_debug] ensure_fail reason=cycle item={} resolving={}", itemId, resolvingItemIds);
			return false;
		}
		try {
			for (Candidate candidate : orderedCandidates(candidates, state.counts)) {
				int outputPerCraft = Math.max(candidate.displayStack().getCount(), 1);
				int missing = requiredCount - state.counts.getOrDefault(itemId, 0);
				int copies = (missing + outputPerCraft - 1) / outputPerCraft;
				if (copies <= 0) {
					return true;
				}
				PlanningState trialState = state.copy();
				ReachCraftingMod.LOGGER.info(
					"[chain_debug] ensure_try item={} recipe={} output={} missing={} copies={}",
					itemId,
					candidate.recipeId(),
					ContainerUtils.formatStack(candidate.displayStack()),
					missing,
					copies
				);
				if (planRecipe(candidate, copies, trialState, resolvingItemIds, false)
					&& trialState.counts.getOrDefault(itemId, 0) >= requiredCount) {
					state.replaceWith(trialState);
					ReachCraftingMod.LOGGER.info(
						"[chain_debug] ensure_success item={} recipe={} available_after={}",
						itemId,
						candidate.recipeId(),
						state.counts.getOrDefault(itemId, 0)
					);
					return true;
				}
				ReachCraftingMod.LOGGER.info(
					"[chain_debug] ensure_try_failed item={} recipe={} available_after_trial={}",
					itemId,
					candidate.recipeId(),
					trialState.counts.getOrDefault(itemId, 0)
				);
			}
			ReachCraftingMod.LOGGER.info("[chain_debug] ensure_fail reason=no_candidate_succeeded item={} candidates={}", itemId, formatCandidates(candidates));
			return false;
		} finally {
			resolvingItemIds.remove(itemId);
		}
	}

	private Optional<String> chooseIngredientItem(
		RecipeIngredientSummary.IngredientSlot slot,
		Map<String, Integer> virtualCounts,
		Set<String> resolvingItemIds
	) {
		return orderedIngredientItems(slot, virtualCounts, resolvingItemIds).stream()
			.findFirst();
	}

	private List<String> orderedIngredientItems(
		RecipeIngredientSummary.IngredientSlot slot,
		Map<String, Integer> virtualCounts,
		Set<String> resolvingItemIds
	) {
		List<String> ordered = slot.itemIds().stream()
			.filter(itemId -> !resolvingItemIds.contains(itemId))
			.filter(itemId -> virtualCounts.getOrDefault(itemId, 0) > 0 || recipesByOutput.containsKey(itemId))
			.sorted(compareIngredientChoices(virtualCounts))
			.toList();
		Optional<String> resolverChoice = resolveIngredientChoiceWithExistingPlanner(ordered, virtualCounts);
		if (resolverChoice.isEmpty()) {
			return ordered;
		}
		List<String> reordered = new ArrayList<>();
		reordered.add(resolverChoice.get());
		ordered.stream()
			.filter(itemId -> !itemId.equals(resolverChoice.get()))
			.forEach(reordered::add);
		return List.copyOf(reordered);
	}

	private Optional<String> resolveIngredientChoiceWithExistingPlanner(List<String> itemIds, Map<String, Integer> virtualCounts) {
		if (itemIds.size() <= 1 || minecraft.screen == null) {
			return Optional.empty();
		}
		if (itemIds.stream().anyMatch(itemId -> virtualCounts.getOrDefault(itemId, 0) > 0)) {
			return Optional.empty();
		}
		AvailableItemSnapshot availableItems = AvailableItemSnapshot.capture(player, minecraft.screen);
		for (String itemId : itemIds) {
			for (Candidate candidate : recipesByOutput.getOrDefault(itemId, List.of())) {
				if (candidate.collection() == null) {
					continue;
				}
				RecipeVariantResolver.Selection selection = RecipeVariantResolver.resolve(
					minecraft,
					player,
					candidate.recipeId(),
					candidate.collection(),
					candidate.displayStack(),
					false,
					true,
					availableItems,
					virtualCounts,
					virtualCounts,
					false,
					true,
					1
				);
				if (selection != null && itemIds.contains(selection.outputItemId()) && selection.copiesAvailable() > 0) {
					ReachCraftingMod.LOGGER.info(
						"[chain_debug] resolver_choice requested_items={} clicked_recipe={} selected_recipe={} selected_output={} counts={}",
						itemIds,
						candidate.recipeId(),
						selection.recipeId(),
						selection.outputItemId(),
						AvailableItemSnapshot.formatCounts(virtualCounts)
					);
					return Optional.of(selection.outputItemId());
				}
			}
		}
		return Optional.empty();
	}

	private Comparator<String> compareIngredientChoices(Map<String, Integer> virtualCounts) {
		Comparator<String> byAvailableNow = Comparator.comparingInt((String itemId) -> virtualCounts.getOrDefault(itemId, 0) > 0 ? 0 : 1);
		Comparator<String> byRecipeInputAvailability = Comparator.comparingInt((String itemId) -> bestCandidateScore(itemId, virtualCounts, new HashSet<>())).reversed();
		Comparator<String> byCount = Comparator.comparingInt(itemId -> virtualCounts.getOrDefault(itemId, 0));
		if (ReachCraftingConfig.get().countPreference() == IngredientPlanning.CountPreference.HIGHEST_TOTAL) {
			byCount = byCount.reversed();
		}
		return byAvailableNow.thenComparing(byRecipeInputAvailability).thenComparing(byCount).thenComparing(Comparator.naturalOrder());
	}

	private int bestCandidateScore(String itemId, Map<String, Integer> virtualCounts, Set<String> scoringItemIds) {
		int available = virtualCounts.getOrDefault(itemId, 0);
		if (available > 0) {
			return 1_000_000 + available;
		}
		if (!scoringItemIds.add(itemId)) {
			return 0;
		}
		try {
			return recipesByOutput.getOrDefault(itemId, List.of()).stream()
				.mapToInt(candidate -> candidateScore(candidate, virtualCounts, scoringItemIds))
				.max()
				.orElse(0);
		} finally {
			scoringItemIds.remove(itemId);
		}
	}

	private int candidateScore(Candidate candidate, Map<String, Integer> virtualCounts, Set<String> scoringItemIds) {
		int score = 0;
		for (RecipeIngredientSummary.IngredientSlot slot : candidate.ingredientSummary().slots()) {
			if (slot.isEmpty()) {
				continue;
			}
			int bestSlotScore = slot.itemIds().stream()
				.mapToInt(itemId -> bestCandidateScore(itemId, virtualCounts, scoringItemIds))
				.max()
				.orElse(0);
			if (bestSlotScore <= 0) {
				return 0;
			}
			score += bestSlotScore;
		}
		return score;
	}

	private int bestCandidateInputAvailability(String itemId, Map<String, Integer> virtualCounts) {
		return recipesByOutput.getOrDefault(itemId, List.of()).stream()
			.mapToInt(candidate -> candidate.ingredientSummary().acceptedItemIds().stream()
				.mapToInt(candidateIngredient -> virtualCounts.getOrDefault(candidateIngredient, 0))
				.sum())
			.max()
			.orElse(0);
	}

	private List<Candidate> orderedCandidates(List<Candidate> candidates, Map<String, Integer> virtualCounts) {
		return candidates.stream()
			.sorted(Comparator
				.comparingInt((Candidate candidate) -> estimateCandidateCost(candidate, virtualCounts, new HashSet<>()))
				.thenComparing(candidate -> itemId(candidate.displayStack())))
			.toList();
	}

	private int estimateCandidateCost(Candidate candidate, Map<String, Integer> virtualCounts, Set<String> costingItemIds) {
		int cost = 1;
		for (RecipeIngredientSummary.IngredientSlot slot : candidate.ingredientSummary().slots()) {
			if (slot.isEmpty()) {
				continue;
			}
			int bestSlotCost = slot.itemIds().stream()
				.mapToInt(itemId -> estimateItemCost(itemId, virtualCounts, costingItemIds))
				.min()
				.orElse(UNCRAFTABLE_COST);
			if (bestSlotCost >= UNCRAFTABLE_COST) {
				return UNCRAFTABLE_COST;
			}
			cost += bestSlotCost;
		}
		return cost;
	}

	private int estimateItemCost(String itemId, Map<String, Integer> virtualCounts, Set<String> costingItemIds) {
		if (virtualCounts.getOrDefault(itemId, 0) > 0) {
			return 0;
		}
		if (!costingItemIds.add(itemId)) {
			return UNCRAFTABLE_COST;
		}
		try {
			return recipesByOutput.getOrDefault(itemId, List.of()).stream()
				.mapToInt(candidate -> estimateCandidateCost(candidate, virtualCounts, costingItemIds))
				.min()
				.orElse(UNCRAFTABLE_COST);
		} finally {
			costingItemIds.remove(itemId);
		}
	}

	private Map<String, List<Candidate>> buildRecipeIndex() {
		Map<String, List<Candidate>> index = new LinkedHashMap<>();
		Map<RecipeDisplayId, RecipeDisplayEntry> knownRecipes = ((ClientRecipeBookAccessor) player.getRecipeBook()).getKnown();
		for (RecipeDisplayEntry entry : knownRecipes.values()) {
			addCandidate(index, entry, true);
		}
		addSynchronizedRecipeCandidates(index, knownRecipes.keySet());
		return Map.copyOf(index);
	}

	private void addSynchronizedRecipeCandidates(Map<String, List<Candidate>> index, Set<RecipeDisplayId> knownRecipeIds) {
		if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
			int added = addRecipeHolderCandidates(
				index,
				knownRecipeIds,
				minecraft.getSingleplayerServer().getRecipeManager().getRecipes()
			);
			ReachCraftingMod.LOGGER.info("[chain_debug] integrated_recipe_index added={}", added);
			return;
		}

		if (minecraft.getConnection() == null || !(minecraft.getConnection().recipes() instanceof FabricRecipeManager recipeManager)) {
			ReachCraftingMod.LOGGER.info("[chain_debug] synced_recipe_index unavailable connection={} fabric_recipe_manager={}", minecraft.getConnection() != null, false);
			return;
		}

		int added = addRecipeHolderCandidates(
			index,
			knownRecipeIds,
			recipeManager.getSynchronizedRecipes().recipes()
		);
		ReachCraftingMod.LOGGER.info("[chain_debug] synced_recipe_index added={}", added);
	}

	private int addRecipeHolderCandidates(
		Map<String, List<Candidate>> index,
		Set<RecipeDisplayId> knownRecipeIds,
		Iterable<RecipeHolder<?>> recipes
	) {
		int displayIndex = 0;
		int added = 0;
		for (RecipeHolder<?> holder : recipes) {
			Recipe<?> recipe = holder.value();
			for (Object display : recipe.display()) {
				RecipeDisplayId displayId = new RecipeDisplayId(displayIndex++);
				if (!(recipe instanceof CraftingRecipe) || knownRecipeIds.contains(displayId)) {
					continue;
				}
				if (!(display instanceof ShapedCraftingRecipeDisplay) && !(display instanceof ShapelessCraftingRecipeDisplay)) {
					continue;
				}
				RecipeDisplayEntry entry = new RecipeDisplayEntry(
					displayId,
					(net.minecraft.world.item.crafting.display.RecipeDisplay) display,
					java.util.OptionalInt.empty(),
					recipe.recipeBookCategory(),
					java.util.Optional.empty()
				);
				if (addCandidate(index, entry, false)) {
					added++;
				}
			}
		}
		ReachCraftingMod.LOGGER.info("[chain_debug] recipe_holder_index scanned_displays={} added={}", displayIndex, added);
		return added;
	}

	private boolean addCandidate(Map<String, List<Candidate>> index, RecipeDisplayEntry entry, boolean executable) {
		if (!fitsCurrentGrid(entry)) {
			return false;
		}
		ItemStack output = RecipeVariantResolver.resolveDisplayStack(entry.display(), context);
		if (output.isEmpty()) {
			return false;
		}
		RecipeIngredientSummary summary = RecipeIngredientSummary.fromDisplay(entry.display(), context);
		if (summary.slots().isEmpty() || summary.slots().stream().allMatch(RecipeIngredientSummary.IngredientSlot::isEmpty)) {
			return false;
		}
		index.computeIfAbsent(itemId(output), ignored -> new ArrayList<>())
			.add(new Candidate(entry.id(), executable ? resolveCollection(entry.id()) : null, summary, output, executable));
		return true;
	}

	private boolean fitsCurrentGrid(RecipeDisplayEntry entry) {
		if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
			if (gridSlotCount == 4) {
				return shaped.width() <= 2 && shaped.height() <= 2;
			}
			return gridSlotCount == 9 && shaped.width() <= 3 && shaped.height() <= 3;
		}
		if (entry.display() instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return shapeless.ingredients().size() <= gridSlotCount;
		}
		return false;
	}

	private RecipeCollection resolveCollection(RecipeDisplayId recipeId) {
		for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
			if (collection.getRecipes().stream().anyMatch(entry -> entry.id().equals(recipeId))) {
				return collection;
			}
		}
		return null;
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static void subtract(Map<String, Integer> counts, String itemId, int count) {
		int current = counts.getOrDefault(itemId, 0);
		if (current <= count) {
			counts.remove(itemId);
		} else {
			counts.put(itemId, current - count);
		}
	}

	private static String formatCandidates(List<Candidate> candidates) {
		if (candidates.isEmpty()) {
			return "<none>";
		}
		StringJoiner joiner = new StringJoiner(", ");
		for (Candidate candidate : candidates) {
			joiner.add(candidate.recipeId() + "->" + ContainerUtils.formatStack(candidate.displayStack()) + (candidate.executable() ? "" : "(synced)"));
		}
		return joiner.toString();
	}

	private static String formatSteps(List<ChainCraftPlan.Step> steps) {
		if (steps.isEmpty()) {
			return "<none>";
		}
		StringJoiner joiner = new StringJoiner(" | ");
		for (ChainCraftPlan.Step step : steps) {
			joiner.add(step.recipeId() + "x" + step.recipeCopies() + "->" + ContainerUtils.formatStack(step.displayStack()));
		}
		return joiner.toString();
	}

	private static final class PlanningState {
		private final Map<String, Integer> counts;
		private final LinkedHashMap<StepKey, ScheduledCraft> scheduledCrafts;

		private PlanningState(Map<String, Integer> counts, LinkedHashMap<StepKey, ScheduledCraft> scheduledCrafts) {
			this.counts = counts;
			this.scheduledCrafts = scheduledCrafts;
		}

		private PlanningState copy() {
			return new PlanningState(new LinkedHashMap<>(counts), copyScheduledCrafts(scheduledCrafts));
		}

		private void replaceWith(PlanningState other) {
			counts.clear();
			counts.putAll(other.counts);
			scheduledCrafts.clear();
			scheduledCrafts.putAll(copyScheduledCrafts(other.scheduledCrafts));
		}

		private void schedule(Candidate candidate, int recipeCopies, boolean finalStep, Map<String, Integer> requiredInputs) {
			StepKey key = StepKey.from(candidate, finalStep);
			ScheduledCraft existing = scheduledCrafts.get(key);
			if (existing == null) {
				scheduledCrafts.put(key, new ScheduledCraft(candidate, recipeCopies, finalStep, requiredInputs));
				return;
			}
			existing.addCopies(recipeCopies, requiredInputs);
		}

		private List<ChainCraftPlan.Step> toSteps(boolean allowNearby) {
			List<ChainCraftPlan.Step> steps = new ArrayList<>();
			for (ScheduledCraft scheduledCraft : orderedScheduledCrafts()) {
				steps.add(scheduledCraft.toStep(allowNearby));
			}
			return List.copyOf(steps);
		}

		private List<ScheduledCraft> orderedScheduledCrafts() {
			List<ScheduledCraft> crafts = new ArrayList<>(scheduledCrafts.values());
			Map<String, Integer> outputOrder = new LinkedHashMap<>();
			for (int i = 0; i < crafts.size(); i++) {
				outputOrder.putIfAbsent(itemId(crafts.get(i).candidate.displayStack()), i);
			}

			List<ScheduledCraft> ordered = new ArrayList<>();
			Set<ScheduledCraft> emitted = new HashSet<>();
			while (ordered.size() < crafts.size()) {
				ScheduledCraft next = crafts.stream()
					.filter(craft -> !emitted.contains(craft))
					.filter(craft -> dependenciesSatisfied(craft, outputOrder, emitted, crafts))
					.min(Comparator
						.comparingInt((ScheduledCraft craft) -> craft.finalStep ? 1 : 0)
						.thenComparingInt(craft -> flexibleIngredientSlotCount(craft.candidate.ingredientSummary()))
						.thenComparingInt(crafts::indexOf))
					.orElse(null);
				if (next == null) {
					crafts.stream()
						.filter(craft -> !emitted.contains(craft))
						.forEach(ordered::add);
					break;
				}
				emitted.add(next);
				ordered.add(next);
			}
			return List.copyOf(ordered);
		}

		private static boolean dependenciesSatisfied(
			ScheduledCraft craft,
			Map<String, Integer> outputOrder,
			Set<ScheduledCraft> emitted,
			List<ScheduledCraft> crafts
		) {
			Set<String> dependencyOutputs = dependencyOutputIds(craft.candidate.ingredientSummary(), outputOrder);
			for (ScheduledCraft other : crafts) {
				if (other == craft || emitted.contains(other)) {
					continue;
				}
				if (dependencyOutputs.contains(itemId(other.candidate.displayStack()))) {
					return false;
				}
			}
			return true;
		}

		private static Set<String> dependencyOutputIds(RecipeIngredientSummary summary, Map<String, Integer> outputOrder) {
			Set<String> dependencies = new HashSet<>();
			for (RecipeIngredientSummary.IngredientSlot slot : summary.slots()) {
				if (slot.isEmpty()) {
					continue;
				}
				for (String itemId : slot.itemIds()) {
					if (outputOrder.containsKey(itemId)) {
						dependencies.add(itemId);
					}
				}
			}
			return dependencies;
		}

		private static int flexibleIngredientSlotCount(RecipeIngredientSummary summary) {
			int count = 0;
			for (RecipeIngredientSummary.IngredientSlot slot : summary.slots()) {
				if (!slot.isEmpty() && !slot.isExact()) {
					count++;
				}
			}
			return count;
		}

		private static LinkedHashMap<StepKey, ScheduledCraft> copyScheduledCrafts(LinkedHashMap<StepKey, ScheduledCraft> source) {
			LinkedHashMap<StepKey, ScheduledCraft> copy = new LinkedHashMap<>();
			for (Map.Entry<StepKey, ScheduledCraft> entry : source.entrySet()) {
				copy.put(entry.getKey(), entry.getValue().copy());
			}
			return copy;
		}
	}

	private static final class ScheduledCraft {
		private final Candidate candidate;
		private final boolean finalStep;
		private final Map<String, Integer> requiredInputs;
		private int recipeCopies;

		private ScheduledCraft(Candidate candidate, int recipeCopies, boolean finalStep, Map<String, Integer> requiredInputs) {
			this.candidate = candidate;
			this.recipeCopies = Math.max(recipeCopies, 1);
			this.finalStep = finalStep;
			this.requiredInputs = new LinkedHashMap<>(requiredInputs);
		}

		private void addCopies(int copies, Map<String, Integer> additionalRequiredInputs) {
			recipeCopies += Math.max(copies, 1);
			for (Map.Entry<String, Integer> entry : additionalRequiredInputs.entrySet()) {
				requiredInputs.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}

		private ScheduledCraft copy() {
			return new ScheduledCraft(candidate, recipeCopies, finalStep, requiredInputs);
		}

		private ChainCraftPlan.Step toStep(boolean allowNearby) {
			return new ChainCraftPlan.Step(
				candidate.recipeId(),
				candidate.collection(),
				candidate.displayStack(),
				candidate.ingredientSummary(),
				requiredInputs,
				recipeCopies,
				allowNearby,
				finalStep
			);
		}
	}

	private record StepKey(
		RecipeDisplayId recipeId,
		String outputItemId,
		int outputCount,
		String ingredientSummary,
		boolean finalStep
	) {
		private static StepKey from(Candidate candidate, boolean finalStep) {
			return new StepKey(
				candidate.recipeId(),
				itemId(candidate.displayStack()),
				Math.max(candidate.displayStack().getCount(), 1),
				candidate.ingredientSummary().compactSummary(),
				finalStep
			);
		}
	}

	private record Candidate(
		RecipeDisplayId recipeId,
		RecipeCollection collection,
		RecipeIngredientSummary ingredientSummary,
		ItemStack displayStack,
		boolean executable
	) {
		private Candidate {
			displayStack = displayStack.copy();
		}
	}
}
