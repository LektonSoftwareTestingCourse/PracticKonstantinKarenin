package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;

/** Source: CardController, GenerateCardsRequest, application.properties (maximum 10000). */
class CardCollectionTest extends ApiTest {
    @Test
    void filtersByBinIssuerStatusAndCreationDates() {
        // given: own BIN makes size assertions independent of the shared database.
        String bin = createBin();
        String from = Data.now();
        var request = Data.card();
        request.put("bin", bin);
        var card = createCard(request);
        String pan = card.get("pan").toString();
        // when / then
        Api.gateway().queryParam("bin", bin).queryParam("issuerId", card.get("issuerId"))
                .queryParam("status", "ACTIVE").queryParam("startDate", from).queryParam("endDate", Data.now())
                .when().get("/api/cards").then().statusCode(200)
                .body("total", equalTo(1), "cards", hasSize(1), "cards[0].pan", equalTo(pan));
        Api.gateway().queryParam("bin", bin).queryParam("status", "BLOCKED").get("/api/cards").then()
                .statusCode(200).body("total", equalTo(0), "cards", hasSize(0));
    }

    @ParameterizedTest
    @CsvSource({"1,0,1", "1,1,1", "1,2,0", "10000,0,2"})
    void paginatesWithinOwnBin(int limit, int offset, int count) {
        // given
        String bin = createBin();
        var request = Data.card();
        request.put("bin", bin);
        createCard(request);
        createCard(request);
        // when / then
        Api.gateway().queryParam("bin", bin).queryParam("limit", limit).queryParam("offset", offset)
                .when().get("/api/cards").then().statusCode(200)
                .body("total", equalTo(2), "cards", hasSize(count));
    }

    @ParameterizedTest
    @CsvSource({"limit,0,400", "limit,-1,400", "limit,10001,413", "offset,-1,400",
            "bin,123,400", "issuerId,lowercase,400"})
    void rejectsInvalidCollectionParameters(String field, String value, int status) {
        // given / when / then
        Api.gateway().queryParam(field, value).when().get("/api/cards").then().statusCode(status)
                .body("error", not(isEmptyOrNullString()), "message", not(isEmptyOrNullString()))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    void generatesRequestedNumberOfCardsWithKnownBins() {
        // given / when
        var response = Api.gateway().body(Map.of("count", 2, "bins", List.of("400000")))
                .when().post("/api/cards/generate");
        // then
        response.then().statusCode(201).body("generated", equalTo(2), "cards", hasSize(2))
                .body("cards.bin", everyItem(equalTo("400000")))
                .body("cards.pan", everyItem(matchesPattern("400000[0-9]{10}")));
        for (String pan : response.jsonPath().getList("cards.pan", String.class)) {
            Api.cards().delete("/api/cards/{pan}", pan).then().statusCode(204).body(emptyString());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 10001})
    void rejectsGenerationCountOutsideBounds(int count) {
        // given / when / then
        Api.gateway().body(Map.of("count", count, "bins", List.of("400000")))
                .when().post("/api/cards/generate").then().statusCode(400)
                .body("error", notNullValue(), "message", not(isEmptyOrNullString()));
    }

    @Test
    void generationRejectsEmptyAndUnknownBins() {
        // given / when / then
        Api.gateway().body(Map.of("count", 1, "bins", List.of())).when().post("/api/cards/generate").then()
                .statusCode(400).body("error", equalTo("MethodArgumentNotValidException"), "message", notNullValue());
        Api.gateway().body(Map.of("count", 1, "bins", List.of("999999"))).when().post("/api/cards/generate").then()
                .statusCode(404).body("error", equalTo("BinNotFoundException"), "message", containsString("999999"));
    }
}
