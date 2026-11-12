package com.processing.cardmanagement.services.retries;

import com.processing.cardmanagement.exceptions.OutOfRetriesException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryServiceImplTest {
    private final RetryServiceImpl retry = new RetryServiceImpl();

    @Test
    void successfulFirstAttemptIsNotRepeated() {
        Supplier<?> operation = mock(Supplier.class);
        var result = new Object();
        org.mockito.Mockito.doReturn(result).when(operation).get();
        assertThat(retry.supply(3, operation)).isSameAs(result);
        verify(operation).get();
    }

    @Test
    void returnsResultOfLastAllowedAttempt() {
        Supplier<String> operation = stringOperation();
        when(operation.get()).thenThrow(new IllegalStateException("collision 1"))
                .thenThrow(new IllegalStateException("collision 2")).thenReturn("saved");
        assertThat(retry.supply(2, operation)).isEqualTo("saved");
        verify(operation, times(3)).get();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void retriesMeanAdditionalAttemptsAndFailureKeepsOriginalReason(int maxRetries) {
        Supplier<String> operation = stringOperation();
        var reason = new IllegalArgumentException("PAN collision");
        when(operation.get()).thenThrow(reason);

        var failure = catchThrowableOfType(OutOfRetriesException.class, () -> retry.supply(maxRetries, operation));

        assertThat(failure.getReason()).isSameAs(reason);
        assertThat(failure).hasMessageContaining("retries > " + maxRetries).hasMessageContaining("PAN collision");
        verify(operation, times(maxRetries + 1)).get();
    }

    @SuppressWarnings("unchecked")
    private static Supplier<String> stringOperation() {
        return mock(Supplier.class);
    }
}
