package com.jabiz.quizbuks.sponsorpackage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link PackageJson} writes and counts JSON as the page's JavaScript does (the request bytes the plan cuts by). */
class PackageJsonTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1.0|1", "2.5|2.5", "0.1|0.1", "1e21|1e+21", "1e20|100000000000000000000", "1.5e-7|1.5e-7",
        "0.000001|0.000001", "123456789.125|123456789.125", "-0.0|0", "-2.75|-2.75", "1.7976931348623157e308|"
            + "1.7976931348623157e+308", "5e-324|5e-324", "0.1e-6|1e-7", "4e-320|4e-320", "1.5e300|1.5e+300", "2e22|2e+22", "9.5|9.5"})
    void writesDoublesAsJavaScript(double value, String written) {
        assertThat(PackageJson.number(value)).isEqualTo(written);
    }

    @Test
    void writesWholeNumbersAndTheNonFinite() {
        assertThat(PackageJson.number(42)).isEqualTo("42");
        assertThat(PackageJson.number(-7L)).isEqualTo("-7");
        // Past 2^53 JavaScript holds the nearest double.
        assertThat(PackageJson.number(9_007_199_254_740_993L)).isEqualTo("9007199254740992");
        assertThat(PackageJson.number(new BigInteger("1000000000000000000000"))).isEqualTo("1e+21");
        assertThat(PackageJson.number(new BigDecimal("3.50"))).isEqualTo("3.5");
        assertThat(PackageJson.number(Double.NaN)).isEqualTo("null");
        assertThat(PackageJson.number(Double.POSITIVE_INFINITY)).isEqualTo("null");
    }

    @Test
    void quotesStringsAsJsonStringify() {
        assertThat(PackageJson.stringify("a\"b\\c\b\f\n\r\t\u0001\u001f é😀"))
            .isEqualTo("\"a\\\"b\\\\c\\b\\f\\n\\r\\t\\u0001\\u001f é😀\"");
        // A lone surrogate is written as its escape (well-formed JSON.stringify), a pair as it is.
        assertThat(PackageJson.stringify("x\ud800y\udc00")).isEqualTo("\"x\\ud800y\\udc00\"");
        assertThat(PackageJson.byteLength("😀")).isEqualTo(6);
        assertThat(PackageJson.byteLength("\ud800")).isEqualTo(8);
    }

    @Test
    void keepsKeyOrderButDigestsSorted() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("b", 1);
        ab.put("a", Arrays.asList(true, null));
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("a", Arrays.asList(true, null));
        ba.put("b", 1.0);
        assertThat(PackageJson.stringify(ab)).isEqualTo("{\"b\":1,\"a\":[true,null]}");
        assertThat(PackageJson.canonical(ab)).isEqualTo("{\"a\":[true,null],\"b\":1}");
        assertThat(PackageJson.digest(ab)).isEqualTo(PackageJson.digest(ba));
        assertThatThrownBy(() -> PackageJson.stringify(new Object())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsADocumentWithoutItsFilesData() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("img", new LinkedHashMap<>(Map.of("type", "image/png", "data", "AAAA")));
        files.put("odd", "not an object");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("questions", List.of());
        document.put("files", files);
        assertThat(PackageJson.stringify(PackageJson.withoutData(document)))
            .isEqualTo("{\"questions\":[],\"files\":{\"img\":{\"type\":\"image/png\"},\"odd\":\"not an object\"}}");
        assertThat(PackageJson.bytesOf(document)).isEqualTo(75);
        // The original keeps its data.
        assertThat(files.get("img")).isEqualTo(Map.of("type", "image/png", "data", "AAAA"));
    }

    @Test
    void trimsJavaScriptsWhiteSpace() {
        assertThat(PackageJson.jsTrim(" ﻿　 a b \t")).isEqualTo("a b");
        // Java's trim would take the information separators too; JavaScript keeps them.
        assertThat(PackageJson.jsTrim("\u001fa\u001c")).isEqualTo("\u001fa\u001c");
        assertThat(PackageJson.jsTrim("  ")).isEmpty();
    }

    @Test
    void readsNumbersAndTruthAsJavaScript() {
        assertThat(PackageJson.isInteger(2.0)).isTrue();
        assertThat(PackageJson.isInteger(2.5)).isFalse();
        assertThat(PackageJson.isInteger(new BigDecimal("4.000"))).isTrue();
        assertThat(PackageJson.isInteger("2")).isFalse();
        assertThat(PackageJson.truthy(0)).isFalse();
        assertThat(PackageJson.truthy(-0.0)).isFalse();
        assertThat(PackageJson.truthy(Double.NaN)).isFalse();
        assertThat(PackageJson.truthy("")).isFalse();
        assertThat(PackageJson.truthy("0")).isTrue();
        assertThat(PackageJson.truthy(List.of())).isTrue();
    }

    @Test
    void writesTimesAsToIsoString() {
        assertThat(PackageStepInputs.iso(0)).isEqualTo("1970-01-01T00:00:00.000Z");
        assertThat(PackageStepInputs.iso(1_796_083_200_123L)).isEqualTo("2026-12-01T00:00:00.123Z");
        assertThat(PackageStepInputs.iso(253_402_300_800_000L)).isEqualTo("+010000-01-01T00:00:00.000Z");
        assertThatThrownBy(() -> PackageStepInputs.iso(8_640_000_000_000_001L))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
