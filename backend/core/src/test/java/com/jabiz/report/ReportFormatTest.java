package com.jabiz.report;

import com.jabiz.entity.SemanticKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** docs/design/19-reports.md section 4: printed values follow the region, negatives in parentheses. */
class ReportFormatTest {

    private static final ReportColumn USD = new ReportColumn("balance", "Balance", new SemanticKind.Monetary("USD", 2));

    @Test
    void amountsHaveGroupingFixedDecimalsAndParentheses() {
        ReportFormat us = new ReportFormat("en-US", ZoneOffset.UTC);

        assertThat(us.amount(new BigDecimal("-1234.5"), 2)).isEqualTo("(1,234.50)");
        assertThat(us.amount(new BigDecimal("1.005"), 2)).isEqualTo("1.01");
        assertThat(us.amount(new BigDecimal("-0.005"), 2)).isEqualTo("(0.01)");
        assertThat(us.amount(new BigDecimal("1234567"), 0)).isEqualTo("1,234,567");
        assertThat(new ReportFormat("de-DE", ZoneOffset.UTC).amount(new BigDecimal("1234.5"), 2)).isEqualTo("1.234,50");
        assertThat(new ReportFormat(null, ZoneOffset.UTC).amount(new BigDecimal("1234.5"), 2)).isEqualTo("1,234.50");
    }

    @Test
    void timesFollowTheRegionAndZone() {
        Instant time = Instant.parse("2026-01-31T14:05:09Z");

        assertThat(new ReportFormat(null, ZoneOffset.UTC).dateTime(time)).isEqualTo("2026-01-31 14:05:09");
        assertThat(new ReportFormat(" ", ZoneId.of("Asia/Tokyo")).dateTime(time)).isEqualTo("2026-01-31 23:05:09");
        assertThat(new ReportFormat("en-US", ZoneOffset.UTC).dateTime(time)).contains("2026").contains("2:05:09");
        assertThat(new ReportFormat("en-US", ZoneOffset.UTC).zone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void cellsByKind() {
        ReportFormat format = new ReportFormat(null, ZoneOffset.UTC);
        ReportColumn text = new ReportColumn("name", null, new SemanticKind.Text(10, false));

        assertThat(format.value(USD, new BigDecimal("-3"))).isEqualTo("(3.00)");
        assertThat(format.value(USD, 7L)).isEqualTo("7.00");
        assertThat(format.value(text, null)).isEmpty();
        assertThat(format.value(text, 7L)).isEqualTo("7");
        assertThat(format.value(text, OffsetDateTime.parse("2026-01-31T14:05:09Z"))).isEqualTo("2026-01-31 14:05:09");
        assertThat(format.value(text, Map.of("en", "Cash"))).isEqualTo("{en=Cash}");
    }

    @Test
    void columnsKnowTheirKind() {
        assertThat(USD.numeric()).isTrue();
        assertThat(USD.scale()).isEqualTo(2);
        assertThat(new ReportColumn("n", "", new SemanticKind.Numeric(9, 3)).scale()).isEqualTo(3);
        assertThat(new ReportColumn("v", "", new SemanticKind.Version()).numeric()).isTrue();
        ReportColumn plain = new ReportColumn("x", " ", null);
        assertThat(plain.label()).isEqualTo("x");
        assertThat(plain.numeric()).isFalse();
        assertThat(plain.temporal()).isFalse();
        assertThat(plain.scale()).isZero();
    }

    @Test
    void aDocumentChecksItsRowsAndDefaultsKnownAtToTheRunTime() {
        ReportDocument document = ReportTestData.trialBalance();
        assertThat(document.knownAt()).isEqualTo(ReportTestData.RUN);
        assertThat(document.rows().get(1).get(2)).isNull();

        List<List<Object>> short1 = new ArrayList<>();
        short1.add(List.of("only one"));
        assertThatThrownBy(() -> new ReportDocument("q", "v", "t", null, null, List.of(), ReportTestData.RUN, null, null,
            false, document.columns(), short1)).hasMessageContaining("1 values for 3 columns");
        assertThat(new ReportDocument("q", "v", "t", null, null, List.of(), ReportTestData.RUN, null, null, false,
            List.of(), List.of()).company()).isEmpty();
    }
}
