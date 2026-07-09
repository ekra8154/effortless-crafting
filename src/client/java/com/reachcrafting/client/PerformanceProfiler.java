package com.reachcrafting.client;

import com.reachcrafting.ReachCraftingMod;
import java.util.LinkedHashMap;
import java.util.Map;

final class PerformanceProfiler {
	private static final long FLUSH_INTERVAL_NANOS = 1_000_000_000L;
	private static final Map<String, Aggregate> AGGREGATES = new LinkedHashMap<>();
	private static long nextFlushAtNanos = System.nanoTime() + FLUSH_INTERVAL_NANOS;

	private PerformanceProfiler() {
	}

	static long start() {
		return System.nanoTime();
	}

	static void record(String key, long startNanos) {
		recordDuration(key, System.nanoTime() - startNanos, null);
	}

	static void record(String key, long startNanos, String detail) {
		recordDuration(key, System.nanoTime() - startNanos, detail);
	}

	static void recordDuration(String key, long durationNanos) {
		recordDuration(key, durationNanos, null);
	}

	static void recordDuration(String key, long durationNanos, String detail) {
		if (!ReachCraftingConfig.get().performanceLoggingEnabled()) {
			return;
		}

		Aggregate aggregate = AGGREGATES.computeIfAbsent(key, ignored -> new Aggregate());
		aggregate.count++;
		aggregate.totalNanos += durationNanos;
		aggregate.maxNanos = Math.max(aggregate.maxNanos, durationNanos);
		if (detail != null && !detail.isBlank()) {
			aggregate.lastDetail = detail;
		}

		long now = System.nanoTime();
		if (now >= nextFlushAtNanos) {
			flush(now);
		}
	}

	static void logImmediate(String key, long startNanos, String detail) {
		if (!ReachCraftingConfig.get().performanceLoggingEnabled()) {
			return;
		}

		long durationNanos = System.nanoTime() - startNanos;
		ReachCraftingMod.LOGGER.info(
			"[perf] {} took={}ms{}",
			key,
			formatMillis(durationNanos),
			detail == null || detail.isBlank() ? "" : " " + detail
		);
	}

	private static void flush(long now) {
		nextFlushAtNanos = now + FLUSH_INTERVAL_NANOS;
		if (AGGREGATES.isEmpty()) {
			return;
		}

		for (Map.Entry<String, Aggregate> entry : AGGREGATES.entrySet()) {
			Aggregate aggregate = entry.getValue();
			ReachCraftingMod.LOGGER.info(
				"[perf] {} calls={} total={}ms avg={}ms max={}ms{}",
				entry.getKey(),
				aggregate.count,
				formatMillis(aggregate.totalNanos),
				formatMillis(aggregate.totalNanos / Math.max(aggregate.count, 1)),
				formatMillis(aggregate.maxNanos),
				aggregate.lastDetail == null || aggregate.lastDetail.isBlank() ? "" : " last=" + aggregate.lastDetail
			);
		}
		AGGREGATES.clear();
	}

	private static String formatMillis(long nanos) {
		return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0);
	}

	private static final class Aggregate {
		private int count;
		private long totalNanos;
		private long maxNanos;
		private String lastDetail;
	}
}
