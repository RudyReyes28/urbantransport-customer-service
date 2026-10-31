package com.urbantransport.customer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class NitUtilsTest {

    @Test
    @DisplayName("normalize: mayúsculas, sin espacios ni guiones")
    void normalize() {
        assertThat(NitUtils.normalize("1234567-8")).isEqualTo("12345678");
        assertThat(NitUtils.normalize(" 1234 567\t-8 ")).isEqualTo("12345678");
        assertThat(NitUtils.normalize("12345-6k")).isEqualTo("123456K");
        assertThat(NitUtils.normalize("cf")).isEqualTo("CF");
        assertThat(NitUtils.normalize("")).isEmpty();
        assertThat(NitUtils.normalize(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12", "12345678", "1234567K", "1K"})
    @DisplayName("isValid: dígitos y, a lo sumo, una K como último carácter")
    void validNits(String nit) {
        assertThat(NitUtils.isValid(nit)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1", "K", "CF", "12K3", "12 34", "12-34", "1234567k", "KK"})
    @DisplayName("isValid rechaza vacíos, una sola cifra, letras que no sean la K final y minúsculas sin normalizar")
    void invalidNits(String nit) {
        assertThat(NitUtils.isValid(nit)).isFalse();
    }
}