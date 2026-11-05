package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingServiceTest {
    private final RoutingService routing = routing(Map.of("400000", "ISSUER_A", "400001", "ISSUER_B"));

    @ParameterizedTest
    @CsvSource({"400000,ISSUER_A", "4000001234567899,ISSUER_A", "4000019999999999,ISSUER_B"})
    void usesExactlyTheFirstSixCharacters(String pan, String expectedIssuer) {
        assertThat(routing.getIssuerIdByPan(pan)).isEqualTo(expectedIssuer);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "40000", "499999", "4999991234567890"})
    void rejectsMissingShortAndUnknownPan(String pan) {
        assertThatThrownBy(() -> routing.getIssuerIdByPan(pan)).isInstanceOf(UnknownBinException.class);
    }

    @Test
    void configurationIsCopiedWhenServiceIsCreated() {
        var source = new HashMap<>(Map.of("400000", "ISSUER_A"));
        var service = routing(source);
        source.put("400000", "CHANGED");
        source.put("400001", "NEW");
        assertThat(service.getIssuerIdByPan("4000001234567899")).isEqualTo("ISSUER_A");
        assertThatThrownBy(() -> service.getIssuerIdByPan("4000011234567890"))
                .isInstanceOf(UnknownBinException.class);
    }

    private static RoutingService routing(Map<String, String> bins) {
        return new RoutingService(new SwitchProperties("test", bins, null, null, null, null, null, null));
    }
}
