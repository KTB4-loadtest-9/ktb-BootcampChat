package com.ktb.chatapp.service.ratelimit;

/** Data store interface for atomically incrementing fixed-window rate limits. */
public interface RateLimitStore {

    Counter increment(String clientId, long windowSeconds);

    record Counter(long count, long resetEpochSeconds) {}
}
