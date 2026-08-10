package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.SocketIOClient;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.JwtService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.websocket.socketio.handler.ConnectionLoginHandler;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthTokenListenerImplTest {

    @Mock JwtService jwtService;
    @Mock SessionService sessionService;
    @Mock UserRepository userRepository;
    @Mock ObjectProvider<ConnectionLoginHandler> handlerProvider;
    @Mock ConnectionLoginHandler connectionLoginHandler;
    @Mock SocketIOClient client;

    private AuthTokenListenerImpl listener;

    @BeforeEach
    void setUp() {
        listener = new AuthTokenListenerImpl(jwtService, sessionService, userRepository, handlerProvider);
    }

    @Test
    void validJwtAndRedisSessionAuthorizeSocketConnection() {
        String token = "token";
        String userId = "user-1";
        String sessionId = "session-1";
        User user = new User();
        user.setId(userId);
        user.setName("Tester");

        when(jwtService.extractUserId(token)).thenReturn(userId);
        when(jwtService.extractSessionId(token)).thenReturn(sessionId);
        when(sessionService.validateSession(userId, sessionId))
                .thenReturn(SessionValidationResult.valid(null));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(handlerProvider.getObject()).thenReturn(connectionLoginHandler);
        when(client.getSessionId()).thenReturn(java.util.UUID.randomUUID());

        AuthTokenResult result = listener.getAuthTokenResult(
                Map.of("token", token, "sessionId", sessionId), client);

        assertThat(result).isSameAs(AuthTokenResult.AuthTokenResultSuccess);
        verify(connectionLoginHandler).onConnect(eq(client), any(SocketUser.class));
    }

    @Test
    void handshakeSessionMustMatchJwtClaimBeforeStoreLookup() {
        when(jwtService.extractUserId("token")).thenReturn("user-1");
        when(jwtService.extractSessionId("token")).thenReturn("jwt-session");

        AuthTokenResult result = listener.getAuthTokenResult(
                Map.of("token", "token", "sessionId", "other-session"), client);

        assertThat(result).isNotSameAs(AuthTokenResult.AuthTokenResultSuccess);
        verifyNoInteractions(sessionService, userRepository, handlerProvider);
    }

    @Test
    void missingRedisSessionRejectsSocketConnection() {
        when(jwtService.extractUserId("token")).thenReturn("user-1");
        when(jwtService.extractSessionId("token")).thenReturn("session-1");
        when(sessionService.validateSession("user-1", "session-1"))
                .thenReturn(SessionValidationResult.invalid("INVALID_SESSION", "missing"));

        AuthTokenResult result = listener.getAuthTokenResult(
                Map.of("token", "token", "sessionId", "session-1"), client);

        assertThat(result).isNotSameAs(AuthTokenResult.AuthTokenResultSuccess);
        verifyNoInteractions(userRepository, handlerProvider);
    }
}
