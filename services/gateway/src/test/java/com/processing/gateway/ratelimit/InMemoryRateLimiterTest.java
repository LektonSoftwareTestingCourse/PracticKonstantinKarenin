package com.processing.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {
    private final AtomicLong nanos = new AtomicLong();

    private InMemoryRateLimiter limiter(int capacity, double refill) {
        return InMemoryRateLimiter.forTesting(capacity, refill, Duration.ofMinutes(1), 100, nanos::get);
    }

    @Test
    void consumesExactlyCapacityAndKeepsSeparateBuckets() {
        var limiter = limiter(100, 100);
        for (int request = 0; request < 100; request++) {
            assertThat(limiter.allowRequest("client-a")).as("request %s", request + 1).isTrue();
        }
        assertThat(limiter.allowRequest("client-a")).isFalse();
        assertThat(limiter.allowRequest("client-b")).isTrue();
    }

    @Test
    void refillsOneTokenExactlyAtTenMilliseconds() {
        var limiter = limiter(1, 100);
        assertThat(limiter.allowRequest("client")).isTrue();
        nanos.set(9_999_999);
        assertThat(limiter.allowRequest("client")).isFalse();
        nanos.incrementAndGet();
        assertThat(limiter.allowRequest("client")).isTrue();
        assertThat(limiter.allowRequest("client")).isFalse();
    }

    @Test
    void accumulatedRefillCannotExceedCapacity() {
        var limiter = limiter(2, 100);
        assertThat(limiter.allowRequest("client")).isTrue();
        nanos.set(Duration.ofSeconds(20).toNanos());
        assertThat(limiter.allowRequest("client")).isTrue();
        assertThat(limiter.allowRequest("client")).isTrue();
        assertThat(limiter.allowRequest("client")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveCapacityRejectsRequests(int capacity) {
        var limiter = limiter(capacity, 100);
        assertThat(limiter.allowRequest("client")).isFalse();
        nanos.set(Duration.ofSeconds(10).toNanos());
        assertThat(limiter.allowRequest("client")).isFalse();
    }

    @Test
    void expiredBucketStartsWithFullCapacityEvenWithoutRefill() {
        var limiter = limiter(1, 0);
        assertThat(limiter.allowRequest("client")).isTrue();
        nanos.set(Duration.ofSeconds(30).toNanos());
        assertThat(limiter.allowRequest("client")).isFalse();
        nanos.addAndGet(Duration.ofMinutes(1).toNanos());
        limiter.cleanUp();
        assertThat(limiter.allowRequest("client")).isTrue();
        assertThat(limiter.allowRequest("client")).isFalse();
    }
}
