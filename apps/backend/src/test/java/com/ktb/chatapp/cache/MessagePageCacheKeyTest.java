package com.ktb.chatapp.cache;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessagePageCacheKeyTest {

    @Test
    void missingBeforeUsesStableInitialCursor() {
        MessagePageCacheKey key = MessagePageCacheKey.from(
                new FetchMessagesRequest("room-1", null, null));

        assertThat(key.cursor()).isEqualTo("initial");
        assertThat(key.limit()).isEqualTo(30);
        assertThat(key.versionKey()).isEqualTo("chat:message-page:version:room-1");
        assertThat(key.pageKey(0)).isEqualTo("chat:message-page:room-1:v0:initial:30");
        assertThat(key.lockKey()).isEqualTo("chat:message-page:lock:room-1:initial:30");
    }

    @Test
    void positiveBeforeAndLimitArePartOfThePageIdentity() {
        MessagePageCacheKey key = MessagePageCacheKey.from(
                new FetchMessagesRequest("room-1", 15, 123456789L));

        assertThat(key.cursor()).isEqualTo("123456789");
        assertThat(key.limit()).isEqualTo(15);
        assertThat(key.pageKey(7)).isEqualTo("chat:message-page:room-1:v7:123456789:15");
        assertThat(key.lockKey()).isEqualTo("chat:message-page:lock:room-1:123456789:15");

        MessagePageCacheKey otherRoom = MessagePageCacheKey.from(
                new FetchMessagesRequest("room-2", 15, 123456789L));
        assertThat(otherRoom.pageKey(7)).isNotEqualTo(key.pageKey(7));
        assertThat(otherRoom.lockKey()).isNotEqualTo(key.lockKey());
    }
}
