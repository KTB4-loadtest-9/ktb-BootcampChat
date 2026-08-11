package com.ktb.chatapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.MongoClientSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;

class MongoPoolConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(MongoPoolConfig.class);

    @Test
    void usesMaxPoolSizeOf120ByDefault() {
        contextRunner.run(context -> {
            MongoClientSettings.Builder settings = MongoClientSettings.builder();
            context.getBean(MongoClientSettingsBuilderCustomizer.class).customize(settings);

            assertThat(settings.build().getConnectionPoolSettings().getMaxSize())
                .isEqualTo(120);
        });
    }

    @Test
    void appliesConfiguredMaxPoolSizeOverride() {
        contextRunner
            .withPropertyValues("app.mongo.max-pool-size=48")
            .run(context -> {
                MongoClientSettings.Builder settings = MongoClientSettings.builder();
                context.getBean(MongoClientSettingsBuilderCustomizer.class).customize(settings);

                assertThat(settings.build().getConnectionPoolSettings().getMaxSize())
                .isEqualTo(48);
            });
    }

    @Test
    void appliesEnvironmentVariableOverride() {
        contextRunner
            .withPropertyValues("MONGO_MAX_POOL_SIZE=48")
            .run(context -> {
                MongoClientSettings.Builder settings = MongoClientSettings.builder();
                context.getBean(MongoClientSettingsBuilderCustomizer.class).customize(settings);

                assertThat(settings.build().getConnectionPoolSettings().getMaxSize())
                    .isEqualTo(48);
            });
    }

    @Test
    void rejectsZeroMaxPoolSize() {
        contextRunner
            .withPropertyValues("app.mongo.max-pool-size=0")
            .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void rejectsNonNumericMaxPoolSize() {
        contextRunner
            .withPropertyValues("app.mongo.max-pool-size=not-a-number")
            .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }
}
