package com.processing.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionRateLimitFilterTest {
    @Mock private InMemoryRateLimiter limiter;
    @Mock private ClientIpResolver resolver;
    @Mock private GatewayMetrics metrics;
    @Mock private GatewayFilterChain chain;
    private final ObjectMapper mapper = new ObjectMapper();
    private TransactionRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TransactionRateLimitFilter(limiter, resolver, mapper, metrics);
        when(resolver.resolve(any())).thenReturn("192.0.2.42");
    }

    @Test
    void rejectedTransactionHasRetryHintAndDoesNotReachDownstream() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions"));
        when(limiter.allowRequest("POST /api/transactions:192.0.2.42")).thenReturn(false);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        var body = mapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(body.path("error").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.path("retryAfterMs").asInt()).isEqualTo(1000);
        verify(metrics).recordRateLimitRejected();
        verifyNoInteractions(chain);
    }

    @Test
    void allowedTransactionContinuesWithTheSameExchange() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions"));
        when(limiter.allowRequest("POST /api/transactions:192.0.2.42")).thenReturn(true);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/transactions", "POST,/api/cards", "GET,/health"})
    void unrelatedRequestsDoNotConsumeTransactionQuota(String method, String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), path));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(limiter, metrics);
    }
}
