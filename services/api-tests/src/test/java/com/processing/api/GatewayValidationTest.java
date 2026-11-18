package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;

/** Source: TransactionRequestValidator and TransactionValidationFilter. */
class GatewayValidationTest {
    static Stream<Arguments> invalidFields() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("mti", "stan", "pan", "processingCode", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId")) {
            cases.add(Arguments.of(field, null, "required"));
            cases.add(Arguments.of(field, "", "required"));
            cases.add(Arguments.of(field, "  ", "required"));
        }
        cases.add(Arguments.of("transmissionDateTime", null, "required"));
        cases.add(Arguments.of("amount", null, "required"));
        cases.add(Arguments.of("amount", 0, "> 0"));
        cases.add(Arguments.of("amount", -1, "> 0"));
        cases.add(Arguments.of("mti", "0200", "0100"));
        for (String pan : List.of("1".repeat(15), "1".repeat(17), "X".repeat(16))) {
            cases.add(Arguments.of("pan", pan, "16 digits"));
        }
        for (String currency : List.of("64", "6430")) {
            cases.add(Arguments.of("currencyCode", currency, "3 characters"));
        }
        for (String mcc : List.of("541", "54110", "ABCD")) {
            cases.add(Arguments.of("mcc", mcc, "4 digits"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}={1}: {2}")
    @MethodSource("invalidFields")
    void rejectsOneInvalidField(String field, Object value, String rule) {
        // given
        var request = Data.authorization(Data.absentPan());
        request.put(field, value);
        // when
        var response = Api.gateway().body(request).when().post("/api/transactions");
        // then: error belongs to Gateway, not a later service in the chain.
        response.then().statusCode(400).contentType("application/json")
                .body("error", equalTo("VALIDATION_ERROR"), "serviceName", equalTo("gateway"))
                .body("message", allOf(containsString(field), containsString(rule)))
                .body("timestamp", notNullValue(), "retryAfterMs", nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "", "null", "{\"amount\":\"not-a-number\"}"})
    void rejectsMalformedBody(String body) {
        // given / when
        var response = Api.gateway().body(body).when().post("/api/transactions");
        // then
        response.then().statusCode(400).body("serviceName", equalTo("gateway"))
                .body("message", oneOf("Request body must be valid JSON", "Request body is required"));
    }
}
