package com.processing.api.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Synthetic data only. Millisecond precision survives PostgreSQL round trips. */
public final class Data {
    private Data() {}

    public static String digits(int length) {
        var value = new StringBuilder();
        for (int i = 0; i < length; i++) {
            value.append(ThreadLocalRandom.current().nextInt(10));
        }
        return value.toString();
    }

    public static String absentPan() {
        return "999999" + digits(10);
    }

    public static String maskedPan(String pan) {
        return pan.substring(0, 4) + "********" + pan.substring(12);
    }

    public static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    public static Map<String, Object> card() {
        return new LinkedHashMap<>(Map.of("bin", "400000", "cardholderName", "API TEST",
                "currencyCode", "643", "dailyLimit", 10000, "monthlyLimit", 100000, "initialBalance", 10000));
    }

    public static Map<String, Object> authorization(String pan) {
        var request = new LinkedHashMap<String, Object>();
        request.put("mti", "0100");
        request.put("stan", digits(6));
        request.put("pan", pan);
        request.put("processingCode", "000000");
        request.put("amount", 100);
        request.put("currencyCode", "643");
        request.put("transmissionDateTime", now());
        request.put("terminalId", digits(8));
        request.put("terminalType", "POS");
        request.put("merchantId", digits(15));
        request.put("mcc", "5411");
        request.put("acquirerId", "ACQ001");
        request.put("issuerId", "ISS001");
        return request;
    }

    public static Map<String, Object> transaction() {
        var request = authorization(absentPan());
        request.put("id", UUID.randomUUID().toString());
        request.put("rrn", digits(12));
        request.put("status", "APPROVED");
        request.put("acquiringFee", 1);
        request.put("authCode", "API001");
        request.put("processingTimeMs", 1);
        request.put("createdAt", now());
        return request;
    }

    public static Map<String, Object> funds(String pan, String rrn, int amount) {
        return Map.of("pan", pan, "rrn", rrn, "amount", amount);
    }
}
