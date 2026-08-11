package com.ktb.chatapp.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MessagePageCache {

    private static final String METRIC_REQUESTS = "chat.message.cache.requests";
    private static final String METRIC_SOURCES = "chat.message.cache.sources";
    private static final String METRIC_INVALIDATIONS = "chat.message.cache.invalidations";
    private static final String METRIC_LOCK_ACQUISITIONS = "chat.message.cache.lock.acquisitions";
    private static final String METRIC_LOCK_WAIT = "chat.message.cache.lock.wait";
    private static final int INVALIDATION_ATTEMPTS = 2;

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final Jackson2JsonRedisSerializer<FetchMessagesResponse> serializer;
    private final MessageCacheProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<String, Long> bypassUntilByRoom = new ConcurrentHashMap<>();

    @Autowired
    public MessagePageCache(
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            Jackson2JsonRedisSerializer<FetchMessagesResponse> serializer,
            MessageCacheProperties properties,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.serializer = serializer;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public MessagePageCache(
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            MessageCacheProperties properties,
            MeterRegistry meterRegistry) {
        this(
                redisTemplate,
                redissonClient,
                new Jackson2JsonRedisSerializer<>(objectMapper, FetchMessagesResponse.class),
                properties,
                meterRegistry);
    }

    public LoadResult getOrLoad(
            FetchMessagesRequest request,
            Supplier<FetchMessagesResponse> mongoLoader) {
        if (!properties.isEnabled()) {
            recordRequest("bypass");
            recordSource("mongodb");
            return new LoadResult(mongoLoader.get(), false);
        }

        MessagePageCacheKey key = MessagePageCacheKey.from(request);
        if (isBypassed(key.roomId())) {
            recordRequest("bypass");
            recordSource("mongodb");
            return new LoadResult(mongoLoader.get(), false);
        }

        long version;
        try {
            version = readVersion(key);
            FetchMessagesResponse cached = readPage(key.pageKey(version));
            if (cached != null) {
                recordRequest("hit");
                recordSource("redis");
                return new LoadResult(cached, true);
            }
        } catch (Exception e) {
            return bypassAfterCacheFailure("cache read/write failed", key.roomId(), mongoLoader, e);
        }

        return loadWithLock(key, mongoLoader);
    }

    public void invalidateRoom(String roomId) {
        if (!properties.isEnabled()) {
            return;
        }

        String versionKey = "chat:message-page:version:" + roomId;
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= INVALIDATION_ATTEMPTS; attempt++) {
            try {
                redisTemplate.opsForValue().increment(versionKey);
                bypassUntilByRoom.remove(roomId);
                meterRegistry.counter(METRIC_INVALIDATIONS, "result", "success").increment();
                return;
            } catch (Exception e) {
                lastFailure = e;
                if (attempt < INVALIDATION_ATTEMPTS) {
                    Thread.yield();
                }
            }
        }

        bypassUntilByRoom.put(roomId, System.nanoTime() + properties.getTtl().toNanos());
        meterRegistry.counter(METRIC_INVALIDATIONS, "result", "failure").increment();
        log.warn("Message page cache invalidation failed; bypassing cache temporarily for room {}",
                roomId, lastFailure);
    }

    private LoadResult loadWithLock(
            MessagePageCacheKey key,
            Supplier<FetchMessagesResponse> mongoLoader) {
        RLock lock;
        try {
            lock = redissonClient.getLock(key.lockKey());
        } catch (Exception e) {
            return bypassAfterCacheFailure("cache lock creation failed", key.roomId(), mongoLoader, e);
        }
        boolean acquired = false;
        long startedAt = System.nanoTime();
        try {
            try {
                acquired = lock.tryLock(
                        properties.getLockWait().toMillis(),
                        properties.getLockLease().toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return bypassAfterCacheFailure(
                        "interrupted while acquiring cache lock", key.roomId(), mongoLoader, e);
            } catch (Exception e) {
                return bypassAfterCacheFailure("cache lock acquisition failed", key.roomId(), mongoLoader, e);
            }
            recordLock(acquired, System.nanoTime() - startedAt);
            if (!acquired) {
                recordRequest("bypass");
                recordSource("mongodb");
                return new LoadResult(mongoLoader.get(), false);
            }

            long version;
            FetchMessagesResponse cached;
            try {
                version = readVersion(key);
                cached = readPage(key.pageKey(version));
            } catch (Exception e) {
                return bypassAfterCacheFailure("cache recheck failed", key.roomId(), mongoLoader, e);
            }
            if (cached != null) {
                recordRequest("hit");
                recordSource("redis");
                return new LoadResult(cached, true);
            }

            FetchMessagesResponse loaded = mongoLoader.get();
            try {
                String serialized = new String(serializer.serialize(loaded), StandardCharsets.UTF_8);
                redisTemplate.opsForValue().set(
                        key.pageKey(version), serialized, properties.getTtl());
            } catch (Exception e) {
                recordRequest("error");
                recordSource("mongodb");
                log.warn("Unable to store message page cache for room {}; using MongoDB result", key.roomId(), e);
                return new LoadResult(loaded, false);
            }
            recordRequest("miss");
            recordSource("mongodb");
            return new LoadResult(loaded, false);
        } finally {
            if (acquired) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    log.debug("Unable to release message page cache lock {}", key.lockKey(), e);
                }
            }
        }
    }

    private long readVersion(MessagePageCacheKey key) {
        String value = redisTemplate.opsForValue().get(key.versionKey());
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    private FetchMessagesResponse readPage(String pageKey) {
        String serialized = redisTemplate.opsForValue().get(pageKey);
        if (serialized == null) {
            return null;
        }
        return serializer.deserialize(serialized.getBytes(StandardCharsets.UTF_8));
    }

    private LoadResult bypassAfterCacheFailure(
            String message,
            String roomId,
            Supplier<FetchMessagesResponse> mongoLoader,
            Exception exception) {
        recordRequest("error");
        recordSource("mongodb");
        log.warn("{} for room {}; using MongoDB", message, roomId, exception);
        return new LoadResult(mongoLoader.get(), false);
    }

    private boolean isBypassed(String roomId) {
        Long bypassUntil = bypassUntilByRoom.get(roomId);
        if (bypassUntil == null) {
            return false;
        }
        if (bypassUntil > System.nanoTime()) {
            return true;
        }
        bypassUntilByRoom.remove(roomId, bypassUntil);
        return false;
    }

    private void recordRequest(String result) {
        meterRegistry.counter(METRIC_REQUESTS, "result", result).increment();
    }

    private void recordSource(String source) {
        meterRegistry.counter(METRIC_SOURCES, "source", source).increment();
    }

    private void recordLock(boolean acquired, long elapsedNanos) {
        String result = acquired ? "acquired" : "timeout";
        meterRegistry.counter(METRIC_LOCK_ACQUISITIONS, "result", result).increment();
        Timer.builder(METRIC_LOCK_WAIT)
                .tag("result", result)
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public record LoadResult(FetchMessagesResponse response, boolean cacheHit) {
    }
}
