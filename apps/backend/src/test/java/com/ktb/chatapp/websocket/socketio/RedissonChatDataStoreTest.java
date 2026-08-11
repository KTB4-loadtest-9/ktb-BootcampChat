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
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class RedissonChatDataStoreTest {

    @Mock private RedissonClient redissonClient;
    @Mock private RMap<String, Object> storage;

    private RedissonChatDataStore dataStore;

    @BeforeEach
    void setUp() {
        when(redissonClient.<String, Object>getMap(RedissonChatDataStore.MAP_NAME)).thenReturn(storage);
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
        verify(storage).size();
    }
}
