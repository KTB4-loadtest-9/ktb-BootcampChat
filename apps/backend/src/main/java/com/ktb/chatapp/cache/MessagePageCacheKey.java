package com.ktb.chatapp.cache;

import com.ktb.chatapp.dto.FetchMessagesRequest;

public record MessagePageCacheKey(String roomId, String cursor, int limit) {

    public static final int DEFAULT_LIMIT = 30;

    public static MessagePageCacheKey from(FetchMessagesRequest request) {
        String cursor = request.before() == null || request.before() <= 0
                ? "initial"
                : Long.toString(request.before());
        return new MessagePageCacheKey(request.roomId(), cursor, request.limit(DEFAULT_LIMIT));
    }

    public String versionKey() {
        return "chat:message-page:version:" + roomId;
    }

    public String pageKey(long version) {
        return "chat:message-page:" + roomId + ":v" + version + ":" + cursor + ":" + limit;
    }

    public String lockKey() {
        return "chat:message-page:lock:" + roomId + ":" + cursor + ":" + limit;
    }
}
