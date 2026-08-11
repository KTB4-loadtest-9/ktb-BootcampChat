package com.ktb.chatapp.cache;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.message-cache")
public class MessageCacheProperties {

    private boolean enabled = true;
    private Duration ttl = Duration.ofMinutes(10);
    private Duration lockWait = Duration.ofSeconds(2);
    private Duration lockLease = Duration.ofSeconds(30);
}
