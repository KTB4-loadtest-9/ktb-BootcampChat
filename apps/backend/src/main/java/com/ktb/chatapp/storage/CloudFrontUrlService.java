package com.ktb.chatapp.storage;

import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;
import org.springframework.http.ContentDisposition;

@Service
@RequiredArgsConstructor
public class CloudFrontUrlService {
    private final CloudFrontUtilities utilities;

    @Value("${file.cloudfront.domain}")
    private String domain;
    @Value("${file.cloudfront.key-pair-id}")
    private String keyPairId;
    @Value("${file.cloudfront.private-key-path}")
    private String privateKeyPath;

    public String publicUrl(String key) {
        return "https://" + domain + "/" + key;
    }

    public String signedUrl(String key, Duration ttl) {
        return signedUrl(key, ttl, null);
    }

    public String signedUrl(String key, Duration ttl, ContentDisposition disposition) {
        try {
            String resourceUrl = publicUrl(key);
            if (disposition != null) {
                resourceUrl += "?response-content-disposition="
                        + URLEncoder.encode(disposition.toString(), StandardCharsets.UTF_8);
            }
            CannedSignerRequest request = CannedSignerRequest.builder()
                    .resourceUrl(resourceUrl)
                    .privateKey(Path.of(privateKeyPath))
                    .keyPairId(keyPairId)
                    .expirationDate(Instant.now().plus(ttl))
                    .build();
            return utilities.getSignedUrlWithCannedPolicy(request).url();
        } catch (Exception e) {
            throw new IllegalStateException("CloudFront 서명 URL을 생성할 수 없습니다.", e);
        }
    }
}
