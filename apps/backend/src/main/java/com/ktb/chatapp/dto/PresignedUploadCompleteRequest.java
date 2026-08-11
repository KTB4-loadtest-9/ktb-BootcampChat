package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;

/** Completes a browser-to-S3 upload through the legacy upload URI. */
public record PresignedUploadCompleteRequest(
        @NotBlank String uploadId,
        @NotBlank String uploadType
) {
    public static final String CHAT_IMAGE_TYPE = "PRESIGNED_CHAT_IMAGE";
}
