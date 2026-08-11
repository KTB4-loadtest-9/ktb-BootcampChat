package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.ProfileImageAccessUrlsResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.storage.CloudFrontUrlService;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProfileImageAccessService {
    private final UserRepository users;
    private final ObjectProvider<CloudFrontUrlService> cloudFrontProvider;

    @Value("${file.storage.type:local}") private String storageType;
    @Value("${file.cloudfront.signed-url-ttl:PT5M}") private Duration signedUrlTtl;

    public ProfileImageAccessUrlsResponse accessUrls(List<String> userIds) {
        Instant expiresAt = Instant.now().plus(signedUrlTtl);
        List<ProfileImageAccessUrlsResponse.Item> items = userIds.stream().distinct().map(userId -> {
            try {
                User user = users.findById(userId)
                        .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
                String key = normalizeKey(user.getProfileImage());
                if (key == null || key.isBlank()) {
                    return ProfileImageAccessUrlsResponse.Item.failure(userId, "프로필 이미지가 없습니다.");
                }
                if ("s3".equalsIgnoreCase(storageType)) {
                    CloudFrontUrlService cloudFront = cloudFrontProvider.getIfAvailable();
                    if (cloudFront == null) throw new IllegalStateException("CloudFront 설정을 사용할 수 없습니다.");
                    return ProfileImageAccessUrlsResponse.Item.success(
                            userId, cloudFront.signedUrl(key, signedUrlTtl), expiresAt);
                }
                return ProfileImageAccessUrlsResponse.Item.success(userId, FileUrl.of(key), expiresAt);
            } catch (RuntimeException e) {
                return ProfileImageAccessUrlsResponse.Item.failure(userId, e.getMessage());
            }
        }).toList();
        return new ProfileImageAccessUrlsResponse(items);
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) return value;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            String path = URI.create(value).getPath();
            return path.startsWith("/") ? path.substring(1) : path;
        }
        String apiPrefix = "/api/files/";
        return value.startsWith(apiPrefix) ? value.substring(apiPrefix.length()) : value;
    }
}
