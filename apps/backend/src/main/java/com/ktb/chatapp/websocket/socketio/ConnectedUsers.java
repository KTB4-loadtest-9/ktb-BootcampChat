package com.ktb.chatapp.websocket.socketio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ConnectedUsers {
    
    private static final String USER_SOCKET_KEY_PREFIX = "conn_users:userid:";
    private static final String USER_LOCK_KEY_PREFIX = "conn_users:lock:";
    private static final Duration RENEWAL_INTERVAL = Duration.ofSeconds(30);
    
    private final ChatDataStore chatDataStore;
    private final ScheduledExecutorService renewalExecutor =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("socketio-connection-renewal-", 0).factory());
    private final Map<String, ScheduledFuture<?>> renewalTasks = new ConcurrentHashMap<>();
    
    public SocketUser get(String userId) {
        return chatDataStore.get(buildKey(userId), SocketUser.class).orElse(null);
    }
    
    public void set(String userId, SocketUser sockerUser) {
        chatDataStore.set(buildKey(userId), sockerUser);
        ScheduledFuture<?> previous = renewalTasks.put(userId, renewalExecutor.scheduleAtFixedRate(
                () -> renewIfCurrent(userId, sockerUser),
                RENEWAL_INTERVAL.toSeconds(),
                RENEWAL_INTERVAL.toSeconds(),
                TimeUnit.SECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }
    
    public void del(String userId) {
        chatDataStore.delete(buildKey(userId));
        ScheduledFuture<?> renewal = renewalTasks.remove(userId);
        if (renewal != null) {
            renewal.cancel(false);
        }
    }

    public void withUserLock(String userId, Runnable action) {
        chatDataStore.withLock(USER_LOCK_KEY_PREFIX + userId, action);
    }
    
    public int size() {
        return chatDataStore.size();
    }
    
    private String buildKey(String userId) {
        return USER_SOCKET_KEY_PREFIX + userId;
    }

    private void renewIfCurrent(String userId, SocketUser expected) {
        try {
            withUserLock(userId, () -> {
                if (expected.equals(get(userId))) {
                    chatDataStore.set(buildKey(userId), expected);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to renew Socket.IO connection state for user {}", userId, e);
        }
    }

    @PreDestroy
    void shutdownRenewalExecutor() {
        renewalExecutor.shutdownNow();
    }
}
