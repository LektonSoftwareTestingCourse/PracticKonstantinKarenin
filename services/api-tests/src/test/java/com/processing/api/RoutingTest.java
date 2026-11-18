package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/** Source: RouteController, RouteService.route; all observations are made through HTTP. */
class RoutingTest extends ApiTest {
    @Test
    void internalRouteApprovesAndLogs() {
        // given
        String pan = createCard().get("pan").toString();
        // when
        var response = Api.service("switch", 8082).body(Data.authorization(pan))
                .when().post("/api/internal/route");
        // then
        response.then().statusCode(200).body("status", equalTo("APPROVED"), "responseCode", equalTo("00"))
                .body("rrn", matchesPattern("[0-9]{12}"));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                Api.gateway().queryParam("pan", pan).get("/api/transactions/search").then().statusCode(200)
                        .body("total", equalTo(1), "transactions[0].issuerId", equalTo("ISS001")));
    }

    @Test
    void internalRouteAcceptsShortStanBeforeUnknownBinDecline() {
        // given
        var request = Data.authorization(Data.absentPan());
        request.put("stan", "12345");
        // when / then: D-13 RouteController lacks @Valid, unknown BIN bypasses Authorization validation.
        Api.service("switch", 8082).body(request).when().post("/api/internal/route").then().statusCode(200)
                .body("status", equalTo("DECLINED"), "stan", equalTo("12345"))
                .body("responseCode", equalTo("14"), "declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void failedPublicationCompensatesReservationAndNeverBecomesSearchable() {
        // given: regular stack proves publication and search work; the isolated Switch has no broker connection.
        String control = createCard().get("pan").toString();
        Api.gateway().body(Data.authorization(control)).post("/api/transactions").then().statusCode(200)
                .body("status", equalTo("APPROVED"));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                Api.gateway().queryParam("pan", control).get("/api/transactions/search").then().statusCode(200)
                        .body("total", equalTo(1)));
        String pan = createCard().get("pan").toString();
        // when
        var response = Api.service("switch-faults", 28082).body(Data.authorization(pan))
                .when().post("/api/internal/route");
        // then: 96 is a system error from Switch, distinct from Authorization's 96 SERVICE_UNAVAILABLE.
        response.then().statusCode(200).body("status", equalTo("DECLINED"), "responseCode", equalTo("96"))
                .body("declineReason", equalTo("System error"), "rrn", nullValue(), "authCode", nullValue());
        balance(pan, 10000);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    Api.gateway().queryParam("pan", pan).get("/api/transactions/search").then().statusCode(200)
                            .body("total", equalTo(0), "transactions", hasSize(0)));
    }
}
