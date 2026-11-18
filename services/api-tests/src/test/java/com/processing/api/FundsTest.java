package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.Matchers.*;

/** Source: CardController, Reservation and ReservationRollback domain rules. */
class FundsTest extends ApiTest {
    @Test
    void reservesAndRollsBackWithoutDoubleCredit() {
        // given
        String pan = createCard().get("pan").toString();
        String rrn = Data.digits(12);
        var request = Data.funds(pan, rrn, 100);
        // when
        var response = Api.gateway().body(request).when().post("/api/cards/{pan}/reserve", pan);
        // then
        response.then().statusCode(200).body("availableBalance", equalTo(9900));
        Api.gateway().body(request).post("/api/cards/{pan}/reserve", pan).then().statusCode(409)
                .body("error", equalTo("ReservationAlreadyExistsException"), "message", containsString(rrn));
        balance(pan, 9900);
        Api.gateway().body(request).post("/api/cards/{pan}/rollback", pan).then().statusCode(200)
                .body("availableBalance", equalTo(10000));
        Api.gateway().body(request).post("/api/cards/{pan}/rollback", pan).then().statusCode(409)
                .body("error", equalTo("RollbackAlreadySatisfiedException"), "message", notNullValue());
        balance(pan, 10000);
    }

    @ParameterizedTest
    @CsvSource({"1,200,9999", "10000,200,0", "10001,402,10000"})
    void checksBalanceBoundary(int amount, int status, int expectedBalance) {
        // given
        String pan = createCard().get("pan").toString();
        // when
        var response = Api.gateway().body(Data.funds(pan, Data.digits(12), amount))
                .when().post("/api/cards/{pan}/reserve", pan);
        // then
        response.then().statusCode(status);
        if (status == 200) {
            response.then().body("availableBalance", equalTo(expectedBalance));
        } else {
            response.then().body("error", equalTo("InsufficientFundsException"), "message", notNullValue());
        }
        balance(pan, expectedBalance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "rollback"})
    void rejectsUnknownCard(String operation) {
        // given
        String pan = Data.absentPan();
        // when / then
        Api.gateway().body(Data.funds(pan, Data.digits(12), 100))
                .when().post("/api/cards/{pan}/" + operation, pan).then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"), "message", containsString(Data.maskedPan(pan)));
    }

    @ParameterizedTest
    @CsvSource({"reserve,amount,0", "reserve,amount,-1", "rollback,amount,0", "rollback,amount,-1",
            "reserve,rrn,123", "reserve,rrn,1234567890123", "reserve,rrn,ABCDEFGHIJKL",
            "rollback,rrn,123", "rollback,rrn,ABCDEFGHIJKL"})
    void rejectsInvalidFundsRequest(String operation, String field, String value) {
        // given
        String pan = createCard().get("pan").toString();
        var request = new java.util.LinkedHashMap<>(Data.funds(pan, Data.digits(12), 100));
        request.put(field, field.equals("amount") ? Integer.valueOf(value) : value);
        // when / then
        Api.gateway().body(request).when().post("/api/cards/{pan}/" + operation, pan).then().statusCode(400)
                .body("error", equalTo("MethodArgumentNotValidException"), "message", not(isEmptyOrNullString()));
        balance(pan, 10000);
    }

    @Test
    void rejectsRollbackWithoutReservation() {
        // given
        String pan = createCard().get("pan").toString();
        // when / then
        Api.gateway().body(Data.funds(pan, Data.digits(12), 100))
                .when().post("/api/cards/{pan}/rollback", pan).then().statusCode(404)
                .body("error", equalTo("ReservationNotFoundException"), "message", notNullValue());
        balance(pan, 10000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCKED", "INACTIVE", "EXPIRED"})
    void reserveRejectsNonActiveCard(String status) {
        // given
        String pan = createCard().get("pan").toString();
        Api.cards().body(java.util.Map.of("status", status)).patch("/api/cards/{pan}", pan).then()
                .statusCode(200).body("status", equalTo(status));
        // when
        var response = Api.gateway().body(Data.funds(pan, Data.digits(12), 100))
                .when().post("/api/cards/{pan}/reserve", pan);
        // then
        response.then().statusCode(409).body("error", equalTo("IllegalStateException"))
                .body("message", containsString(status));
        balance(pan, 10000);
    }

    @Test
    void knownOverRollbackCreditsMoreThanReservedAmount() {
        // given
        String pan = createCard().get("pan").toString();
        String rrn = Data.digits(12);
        Api.gateway().body(Data.funds(pan, rrn, 100)).post("/api/cards/{pan}/reserve", pan)
                .then().statusCode(200).body("availableBalance", equalTo(9900));
        // when / then: D-07 real defect, return amount is not bounded by the reservation.
        Api.gateway().body(Data.funds(pan, rrn, 101)).when().post("/api/cards/{pan}/rollback", pan)
                .then().statusCode(200).body("availableBalance", equalTo(10001), "pan", equalTo(pan));
        balance(pan, 10001);
    }
}
