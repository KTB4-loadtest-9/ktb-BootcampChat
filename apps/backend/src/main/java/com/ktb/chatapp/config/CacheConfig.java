package com.ktb.chatapp.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.cache.MessageCacheProperties;
import com.ktb.chatapp.dto.RoomsResponse;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

@Configuration
@EnableCaching
@EnableConfigurationProperties(MessageCacheProperties.class)
public class CacheConfig implements CachingConfigurer {

    @Bean
    CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedisCacheConfiguration roomCacheConfiguration) {
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(roomCacheConfiguration)
            .build();
    }

    @Bean
    RedisCacheConfiguration roomCacheConfiguration() {
        ObjectMapper objectMapper = JsonMapper.builder()
            .disable(MapperFeature.USE_ANNOTATIONS)
            .addModule(new JavaTimeModule())
            .build();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofSeconds(10))
            .disableCachingNullValues()
            .serializeValuesWith(SerializationPair.fromSerializer(
                new Jackson2JsonRedisSerializer<>(objectMapper, RoomsResponse.class)));
    }

    @Bean
    ObjectMapper messageCacheObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }

    @Bean
    Jackson2JsonRedisSerializer<FetchMessagesResponse> messagePageCacheSerializer(
            @Qualifier("messageCacheObjectMapper") ObjectMapper objectMapper) {
        return new Jackson2JsonRedisSerializer<>(objectMapper, FetchMessagesResponse.class);
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(true);
    }
}
