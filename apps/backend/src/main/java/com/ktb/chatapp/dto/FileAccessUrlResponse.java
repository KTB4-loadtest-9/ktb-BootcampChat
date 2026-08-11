package com.ktb.chatapp.dto;

import java.time.Instant;
import java.util.List;

public record FileAccessUrlResponse(List<Item> items) {
    public record Item(String fileId, String url, Instant expiresAt, String error) {
        public static Item success(String fileId, String url, Instant expiresAt) {
            return new Item(fileId, url, expiresAt, null);
        }
        public static Item failure(String fileId, String error) {
            return new Item(fileId, null, null, error);
        }
    }
}
