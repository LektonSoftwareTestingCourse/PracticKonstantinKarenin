package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** Separate Compose instances point to a refused TCP port; no DB/broker fault injection. */
class UnavailableServiceTest {
    @ParameterizedTest
    @CsvSource({"GET,/api/cards,cardManagement", "POST,/api/cards,cardManagement",
            "GET,/api/cards/4000001234567890,cardManagement", "PATCH,/api/cards/4000001234567890,cardManagement",
            "DELETE,/api/cards/4000001234567890,cardManagement", "POST,/api/cards/generate,cardManagement",
            "POST,/api/cards/bulk-update,cardManagement", "POST,/api/cards/4000001234567890/reserve,cardManagement",
            "POST,/api/cards/4000001234567890/rollback,cardManagement",
            "GET,/api/transactions/search,logger", "GET,/api/dashboard/stats,logger",
            "GET,/api/transactions/export,logger", "GET,/api/dashboard/recent,logger", "GET,/api/dashboard/charts,logger",
            "POST,/api/simulator/terminal/run,terminalSimulator", "POST,/api/simulator/merchant/run,merchantSimulator",
            "POST,/api/simulator/terminal/start-continuous,terminalSimulator",
            "POST,/api/simulator/terminal/stop,terminalSimulator", "POST,/api/simulator/merchant/fee,merchantSimulator",
            "POST,/api/transactions,switch"})
    void gatewayIdentifiesTheUnavailableDownstream(String method, String endpoint, String service) {
        // given
        var request = Api.service("gateway-faults", 28080).header("X-Forwarded-For", "test-" + Data.digits(12))
                .body(Data.authorization(Data.absentPan()));
        // when
        var response = request.when().request(method, endpoint);
        // then
        response.then().statusCode(503).body("error", equalTo("SERVICE_UNAVAILABLE"))
                .body("serviceName", equalTo(service), "message", not(isEmptyOrNullString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorize", "rollback"})
    void authorizationReturnsDomain96WhenCmsIsUnavailable(String operation) {
        // given
        var body = operation.equals("authorize") ? Data.authorization(Data.absentPan())
                : Data.funds(Data.absentPan(), Data.digits(12), 100);
        // when / then
        Api.service("authorization-faults", 28083).body(body).when().post("/api/internal/" + operation)
                .then().statusCode(503).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("96"), "declineReason", equalTo("SERVICE_UNAVAILABLE"));
    }

    @Test
    void rateLimitRejectsBurstAndRecovers() {
        // given: dedicated instance has capacity 2, refill 1/s. Same client key within this test only.
        String client = "rate-" + Data.digits(12);
        var responses = new java.util.ArrayList<io.restassured.response.Response>();
        // when
        for (int i = 0; i < 6; i++) {
            responses.add(Api.service("gateway-faults", 28080).header("X-Forwarded-For", client)
                    .body("{}").post("/api/transactions"));
        }
        // then
        var limited = responses.stream().filter(response -> response.statusCode() == 429).toList();
        assertThat(limited.size(), greaterThanOrEqualTo(1));
        for (var response : limited) {
            response.then().header("Retry-After", equalTo("1"))
                    .body("error", equalTo("RATE_LIMIT_EXCEEDED"), "retryAfterMs", equalTo(1000))
                    .body("message", equalTo("Too many requests. Try again later."));
        }
        await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                Api.service("gateway-faults", 28080).header("X-Forwarded-For", client).body("{}")
                        .post("/api/transactions").then().statusCode(400)
                        .body("serviceName", equalTo("gateway"), "error", equalTo("VALIDATION_ERROR")));
    }
}
