package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {
    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @Test
    void rejectsMissingBody() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @ParameterizedTest(name = "required {0}: [{1}]")
    @MethodSource("missingFields")
    void rejectsMissingRequiredText(String field, String value) {
        var builder = validRequest();
        switch (field) {
            case "mti" -> builder.mti(value);
            case "stan" -> builder.stan(value);
            case "pan" -> builder.pan(value);
            case "processingCode" -> builder.processingCode(value);
            case "currencyCode" -> builder.currencyCode(value);
            case "terminalId" -> builder.terminalId(value);
            case "merchantId" -> builder.merchantId(value);
            case "mcc" -> builder.mcc(value);
            case "acquirerId" -> builder.acquirerId(value);
            default -> throw new IllegalArgumentException(field);
        }
        assertThatThrownBy(() -> validator.validate(builder.build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field '" + field + "' is required");
    }

    static Stream<Arguments> missingFields() {
        return Stream.of("mti", "stan", "pan", "processingCode", "currencyCode",
                        "terminalId", "merchantId", "mcc", "acquirerId")
                .flatMap(field -> Stream.of(null, "", " \t ").map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-0.01", "0", "0.00"})
    void rejectsMissingOrNonPositiveAmount(String amount) {
        var request = validRequest().amount(amount == null ? null : new BigDecimal(amount)).build();
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class).hasMessageContaining("'amount'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "12000.00"})
    void acceptsPositiveAmountBeforeIssuerHasBeenResolved(String amount) {
        var request = validRequest().amount(new BigDecimal(amount)).issuerId(null).terminalType(null).build();
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"pan,400000123456789", "pan,40000012345678930", "pan,400000123456789X",
            "mti,0200", "currencyCode,64", "currencyCode,0643", "mcc,123", "mcc,12345", "mcc,12X4"})
    void rejectsInvalidFieldFormat(String field, String value) {
        var builder = validRequest();
        switch (field) {
            case "pan" -> builder.pan(value);
            case "mti" -> builder.mti(value);
            case "currencyCode" -> builder.currencyCode(value);
            case "mcc" -> builder.mcc(value);
            default -> throw new IllegalArgumentException(field);
        }
        assertThatThrownBy(() -> validator.validate(builder.build()))
                .isInstanceOf(TransactionValidationException.class).hasMessageContaining("'" + field + "'");
    }

    @Test
    void requiresTransmissionTime() {
        assertThatThrownBy(() -> validator.validate(validRequest().transmissionDateTime(null).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'transmissionDateTime' is required");
    }

    private static AuthorizationRequest.AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequest.builder().mti("0100").stan("004201").pan("4000001234567899")
                .processingCode("000000").amount(new BigDecimal("3000.00")).currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-11-05T09:00:00Z"))
                .terminalId("TERM0042").merchantId("MERCHANT0000042").mcc("5411").acquirerId("ACQ042");
    }
}
