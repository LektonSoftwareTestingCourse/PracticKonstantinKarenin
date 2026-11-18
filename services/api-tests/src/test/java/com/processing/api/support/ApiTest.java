package com.processing.api.support;

import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;

public abstract class ApiTest {
    private final List<String> pans = new ArrayList<>();
    private final List<String> bins = new ArrayList<>();

    protected String createBin() {
        String bin = "7" + Data.digits(5);
        String issuer = "T" + Data.digits(8);
        Api.cards().body(Map.of("bin", bin, "issuerId", issuer)).post("/api/bins").then()
                .statusCode(201).body("bin", equalTo(bin), "issuerId", equalTo(issuer));
        bins.add(bin);
        return bin;
    }

    protected Map<String, Object> createCard() {
        return createCard(Data.card());
    }

    protected Map<String, Object> createCard(Map<String, Object> request) {
        Map<String, Object> card = Api.gateway().body(request).when().post("/api/cards")
                .then().statusCode(201).body("pan", matchesPattern("[0-9]{16}"))
                .body("status", equalTo("ACTIVE")).extract().jsonPath().getMap("$");
        pans.add(card.get("pan").toString());
        return card;
    }

    protected void balance(String pan, int expected) {
        // Direct CMS read avoids a potentially stale Gateway response cache (D-03).
        Api.cards().when().get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("availableBalance", equalTo(expected));
    }

    protected void stored(Map<String, Object> request) {
        Api.logger().body(request).when().post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(request.get("id"))).body("status", equalTo("stored"));
    }

    @AfterEach
    void retireOwnCards() {
        for (String pan : pans) {
            var response = Api.cards().when().delete("/api/cards/{pan}", pan);
            if (response.statusCode() == 404) {
                response.then().body("error", equalTo("CardNotFoundException"))
                        .body("message", containsString(Data.maskedPan(pan)));
            } else {
                response.then().statusCode(204).body(emptyString());
            }
        }
        for (String bin : bins) {
            Api.cards().delete("/api/bins/{bin}", bin).then().statusCode(204).body(emptyString());
        }
    }
}
