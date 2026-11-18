package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;

/** Source: every Bean Validation constraint in common/TransactionRequest. */
class LoggerValidationTest {
    static Stream<Arguments> invalidData() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("id", "mti", "stan", "pan", "processingCode", "amount", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId", "status", "transmissionDateTime", "createdAt")) {
            cases.add(Arguments.of(field, null));
        }
        for (String field : List.of("mti", "stan", "pan", "processingCode", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId")) {
            cases.add(Arguments.of(field, ""));
        }
        for (String field : List.of("stan", "processingCode")) {
            for (String value : List.of("1".repeat(5), "1".repeat(7), "ABCDEF")) {
                cases.add(Arguments.of(field, value));
            }
        }
        for (String value : List.of("1".repeat(15), "1".repeat(17), "X".repeat(16))) {
            cases.add(Arguments.of("pan", value));
        }
        for (String value : List.of("64", "6430", "ABC")) {
            cases.add(Arguments.of("currencyCode", value));
        }
        for (String value : List.of("541", "54110", "ABCD")) {
            cases.add(Arguments.of("mcc", value));
        }
        for (var entry : java.util.Map.of("mti", 4, "rrn", 12, "terminalId", 8, "terminalType", 10,
                "merchantId", 15, "acquirerId", 10, "issuerId", 10, "authCode", 6, "declineReason", 100).entrySet()) {
            cases.add(Arguments.of(entry.getKey(), "X".repeat(entry.getValue() + 1)));
        }
        cases.add(Arguments.of("amount", 0));
        cases.add(Arguments.of("amount", -1));
        cases.add(Arguments.of("acquiringFee", -1));
        cases.add(Arguments.of("processingTimeMs", -1));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidData")
    void reportsFieldAndRuleForInvalidLog(String field, Object value) {
        // given
        var request = Data.transaction();
        request.put(field, value);
        // when
        var response = Api.logger().body(request).when().post("/api/internal/log");
        // then
        response.then().statusCode(400).body("error", equalTo("Validation error"))
                .body("message", allOf(containsString(field), not(equalTo(field))))
                .body("serviceName", equalTo("transaction-logger"), "timestamp", notNullValue());
        Api.gateway().queryParam("pan", request.get("pan") == null ? Data.absentPan() : request.get("pan"))
                .get("/api/transactions/search").then().statusCode(200)
                .body("transactions", hasSize(0), "total", equalTo(0));
    }
}
