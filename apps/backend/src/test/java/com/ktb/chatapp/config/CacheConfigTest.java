package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ktb.chatapp.dto.RoomResponse;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class CacheConfigTest {

    @Test
    void cacheManagerUsesRedisInsteadOfRedissonJCache() {
        CacheConfig config = new CacheConfig();
        CacheManager cacheManager = config.cacheManager(
            mock(RedisConnectionFactory.class), config.roomCacheConfiguration());

        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
    }

    @Test
    void roomCacheKeepsResponseFieldsAndExpiresAfterTenSeconds() {
        RedisCacheConfiguration configuration = new CacheConfig().roomCacheConfiguration();
        LocalDateTime createdAt = LocalDateTime.parse("2026-08-10T15:00:00");
        RoomsResponse source = RoomsResponse.builder()
            .success(true)
            .data(List.of(RoomResponse.builder()
                .id("room-1")
                .name("cached room")
                .createdAtDateTime(createdAt)
                .participants(List.of())
                .build()))
            .build();

        Object restored = configuration.getValueSerializationPair().read(
            configuration.getValueSerializationPair().write(source));

        assertThat(restored).isInstanceOf(RoomsResponse.class);
        assertThat(((RoomsResponse) restored).getData().getFirst().getCreatedAtDateTime())
            .isEqualTo(createdAt);
        assertThat(configuration.getTtlFunction().getTimeToLive("key", source))
            .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void messagePageSerializerRestoresFetchMessagesResponse() {
        CacheConfig config = new CacheConfig();
        var serializer = config.messagePageCacheSerializer(config.messageCacheObjectMapper());
        FetchMessagesResponse source = FetchMessagesResponse.builder()
                .messages(List.of(MessageResponse.builder()
                        .id("message-1")
                        .roomId("room-1")
                        .timestamp(1_754_964_000_000L)
                        .build()))
                .hasMore(true)
                .build();

        FetchMessagesResponse restored = serializer.deserialize(serializer.serialize(source));

        assertThat(restored).isEqualTo(source);
    }
}
