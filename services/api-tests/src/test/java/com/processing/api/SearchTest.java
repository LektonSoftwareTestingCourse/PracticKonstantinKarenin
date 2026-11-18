package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.*;

/** Sources: TransactionFilter, TransactionSpecification, OffsetBasedPageRequest. */
class SearchTest extends ApiTest {
    @ParameterizedTest
    @ValueSource(strings = {"pan", "status", "dateFrom", "dateTo", "merchantId", "issuerId", "mcc", "declineReason"})
    void eachFilterSelectsTheExpectedRecord(String field) {
        // given: a unique PAN always scopes the query, a second row is excluded by the tested field.
        var first = Data.transaction();
        first.put("status", "DECLINED");
        first.put("declineReason", "CARD_BLOCKED");
        first.put("transmissionDateTime", "2025-06-15T12:00:00Z");
        stored(first);
        var other = Data.transaction();
        other.put("pan", first.get("pan"));
        other.put("issuerId", "ISS002");
        other.put("mcc", "5812");
        other.put("transmissionDateTime", field.equals("dateFrom") ? "2025-06-14T23:59:59Z" : "2025-06-16T00:00:00Z");
        if (field.equals("pan")) {
            other.put("pan", Data.absentPan());
        }
        stored(other);
        Map<String, Object> query = new LinkedHashMap<>(Map.of("pan", first.get("pan")));
        query.put(field, switch (field) {
            case "dateFrom", "dateTo" -> "2025-06-15";
            case "declineReason" -> "blocked";
            default -> first.get(field);
        });
        // when / then
        Api.gateway().queryParams(query).when().get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(1), "transactions", hasSize(1))
                .body("transactions[0].id", equalTo(first.get("id")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("pairwiseRows")
    void pairwiseFilterCombinations(int row) {
        // given: binary orthogonal array OA(16, 10, 2, 2); every pair has all four combinations.
        var request = Data.transaction();
        request.put("status", "DECLINED");
        request.put("declineReason", "CARD_BLOCKED");
        stored(request);
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String[] fields = {"pan", "status", "dateFrom", "dateTo", "merchantId", "issuerId", "mcc",
                "declineReason", "limit", "offset"};
        Object[] values = {request.get("pan"), "DECLINED", today, today, request.get("merchantId"),
                "ISS001", "5411", "blocked", 1, 0};
        Map<String, Object> query = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i++) {
            if (Integer.bitCount(row & (i + 1)) % 2 == 1) {
                query.put(fields[i], values[i]);
            }
        }
        // when / then
        Api.gateway().queryParams(query).when().get("/api/transactions/search").then().statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("transactions.findAll { it.id == '" + request.get("id") + "' }", hasSize(1));
    }

    static IntStream pairwiseRows() {
        return IntStream.range(0, 16);
    }

    @ParameterizedTest
    @CsvSource({"1,0,1", "1,1,1", "1,2,1", "1,3,0", "500,0,3"})
    void paginationReturnsExactSliceAndPreservesTotal(int limit, int offset, int size) {
        // given
        String pan = Data.absentPan();
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var request = Data.transaction();
            request.put("pan", pan);
            request.put("createdAt", "2025-01-0" + (i + 1) + "T00:00:00Z");
            stored(request);
            ids.add(request.get("id").toString());
        }
        // when / then
        var response = Api.gateway().queryParam("pan", pan).queryParam("limit", limit).queryParam("offset", offset)
                .when().get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(3), "transactions", hasSize(size));
        if (size > 0) {
            response.body("transactions[0].id", equalTo(ids.get(2 - offset)));
        }
    }

    @Test
    void dateFiltersIncludeBothUtcDayBoundaries() {
        // given
        String pan = Data.absentPan();
        String[] times = {"2025-06-14T23:59:59.999Z", "2025-06-15T00:00:00Z",
                "2025-06-15T23:59:59.999Z", "2025-06-16T00:00:00Z"};
        var expected = new java.util.ArrayList<String>();
        for (int i = 0; i < times.length; i++) {
            var request = Data.transaction();
            request.put("pan", pan);
            request.put("transmissionDateTime", times[i]);
            stored(request);
            if (i == 1 || i == 2) {
                expected.add(request.get("id").toString());
            }
        }
        // when / then
        Api.gateway().queryParam("pan", pan).queryParam("dateFrom", "2025-06-15").queryParam("dateTo", "2025-06-15")
                .when().get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(2), "transactions.id", containsInAnyOrder(expected.toArray()));
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,-1", "limit,501", "offset,-1", "status,UNKNOWN",
            "dateFrom,not-a-date", "dateTo,not-a-date"})
    void invalidSearchParametersNameTheField(String field, String value) {
        // given / when
        var response = Api.gateway().queryParam(field, value).when().get("/api/transactions/search");
        // then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString(field), "error", notNullValue());
    }

    @Test
    void reversedDateRangeIsRejected() {
        // given / when
        var response = Api.gateway().queryParam("dateFrom", "2025-02-02").queryParam("dateTo", "2025-02-01")
                .when().get("/api/transactions/search");
        // then: D-06 the class-level validation error loses its message.
        response.then().statusCode(400).body("error", equalTo("Validation error"))
                .body("message", equalTo(""), "serviceName", equalTo("transaction-logger"));
    }

    @Test
    void emptySelectionAndCsvExport() {
        // given
        var request = Data.transaction();
        stored(request);
        // when / then
        Api.gateway().queryParam("pan", Data.absentPan()).when().get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(0), "transactions", hasSize(0));
        Api.gateway().accept("text/csv").queryParam("pan", request.get("pan"))
                .when().get("/api/transactions/export").then().statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("transactions.csv"))
                .body(containsString(request.get("id").toString()), containsString(request.get("pan").toString()));
    }

    @Test
    void exportReusesSearchValidation() {
        // given
        var request = Api.gateway().queryParam("limit", 0);
        // when
        var response = request.when().get("/api/transactions/export");
        // then
        response.then().statusCode(400).body("message", containsString("limit"))
                .body("serviceName", equalTo("transaction-logger"));
    }
}
