package com.jabiz.quizbuks.country;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CountryDataTest {

    private static final Map<String, CountryData.Country> BY_CODE = CountryData.all().stream()
        .collect(Collectors.toMap(CountryData.Country::code, c -> c));

    @Test
    void holdsEveryIso3166Country() {
        assertThat(CountryData.all()).extracting(CountryData.Country::code)
            .containsExactlyElementsOf(Arrays.stream(Locale.getISOCountries()).sorted().toList())
            .hasSize(249);
    }

    @Test
    void namesEveryCountryInTheThreeLanguages() {
        assertThat(CountryData.all()).allSatisfy(country -> assertThat(country.names())
            .containsOnlyKeys("en", "zh", "ja")
            .allSatisfy((language, name) -> assertThat(name).isNotBlank()));
        assertThat(BY_CODE.get("JP").names()).containsEntry("en", "Japan").containsEntry("zh", "日本")
            .containsEntry("ja", "日本");
        assertThat(BY_CODE.get("SH").names().get("en")).isEqualTo("Saint Helena, Ascension and Tristan da Cunha");
    }

    @Test
    void everyCountryIsGlobalAndItsRegionsAreWrittenInOneOrder() {
        assertThat(CountryData.all()).allSatisfy(country -> {
            assertThat(country.regions()).matches(Regions.SET).startsWith(Regions.GLOBAL);
            assertThat(country.regions()).isEqualTo(Regions.withGlobal(Regions.parse(country.regions())));
        });
    }

    @Test
    void theSingleCountryRegionsHoldOnlyTheirCountry() {
        assertThat(BY_CODE.get("JP").regions()).isEqualTo("GLOBAL,JP,ASIA");
        assertThat(BY_CODE.get("CN").regions()).isEqualTo("GLOBAL,CN,ASIA");
        assertThat(BY_CODE.get("US").regions()).isEqualTo("GLOBAL,US,NORTH_AMERICA");
        for (String region : List.of(Regions.JP, Regions.CN, Regions.US)) {
            assertThat(CountryData.all()).filteredOn(c -> Regions.parse(c.regions()).contains(region))
                .extracting(CountryData.Country::code).containsExactly(region);
        }
    }

    @Test
    void continentsFollowTheJudgementCallsOfThePlan() {
        assertThat(BY_CODE.get("DE").regions()).isEqualTo("GLOBAL,EUROPE");
        assertThat(BY_CODE.get("RU").regions()).isEqualTo("GLOBAL,EUROPE,ASIA");
        assertThat(BY_CODE.get("TR").regions()).isEqualTo("GLOBAL,EUROPE,ASIA");
        assertThat(BY_CODE.get("MX").regions()).isEqualTo("GLOBAL,NORTH_AMERICA");
        assertThat(BY_CODE.get("PA").regions()).isEqualTo("GLOBAL,NORTH_AMERICA");
        assertThat(BY_CODE.get("BR").regions()).isEqualTo("GLOBAL,SOUTH_AMERICA");
        assertThat(BY_CODE.get("HK").regions()).isEqualTo("GLOBAL,ASIA");
        // Africa, Oceania and Antarctica have no region of their own: GLOBAL only.
        assertThat(BY_CODE.get("ZA").regions()).isEqualTo("GLOBAL");
        assertThat(BY_CODE.get("AU").regions()).isEqualTo("GLOBAL");
        assertThat(BY_CODE.get("AQ").regions()).isEqualTo("GLOBAL");
    }

    @Test
    void refusesMalformedFiles() {
        assertThatThrownBy(() -> CountryData.parse(List.of("code,name"))).hasMessageContaining("header");
        String header = "code,en,zh,ja,regions";
        assertThatThrownBy(() -> CountryData.parse(List.of(header, "jp,Japan,日本,日本,JP")))
            .hasMessageContaining("Line 2").hasMessageContaining("not a country code");
        assertThatThrownBy(() -> CountryData.parse(List.of(header, "JP,Japan,日本,日本,JP", "JP,Japan,日本,日本,JP")))
            .hasMessageContaining("Line 3").hasMessageContaining("twice");
        assertThatThrownBy(() -> CountryData.parse(List.of(header, "JP,Japan,,日本,JP")))
            .hasMessageContaining("no name in zh");
        assertThatThrownBy(() -> CountryData.parse(List.of(header, "JP,Japan,日本,日本,MARS")))
            .hasMessageContaining("Not regions: [MARS]");
        assertThatThrownBy(() -> CountryData.parse(List.of(header, "JP,Japan,日本")))
            .hasMessageContaining("5 cells");
    }

    @Test
    void readsQuotedCells() {
        assertThat(CountryData.cells("XX,\"A, \"\"B\"\"\",c,d,")).containsExactly("XX", "A, \"B\"", "c", "d", "");
        assertThatThrownBy(() -> CountryData.cells("\"open")).hasMessageContaining("Unclosed");
    }

    @Test
    void writesRegionSetsInTheDictionaryOrder() {
        assertThat(Regions.withGlobal(List.of(Regions.ASIA, Regions.JP))).isEqualTo("GLOBAL,JP,ASIA");
        assertThat(Regions.withGlobal(List.of())).isEqualTo("GLOBAL");
        assertThat(Regions.parse(null)).isEmpty();
        assertThat(Regions.MAX_LENGTH).isEqualTo(String.join(",", Regions.ALL).length());
    }
}
