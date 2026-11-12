package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationClientTest {
    private static final String AUTHORIZATION_URL = "http://authorization.test";
    private static final String RRN = "630900001042";
    private static final int MAX_ATTEMPTS = 3;
    private static final AuthorizationRequest REQUEST = new AuthorizationRequest(
            "0100", "004201", "4000001234567899", "000000", new BigDecimal("3000.00"), "643",
            Instant.parse("2026-11-05T09:00:00Z"), "TERM0042", "POS", "MERCHANT0000042", "5411", "ACQ042", "ISSUER_A");

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec post;
    @Mock private RestClient.RequestBodySpec body;
    @Mock private RestClient.RequestHeadersUriSpec<?> get;
    @Mock private RestClient.RequestHeadersSpec<?> headers;
    @Mock private RestClient.ResponseSpec response;
    private CircuitBreaker circuitBreaker;
    private Retry retry;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        var properties = new SwitchProperties("test", Map.of(), AUTHORIZATION_URL, null, null, null,
                new SwitchProperties.RetryProperties(MAX_ATTEMPTS, List.of()), null);
        circuitBreaker = CircuitBreaker.ofDefaults("authorization-test");
        retry = Retry.of("authorization-test", RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS).waitDuration(Duration.ZERO).build());
        client = new AuthorizationClient(properties, restClient, retry, circuitBreaker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnAuthorizationResponseWhenStatusIsValid(String status) {
        // Arrange
        var expected = authorizationResponse(status);
        stubPost("/api/internal/authorize", REQUEST);
        when(response.body(AuthorizationResponse.class)).thenReturn(expected);

        // Act
        var actual = client.authorize(REQUEST);

        // Assert
        assertThat(actual).isSameAs(expected);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidAuthorizationResponses")
    void shouldRejectAuthorizationWhenResponseIsMissingOrStatusIsInvalid(AuthorizationResponse invalid) {
        // Arrange
        stubPost("/api/internal/authorize", REQUEST);
        when(response.body(AuthorizationResponse.class)).thenReturn(invalid);

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(REQUEST))
                .isInstanceOfSatisfying(AuthorizationException.class, error -> {
                    assertThat(error.getStan()).isEqualTo(REQUEST.stan());
                    assertThat(error).hasMessageContaining("after 3 attempts")
                            .hasMessageContaining("Invalid response from Authorization");
                });
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    void shouldReturnAuthorizationWhenRetryRecoversFromTransportFailure() {
        // Arrange
        var expected = authorizationResponse("APPROVED");
        stubPost("/api/internal/authorize", REQUEST);
        when(response.body(AuthorizationResponse.class))
                .thenThrow(new IllegalStateException("connection reset")).thenReturn(expected);

        // Act
        var actual = client.authorize(REQUEST);

        // Assert
        assertThat(actual).isSameAs(expected);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void shouldPreserveLastErrorWhenAuthorizationRetriesAreExhausted(RuntimeException failure, String expectedError) {
        // Arrange
        stubPost("/api/internal/authorize", REQUEST);
        when(response.body(AuthorizationResponse.class)).thenThrow(failure);

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(REQUEST))
                .isInstanceOfSatisfying(AuthorizationException.class, error -> {
                    assertThat(error.getStan()).isEqualTo(REQUEST.stan());
                    assertThat(error).hasMessageContaining("after 3 attempts")
                            .hasMessageEndingWith("Last error: " + expectedError);
                });
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    void shouldRejectAuthorizationWithoutHttpCallWhenCircuitBreakerIsOpen() {
        // Arrange
        circuitBreaker.transitionToOpenState();

        // Act / Assert
        assertThatThrownBy(() -> client.authorize(REQUEST))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
        verifyNoInteractions(restClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnRollbackResponseWhenStatusIsValid(String status) {
        // Arrange
        var expected = new RollbackResponse(RRN, "00", status, null, 1);
        stubPost("/api/internal/rollback", new RollbackRequest(RRN, REQUEST.pan(), REQUEST.amount()));
        when(response.body(RollbackResponse.class)).thenReturn(expected);

        // Act
        var actual = client.rollback(REQUEST, RRN);

        // Assert
        assertThat(actual).isSameAs(expected);
    }

    @ParameterizedTest
    @MethodSource("invalidRollbackResponses")
    void shouldReturnNullWhenRollbackResponseIsMissingOrStatusIsInvalid(RollbackResponse invalid) {
        // Arrange
        stubPost("/api/internal/rollback", new RollbackRequest(RRN, REQUEST.pan(), REQUEST.amount()));
        when(response.body(RollbackResponse.class)).thenReturn(invalid);

        // Act
        var actual = client.rollback(REQUEST, RRN);

        // Assert
        assertThat(actual).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackTransportFails() {
        // Arrange
        stubPost("/api/internal/rollback", new RollbackRequest(RRN, REQUEST.pan(), REQUEST.amount()));
        when(response.body(RollbackResponse.class)).thenThrow(new IllegalStateException("connection reset"));

        // Act
        var actual = client.rollback(REQUEST, RRN);

        // Assert
        assertThat(actual).isNull();
    }

    @Test
    void shouldReportOkWhenHealthRequestSucceeds() {
        // Arrange
        stubHealthRequest();

        // Act
        var health = client.checkHealth();

        // Assert
        assertThat(health).isEqualTo("ok");
    }

    @Test
    void shouldReportDownWhenHealthRequestFails() {
        // Arrange
        stubHealthRequest();
        when(response.toBodilessEntity()).thenThrow(new IllegalStateException("connection reset"));

        // Act
        var health = client.checkHealth();

        // Assert
        assertThat(health).isEqualTo("down");
    }

    static Stream<AuthorizationResponse> invalidAuthorizationResponses() {
        return Stream.of(null, authorizationResponse(null), authorizationResponse(""), authorizationResponse("PENDING"));
    }

    static Stream<RollbackResponse> invalidRollbackResponses() {
        return Stream.of(null, new RollbackResponse(RRN, "00", null, null, 1),
                new RollbackResponse(RRN, "00", "", null, 1), new RollbackResponse(RRN, "00", "PENDING", null, 1));
    }

    static Stream<Arguments> transportFailures() {
        return Stream.of(
                Arguments.of(new IllegalStateException("connection reset"), "connection reset"),
                Arguments.of(new IllegalStateException("request failed", new IllegalArgumentException("timeout")), "timeout"),
                Arguments.of(new IllegalStateException((String) null), "unknown"));
    }

    private static AuthorizationResponse authorizationResponse(String status) {
        return new AuthorizationResponse("0110", REQUEST.stan(), RRN, "AUTH42", "00", status, null, 1);
    }

    private void stubPost(String endpoint, Object request) {
        when(restClient.post()).thenReturn(post);
        when(post.uri(AUTHORIZATION_URL + endpoint)).thenReturn(body);
        when(body.body(request)).thenReturn(body);
        when(body.retrieve()).thenReturn(response);
        when(response.onStatus(any(), any())).thenReturn(response);
    }

    private void stubHealthRequest() {
        when(restClient.get()).thenAnswer(invocation -> get);
        when(get.uri(AUTHORIZATION_URL + "/health")).thenAnswer(invocation -> headers);
        when(headers.retrieve()).thenReturn(response);
    }
}
