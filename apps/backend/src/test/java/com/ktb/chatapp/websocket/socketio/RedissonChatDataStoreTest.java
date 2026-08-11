package com.ktb.chatapp.websocket.socketio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class RedissonChatDataStoreTest {

    @Mock private RedissonClient redissonClient;
    @Mock private RMap<String, Object> storage;
    @Mock private RMapCache<String, Object> activeConnections;
    @Mock private RLock lock;

    private RedissonChatDataStore dataStore;

    @BeforeEach
    void setUp() {
        when(redissonClient.<String, Object>getMap(RedissonChatDataStore.MAP_NAME)).thenReturn(storage);
        when(redissonClient.<String, Object>getMapCache(RedissonChatDataStore.ACTIVE_CONNECTION_MAP_NAME))
                .thenReturn(activeConnections);
        dataStore = new RedissonChatDataStore(redissonClient);
    }

    @Test
    void getReturnsOnlyValuesOfTheRequestedType() {
        when(storage.get("key")).thenReturn("value");

        assertThat(dataStore.get("key", String.class)).contains("value");
        assertThat(dataStore.get("key", Integer.class)).isEmpty();
    }

    @Test
    void setDeleteAndSizeUseTheSharedRedisMap() {
        dataStore.set("key", "value");
        dataStore.delete("key");
        dataStore.size();

        verify(storage).put("key", "value");
        verify(storage).remove("key");
        verify(activeConnections).size();
    }

    @Test
    void activeConnectionEntriesUseSeparateTtlMap() {
        String key = "conn_users:userid:user-1";

        dataStore.set(key, "value");
        dataStore.delete(key);

        verify(activeConnections).put(
                key,
                "value",
                RedissonChatDataStore.ACTIVE_CONNECTION_TTL.toMillis(),
                TimeUnit.MILLISECONDS);
        verify(activeConnections).remove(key);
    }

    @Test
    void withLockRunsActionAndReleasesTheRedisLock() {
        boolean[] ran = {false};
        when(redissonClient.getLock("chat:socket:lock:user-1")).thenReturn(lock);

        dataStore.withLock("user-1", () -> ran[0] = true);

        assertThat(ran[0]).isTrue();
        verify(lock).lock();
        verify(lock).unlock();
    }
}
