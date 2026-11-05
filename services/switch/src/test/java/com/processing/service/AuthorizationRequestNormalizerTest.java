package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {
    @ParameterizedTest
    @CsvSource({"T,T0000000", "TERM0042,TERM0042", "TERM0042extra,TERM0042"})
    void terminalIdentifierIsPaddedOrTruncated(String input, String expected) {
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"SHOP,SHOP00000000000", "MERCH1234567890,MERCH1234567890",
            "MERCH01234567890,MERCH1234567890", "MERCHX1234567890,MERCHX123456789",
            "STORE01234567890,STORE0123456789", "MERCH01234567890EXTRA,MERCH0123456789"})
    void merchantIdentifierHandlesLegacyPrefixAndLengthBoundaries(String input, String expected) {
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void absentIdentifiersRemainAvailableForValidation(String value) {
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId(value)).isEqualTo(value);
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId(value)).isEqualTo(value);
    }

    @Test
    void normalizationIsIdempotentAndPreservesPaymentData() {
        var input = new AuthorizationRequest("0100", "004201", "4000001234567899", "000000",
                new BigDecimal("3000.00"), "643", Instant.parse("2026-11-05T09:00:00Z"),
                "T", "POS", "SHOP", "5411", "ACQ042", "ISSUER_A");
        var expected = new AuthorizationRequest("0100", "004201", "4000001234567899", "000000",
                new BigDecimal("3000.00"), "643", Instant.parse("2026-11-05T09:00:00Z"),
                "T0000000", "POS", "SHOP00000000000", "5411", "ACQ042", "ISSUER_A");

        var normalized = AuthorizationRequestNormalizer.normalize(input);

        assertThat(normalized).isEqualTo(expected);
        assertThat(AuthorizationRequestNormalizer.normalize(normalized)).isEqualTo(expected);
        assertThat(input.terminalId()).isEqualTo("T");
        assertThat(input.merchantId()).isEqualTo("SHOP");
    }
}
