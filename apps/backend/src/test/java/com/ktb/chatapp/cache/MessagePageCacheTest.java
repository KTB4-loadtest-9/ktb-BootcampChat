package com.ktb.chatapp.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.FileResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MessagePageCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private RedissonClient redissonClient;
    private RLock lock;
    private MessageCacheProperties properties;
    private MessagePageCache cache;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        properties = new MessageCacheProperties();
        properties.setEnabled(true);
        properties.setTtl(Duration.ofMinutes(10));
        properties.setLockWait(Duration.ofSeconds(2));
        properties.setLockLease(Duration.ofSeconds(30));

        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        cache = new MessagePageCache(
                redisTemplate,
                redissonClient,
                objectMapper,
                properties,
                new SimpleMeterRegistry());
    }

    @Test
    void samePageUsesLoaderOnceAfterTheFirstMiss() throws Exception {
        FetchMessagesResponse response = response("message-1");
        String serialized = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build()
                .writeValueAsString(response);
        when(values.get("chat:message-page:version:room-1")).thenReturn(null);
        when(values.get("chat:message-page:room-1:v0:initial:30"))
                .thenReturn(null, null, serialized);
        AtomicInteger loads = new AtomicInteger();

        MessagePageCache.LoadResult first = cache.getOrLoad(
                new FetchMessagesRequest("room-1", 30, null),
                () -> {
                    loads.incrementAndGet();
                    return response;
                });
        MessagePageCache.LoadResult second = cache.getOrLoad(
                new FetchMessagesRequest("room-1", 30, null),
                () -> {
                    loads.incrementAndGet();
                    return response;
                });

        assertThat(first.cacheHit()).isFalse();
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.response()).isEqualTo(response);
        assertThat(loads).hasValue(1);
        verify(values).set(
                eq("chat:message-page:room-1:v0:initial:30"),
                eq(serialized),
                eq(Duration.ofMinutes(10)));
    }

    @Test
    void invalidatingRoomIncrementsItsVersion() {
        when(values.increment("chat:message-page:version:room-1")).thenReturn(1L);

        cache.invalidateRoom("room-1");

        verify(values).increment("chat:message-page:version:room-1");
    }

    @Test
    void lockRecheckUsesValueWrittenByAnotherLoader() throws Exception {
        FetchMessagesResponse response = response("message-1");
        String serialized = objectMapper.writeValueAsString(response);
        when(values.get("chat:message-page:version:room-1"))
                .thenReturn((String) null, (String) null);
        when(values.get("chat:message-page:room-1:v0:initial:30"))
                .thenReturn(null, serialized);
        AtomicInteger loads = new AtomicInteger();

        MessagePageCache.LoadResult result = cache.getOrLoad(
                new FetchMessagesRequest("room-1", 30, null),
                () -> {
                    loads.incrementAndGet();
                    return response;
                });

        assertThat(result.cacheHit()).isTrue();
        assertThat(result.response()).isEqualTo(response);
        assertThat(loads).hasValue(0);
        verify(lock).unlock();
    }

    @Test
    void invalidationFailureBypassesCacheForTheRoom() {
        FetchMessagesResponse response = response("message-1");
        when(values.increment("chat:message-page:version:room-1"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        cache.invalidateRoom("room-1");

        MessagePageCache.LoadResult result = cache.getOrLoad(
                new FetchMessagesRequest("room-1", 30, null),
                () -> response);

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.response()).isEqualTo(response);
        verify(values, times(2)).increment("chat:message-page:version:room-1");
        verifyNoMoreInteractions(redissonClient);
    }

    @Test
    void serializationPreservesMessageResponseFields() throws Exception {
        MessageResponse responseMessage = MessageResponse.builder()
                .id("message-1")
                .roomId("room-1")
                .content("hello")
                .sender(UserResponse.builder()
                        .id("user-1")
                        .name("Tester")
                        .email("tester@example.com")
                        .profileImage("profile.png")
                        .build())
                .file(FileResponse.builder()
                        .id("file-1")
                        .filename("stored.txt")
                        .originalname("original.txt")
                        .mimetype("text/plain")
                        .size(42L)
                        .user("user-1")
                        .uploadDate(LocalDateTime.of(2026, 8, 12, 5, 0))
                        .build())
                .timestamp(1_754_964_000_000L)
                .reactions(Map.of("👍", Set.of("user-1")))
                .readers(List.of(Message.MessageReader.builder()
                        .userId("user-2")
                        .readAt(LocalDateTime.of(2026, 8, 12, 5, 1))
                        .build()))
                .metadata(Map.of("source", "test", "attempt", 1))
                .build();
        FetchMessagesResponse response = FetchMessagesResponse.builder()
                .messages(List.of(responseMessage))
                .hasMore(true)
                .build();
        when(values.get("chat:message-page:version:room-1"))
                .thenReturn((String) null, (String) null);
        when(values.get("chat:message-page:room-1:v0:initial:30"))
                .thenReturn((String) null, (String) null);

        cache.getOrLoad(new FetchMessagesRequest("room-1", 30, null), () -> response);

        ArgumentCaptor<String> serialized = ArgumentCaptor.forClass(String.class);
        verify(values).set(
                eq("chat:message-page:room-1:v0:initial:30"),
                serialized.capture(),
                eq(Duration.ofMinutes(10)));
        FetchMessagesResponse restored = objectMapper.readValue(
                serialized.getValue(), FetchMessagesResponse.class);

        assertThat(restored).isEqualTo(response);
    }

    @Test
    void redisReadFailureFallsBackToMongoLoader() {
        FetchMessagesResponse response = response("message-1");
        when(values.get("chat:message-page:version:room-1"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        MessagePageCache.LoadResult result = cache.getOrLoad(
                new FetchMessagesRequest("room-1", 30, null),
                () -> response);

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.response()).isEqualTo(response);
    }

    @Test
    void lockFailureFallsBackToMongoLoader() {
        FetchMessagesResponse response = response("message-1");
        when(values.get("chat:message-page:version:room-1")).thenReturn(null);
        when(values.get("chat:message-page:room-1:v0:initial:30")).thenReturn(null);
        when(redissonClient.getLock("chat:message-page:lock:room-1:initial:30"))
                .thenThrow(new IllegalStateException("lock unavailable"));

        MessagePageCache.LoadResult result = cache.getOrLoad(
                new FetchMessagesRequest("room-1", 30, null),
                () -> response);

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.response()).isEqualTo(response);
    }

    @Test
    void disabledCacheCallsMongoLoaderWithoutTouchingRedis() {
        properties.setEnabled(false);
        FetchMessagesResponse response = response("message-1");

        MessagePageCache.LoadResult result = cache.getOrLoad(
                new FetchMessagesRequest("room-1", 30, null),
                () -> response);

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.response()).isEqualTo(response);
        verifyNoInteractions(redisTemplate, redissonClient);
    }

    private static FetchMessagesResponse response(String id) {
        return FetchMessagesResponse.builder()
                .messages(List.of(com.ktb.chatapp.dto.MessageResponse.builder().id(id).build()))
                .hasMore(false)
                .build();
    }
}
