package com.ktb.chatapp.dto;

import java.time.Instant;
import java.util.Map;

public record DirectUploadResponse(
        String uploadId,
        String objectKey,
        String uploadUrl,
        Map<String, String> requiredHeaders,
        Instant expiresAt) {
}
