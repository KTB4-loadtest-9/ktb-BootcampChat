package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis-backed single-session store. Session id checks and mutations are atomic. */
@Component
@ConditionalOnProperty(name = "app.session.store", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class SessionRedisStore implements SessionStore {

    private static final String KEY_PREFIX = "chat:session:";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_SESSION_ID = "sessionId";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_LAST_ACTIVITY = "lastActivity";
    private static final String FIELD_USER_AGENT = "userAgent";
    private static final String FIELD_IP_ADDRESS = "ipAddress";
    private static final String FIELD_DEVICE_INFO = "deviceInfo";

    private static final DefaultRedisScript<Long> DELETE_IF_MATCHES = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> TOUCH_IF_MATCHES = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1] then
              redis.call('HSET', KEYS[1], 'lastActivity', ARGV[2])
              redis.call('EXPIRE', KEYS[1], ARGV[3])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public Optional<Session> findByUserId(String userId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(userId));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Session.builder()
                .userId(stringValue(values, FIELD_USER_ID))
                .sessionId(stringValue(values, FIELD_SESSION_ID))
                .createdAt(longValue(values, FIELD_CREATED_AT))
                .lastActivity(longValue(values, FIELD_LAST_ACTIVITY))
                .metadata(readMetadata(values))
                .expiresAt(expiresAt(userId))
                .build());
    }

    @Override
    public Session save(Session session) {
        String key = key(session.getUserId());
        Map<String, String> values = new HashMap<>();
        values.put(FIELD_USER_ID, session.getUserId());
        values.put(FIELD_SESSION_ID, session.getSessionId());
        values.put(FIELD_CREATED_AT, Long.toString(session.getCreatedAt()));
        values.put(FIELD_LAST_ACTIVITY, Long.toString(session.getLastActivity()));
        if (session.getMetadata() != null) {
            values.put(FIELD_USER_AGENT, nullToEmpty(session.getMetadata().userAgent()));
            values.put(FIELD_IP_ADDRESS, nullToEmpty(session.getMetadata().ipAddress()));
            values.put(FIELD_DEVICE_INFO, nullToEmpty(session.getMetadata().deviceInfo()));
        }
        redisTemplate.opsForHash().putAll(key, values);
        Duration ttl = Duration.between(Instant.now(), session.getExpiresAt());
        redisTemplate.expire(key, ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(1) : ttl);
        return session;
    }

    @Override
    public void deleteAll(String userId) {
        redisTemplate.delete(key(userId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        redisTemplate.execute(DELETE_IF_MATCHES, java.util.List.of(key(userId)), sessionId);
    }

    @Override
    public boolean touch(String userId, String sessionId, long lastActivity, Duration ttl) {
        Long result = redisTemplate.execute(
                TOUCH_IF_MATCHES,
                java.util.List.of(key(userId)),
                sessionId,
                Long.toString(lastActivity),
                Long.toString(ttl.toSeconds()));
        return Long.valueOf(1).equals(result);
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }

    private Instant expiresAt(String userId) {
        Long seconds = redisTemplate.getExpire(key(userId));
        return Instant.now().plusSeconds(seconds == null || seconds < 0 ? 0 : seconds);
    }

    private String stringValue(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? null : value.toString();
    }

    private long longValue(Map<Object, Object> values, String field) {
        return Long.parseLong(stringValue(values, field));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private SessionMetadata readMetadata(Map<Object, Object> values) {
        if (!values.containsKey(FIELD_USER_AGENT)
                && !values.containsKey(FIELD_IP_ADDRESS)
                && !values.containsKey(FIELD_DEVICE_INFO)) {
            return null;
        }
        return new SessionMetadata(
                stringValue(values, FIELD_USER_AGENT),
                stringValue(values, FIELD_IP_ADDRESS),
                stringValue(values, FIELD_DEVICE_INFO));
    }
}
