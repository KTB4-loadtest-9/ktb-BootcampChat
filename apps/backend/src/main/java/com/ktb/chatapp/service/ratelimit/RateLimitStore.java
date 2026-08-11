package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import java.time.Instant;

/**
 * Atomic rate-limit storage operation.
 */
public interface RateLimitStore {
    
    RateLimit incrementAndGet(String clientId, Instant now, Instant resetExpiresAt);
}
