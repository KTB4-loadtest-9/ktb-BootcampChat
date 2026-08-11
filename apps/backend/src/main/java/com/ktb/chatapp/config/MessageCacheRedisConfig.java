package com.ktb.chatapp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;

@Configuration
public class MessageCacheRedisConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    @Conditional(MessageCacheRedisRequiredCondition.class)
    public RedissonClient messageCacheRedissonClient(
            DataRedisConnectionDetails connectionDetails,
            @Value("${spring.data.redis.password:}") String configuredPassword) {
        DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        Config config = new Config();
        var server = config.useSingleServer()
                .setAddress("redis://%s:%d".formatted(standalone.getHost(), standalone.getPort()));
        String redisPassword = connectionDetails.getPassword();
        if (redisPassword == null || redisPassword.isBlank()) {
            redisPassword = configuredPassword;
        }
        if (!redisPassword.isBlank()) {
            server.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }

    static class MessageCacheRedisRequiredCondition extends org.springframework.boot.autoconfigure.condition.AnyNestedCondition {

        MessageCacheRedisRequiredCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "socketio.enabled", havingValue = "false")
        static class SocketIoDisabled {
        }

        @ConditionalOnProperty(name = "socketio.store", havingValue = "memory")
        static class SocketIoMemoryStore {
        }
    }
}
