package com.ktb.chatapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MongoPoolProperties.class)
public class MongoPoolConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    MongoClientSettingsBuilderCustomizer mongoConnectionPoolCustomizer(
            MongoPoolProperties properties) {
        return settings -> settings.applyToConnectionPoolSettings(
            connectionPool -> connectionPool.maxSize(properties.getMaxPoolSize()));
    }
}
