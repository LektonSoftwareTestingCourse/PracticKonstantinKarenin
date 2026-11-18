package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.hamcrest.Matchers.*;

/** Sources: InternalTransactionController, TransactionMapper.matches, tz/08. */
class LoggerDeliveryTest extends ApiTest {
    @Test
    void repeatedDeliveryReturnsExistingTransactionWithoutCreatingAnotherRow() {
        // given
        var request = Data.transaction();
        stored(request);
        // when
        var response = Api.logger().body(request).when().post("/api/internal/log");
        // then: D-02 repeat returns full TransactionResponse, not {status: already_exists}.
        response.then().statusCode(200).body("id", equalTo(request.get("id")), "status", equalTo("APPROVED"))
                .body("pan", equalTo(request.get("pan")), "amount", equalTo(100));
        Api.gateway().queryParam("pan", request.get("pan")).get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(1), "transactions", hasSize(1))
                .body("transactions[0].id", equalTo(request.get("id")));
    }

    @ParameterizedTest
    @CsvSource({"amount,101", "stan,654321", "rrn,123456789012", "pan,9999991234567890",
            "mti,0110", "currencyCode,840", "processingCode,111111", "terminalId,87654321",
            "terminalType,ATM", "merchantId,123456789012345", "mcc,5812", "acquirerId,OTHER",
            "issuerId,ISS002", "acquiringFee,2", "authCode,OTHER1", "processingTimeMs,2",
            "status,DECLINED", "declineReason,CARD_BLOCKED", "transmissionDateTime,2025-01-01T00:00:00Z"})
    void sameIdWithDifferentBusinessDataIsConflict(String field, String value) {
        // given
        var request = Data.transaction();
        stored(request);
        var changed = new java.util.LinkedHashMap<>(request);
        Object replacement = switch (field) {
            case "amount", "acquiringFee", "processingTimeMs" -> Integer.valueOf(value);
            default -> value;
        };
        changed.put(field, replacement);
        // when
        var response = Api.logger().body(changed).when().post("/api/internal/log");
        // then
        response.then().statusCode(409).body("error", equalTo("Transaction conflict"))
                .body("serviceName", equalTo("transaction-logger"), "message", containsString(request.get("id").toString()));
        Api.gateway().queryParam("pan", request.get("pan")).get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(1), "transactions[0].amount", equalTo(100));
    }

    @Test
    void equivalentDecimalScaleAndChangedCreatedAtAreStillDuplicates() {
        // given
        var request = Data.transaction();
        stored(request);
        request.put("amount", new java.math.BigDecimal("100.00"));
        request.put("createdAt", "2025-01-01T00:00:00Z");
        // when / then
        Api.logger().body(request).when().post("/api/internal/log").then().statusCode(200)
                .body("id", equalTo(request.get("id")), "amount", equalTo(100));
    }

    @Test
    void differentIdIsANewDeliveryEvenForIdenticalBusinessData() {
        // given
        var request = Data.transaction();
        stored(request);
        request.put("id", UUID.randomUUID().toString());
        // when / then
        Api.logger().body(request).when().post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(request.get("id")), "status", equalTo("stored"));
        Api.gateway().queryParam("pan", request.get("pan")).get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(2), "transactions", hasSize(2));
    }
}
