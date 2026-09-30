package com.jabiz.export;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The CSV of the open-format export (docs/design/21-audit-retention.md section 4). */
class OpenCsvTest {

    @Test
    void cellsAreQuotedOnlyWhenTheyMustBe() {
        assertThat(OpenCsv.line(List.of("a", "b c", ""))).isEqualTo("a,b c,\r\n");
        assertThat(OpenCsv.line(List.of("x,y", "say \"hi\"", "two\nlines", " padded", "=1+1")))
            .isEqualTo("\"x,y\",\"say \"\"hi\"\"\",\"two\nlines\",\" padded\",=1+1\r\n");
        assertThat(OpenCsv.cell(null)).isEmpty();
        assertThat(OpenCsv.cell("tail ")).isEqualTo("\"tail \"");
        assertThat(OpenCsv.cell("cr\rx")).isEqualTo("\"cr\rx\"");
    }

    @Test
    void valuesKeepTheirMeaning() {
        assertThat(OpenCsv.value(null, Object::toString)).isEmpty();
        assertThat(OpenCsv.value(new BigDecimal("1E+3"), Object::toString)).isEqualTo("1000");
        assertThat(OpenCsv.value(new BigDecimal("10.50"), Object::toString)).isEqualTo("10.50");
        assertThat(OpenCsv.value(Instant.parse("2026-01-31T09:00:00Z"), Object::toString))
            .isEqualTo("2026-01-31T09:00:00Z");
        assertThat(OpenCsv.value(OffsetDateTime.of(2026, 1, 31, 18, 0, 0, 0, ZoneOffset.ofHours(9)), Object::toString))
            .isEqualTo("2026-01-31T09:00:00Z");
        assertThat(OpenCsv.value(ZonedDateTime.of(2026, 1, 31, 18, 0, 0, 0, ZoneOffset.ofHours(9)), Object::toString))
            .isEqualTo("2026-01-31T09:00:00Z");
        assertThat(OpenCsv.value(LocalDate.of(2026, 1, 31), Object::toString)).isEqualTo("2026-01-31");
        assertThat(OpenCsv.value(Map.of("en", "x"), value -> "JSON")).isEqualTo("JSON");
        assertThat(OpenCsv.value(List.of(1), value -> "JSON")).isEqualTo("JSON");
        assertThat(OpenCsv.value(true, Object::toString)).isEqualTo("true");
    }
}
