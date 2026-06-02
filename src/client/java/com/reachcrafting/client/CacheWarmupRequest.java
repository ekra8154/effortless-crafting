package com.reachcrafting.client;

record CacheWarmupRequest(String reason) {
	CacheWarmupRequest {
		reason = reason == null ? "cache_warmup" : reason;
	}
}
