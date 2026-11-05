package com.processing.cardmanagement.services;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {
    private final LuhnValidator validator = new LuhnValidator();

    @ParameterizedTest
    @CsvSource({"4000001234567899,true", "4000001234567890,false", "4000001234567898,false",
            "4000011234567898,true", "4000021234567897,true"})
    void checksKnownSyntheticPanVectors(String pan, boolean expected) {
        assertThat(validator.isValid(pan)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"400000", "400001", "400002", "400003", "400004"})
    void generatedPanHasRequestedBinAndOneValidCheckDigit(String bin) {
        String generated = validator.generatePan(bin);
        assertThat(generated).startsWith(bin).matches("\\d{16}");
        assertThat(validator.isValid(generated)).isTrue();
        String prefix = generated.substring(0, 15);
        for (int checkDigit = 0; checkDigit < 10; checkDigit++) {
            String candidate = prefix + checkDigit;
            assertThat(validator.isValid(candidate)).as("check digit %s", checkDigit)
                    .isEqualTo(candidate.equals(generated));
        }
    }
}
