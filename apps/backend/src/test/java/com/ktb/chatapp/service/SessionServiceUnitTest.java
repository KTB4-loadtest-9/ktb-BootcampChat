package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.session.SessionStore;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionService 단위 테스트")
class SessionServiceUnitTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "session-1";

    @Mock
    private SessionStore sessionStore;

    @InjectMocks
    private SessionService sessionService;

    @Test
    @DisplayName("세션 생성은 기존 사용자 세션을 제거한 뒤 새 세션을 저장한다")
    void createSession_RemovesExistingSessionsBeforeSave() {
        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        when(sessionStore.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SessionCreationResult result = sessionService.createSession(
                USER_ID,
                new SessionMetadata("agent", "127.0.0.1", "device"));

        verify(sessionStore).deleteAll(USER_ID);
        verify(sessionStore).save(sessionCaptor.capture());
        Session savedSession = sessionCaptor.getValue();
        assertThat(result.getSessionId()).isEqualTo(savedSession.getSessionId());
        assertThat(result.getExpiresIn()).isEqualTo(SessionService.SESSION_TTL_SEC);
        assertThat(result.getSessionData().getUserId()).isEqualTo(USER_ID);
        assertThat(savedSession.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("세션 생성 중 저장소 실패는 RuntimeException으로 래핑된다")
    void createSession_StoreFailure_ThrowsRuntimeException() {
        doThrow(new IllegalStateException("store down")).when(sessionStore).deleteAll(USER_ID);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> sessionService.createSession(USER_ID, null));

        assertThat(exception).hasMessage("세션 생성 중 오류가 발생했습니다.");
        assertThat(exception).hasRootCauseInstanceOf(IllegalStateException.class);
        verify(sessionStore, never()).save(any(Session.class));
    }

    @Test
    @DisplayName("세션 검증은 null 입력을 저장소 조회 없이 거부한다")
    void validateSession_NullInputs_ReturnsInvalidParameters() {
        SessionValidationResult missingUser = sessionService.validateSession(null, SESSION_ID);
        SessionValidationResult missingSession = sessionService.validateSession(USER_ID, null);

        assertThat(missingUser.isValid()).isFalse();
        assertThat(missingUser.getError()).isEqualTo("INVALID_PARAMETERS");
        assertThat(missingSession.isValid()).isFalse();
        assertThat(missingSession.getError()).isEqualTo("INVALID_PARAMETERS");
        verify(sessionStore, never()).findByUserId(anyString());
    }

    @Test
    @DisplayName("세션 검증은 누락된 세션을 INVALID_SESSION으로 반환한다")
    void validateSession_MissingSession_ReturnsInvalidSession() {
        when(sessionStore.findByUserId(USER_ID)).thenReturn(Optional.empty());

        SessionValidationResult result = sessionService.validateSession(USER_ID, SESSION_ID);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).isEqualTo("INVALID_SESSION");
        verify(sessionStore, never()).save(any(Session.class));
    }

    @Test
    @DisplayName("세션 검증은 만료된 세션을 제거하고 SESSION_EXPIRED로 반환한다")
    void validateSession_ExpiredSession_RemovesSession() {
        Session expiredSession = Session.builder()
                .userId(USER_ID)
                .sessionId(SESSION_ID)
                .createdAt(Instant.now().minusSeconds(SessionService.SESSION_TTL_SEC + 10).toEpochMilli())
                .lastActivity(Instant.now().minusSeconds(SessionService.SESSION_TTL_SEC + 10).toEpochMilli())
                .expiresAt(Instant.now().minusSeconds(10))
                .build();
        when(sessionStore.findByUserId(USER_ID)).thenReturn(Optional.of(expiredSession));

        SessionValidationResult result = sessionService.validateSession(USER_ID, SESSION_ID);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).isEqualTo("SESSION_EXPIRED");
        verify(sessionStore).delete(USER_ID, SESSION_ID);
        verify(sessionStore, never()).save(any(Session.class));
    }

    @Test
    @DisplayName("세션 검증 중 저장소 실패는 VALIDATION_ERROR로 반환한다")
    void validateSession_StoreFailure_ReturnsValidationError() {
        when(sessionStore.findByUserId(USER_ID)).thenThrow(new IllegalStateException("store down"));

        SessionValidationResult result = sessionService.validateSession(USER_ID, SESSION_ID);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("활성 세션 조회 중 저장소 실패는 null로 반환된다")
    void getActiveSession_StoreFailure_ReturnsNull() {
        when(sessionStore.findByUserId(USER_ID)).thenThrow(new IllegalStateException("store down"));

        SessionData result = sessionService.getActiveSession(USER_ID);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Socket.IO 활동 갱신은 30초 안에 중복 저장하지 않는다")
    void touchSessionIfDue_CoalescesRepeatedActivity() {
        when(sessionStore.touch(anyString(), anyString(), anyLong(), any(Duration.class)))
                .thenReturn(true);

        assertThat(sessionService.touchSessionIfDue(USER_ID, SESSION_ID)).isTrue();
        assertThat(sessionService.touchSessionIfDue(USER_ID, SESSION_ID)).isTrue();

        verify(sessionStore, times(1))
                .touch(eq(USER_ID), eq(SESSION_ID), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("새 sessionId는 이전 세션의 30초 touch gate를 공유하지 않는다")
    void touchSessionIfDue_DoesNotShareGateAcrossRelogin() {
        when(sessionStore.touch(anyString(), anyString(), anyLong(), any(Duration.class)))
                .thenReturn(true);

        assertThat(sessionService.touchSessionIfDue(USER_ID, "old-session")).isTrue();
        assertThat(sessionService.touchSessionIfDue(USER_ID, "new-session")).isTrue();

        verify(sessionStore).touch(eq(USER_ID), eq("old-session"), anyLong(), any(Duration.class));
        verify(sessionStore).touch(eq(USER_ID), eq("new-session"), anyLong(), any(Duration.class));
    }
}
