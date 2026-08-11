package com.ktb.chatapp.service.ratelimit;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RateLimitRedisStore implements RateLimitStore {

    private static final String KEY_PREFIX = "chat:rate-limit:";
    private static final DefaultRedisScript<List> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return {count, math.floor(redis.call('PEXPIRETIME', KEYS[1]) / 1000)}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public Counter increment(String clientId, long windowSeconds) {
        List<?> result = redisTemplate.execute(
                INCREMENT, List.of(KEY_PREFIX + clientId), Long.toString(windowSeconds));
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Redis rate limit increment returned no result");
        }
        return new Counter(longValue(result.get(0)), longValue(result.get(1)));
    }

    private long longValue(Object value) {
        return ((Number) value).longValue();
    }
}
