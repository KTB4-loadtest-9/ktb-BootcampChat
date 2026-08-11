package com.ktb.chatapp.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.mongo")
public class MongoPoolProperties {

    @Min(value = 1, message = "app.mongo.max-pool-size must be at least 1")
    private int maxPoolSize = 120;
}
