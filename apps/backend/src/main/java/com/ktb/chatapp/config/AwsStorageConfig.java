package com.ktb.chatapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class AwsStorageConfig {
    @Bean
    S3Client s3Client(@Value("${file.s3.region}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean
    S3Presigner s3Presigner(@Value("${file.s3.region}") String region) {
        return S3Presigner.builder().region(Region.of(region)).build();
    }

    @Bean
    CloudFrontUtilities cloudFrontUtilities() {
        return CloudFrontUtilities.create();
    }

    @Bean
    CloudFrontClient cloudFrontClient() {
        return CloudFrontClient.builder().region(Region.AWS_GLOBAL).build();
    }
}
