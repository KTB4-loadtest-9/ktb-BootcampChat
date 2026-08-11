package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ProfileImageAccessUrlsRequest(@NotEmpty @Size(max = 50) List<String> userIds) {
}
