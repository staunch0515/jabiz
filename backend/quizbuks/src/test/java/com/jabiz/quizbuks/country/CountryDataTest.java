package com.jabiz.quizbuks.country;

import com.jabiz.entity.FieldRule;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CountryDataTest {

    private static final CountryCatalog CATALOG = new CountryCatalog();
    private static final Map<String, CountryData.Country> BY_CODE = CATALOG.countries().stream()
        .collect(Collectors.toMap(CountryData.Country::code, c -> c));
    private static final String HEADER = "code|en|zh|ja|regions";

    @Test
    void theShippedFileHasNoProblemAndEveryIso3166Country() {
        assertThat(CATALOG.check()).isEmpty();
        assertThat(CATALOG.countries()).extracting(CountryData.Country::code)
            .containsExactlyElementsOf(Arrays.stream(Locale.getISOCountries()).sorted().toList())
            .hasSize(249);
    }

    @Test
    void namesEveryCountryInTheThreeLanguages() {
        assertThat(CATALOG.countries()).allSatisfy(country -> assertThat(country.names())
            .containsOnlyKeys("en", "zh", "ja")
            .allSatisfy((language, name) -> assertThat(name).isNotBlank()));
        assertThat(BY_CODE.get("JP").names()).containsEntry("en", "Japan").containsEntry("zh", "日本")
            .containsEntry("ja", "日本");
        assertThat(BY_CODE.get("SH").names().get("en")).isEqualTo("Saint Helena, Ascension and Tristan da Cunha");
    }

    @Test
    void everyCountryIsWrittenInTheCanonicalForm() {
        assertThat(CATALOG.countries()).allSatisfy(country ->
            assertThat(country.regions()).matches(Regions.CANONICAL));
    }

    @Test
    void theSingleCountryRegionsHoldOnlyTheirCountry() {
        assertThat(BY_CODE.get("JP").regions()).isEqualTo("GLOBAL,JP,ASIA");
        assertThat(BY_CODE.get("CN").regions()).isEqualTo("GLOBAL,CN,ASIA");
        assertThat(BY_CODE.get("US").regions()).isEqualTo("GLOBAL,US,NORTH_AMERICA");
        for (String region : List.of(Regions.JP, Regions.CN, Regions.US)) {
            assertThat(CATALOG.countries()).filteredOn(c -> Regions.parse(c.regions()).contains(region))
                .extracting(CountryData.Country::code).containsExactly(region);
        }
    }

    @Test
    void continentsFollowTheJudgementCallsOfThePlan() {
        assertThat(BY_CODE.get("DE").regions()).isEqualTo("GLOBAL,EUROPE");
        assertThat(BY_CODE.get("RU").regions()).isEqualTo("GLOBAL,EUROPE,ASIA");
        assertThat(BY_CODE.get("TR").regions()).isEqualTo("GLOBAL,EUROPE,ASIA");
        assertThat(BY_CODE.get("CY").regions()).isEqualTo("GLOBAL,EUROPE,ASIA");
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
    void trimsCellsAndRegionsAndSkipsCommentsAndBlankLines() {
        CountryData.Result result = CountryData.parse(List.of("# a comment", "", " code | en | zh | ja | regions ",
            " JP | Japan | 日本 | 日本 |  ASIA ,  JP ", "KR|South Korea|韩国|韓国|"));
        assertThat(result.problems()).isEmpty();
        assertThat(result.countries()).extracting(CountryData.Country::regions)
            .containsExactly("GLOBAL,JP,ASIA", "GLOBAL");
    }

    @Test
    void namesMayHoldCommasButNoQuotes() {
        CountryData.Result result = CountryData.parse(List.of(HEADER,
            "SH|Saint Helena, Ascension and Tristan da Cunha|圣赫勒拿|セントヘレナ|",
            "XX|\"Quoted\"|x|x|"));
        assertThat(result.countries()).extracting(CountryData.Country::code).containsExactly("SH");
        assertThat(result.problems()).containsExactly(new CountryData.Problem(3,
            "the name in en holds a quote: the format has no quoting"));
    }

    @Test
    void reportsEveryProblemOfEveryLineAtOnce() {
        CountryData.Result result = CountryData.parse(List.of(HEADER,
            "jp|Japan|日本|日本|JP",
            "JP|Japan||日本|JP,JP",
            "JP|Japan|日本|日本|JP",
            "JP|Japan|日本|日本|ASIA",
            "CN|China|中国|中国|MARS,",
            "US|United States|美国|アメリカ|GLOBAL,US",
            "FR|France|法国",
            "KR|South Korea|韩国|韓国|ASIA"));
        assertThat(result.problems()).containsExactly(
            new CountryData.Problem(2, "'jp' is not a country code"),
            new CountryData.Problem(3, "no name in zh"),
            new CountryData.Problem(3, "a region is listed twice in 'JP,JP'"),
            new CountryData.Problem(4, "JP appears twice"),
            new CountryData.Problem(5, "JP appears twice"),
            new CountryData.Problem(6, "empty region in 'MARS,'"),
            new CountryData.Problem(6, "not regions: [MARS]"),
            new CountryData.Problem(7, "GLOBAL is implied and not listed"),
            new CountryData.Problem(8, "5 cells separated by '|' expected, found 3"));
        assertThat(result.countries()).extracting(CountryData.Country::code).containsExactly("KR");
    }

    @Test
    void needsTheHeader() {
        assertThat(CountryData.parse(List.of("code,en,zh,ja,regions")).problems())
            .containsExactly(new CountryData.Problem(1, "the header must be " + HEADER));
        assertThat(CountryData.parse(List.of("# nothing")).problems()).singleElement()
            .satisfies(problem -> assertThat(problem.message()).startsWith("the file is empty"));
    }

    @Test
    void startupReportsTheProblemsOfABadFileUnderItsOwnCategory() {
        List<CheckProblem> problems = new CountryCatalog("quizbuks-test/bad-countries.txt").check();
        assertThat(problems).extracting(CheckProblem::category).containsOnly(CountryCatalog.CATEGORY);
        assertThat(problems).extracting(CheckProblem::location)
            .containsExactly("quizbuks-test/bad-countries.txt:3", "quizbuks-test/bad-countries.txt:4");
        assertThat(new CountryCatalog("quizbuks-test/missing.txt").check()).singleElement()
            .satisfies(problem -> assertThat(problem.message()).isEqualTo("the file is missing"));
    }

    @Test
    void theRegionsRuleAcceptsOnlyTheCanonicalForm() {
        FieldRule rule = QbCountries.COUNTRY_ENTITY.fields.get("regions").rules().stream()
            .filter(r -> r.code().equals(QbCountries.REGIONS_FORMAT)).findFirst().orElseThrow();
        for (String valid : List.of("GLOBAL", "GLOBAL,JP,ASIA", "GLOBAL,EUROPE,ASIA",
            String.join(",", Regions.ALL))) {
            assertThat(rule.isSatisfiedBy(valid, null)).as(valid).isTrue();
        }
        for (String invalid : List.of("", "ASIA", "JP,JP", "ASIA,GLOBAL", "GLOBAL,JP,JP", "GLOBAL,ASIA,JP",
            "GLOBAL,MARS", "GLOBAL,", "GLOBAL, JP", "GLOBAL,GLOBAL")) {
            assertThat(rule.isSatisfiedBy(invalid, null)).as(invalid).isFalse();
        }
    }

    @Test
    void writesRegionSetsInTheDictionaryOrder() {
        assertThat(Regions.withGlobal(List.of(Regions.ASIA, Regions.JP))).isEqualTo("GLOBAL,JP,ASIA");
        assertThat(Regions.withGlobal(List.of())).isEqualTo("GLOBAL");
        assertThat(Regions.parse(null)).isEmpty();
        assertThat(Regions.MAX_LENGTH).isEqualTo(String.join(",", Regions.ALL).length());
    }
}
