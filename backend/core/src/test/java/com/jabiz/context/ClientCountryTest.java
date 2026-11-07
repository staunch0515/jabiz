package com.jabiz.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientCountryTest {

    @Test
    void mainlandChinaIsCnOnly() {
        assertThat(ClientCountry.of("cn").mainlandChina()).isTrue();
        assertThat(ClientCountry.of("CN").code()).isEqualTo("CN");
        for (String other : new String[] {"HK", "MO", "TW", "JP"}) {
            assertThat(ClientCountry.of(other).mainlandChina()).isFalse();
        }
        assertThat(ClientCountry.of("JP").is("jp")).isTrue();
    }

    @Test
    void unknownForNothingBlankOrThePlaceholder() {
        for (String nothing : new String[] {null, "", " ", "ZZ", "zz"}) {
            ClientCountry country = ClientCountry.of(nothing);
            assertThat(country).isEqualTo(ClientCountry.UNKNOWN);
            assertThat(country.known()).isFalse();
            assertThat(country.mainlandChina()).isFalse();
            assertThat(country.is("CN")).isFalse();
        }
    }

    @Test
    void refusesWhatIsNoCode() {
        assertThatThrownBy(() -> ClientCountry.of("CHN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientCountry.of("C1")).isInstanceOf(IllegalArgumentException.class);
    }
}
