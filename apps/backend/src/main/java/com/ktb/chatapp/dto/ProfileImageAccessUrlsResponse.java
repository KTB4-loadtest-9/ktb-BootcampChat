package com.ktb.chatapp.dto;

import java.time.Instant;
import java.util.List;

public record ProfileImageAccessUrlsResponse(List<Item> items) {
    public record Item(String userId, String url, Instant expiresAt, String error) {
        public static Item success(String userId, String url, Instant expiresAt) {
            return new Item(userId, url, expiresAt, null);
        }

        public static Item failure(String userId, String error) {
            return new Item(userId, null, null, error);
        }
    }
}
