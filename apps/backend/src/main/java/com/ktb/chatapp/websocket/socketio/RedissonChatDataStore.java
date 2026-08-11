package com.ktb.chatapp.websocket.socketio;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/** Redis-backed store for the user and room state used by Socket.IO handlers. */
public class RedissonChatDataStore implements ChatDataStore {

    static final String MAP_NAME = "chat:socket:data";
    static final String ACTIVE_CONNECTION_MAP_NAME = "chat:socket:active-connections";
    static final Duration ACTIVE_CONNECTION_TTL = Duration.ofMinutes(2);

    private static final String ACTIVE_CONNECTION_KEY_PREFIX = "conn_users:userid:";
    private static final String LOCK_NAME_PREFIX = "chat:socket:lock:";
    static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration LOCK_LEASE = Duration.ofMinutes(2);

    private final RMap<String, Object> storage;
    private final RMapCache<String, Object> activeConnections;
    private final RedissonClient redissonClient;

    public RedissonChatDataStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.storage = redissonClient.getMap(MAP_NAME);
        this.activeConnections = redissonClient.getMapCache(ACTIVE_CONNECTION_MAP_NAME);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = mapFor(key).get(key);
        return Optional.ofNullable(value)
                .filter(type::isInstance)
                .map(type::cast);
    }

    @Override
    public void set(String key, Object value) {
        if (isActiveConnectionKey(key)) {
            activeConnections.put(key, value, ACTIVE_CONNECTION_TTL.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            storage.put(key, value);
        }
    }

    @Override
    public void delete(String key) {
        mapFor(key).remove(key);
    }

    @Override
    public void withLock(String key, Runnable action) {
        RLock lock = redissonClient.getLock(LOCK_NAME_PREFIX + key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(
                    LOCK_WAIT_TIMEOUT.toMillis(),
                    LOCK_LEASE.toMillis(),
                    TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new IllegalStateException("Could not acquire Redis lock: " + key);
            }
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring Redis lock: " + key, e);
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    @Override
    public int size() {
        return activeConnections.size();
    }

    private RMap<String, Object> mapFor(String key) {
        return isActiveConnectionKey(key) ? activeConnections : storage;
    }

    private boolean isActiveConnectionKey(String key) {
        return key.startsWith(ACTIVE_CONNECTION_KEY_PREFIX);
    }
}
