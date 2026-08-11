package com.ktb.chatapp.storage;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationRequest;
import software.amazon.awssdk.services.cloudfront.model.InvalidationBatch;
import software.amazon.awssdk.services.cloudfront.model.Paths;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class CloudFrontInvalidationService {
    private final CloudFrontClient cloudFront;
    @Value("${file.cloudfront.distribution-id:}") private String distributionId;

    public void invalidate(String key) {
        if (distributionId == null || distributionId.isBlank()) return;
        cloudFront.createInvalidation(CreateInvalidationRequest.builder()
                .distributionId(distributionId)
                .invalidationBatch(InvalidationBatch.builder()
                        .callerReference(key + "-" + Instant.now().toEpochMilli())
                        .paths(Paths.builder().quantity(1).items("/" + key).build())
                        .build())
                .build());
    }
}
