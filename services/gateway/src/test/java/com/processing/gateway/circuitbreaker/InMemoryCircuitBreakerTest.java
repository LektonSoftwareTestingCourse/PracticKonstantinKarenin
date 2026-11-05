package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemoryCircuitBreakerTest {
    private static final Instant START = Instant.parse("2026-11-05T09:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private InMemoryCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        breaker = InMemoryCircuitBreaker.forTesting(Duration.ofSeconds(10), 3, clock);
    }

    @Test
    void opensAtThirdFailureAndAllowsOneProbeAtExactTimeout() {
        assertThat(breaker.allowRequest("switch")).isTrue();
        breaker.recordFailure("switch");
        breaker.recordFailure("switch");
        assertThat(breaker.allowRequest("switch")).isTrue();
        breaker.recordFailure("switch");
        assertThat(breaker.allowRequest("switch")).isFalse();
        assertThat(breaker.allowRequest("cards")).isTrue();
        now.set(START.plusSeconds(10).minusNanos(1));
        assertThat(breaker.allowRequest("switch")).isFalse();
        now.set(START.plusSeconds(10));
        assertThat(breaker.allowRequest("switch")).isTrue();
        assertThat(breaker.allowRequest("switch")).isFalse();
        breaker.recordSuccess("switch");
        assertThat(breaker.allowRequest("switch")).isTrue();
        breaker.recordFailure("switch");
        assertThat(breaker.allowRequest("switch")).isTrue();
    }

    @Test
    void unsuccessfulProbeStartsANewOpenInterval() {
        openCircuit();
        now.set(START.plusSeconds(10));
        assertThat(breaker.allowRequest("switch")).isTrue();
        breaker.recordFailure("switch");
        now.set(START.plusSeconds(20).minusNanos(1));
        assertThat(breaker.allowRequest("switch")).isFalse();
        now.set(START.plusSeconds(20));
        assertThat(breaker.allowRequest("switch")).isTrue();
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        breaker.recordFailure("switch");
        breaker.recordFailure("switch");
        breaker.recordSuccess("switch");
        breaker.recordFailure("switch");
        breaker.recordFailure("switch");
        assertThat(breaker.allowRequest("switch")).isTrue();
        breaker.recordFailure("switch");
        assertThat(breaker.allowRequest("switch")).isFalse();
    }

    @Test
    void cancelledProbeReleasesOnlyItsSlot() {
        openCircuit();
        breaker.releaseRequest("switch");
        assertThat(breaker.allowRequest("switch")).isFalse();
        now.set(START.plusSeconds(10));
        assertThat(breaker.allowRequest("switch")).isTrue();
        breaker.releaseRequest("switch");
        assertThat(breaker.allowRequest("switch")).isTrue();
        assertThat(breaker.allowRequest("switch")).isFalse();
    }

    @Test
    void concurrentRequestsShareOneHalfOpenProbe() throws Exception {
        openCircuit();
        now.set(START.plusSeconds(10));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Probe start timed out");
                    }
                    return breaker.allowRequest("switch");
                }));
            }
            start.countDown();
            int allowed = 0;
            for (var result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(1);
        }
    }

    private void openCircuit() {
        for (int failure = 0; failure < 3; failure++) {
            breaker.recordFailure("switch");
        }
    }
}
