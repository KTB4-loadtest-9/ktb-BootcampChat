package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of SessionStore.
 * Uses SessionRepository for persistence.
 */
@Component
@ConditionalOnProperty(name = "app.session.store", havingValue = "mongo")
@RequiredArgsConstructor
public class SessionMongoStore implements SessionStore {
    
    private final SessionRepository sessionRepository;
    
    @Override
    public Optional<Session> findByUserId(String userId) {
        return sessionRepository.findByUserId(userId);
    }
    
    @Override
    public Session save(Session session) {
        return sessionRepository.save(session);
    }
    
    @Override
    public void delete(String userId, String sessionId) {
        Session session = sessionRepository.findByUserId(userId).orElse(null);
        if (session != null && sessionId.equals(session.getSessionId())) {
            sessionRepository.delete(session);
        }
    }
    
    @Override
    public void deleteAll(String userId) {
        sessionRepository.deleteByUserId(userId);
    }

    @Override
    public boolean touch(String userId, String sessionId, long lastActivity, Duration ttl) {
        Session session = sessionRepository.findByUserId(userId).orElse(null);
        if (session == null || !sessionId.equals(session.getSessionId())) {
            return false;
        }
        session.setLastActivity(lastActivity);
        session.setExpiresAt(Instant.now().plus(ttl));
        sessionRepository.save(session);
        return true;
    }
}
