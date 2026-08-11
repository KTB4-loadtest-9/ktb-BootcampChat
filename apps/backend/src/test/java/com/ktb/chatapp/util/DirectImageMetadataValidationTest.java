package com.ktb.chatapp.util;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DirectImageMetadataValidationTest {
    @Test
    void acceptsSupportedImageMetadata() {
        FileUtil.validateImageMetadata("photo.webp", "image/webp", 1024);
    }

    @Test
    void rejectsMismatchedExtension() {
        assertThatThrownBy(() -> FileUtil.validateImageMetadata("photo.png", "image/jpeg", 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsImagesLargerThanFiveMegabytes() {
        assertThatThrownBy(() -> FileUtil.validateImageMetadata("photo.png", "image/png", 5L * 1024 * 1024 + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
