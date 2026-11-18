package com.processing.api;

import com.processing.api.support.Api;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** Source: DashboardController and ChartsFilter; global counters are intentionally not fixed. */
class DashboardTest {
    @Test
    void statsAreInternallyConsistent() {
        // given / when
        var response = Api.gateway().when().get("/api/dashboard/stats");
        // then
        response.then().statusCode(200).body("totalTransactions", greaterThanOrEqualTo(0))
                .body("approvedCount", greaterThanOrEqualTo(0), "declinedCount", greaterThanOrEqualTo(0))
                .body("approvalRate", allOf(greaterThanOrEqualTo(0f), lessThanOrEqualTo(1f)))
                .body("totalAmount", notNullValue(), "averageAmount", notNullValue());
        int approved = response.path("approvedCount");
        int declined = response.path("declinedCount");
        assertThat(response.<Integer>path("totalTransactions"), equalTo(approved + declined));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 500})
    void recentHonorsLimit(int limit) {
        // given / when
        var response = Api.gateway().queryParam("limit", limit).when().get("/api/dashboard/recent");
        // then
        response.then().statusCode(200).body("size()", lessThanOrEqualTo(limit));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 501})
    void recentRejectsOutOfRangeLimit(int limit) {
        // given / when
        var response = Api.gateway().queryParam("limit", limit).when().get("/api/dashboard/recent");
        // then
        response.then().statusCode(400).body("message", containsString("limit"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void chartsAcceptBothGranularitiesAndAnEmptyWindow(String granularity) {
        // given / when
        var response = Api.gateway().queryParam("granularity", granularity)
                .queryParam("from", "2000-01-01T00:00:00Z").queryParam("to", "2000-01-01T23:59:59Z")
                .when().get("/api/dashboard/charts");
        // then
        response.then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void chartsRejectUnknownGranularity() {
        // given / when
        var response = Api.gateway().queryParam("granularity", "minute").when().get("/api/dashboard/charts");
        // then
        response.then().statusCode(400).body("message", containsString("granularity"))
                .body("serviceName", equalTo("transaction-logger"));
    }
}
