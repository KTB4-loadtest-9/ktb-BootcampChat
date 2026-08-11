package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.model.RateLimit;
import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceUnitTest {

    private static final String HOST_NAME = "test-host";
    private static final String CLIENT_ID = "client-1";
    private static final String STORE_CLIENT_ID = HOST_NAME + ":" + CLIENT_ID;

    @Mock
    private RateLimitStore rateLimitStore;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(rateLimitStore);
        ReflectionTestUtils.setField(rateLimitService, "hostName", HOST_NAME);
    }

    private RateLimit stored(int count, Instant expiresAt) {
        return RateLimit.builder()
                .clientId(STORE_CLIENT_ID)
                .count(count)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("최초 요청은 host-prefixed key로 원자 증가한다")
    void checkRateLimit_FirstRequest_UsesHostPrefixedKeyAndResetExpiry() {
        when(rateLimitStore.incrementAndGet(
                eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(1, Instant.now().plusSeconds(30)));
        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> resetCaptor = ArgumentCaptor.forClass(Instant.class);

        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(2);
        verify(rateLimitStore).incrementAndGet(
                eq(STORE_CLIENT_ID), nowCaptor.capture(), resetCaptor.capture());
        assertThat(Duration.between(nowCaptor.getValue(), resetCaptor.getValue()).getSeconds())
                .isEqualTo(30);
    }

    @Test
    @DisplayName("증가된 count가 한도 미만이면 허용한다")
    void checkRateLimit_BelowLimit_Allows() {
        when(rateLimitStore.incrementAndGet(
                eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(2, Instant.now().plusSeconds(20)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("count가 한도와 같으면 마지막 요청을 허용한다")
    void checkRateLimit_AtLimit_AllowsWithZeroRemaining() {
        when(rateLimitStore.incrementAndGet(
                eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(3, Instant.now().plusSeconds(20)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isZero();
    }

    @Test
    @DisplayName("증가된 count가 한도를 초과하면 거부한다")
    void checkRateLimit_OverLimit_Rejects() {
        Instant expiresAt = Instant.now().plusSeconds(10);
        when(rateLimitStore.incrementAndGet(
                eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(4, expiresAt));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isBetween(1L, 10L);
        assertThat(result.resetEpochSeconds()).isEqualTo(expiresAt.getEpochSecond());
    }

    @Test
    @DisplayName("0초 window는 최소 1초 window로 정규화된다")
    void checkRateLimit_ZeroWindow_NormalizesToOneSecond() {
        when(rateLimitStore.incrementAndGet(
                eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(1, Instant.now().plusSeconds(1)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                CLIENT_ID, 3, Duration.ZERO);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("null window는 최소 1초 window로 정규화된다")
    void checkRateLimit_NullWindow_NormalizesToOneSecond() {
        when(rateLimitStore.incrementAndGet(
                eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(stored(1, Instant.now().plusSeconds(1)));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("저장소 실패 시 요청을 허용한다")
    void checkRateLimit_StoreFailure_FailsOpenDeterministically() {
        when(rateLimitStore.incrementAndGet(
                eq(STORE_CLIENT_ID), any(Instant.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("store down"));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("null clientId도 host prefix가 적용된다")
    void checkRateLimit_NullClientId_UsesHostPrefixedNullKey() {
        String storeClientId = HOST_NAME + ":null";
        when(rateLimitStore.incrementAndGet(
                eq(storeClientId), any(Instant.class), any(Instant.class)))
                .thenReturn(RateLimit.builder()
                        .clientId(storeClientId)
                        .count(1)
                        .expiresAt(Instant.now().plusSeconds(30))
                        .build());

        RateLimitCheckResult result = rateLimitService.checkRateLimit(
                null, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        verify(rateLimitStore).incrementAndGet(
                eq(storeClientId), any(Instant.class), any(Instant.class));
    }
}
