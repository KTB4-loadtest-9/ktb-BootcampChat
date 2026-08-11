package com.ktb.chatapp.service;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = {
        "socketio.enabled=false"
})
@DisplayName("RateLimitService 통합 테스트")
class RateLimitServiceTest {

    @Autowired
    private RateLimitService rateLimitService;

    @Test
    @DisplayName("최초 요청은 허용되고 TTL과 남은 횟수가 갱신된다")
    void checkRateLimit_AllowsFirstRequest() {
        int maxRequests = 5;
        Duration window = Duration.ofSeconds(60);
        String clientId = clientId("ip");

        long beforeCall = Instant.now().getEpochSecond();
        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        long afterCall = Instant.now().getEpochSecond();

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(maxRequests);
        assertThat(result.remaining()).isEqualTo(maxRequests - 1);
        assertThat(result.windowSeconds()).isEqualTo(window.getSeconds());
        assertThat(result.retryAfterSeconds()).isPositive();
        assertThat(result.resetEpochSeconds())
                .isBetween(beforeCall + result.retryAfterSeconds(), afterCall + result.retryAfterSeconds());
    }

    @Test
    @DisplayName("요청 한도를 초과하면 차단된다")
    void checkRateLimit_DeniesWhenLimitExceeded() {
        int maxRequests = 5;
        Duration window = Duration.ofSeconds(60);
        String clientId = clientId("ip");

        // 한도까지 요청을 수행
        for (int i = 0; i < maxRequests; i++) {
            RateLimitCheckResult result =
                    rateLimitService.checkRateLimit(clientId, maxRequests, window);
            assertThat(result.allowed()).isTrue();
        }

        // 한도 초과 요청
        long beforeCall = Instant.now().getEpochSecond();
        RateLimitCheckResult result =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        long afterCall = Instant.now().getEpochSecond();

        assertThat(result.allowed()).isFalse();
        assertThat(result.limit()).isEqualTo(maxRequests);
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterSeconds()).isBetween(1L, window.getSeconds());
        assertThat(result.resetEpochSeconds())
                .isBetween(beforeCall + result.retryAfterSeconds(), afterCall + result.retryAfterSeconds());
    }

    @Test
    @DisplayName("연속 요청 시 카운트가 증가하고 남은 횟수가 감소한다")
    void checkRateLimit_DecreasesRemainingOnConsecutiveRequests() {
        int maxRequests = 3;
        Duration window = Duration.ofSeconds(60);
        String clientId = clientId("ip");

        RateLimitCheckResult result1 =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        assertThat(result1.allowed()).isTrue();
        assertThat(result1.remaining()).isEqualTo(2);

        RateLimitCheckResult result2 =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        assertThat(result2.allowed()).isTrue();
        assertThat(result2.remaining()).isEqualTo(1);

        RateLimitCheckResult result3 =
                rateLimitService.checkRateLimit(clientId, maxRequests, window);
        assertThat(result3.allowed()).isTrue();
        assertThat(result3.remaining()).isZero();
    }

    @Test
    @DisplayName("서로 다른 clientId와 scope는 독립적인 rate limit을 갖는다")
    void checkRateLimit_IndependentLimitsPerClientAndScope() {
        String suffix = UUID.randomUUID().toString();

        RateLimitCheckResult ip = rateLimitService.checkRateLimit(
                "ip:" + suffix, 1, Duration.ofSeconds(60));
        RateLimitCheckResult user = rateLimitService.checkRateLimit(
                "user:" + suffix, 1, Duration.ofSeconds(60));
        RateLimitCheckResult ipAndUser = rateLimitService.checkRateLimit(
                "ip_user:" + suffix, 1, Duration.ofSeconds(60));

        assertThat(List.of(ip, user, ipAndUser))
                .allSatisfy(result -> {
                    assertThat(result.allowed()).isTrue();
                    assertThat(result.remaining()).isZero();
                });
    }

    @Test
    @DisplayName("동시 요청의 증가 횟수가 유실되지 않는다")
    void checkRateLimit_ConcurrentRequests_DoNotLoseIncrements() throws Exception {
        int requestCount = 20;
        String clientId = clientId("user");
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<RateLimitCheckResult>> futures = IntStream.range(0, requestCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return rateLimitService.checkRateLimit(
                                clientId, requestCount, Duration.ofSeconds(60));
                    }))
                    .toList();

            ready.await();
            start.countDown();

            List<Integer> remaining = futures.stream()
                    .map(future -> {
                        try {
                            return future.get().remaining();
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    })
                    .toList();

            assertThat(remaining).containsExactlyInAnyOrderElementsOf(
                    IntStream.range(0, requestCount).boxed().toList());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("후속 요청은 fixed window의 reset을 연장하지 않는다")
    void checkRateLimit_ConsecutiveRequests_DoNotExtendWindow() throws Exception {
        String clientId = clientId("user");

        RateLimitCheckResult first = rateLimitService.checkRateLimit(
                clientId, 3, Duration.ofSeconds(3));
        Thread.sleep(1100);
        RateLimitCheckResult second = rateLimitService.checkRateLimit(
                clientId, 3, Duration.ofSeconds(3));

        assertThat(second.resetEpochSeconds()).isEqualTo(first.resetEpochSeconds());
        assertThat(second.retryAfterSeconds()).isLessThan(first.retryAfterSeconds());
    }

    @Test
    @DisplayName("window 만료 후 카운터는 1부터 다시 시작한다")
    void checkRateLimit_ExpiredWindow_RestartsCounter() throws Exception {
        String clientId = clientId("user");

        RateLimitCheckResult first = rateLimitService.checkRateLimit(
                clientId, 3, Duration.ofSeconds(1));
        Thread.sleep(1200);
        RateLimitCheckResult restarted = rateLimitService.checkRateLimit(
                clientId, 3, Duration.ofSeconds(1));

        assertThat(restarted.allowed()).isTrue();
        assertThat(restarted.remaining()).isEqualTo(2);
        assertThat(restarted.resetEpochSeconds()).isGreaterThan(first.resetEpochSeconds());
    }

    private String clientId(String scope) {
        return scope + ":" + UUID.randomUUID();
    }
}
