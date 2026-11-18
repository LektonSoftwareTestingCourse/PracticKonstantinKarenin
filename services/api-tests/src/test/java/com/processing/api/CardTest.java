package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.hamcrest.Matchers.*;

/** Source: api-spec §2, CardController, CardServiceImpl and CreateCardRequest. */
class CardTest extends ApiTest {
    @Test
    void gatewayCacheDoesNotObserveAnInternalReservation() {
        // given: D-03 cache is invalidated by public mutations, not internal CMS calls.
        String pan = createCard().get("pan").toString();
        Api.gateway().get("/api/cards/{pan}", pan).then().statusCode(200)
                .header("X-Cache", equalTo("MISS")).body("availableBalance", equalTo(10000));
        // when
        Api.cards().body(Data.funds(pan, Data.digits(12), 100)).when().post("/api/cards/{pan}/reserve", pan)
                .then().statusCode(200).body("availableBalance", equalTo(9900));
        // then
        Api.gateway().get("/api/cards/{pan}", pan).then().statusCode(200)
                .header("X-Cache", equalTo("HIT")).body("availableBalance", equalTo(10000));
        balance(pan, 9900);
    }

    @Test
    void createsReadsUpdatesAndDeletesOwnCard() {
        // given
        var card = createCard();
        String pan = card.get("pan").toString();
        // when
        var response = Api.gateway().when().get("/api/cards/{pan}", pan);
        // then
        response.then().statusCode(200).header("X-Cache", equalTo("MISS"))
                .body("id", matchesPattern("[a-f0-9-]{36}"), "bin", equalTo("400000"))
                .body("cardholderName", equalTo("API TEST"), "currencyCode", equalTo("643"))
                .body("dailyLimit", equalTo(10000), "monthlyLimit", equalTo(100000))
                .body("availableBalance", equalTo(10000), "issuerId", equalTo("ISS001"))
                .body("createdAt", notNullValue());
        Api.gateway().when().get("/api/cards/{pan}", pan).then().statusCode(200)
                .header("X-Cache", equalTo("HIT")).body("pan", equalTo(pan));
        Api.gateway().body(Map.of("status", "BLOCKED", "availableBalance", 5000))
                .when().patch("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo("BLOCKED"), "availableBalance", equalTo(5000));
        Api.gateway().when().get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo("BLOCKED"));
        Api.gateway().when().delete("/api/cards/{pan}", pan).then().statusCode(204).body(emptyString());
        Api.cards().when().get("/api/cards/{pan}", pan).then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"), "message", containsString(Data.maskedPan(pan)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "BLOCKED", "INACTIVE", "EXPIRED"})
    void updatesEveryCardStatus(String status) {
        // given
        String pan = createCard().get("pan").toString();
        // when
        var response = Api.gateway().body(Map.of("status", status)).when().patch("/api/cards/{pan}", pan);
        // then
        response.then().statusCode(200).body("pan", equalTo(pan), "status", equalTo(status));
    }

    @ParameterizedTest
    @CsvSource({"bin,40000,BIN", "bin,4000000,BIN", "bin,ABCDEF,BIN",
            "cardholderName,Ivan,uppercase", "currencyCode,64,3", "currencyCode,6430,3",
            "currencyCode,ABC,digits", "dailyLimit,-1,negative", "monthlyLimit,-1,negative"})
    void rejectsInvalidCreation(String field, String value, String message) {
        // given
        var request = Data.card();
        request.put(field, field.endsWith("Limit") ? Integer.valueOf(value) : value);
        // when
        var response = Api.gateway().body(request).when().post("/api/cards");
        // then: D-05 CMS messages do not always include the field name.
        response.then().statusCode(400).body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", containsStringIgnoringCase(message)).body("serviceName", notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bin", "cardholderName", "currencyCode"})
    void rejectsMissingCreationFields(String field) {
        // given
        var request = Data.card();
        request.remove(field);
        // when
        var response = Api.gateway().body(request).when().post("/api/cards");
        // then
        response.then().statusCode(400).body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", not(isEmptyOrNullString()), "serviceName", notNullValue());
    }

    @Test
    void rejectsUnknownBin() {
        // given
        var request = Data.card();
        request.put("bin", "999999");
        // when / then
        Api.gateway().body(request).when().post("/api/cards").then().statusCode(404)
                .body("error", equalTo("BinNotFoundException"), "message", containsString("999999"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE"})
    void rejectsUnknownCard(String method) {
        // given
        String pan = Data.absentPan();
        // when
        var response = Api.cards().body(Map.of("status", "ACTIVE"))
                .when().request(method, "/api/cards/" + pan);
        // then
        response.then().statusCode(404).body("error", equalTo("CardNotFoundException"))
                .body("message", containsString(Data.maskedPan(pan)), "serviceName", notNullValue());
    }

    @ParameterizedTest
    @CsvSource({"GET,123456789012345", "GET,12345678901234567", "GET,ABCDEFGHIJKLMNOP",
            "PATCH,123456789012345", "DELETE,123456789012345"})
    void pathPanIsValidated(String method, String pan) {
        // given
        var request = Api.cards().body(Map.of("status", "ACTIVE"));
        // when
        var response = request.when().request(method, "/api/cards/" + pan);
        // then
        response.then().statusCode(400).body("error", equalTo("ConstraintViolationException"))
                .body("message", containsString("PAN"), "serviceName", equalTo("card-management"));
    }

    @ParameterizedTest
    @CsvSource({"dailyLimit,-1", "monthlyLimit,-1", "dailyLimit,100001"})
    void patchRejectsInvalidLimits(String field, int value) {
        // given
        String pan = createCard().get("pan").toString();
        // when
        var response = Api.cards().body(Map.of(field, value)).when().patch("/api/cards/{pan}", pan);
        // then
        response.then().statusCode(400)
                .body("message", containsStringIgnoringCase(value < 0 ? "negative" : "limit"))
                .body("serviceName", equalTo("card-management"));
        balance(pan, 10000);
    }

    @Test
    void bulkUpdateTargetsOnlyOwnCards() {
        // given
        String pan = createCard().get("pan").toString();
        String control = createCard().get("pan").toString();
        // when
        var response = Api.gateway().body(Map.of("pans", java.util.List.of(pan), "status", "BLOCKED"))
                .when().post("/api/cards/bulk-update");
        // then
        response.then().statusCode(200).body("updated", equalTo(1));
        Api.cards().get("/api/cards/{pan}", pan).then().statusCode(200).body("status", equalTo("BLOCKED"));
        Api.cards().get("/api/cards/{pan}", control).then().statusCode(200).body("status", equalTo("ACTIVE"));
    }

    @Test
    void rejectsAmbiguousBulkUpdate() {
        // given / when
        var response = Api.gateway().body(Map.of("status", "BLOCKED"))
                .when().post("/api/cards/bulk-update");
        // then
        response.then().statusCode(400).body("error", equalTo("IllegalArgumentException"))
                .body("message", equalTo("Either bin or pans must be provided"));
    }
}
