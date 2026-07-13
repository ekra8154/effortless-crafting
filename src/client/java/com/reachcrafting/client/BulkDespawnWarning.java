package com.reachcrafting.client;

/**
 * One-shot chat reminder for long-running bulk sessions: items ejected on the
 * ground despawn five minutes after being dropped, so a session that outlives
 * the configured threshold risks losing its earliest ejections. Armed by both
 * the flat bulk and bulk chain controllers (mutually exclusive, so the shared
 * clock is unambiguous). The clock runs from session start, which precedes the
 * first eject and therefore warns on the early side.
 */
final class BulkDespawnWarning {
	private static long sessionStartMillis;
	private static boolean warned;

	private BulkDespawnWarning() {
	}

	static void noteSessionStart() {
		sessionStartMillis = System.currentTimeMillis();
		warned = false;
	}

	static void clear() {
		sessionStartMillis = 0L;
		warned = false;
	}

	/**
	 * Approximate session duration for the end-of-run summary chat, e.g.
	 * " in ~4m 10s". Empty when no session clock is armed. Must be read
	 * before clear() in the stop paths.
	 */
	static String elapsedSummarySuffix() {
		if (sessionStartMillis == 0L) {
			return "";
		}
		int seconds = (int) Math.max(1, Math.round((System.currentTimeMillis() - sessionStartMillis) / 1000.0));
		return " in ~" + formatDuration(seconds);
	}

	static void tick() {
		if (warned || sessionStartMillis == 0L) {
			return;
		}
		int thresholdSeconds = ReachCraftingConfig.get().bulkDespawnWarningSeconds();
		if (thresholdSeconds < 0 || System.currentTimeMillis() - sessionStartMillis < thresholdSeconds * 1000L) {
			return;
		}
		warned = true;
		ReachCraftingModClient.sendChat(
			"Bulk session has been running for over " + formatDuration(thresholdSeconds)
				+ ": items ejected on the ground despawn 5 minutes after being dropped."
		);
	}

	private static String formatDuration(int seconds) {
		int minutes = seconds / 60;
		int remainder = seconds % 60;
		if (minutes == 0) {
			return remainder + "s";
		}
		return remainder == 0 ? minutes + "m" : minutes + "m " + remainder + "s";
	}
}
