package com.jabiz.imports;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;
import com.jabiz.i18n.PlatformErrorCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportValuesTest {

    private static final SemanticKind USD = new SemanticKind.Monetary("USD", 2);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Object read(SemanticKind kind, String text) throws ImportValues.Invalid {
        return ImportValues.read(kind, text, NEW_YORK, List.of(strict("MM/dd/uuuu"), strict("MM/dd/uuuu HH:mm")));
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(java.time.format.ResolverStyle.STRICT);
    }

    private static String code(SemanticKind kind, String text) {
        try {
            read(kind, text);
            return null;
        } catch (ImportValues.Invalid e) {
            return e.code();
        }
    }

    @Test
    void readsAmountsAsExportsWriteThem() throws Exception {
        assertThat(read(USD, "1,234.5")).isEqualTo(new BigDecimal("1234.50"));
        assertThat(read(USD, "$1,234.56")).isEqualTo(new BigDecimal("1234.56"));
        assertThat(read(USD, "(12.00)")).isEqualTo(new BigDecimal("-12.00"));
        assertThat(read(USD, "( $ 12 )")).isEqualTo(new BigDecimal("-12.00"));
        assertThat(read(USD, "12.30-")).isEqualTo(new BigDecimal("-12.30"));
        assertThat(read(USD, "-0.5")).isEqualTo(new BigDecimal("-0.50"));
        assertThat(read(USD, "+.5")).isEqualTo(new BigDecimal("0.50"));
        assertThat(read(USD, "1.000")).isEqualTo(new BigDecimal("1.00"));
        assertThat(read(USD, "  ")).isNull();
        assertThat(read(USD, null)).isNull();
        assertThat(code(USD, "1.005")).isEqualTo(PlatformErrorCodes.MONETARY_SCALE);
        assertThat(code(USD, "1,23")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(USD, "12,34.00")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(USD, "(12")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(USD, "-(12)")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(USD, "-12-")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(USD, "abc")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(USD, "$")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(USD, "1e5")).isEqualTo(ImportCodes.VALUE_INVALID);
    }

    @Test
    void readsNumbersWithinTheirPrecision() throws Exception {
        SemanticKind rate = new SemanticKind.Numeric(8, 4);
        assertThat(read(rate, "1.2345")).isEqualTo(new BigDecimal("1.2345"));
        assertThat(read(rate, "1234")).isEqualTo(new BigDecimal("1234.0000"));
        assertThat(code(rate, "1.23456")).isEqualTo(PlatformErrorCodes.NUMERIC_PRECISION);
        assertThat(code(rate, "12345")).isEqualTo(PlatformErrorCodes.NUMERIC_PRECISION);
        assertThat(read(new SemanticKind.Version(), "1,024")).isEqualTo(1024L);
        assertThat(code(new SemanticKind.Version(), "1.5")).isEqualTo(ImportCodes.VALUE_INVALID);
    }

    @Test
    void readsDatesAsDays() throws Exception {
        SemanticKind date = new SemanticKind.Date();
        assertThat(read(date, "2026-01-31")).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(read(date, "01/31/2026")).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(code(date, "02/30/2024")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(date, "2026-01-31T10:00:00Z")).isEqualTo(ImportCodes.VALUE_INVALID);
    }

    @Test
    void readsTimesInTheImportsZone() throws Exception {
        SemanticKind time = new SemanticKind.Temporal(TemporalRole.EVENT_TIME);
        assertThat(read(time, "2026-01-31T10:00:00Z")).isEqualTo(Instant.parse("2026-01-31T10:00:00Z"));
        assertThat(read(time, "2026-01-31T10:00:00+01:00")).isEqualTo(Instant.parse("2026-01-31T09:00:00Z"));
        assertThat(read(time, "2026-01-31")).isEqualTo(Instant.parse("2026-01-31T05:00:00Z"));
        assertThat(read(time, "2026-01-31T12:00")).isEqualTo(Instant.parse("2026-01-31T17:00:00Z"));
        assertThat(read(time, "01/31/2026")).isEqualTo(Instant.parse("2026-01-31T05:00:00Z"));
        assertThat(read(time, "01/31/2026 12:30")).isEqualTo(Instant.parse("2026-01-31T17:30:00Z"));
        assertThat(code(time, "31.01.2026")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(time, "02/30/2024")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(code(time, "2024-02-30")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(ImportValues.read(time, "2026-01-31", ZoneOffset.UTC, List.of()))
            .isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
    }

    @Test
    void readsTextCodesAndBooleans() throws Exception {
        assertThat(read(new SemanticKind.Text(5, false), " ab ")).isEqualTo(" ab ");
        assertThat(code(new SemanticKind.Text(2, false), "abc")).isEqualTo(PlatformErrorCodes.TOO_LONG);
        assertThat(read(new SemanticKind.Text(null, false), "x")).isEqualTo("x");
        SemanticKind code = new SemanticKind.Code("urn:d", List.of("A", "B"));
        assertThat(read(code, " A ")).isEqualTo("A");
        assertThat(code(code, "C")).isEqualTo(PlatformErrorCodes.NOT_IN_DICTIONARY);
        assertThat(read(new SemanticKind.Code("urn:d", List.of()), "Z")).isEqualTo("Z");
        SemanticKind bool = new SemanticKind.Bool();
        assertThat(read(bool, "TRUE")).isEqualTo(true);
        assertThat(read(bool, "yes")).isEqualTo(true);
        assertThat(read(bool, "0")).isEqualTo(false);
        assertThat(read(bool, "N")).isEqualTo(false);
        assertThat(code(bool, "maybe")).isEqualTo(ImportCodes.VALUE_INVALID);
        assertThat(read(new SemanticKind.Reference("Product"), " p1 ")).isEqualTo("p1");
        assertThatThrownBy(() -> read(new SemanticKind.None(), "x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidCarriesParams() {
        try {
            read(USD, "1.001");
        } catch (ImportValues.Invalid e) {
            assertThat(e.params()).containsEntry("scale", 2).containsEntry("currency", "USD");
            assertThat(e.getMessage()).contains("2 decimals");
            return;
        }
        throw new AssertionError("expected Invalid");
    }
}
