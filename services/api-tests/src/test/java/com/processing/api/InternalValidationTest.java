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

/** Source: AuthorizationRequest annotations, validated independently of Gateway. */
class InternalValidationTest {
    static Stream<Arguments> invalidData() {
        var cases = new ArrayList<Arguments>();
        for (String field : List.of("mti", "stan", "pan", "processingCode", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId", "issuerId", "amount", "transmissionDateTime")) {
            cases.add(Arguments.of(field, null));
        }
        for (var field : java.util.Map.of("mti", 4, "stan", 6, "pan", 16, "processingCode", 6,
                "currencyCode", 3, "terminalId", 8, "merchantId", 15, "mcc", 4).entrySet()) {
            cases.add(Arguments.of(field.getKey(), "1".repeat(field.getValue() - 1)));
            cases.add(Arguments.of(field.getKey(), "1".repeat(field.getValue() + 1)));
        }
        cases.add(Arguments.of("amount", -1));
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidData")
    void directAuthorizationRejectsInvalidDto(String field, Object value) {
        // given
        var request = Data.authorization(Data.absentPan());
        request.put(field, value);
        // when
        var response = Api.authorization().body(request).when().post("/api/internal/authorize");
        // then: D-05 standard Spring body lacks the field and rule, unlike Gateway and Logger.
        response.then().statusCode(400).body("status", equalTo(400), "error", equalTo("Bad Request"))
                .body("path", equalTo("/api/internal/authorize"), "timestamp", notNullValue());
    }
}
