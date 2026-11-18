package com.processing.api;

import com.processing.api.support.Api;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.Matchers.*;

/** Sources: Gateway route predicates, health controllers, BinLookupController. */
class MetadataTest {
    @ParameterizedTest
    @CsvSource({"gateway,8080", "cards,8081", "switch,8082", "authorization,8083",
            "merchant,8084", "terminal,8085", "logger,8088"})
    void servicesAreHealthy(String service, int port) {
        // given / when
        var response = Api.service(service, port).when().get("/health");
        // then
        response.then().statusCode(200).body("status", equalTo("ok"), "service", not(isEmptyOrNullString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"switch-docs", "logger-docs", "terminal-docs", "merchant-docs", "cards-docs"})
    void documentationRoutesReturnOpenApi(String route) {
        // given / when
        var response = Api.gateway().when().get("/" + route);
        // then
        response.then().statusCode(200).body("openapi", startsWith("3."), "paths", notNullValue());
    }

    @ParameterizedTest
    @CsvSource({"400000,ISS001", "400004,ISS005"})
    void binLookupReturnsExpectedIssuer(String bin, String issuer) {
        // given / when
        var response = Api.service("bin", 8096).when().get("/api/bin/{bin}", bin);
        // then
        response.then().statusCode(200).body("bin", equalTo(bin), "issuerId", equalTo(issuer), "issuerName", notNullValue());
    }

    @Test
    void binLookupUnknownAndForcedFailureHaveEmptyBodies() {
        // given / when / then: these controllers explicitly return empty 404/500 responses.
        Api.service("bin", 8096).get("/api/bin/999999").then().statusCode(404).body(emptyString());
        Api.service("bin", 8096).queryParam("fail", true).get("/api/bin/400000").then()
                .statusCode(500).body(emptyString());
    }

    @Test
    void merchantListIsInternalBecauseGatewayPredicateDoesNotMatchPluralPath() {
        // given / when / then: D-08 /api/simulator/merchants is outside /api/simulator/merchant/**.
        Api.gateway().get("/api/simulator/merchants").then().statusCode(404)
                .body("path", equalTo("/api/simulator/merchants"), "status", equalTo(404));
        Api.service("merchant", 8084).get("/api/simulator/merchants").then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1), "[0].id", notNullValue());
    }
}
