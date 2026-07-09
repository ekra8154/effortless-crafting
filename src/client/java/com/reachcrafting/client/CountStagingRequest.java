package com.reachcrafting.client;

import java.util.Map;

record CountStagingRequest(Map<String, Integer> desiredCounts, String reason) {
	CountStagingRequest {
		desiredCounts = Map.copyOf(desiredCounts);
		reason = reason == null ? "count_staging" : reason;
	}
}
