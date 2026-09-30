package com.jabiz.report;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/design/19-reports.md section 4. */
class CsvReportWriterTest {

    @Test
    void writesExactValuesUnderTheColumnLabels() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvReportWriter.write(ReportTestData.trialBalance(), out);
        byte[] bytes = out.toByteArray();

        // A byte order mark, so that spreadsheet programs read UTF-8.
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, StandardCharsets.UTF_8).substring(1);
        assertThat(text.split("\r\n", -1)).containsExactly(
            "Account,Balance,bookedAt",
            "1000,-5000.00,2026-02-03T15:00:00Z",
            "\"2000, payables\",12345.50,",
            // A formula entered as data stays text.
            "\"'=HYPERLINK(\"\"x\"\")\",0.00,2026-02-05T00:00:00Z",
            "\"{en=Cash, zh=现金}\",1.005,2026-02-05T14:05:09Z",
            "");
    }

    @Test
    void textsThatReadAsFormulasGetAnApostrophe() {
        assertThat(CsvReportWriter.inert("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvReportWriter.inert("+1")).isEqualTo("'+1");
        assertThat(CsvReportWriter.inert("-1")).isEqualTo("'-1");
        assertThat(CsvReportWriter.inert("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvReportWriter.inert("\tx")).isEqualTo("'\tx");
        assertThat(CsvReportWriter.inert("plain")).isEqualTo("plain");
        assertThat(CsvReportWriter.inert("")).isEmpty();
        assertThat(CsvReportWriter.text(-5L)).isEqualTo("-5");
        assertThat(CsvReportWriter.text(true)).isEqualTo("true");
        assertThat(CsvReportWriter.quoted("a\nb")).isEqualTo("\"a\nb\"");
        assertThat(CsvReportWriter.scaled(new java.math.BigDecimal("300.0000"), 0)).isEqualTo("300");
        assertThat(CsvReportWriter.scaled(new java.math.BigDecimal("300"), 2)).isEqualTo("300.00");
        assertThat(CsvReportWriter.scaled(new java.math.BigDecimal("1E+3"), 0)).isEqualTo("1000");
        assertThat(CsvReportWriter.scaled(new java.math.BigDecimal("1.005"), 2)).isEqualTo("1.005");
    }
}
