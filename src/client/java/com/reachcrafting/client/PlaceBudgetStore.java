package com.reachcrafting.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.reachcrafting.ReachCraftingMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Persisted per-server place-packet budgets. The AIMD in
 * {@link PlaceRecipeBudget} converges toward whatever rate the current
 * server actually enforces, but that knowledge was static in-memory state:
 * every client restart re-learned it from scratch — either re-eating the
 * probe drops (strict servers) or re-climbing from the conservative default
 * (permissive servers). One JSON file in the config dir keeps the converged
 * rate/burst/ceiling per server address.
 */
final class PlaceBudgetStore {

	record ServerBudget(double rate, double burst, double ceiling, long updatedMillis) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("effortless-crafting-server-budgets.json");

	private static Map<String, ServerBudget> budgets = null;

	private PlaceBudgetStore() {
	}

	static ServerBudget load(String serverKey) {
		ensureLoaded();
		return budgets.get(serverKey);
	}

	static void save(String serverKey, ServerBudget budget) {
		ensureLoaded();
		budgets.put(serverKey, budget);
		try {
			Files.writeString(PATH, GSON.toJson(budgets));
		} catch (Exception e) {
			ReachCraftingMod.LOGGER.warn("[place_budget] failed to persist server budgets: {}", e.toString());
		}
	}

	/** Forget every learned server budget (delete the file + clear cache). */
	static void clearAll() {
		budgets = new HashMap<>();
		try {
			Files.deleteIfExists(PATH);
		} catch (Exception e) {
			ReachCraftingMod.LOGGER.warn("[place_budget] failed to delete server budgets file: {}", e.toString());
		}
	}

	private static void ensureLoaded() {
		if (budgets != null) {
			return;
		}
		budgets = new HashMap<>();
		try {
			if (Files.exists(PATH)) {
				Map<String, ServerBudget> read = GSON.fromJson(
					Files.readString(PATH),
					new TypeToken<HashMap<String, ServerBudget>>() {
					}.getType()
				);
				if (read != null) {
					budgets = read;
				}
			}
		} catch (Exception e) {
			ReachCraftingMod.LOGGER.warn("[place_budget] failed to read server budgets (starting fresh): {}", e.toString());
		}
	}
}
