package com.ktb.chatapp.service;

import com.ktb.chatapp.service.ratelimit.RateLimitStore;
import com.ktb.chatapp.service.ratelimit.RateLimitStore.Counter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    @DisplayName("최초 요청은 host-prefixed clientId로 증가되고 남은 횟수를 반환한다")
    void checkRateLimit_FirstRequest_IncrementsHostPrefixedClientId() {
        long reset = Instant.now().getEpochSecond() + 30;
        when(rateLimitStore.increment(STORE_CLIENT_ID, 30)).thenReturn(new Counter(1, reset));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.resetEpochSeconds()).isEqualTo(reset);
        assertThat(result.retryAfterSeconds()).isBetween(29L, 30L);
        verify(rateLimitStore).increment(STORE_CLIENT_ID, 30);
    }

    @Test
    @DisplayName("증가된 카운트가 한도 이하면 남은 횟수를 반환한다")
    void checkRateLimit_BelowLimit_ReturnsRemaining() {
        when(rateLimitStore.increment(STORE_CLIENT_ID, 30))
                .thenReturn(new Counter(2, Instant.now().getEpochSecond() + 20));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("증가된 카운트가 한도를 넘으면 retry-after와 reset epoch를 반환한다")
    void checkRateLimit_LimitExceeded_ReturnsRetryAfterAndReset() {
        long reset = Instant.now().getEpochSecond() + 10;
        when(rateLimitStore.increment(STORE_CLIENT_ID, 30)).thenReturn(new Counter(4, reset));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isFalse();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isBetween(9L, 10L);
        assertThat(result.resetEpochSeconds()).isEqualTo(reset);
    }

    @Test
    @DisplayName("0초 window는 최소 1초 window로 정규화된다")
    void checkRateLimit_ZeroWindow_NormalizesToOneSecond() {
        when(rateLimitStore.increment(STORE_CLIENT_ID, 1))
                .thenReturn(new Counter(1, Instant.now().getEpochSecond() + 1));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ZERO);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("null window는 최소 1초 window로 정규화된다")
    void checkRateLimit_NullWindow_NormalizesToOneSecond() {
        when(rateLimitStore.increment(STORE_CLIENT_ID, 1))
                .thenReturn(new Counter(1, Instant.now().getEpochSecond() + 1));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, null);

        assertThat(result.allowed()).isTrue();
        assertThat(result.windowSeconds()).isEqualTo(1);
        assertThat(result.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("저장소 실패 시 요청은 허용하고 전체 한도를 남긴다")
    void checkRateLimit_StoreFailure_FailsOpenDeterministically() {
        when(rateLimitStore.increment(STORE_CLIENT_ID, 30))
                .thenThrow(new IllegalStateException("store down"));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(CLIENT_ID, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.windowSeconds()).isEqualTo(30);
        assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("null clientId도 host prefix가 적용된 저장소 key로 처리된다")
    void checkRateLimit_NullClientId_UsesHostPrefixedKey() {
        String storeClientId = HOST_NAME + ":null";
        when(rateLimitStore.increment(storeClientId, 30))
                .thenReturn(new Counter(1, Instant.now().getEpochSecond() + 30));

        RateLimitCheckResult result = rateLimitService.checkRateLimit(null, 3, Duration.ofSeconds(30));

        assertThat(result.allowed()).isTrue();
        verify(rateLimitStore).increment(storeClientId, 30);
    }
}
