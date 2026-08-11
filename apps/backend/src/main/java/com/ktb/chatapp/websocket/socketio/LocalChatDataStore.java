package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local in-memory implementation of ChatDataStore using ConcurrentHashMap.
 * Thread-safe storage for chat-related data without external dependencies.
 */
public class LocalChatDataStore implements ChatDataStore {

    private static final String ACTIVE_CONNECTION_KEY_PREFIX = "conn_users:userid:";
    
    private final ConcurrentHashMap<String, Object> storage = new ConcurrentHashMap<>();
    
    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = storage.get(key);
        if (value == null) {
            return Optional.empty();
        }
        
        try {
            return Optional.of(type.cast(value));
        } catch (ClassCastException e) {
            return Optional.empty();
        }
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
    public void withLock(String key, Runnable action) {
        // ponytail: global lock is enough for the single-node fallback; Redisson uses per-user locks.
        synchronized (this) {
            action.run();
        }
    }
    
    @Override
    public int size() {
        return (int) storage.keySet().stream()
                .filter(key -> key.startsWith(ACTIVE_CONNECTION_KEY_PREFIX))
                .count();
    }
}
