package com.jabiz.runtime.report;

import com.jabiz.report.ReportFormat;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/design/19-reports.md section 4: amounts are numeric cells whose sum equals the report's total. */
class XlsxReportWriterTest {

    @Test
    void amountsAreNumericCellsAndTextsStayText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxReportWriter.write(ReportSamples.trialBalance(0, false), new ReportFormat("en-US", ZoneOffset.UTC),
            ReportLabels.ENGLISH, out);

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows.getFirst().getCellText(0)).isEqualTo("Trial balance");
            assertThat(rows.stream().map(r -> r.getCellText(0)).toList())
                .contains("Acme Inc.", "Period", "Run at", "As known on", "asOf", "Version");
            int header = indexOf(rows, "Account");
            assertThat(rows.get(header).stream().map(c -> c.getText()).collect(Collectors.toList()))
                .containsExactly("Account", "Account name", "Balance", "Booked");

            List<Row> data = rows.subList(header + 1, rows.size());
            assertThat(data).hasSize(3);
            BigDecimal sum = BigDecimal.ZERO;
            for (Row row : data) {
                assertThat(row.getCell(2).getType()).isEqualTo(CellType.NUMBER);
                sum = sum.add(row.getCellAsNumber(2).orElseThrow());
            }
            assertThat(sum).isEqualByComparingTo("7345.55");
            // A formula entered as data is written as text, not as a formula.
            assertThat(data.get(2).getCell(1).getType()).isEqualTo(CellType.STRING);
            assertThat(data.get(0).getCellAsDate(3).orElseThrow()).isEqualTo("2026-02-03T15:00:00");
            assertThat(data.get(1).getCellText(3)).isEmpty();
        }
    }

    @Test
    void numberFormatsShowTheScaleAndParentheses() {
        assertThat(XlsxReportWriter.numberFormat(2)).isEqualTo("#,##0.00;(#,##0.00)");
        assertThat(XlsxReportWriter.numberFormat(0)).isEqualTo("#,##0;(#,##0)");
    }

    private static int indexOf(List<Row> rows, String firstCell) {
        for (int i = 0; i < rows.size(); i++) {
            if (firstCell.equals(rows.get(i).getCellText(0))) {
                return i;
            }
        }
        throw new AssertionError("no row starting with " + firstCell);
    }
}
