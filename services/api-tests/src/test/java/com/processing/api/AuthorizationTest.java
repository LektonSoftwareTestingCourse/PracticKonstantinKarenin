package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/** Sources: tz/04, DeclineOutcome, AuthControllerImpl and AuthorizationResponse. */
class AuthorizationTest extends ApiTest {
    @Test
    void directApprovalHasFormattedGeneratedFields() {
        // given
        String pan = createCard().get("pan").toString();
        // when / then: authCode and RRN must be checked independently of HTTP status.
        Api.authorization().body(Data.authorization(pan)).when().post("/api/internal/authorize")
                .then().statusCode(200).body("status", equalTo("APPROVED"), "responseCode", equalTo("00"))
                .body("rrn", matchesPattern("[0-9]{12}"), "authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("declineReason", nullValue(), "processingTimeMs", greaterThanOrEqualTo(0));
        balance(pan, 9900);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void expiryMonthIncludesItsLastDay(int daysAfterExpiry) {
        // given: YearMonth is actually serialized as yyyy-MM (D-09).
        var card = createCard();
        String pan = card.get("pan").toString();
        var expiry = java.time.YearMonth.parse(card.get("expiryDate").toString());
        var request = Data.authorization(pan);
        request.put("transmissionDateTime", expiry.atEndOfMonth().plusDays(daysAfterExpiry)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString());
        // when
        var response = Api.authorization().body(request).when().post("/api/internal/authorize");
        // then
        response.then().statusCode(daysAfterExpiry == 0 ? 200 : 403)
                .body("responseCode", equalTo(daysAfterExpiry == 0 ? "00" : "54"))
                .body("declineReason", daysAfterExpiry == 0 ? nullValue() : equalTo("CARD_EXPIRED"));
        balance(pan, daysAfterExpiry == 0 ? 9900 : 10000);
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,05,CARD_BLOCKED,403", "INACTIVE,05,CARD_INACTIVE,403",
            "EXPIRED,54,CARD_EXPIRED,403"})
    void declinesByCardState(String state, String code, String reason, int httpStatus) {
        // given
        String pan = createCard().get("pan").toString();
        Api.cards().body(Map.of("status", state)).patch("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo(state));
        var request = Data.authorization(pan);
        // when
        var response = Api.authorization().body(request).when().post("/api/internal/authorize");
        // then
        response.then().statusCode(httpStatus).body("responseCode", equalTo(code), "declineReason", equalTo(reason))
                .body("status", equalTo("DECLINED"), "rrn", nullValue(), "authCode", nullValue());
        balance(pan, 10000);
    }

    @ParameterizedTest
    @CsvSource({"10001,10000,100000,51,INSUFFICIENT_FUNDS,422",
            "101,100,100000,61,EXCEEDS_AMOUNT_LIMIT,400", "101,10000,100,61,EXCEEDS_AMOUNT_LIMIT,400"})
    void distinguishesFundsDailyAndMonthlyLimit(int amount, int daily, int monthly,
                                               String code, String reason, int status) {
        // given
        var cardRequest = Data.card();
        cardRequest.put("dailyLimit", daily);
        cardRequest.put("monthlyLimit", monthly);
        String pan = createCard(cardRequest).get("pan").toString();
        var request = Data.authorization(pan);
        request.put("amount", amount);
        // when / then
        Api.authorization().body(request).when().post("/api/internal/authorize").then().statusCode(status)
                .body("responseCode", equalTo(code), "declineReason", equalTo(reason))
                .body("status", equalTo("DECLINED"), "authCode", nullValue());
        balance(pan, 10000);
    }

    @ParameterizedTest
    @CsvSource({"2025-06-15,400,61", "2025-06-16,400,61", "2025-07-01,200,00"})
    void accumulatedLimitsResetAtTheCorrectPeriodBoundary(String secondDay, int httpStatus, String code) {
        // given: one request exactly exhausts both daily and monthly allowance.
        var card = Data.card();
        card.put("dailyLimit", 100);
        card.put("monthlyLimit", 100);
        String pan = createCard(card).get("pan").toString();
        var first = Data.authorization(pan);
        first.put("transmissionDateTime", "2025-06-15T12:00:00Z");
        Api.authorization().body(first).post("/api/internal/authorize").then().statusCode(200)
                .body("status", equalTo("APPROVED"));
        var second = Data.authorization(pan);
        second.put("transmissionDateTime", secondDay + "T12:00:00Z");
        second.put("amount", 1);
        // when
        var response = Api.authorization().body(second).when().post("/api/internal/authorize");
        // then: same day exhausts daily, next day still exhausts monthly; next month resets both.
        response.then().statusCode(httpStatus).body("responseCode", equalTo(code))
                .body("declineReason", httpStatus == 200 ? nullValue() : equalTo("EXCEEDS_AMOUNT_LIMIT"));
        balance(pan, httpStatus == 200 ? 9899 : 9900);
    }

    @Test
    void declinesExpiryReachedThroughRequestDate() {
        // given: same outcome as status EXPIRED, with an ACTIVE card and a later request date.
        String pan = createCard().get("pan").toString();
        var request = Data.authorization(pan);
        request.put("transmissionDateTime", "2100-01-01T00:00:00Z");
        // when / then
        Api.authorization().body(request).when().post("/api/internal/authorize").then().statusCode(403)
                .body("responseCode", equalTo("54"), "declineReason", equalTo("CARD_EXPIRED"));
        balance(pan, 10000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999", "400000"})
    void declinesMissingCardViaUnknownAndKnownBin(String bin) {
        // given
        var request = Data.authorization(bin + Data.digits(10));
        // when / then
        Api.gateway().body(request).when().post("/api/transactions").then().statusCode(200)
                .body("stan", equalTo(request.get("stan")), "status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"), "declineReason", equalTo("CARD_NOT_FOUND"))
                .body("rrn", nullValue(), "authCode", nullValue());
    }

    @Test
    void directAuthorizationIdentifiesMissingCardWith404() {
        // given
        var request = Data.authorization(Data.absentPan());
        // when
        var response = Api.authorization().body(request).when().post("/api/internal/authorize");
        // then
        response.then().statusCode(404).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"), "declineReason", equalTo("CARD_NOT_FOUND"))
                .body("rrn", nullValue(), "authCode", nullValue());
    }

    @Test
    void approvesAtLimitAndReachesSearchThroughGateway() {
        // given: exact daily limit is allowed; data is unique to this test.
        String pan = createCard().get("pan").toString();
        var request = Data.authorization(pan);
        request.put("amount", 10000);
        // when
        var response = Api.gateway().body(request).when().post("/api/transactions");
        // then: D-01 approved mti is echoed as 0100, contrary to api-spec's 0110.
        response.then().statusCode(200).body(matchesJsonSchemaInClasspath("schemas/authorization.json"))
                .body("mti", equalTo("0100"), "stan", equalTo(request.get("stan")))
                .body("status", equalTo("APPROVED"), "responseCode", equalTo("00"))
                .body("rrn", matchesPattern("[0-9]{12}"), "authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("declineReason", nullValue(), "processingTimeMs", greaterThanOrEqualTo(0));
        String rrn = response.path("rrn");
        balance(pan, 0);
        await().alias("Gateway transaction becomes searchable").atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                    Api.gateway().queryParam("pan", pan).get("/api/transactions/search").then().statusCode(200)
                            .body("total", equalTo(1), "transactions", hasSize(1))
                            .body("transactions.findAll { it.rrn == '" + rrn + "' }.stan",
                                    contains(request.get("stan")))
                            .body("transactions[0].amount", equalTo(10000))
                            .body("transactions[0].status", equalTo("APPROVED")));
    }

    @Test
    void rollbackRestoresBalanceAndDoesNotCreateALogEntry() {
        // given: control transaction proves the logging pipeline is live.
        String control = createCard().get("pan").toString();
        Api.gateway().body(Data.authorization(control)).post("/api/transactions").then().statusCode(200)
                .body("status", equalTo("APPROVED"));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                Api.gateway().queryParam("pan", control).get("/api/transactions/search").then()
                        .statusCode(200).body("total", equalTo(1)));
        String pan = createCard().get("pan").toString();
        String rrn = Api.authorization().body(Data.authorization(pan)).post("/api/internal/authorize")
                .then().statusCode(200).body("status", equalTo("APPROVED")).extract().path("rrn");
        balance(pan, 9900);
        // when
        var response = Api.authorization().body(Data.funds(pan, rrn, 100)).when().post("/api/internal/rollback");
        // then: absence is observed continuously, not just once before an async write.
        response.then().statusCode(200).body("status", equalTo("APPROVED"), "responseCode", equalTo("00"))
                .body("rrn", equalTo(rrn), "declineReason", nullValue());
        balance(pan, 10000);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    Api.gateway().queryParam("pan", pan).get("/api/transactions/search").then().statusCode(200)
                            .body("total", equalTo(0), "transactions", hasSize(0)));
        Api.authorization().body(Data.funds(pan, rrn, 100)).post("/api/internal/rollback").then().statusCode(409)
                .body("responseCode", equalTo("05"), "declineReason", equalTo("ALREADY_ROLLED_BACK"));
        balance(pan, 10000);
    }

    @Test
    void rollbackOfMissingReservationIsDomain404() {
        // given
        String pan = createCard().get("pan").toString();
        // when / then
        Api.authorization().body(Data.funds(pan, Data.digits(12), 100)).when().post("/api/internal/rollback")
                .then().statusCode(404).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"), "declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }
}
