package com.ktb.chatapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record DirectUploadRequest(
        @NotBlank String originalName,
        @NotBlank String contentType,
        @Min(1) long size) {
}
