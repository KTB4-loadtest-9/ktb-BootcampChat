package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

/** Redis-backed store for the user and room state used by Socket.IO handlers. */
public class RedissonChatDataStore implements ChatDataStore {

    static final String MAP_NAME = "chat:socket:data";

    private final RMap<String, Object> storage;

    public RedissonChatDataStore(RedissonClient redissonClient) {
        this.storage = redissonClient.getMap(MAP_NAME);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = storage.get(key);
        return Optional.ofNullable(value)
                .filter(type::isInstance)
                .map(type::cast);
    }

    @Override
    public void set(String key, Object value) {
        storage.put(key, value);
    }

    @Override
    public void delete(String key) {
        storage.remove(key);
    }

    @Override
    public int size() {
        return storage.size();
    }
}
