package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;

import static org.awaitility.Awaitility.await;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** Sources: TerminalRunRequest, SimulatorRequest, both simulator controllers. */
class SimulatorTest extends ApiTest {
    @ParameterizedTest
    @CsvSource({"terminal,normal", "terminal,mixed", "terminal,high_value", "terminal,night_time",
            "terminal,declines_test", "merchant,grocery", "merchant,electronics", "merchant,restaurant", "merchant,travel"})
    void runsEveryScenario(String simulator, String scenario) {
        // given
        createCard();
        String blocked = createCard().get("pan").toString();
        Api.gateway().body(Map.of("status", "BLOCKED")).patch("/api/cards/{pan}", blocked).then()
                .statusCode(200).body("status", equalTo("BLOCKED"));
        int count = simulator.equals("terminal") ? 5 : 1;
        var request = Map.of("count", count, "scenario", scenario, "tps", 10);
        // when
        var response = Api.gateway().body(request).when().post("/api/simulator/" + simulator + "/run");
        // then: distribution is random, while submitted = approved + declined is deterministic.
        response.then().statusCode(200).body("totalSubmitted", equalTo(count), "transactions", hasSize(count))
                .body("elapsedMs", greaterThanOrEqualTo(0))
                .body("transactions[0].status", oneOf("APPROVED", "DECLINED"));
        int approved = response.path("approved");
        int declined = response.path("declined");
        assertThat(approved + declined, equalTo(count));
    }

    @ParameterizedTest
    @CsvSource({"terminal,count,0", "terminal,count,-1", "terminal,tps,0", "terminal,tps,-1",
            "merchant,count,0", "merchant,count,-1", "merchant,tps,0", "merchant,tps,-1"})
    void rejectsInvalidNumericInputs(String simulator, String field, int value) {
        // given
        var request = new java.util.LinkedHashMap<String, Object>();
        request.put("count", 1);
        request.put("tps", 10);
        request.put("scenario", simulator.equals("terminal") ? "normal" : "grocery");
        request.put(field, value);
        // when / then
        Api.gateway().body(request).when().post("/api/simulator/" + simulator + "/run").then()
                .statusCode(400).body("message", not(isEmptyOrNullString()), "serviceName", notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"terminal", "merchant"})
    void rejectsUnknownScenario(String simulator) {
        // given / when
        var response = Api.gateway().body(Map.of("count", 1, "tps", 10, "scenario", "unknown"))
                .when().post("/api/simulator/" + simulator + "/run");
        // then
        response.then().statusCode(400).body("message", not(isEmptyOrNullString()));
    }

    @Test
    void continuousModeCanBeStartedAndStopped() {
        // given
        createCard();
        try {
            // when / then: these endpoints intentionally have no response body.
            Api.gateway().body(Map.of("tps", 1, "transactionType", "NORMAL"))
                    .when().post("/api/simulator/terminal/start-continuous").then().statusCode(200).body(emptyString());
        } finally {
            Api.gateway().post("/api/simulator/terminal/stop").then().statusCode(200).body(emptyString());
        }
    }

    @Test
    void feeLookupReturnsAnIdentifiable404() {
        // given
        var request = Data.authorization(Data.absentPan());
        // when / then
        Api.gateway().body(request).when().post("/api/simulator/merchant/fee").then().statusCode(404)
                .body("message", not(isEmptyOrNullString()), "serviceName", notNullValue());
    }

    @Test
    void feeLookupReturnsFeeForTheTransactionProducedByThisSimulation() {
        // given: the simulator chooses its card; correlate its own response STAN with a new log entry.
        createCard();
        Instant started = Instant.now();
        var result = Api.gateway().body(Map.of("count", 1, "scenario", "grocery", "tps", 10))
                .post("/api/simulator/merchant/run").then().statusCode(200)
                .body("transactions", hasSize(1)).extract().response();
        String stan = result.path("transactions[0].stan");
        var entry = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            var rows = Api.gateway().queryParam("limit", 500).get("/api/transactions/search").then()
                    .statusCode(200).extract().jsonPath().<Map<String, Object>>getList("transactions");
            var matching = rows.stream().filter(row -> stan.equals(row.get("stan")))
                    .filter(row -> !Instant.parse(row.get("createdAt").toString()).isBefore(started)).toList();
            assertThat(matching, hasSize(1));
            entry.set(matching.getFirst());
        });
        var log = entry.get();
        var request = Map.of("pan", log.get("pan"), "stan", log.get("stan"), "amount", log.get("amount"),
                "terminalId", log.get("terminalId"), "transmissionDateTime", log.get("transmissionDateTime"));
        // when
        var response = Api.gateway().body(request).when().post("/api/simulator/merchant/fee");
        // then
        response.then().statusCode(200).body("acquirerFee", notNullValue());
        assertThat(new BigDecimal(response.jsonPath().getString("acquirerFee")), greaterThanOrEqualTo(BigDecimal.ZERO));
    }

    @Test
    void feeLookupValidatesRequiredFields() {
        // given / when / then
        Api.gateway().body(Map.of()).when().post("/api/simulator/merchant/fee").then().statusCode(400)
                .body("message", not(isEmptyOrNullString()), "serviceName", notNullValue());
    }
}
