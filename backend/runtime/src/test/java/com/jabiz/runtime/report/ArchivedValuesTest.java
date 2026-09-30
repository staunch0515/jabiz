package com.jabiz.runtime.report;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;
import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportDocument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/design/19-reports.md section 5: what is stored reads back to the same values and the same hash. */
class ArchivedValuesTest {

    private static final List<ReportColumn> COLUMNS = List.of(
        new ReportColumn("code", "Code", new SemanticKind.Text(10, false)),
        new ReportColumn("amount", "Amount", new SemanticKind.Monetary("USD", 2)),
        new ReportColumn("at", "At", new SemanticKind.Temporal(TemporalRole.EVENT_TIME)),
        new ReportColumn("open", "Open", new SemanticKind.Bool()),
        new ReportColumn("name", "Name", new SemanticKind.Custom("jabiz.i18n-text", Map.of())),
        new ReportColumn("other", "Other", null));

    @Test
    void storedRowsReadBackToTheSameValuesAndHash() {
        List<List<Object>> rows = List.of(
            Arrays.asList("A", new BigDecimal("300.0000"), OffsetDateTime.parse("2026-02-05T09:00:00+09:00"), true,
                Map.of("zh", "现金", "en", "Cash"), 7L),
            Arrays.asList(null, null, null, null, null, null));

        String json = ArchivedValues.rowsJson(COLUMNS, rows);
        List<List<Object>> read = ArchivedValues.rows(COLUMNS, json);

        assertThat(read.getFirst()).containsExactly("A", new BigDecimal("300.0000"),
            Instant.parse("2026-02-05T00:00:00Z"), true, new java.util.TreeMap<>(Map.of("en", "Cash", "zh", "现金")),
            "7");
        assertThat(ArchivedValues.hash(COLUMNS, read)).isEqualTo(ArchivedValues.hash(COLUMNS, rows));
        // The scale of an amount does not change the hash; its value does.
        assertThat(ArchivedValues.hash(COLUMNS, List.of(Arrays.asList("A", new BigDecimal("300"),
            Instant.parse("2026-02-05T00:00:00Z"), true, Map.of("en", "Cash", "zh", "现金"), "7"), rows.get(1))))
            .isEqualTo(ArchivedValues.hash(COLUMNS, rows));
        assertThat(ArchivedValues.hash(COLUMNS, List.of(Arrays.asList("A", new BigDecimal("300.01"),
            Instant.parse("2026-02-05T00:00:00Z"), true, Map.of("en", "Cash", "zh", "现金"), "7"), rows.get(1))))
            .isNotEqualTo(ArchivedValues.hash(COLUMNS, rows));
    }

    @Test
    void columnsParametersAndParamsReadBack() {
        assertThat(ArchivedValues.columns(ArchivedValues.columnsJson(COLUMNS))).isEqualTo(COLUMNS);
        List<ReportDocument.Parameter> parameters = List.of(new ReportDocument.Parameter("asOf", "2026-02-05"));
        assertThat(ArchivedValues.parameters(ArchivedValues.parametersJson(parameters))).isEqualTo(parameters);
        assertThat(ArchivedValues.params(ArchivedValues.paramsJson(Map.of("b", 1, "a", List.of("x")))))
            .containsExactly(Map.entry("a", List.of("x")), Map.entry("b", 1));
    }
}
