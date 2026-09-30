package com.jabiz.runtime.report;

import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportDocument;
import com.jabiz.report.ReportFormat;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a report as an Excel workbook (docs/design/19-reports.md section 4): the page header in the first rows, then
 * the column labels (frozen) and the rows. Amounts and numbers are numeric cells with the report's decimals and
 * negatives in parentheses, so that sums in the spreadsheet equal the report's totals; times are date cells in the
 * report's zone. Texts are always text cells, never formulas.
 */
public final class XlsxReportWriter {

    private static final String DATE_TIME = "yyyy-mm-dd hh:mm:ss";
    private static final String DATE = "yyyy-mm-dd";

    private XlsxReportWriter() {}

    public static void write(ReportDocument document, ReportFormat format, ReportLabels labels, OutputStream out) {
        try {
            Workbook workbook = new Workbook(out, "jabiz", "1.0");
            workbook.properties().setTitle(document.title());
            Worksheet sheet = workbook.newWorksheet("Report");
            int row = header(sheet, document, format, labels);
            List<ReportColumn> columns = document.columns();
            for (int c = 0; c < columns.size(); c++) {
                sheet.value(row, c, columns.get(c).label());
                sheet.style(row, c).bold().borderStyle(org.dhatim.fastexcel.BorderSide.BOTTOM, "thin").set();
            }
            sheet.freezePane(0, row + 1);
            int first = row + 1;
            for (List<Object> values : document.rows()) {
                row++;
                for (int c = 0; c < columns.size(); c++) {
                    cell(sheet, row, c, values.get(c), format);
                }
            }
            if (row >= first) {
                for (int c = 0; c < columns.size(); c++) {
                    ReportColumn column = columns.get(c);
                    if (column.numeric()) {
                        sheet.range(first, c, row, c).style().format(numberFormat(column.scale())).set();
                    } else if (column.temporal()) {
                        sheet.range(first, c, row, c).style().format(DATE_TIME).set();
                    } else if (column.date()) {
                        sheet.range(first, c, row, c).style().format(DATE).set();
                    }
                }
            }
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The number format of a column: grouping, its decimals, negatives in parentheses. */
    static String numberFormat(int scale) {
        String positive = "#,##0" + (scale > 0 ? "." + "0".repeat(scale) : "");
        return positive + ";(" + positive + ")";
    }

    /** Title, company, period, run time, effective and recorded times, parameters and version; returns the row of the column labels. */
    private static int header(Worksheet sheet, ReportDocument document, ReportFormat format, ReportLabels labels) {
        int row = 0;
        sheet.value(row, 0, document.title());
        sheet.style(row, 0).bold().fontSize(14).set();
        row++;
        if (!document.company().isBlank()) {
            sheet.value(row++, 0, document.company());
        }
        if (document.period() != null) {
            sheet.value(row, 0, labels.period());
            sheet.value(row++, 1, document.period());
        }
        sheet.value(row, 0, labels.runAt());
        sheet.value(row++, 1, format.dateTime(document.runTime()));
        if (document.asOf() != null) {
            sheet.value(row, 0, labels.asOf());
            sheet.value(row++, 1, format.dateTime(document.asOf()));
        }
        sheet.value(row, 0, labels.knownAt());
        sheet.value(row++, 1, format.dateTime(document.knownAt()));
        for (ReportDocument.Parameter parameter : document.parameters()) {
            sheet.value(row, 0, parameter.label());
            sheet.value(row++, 1, parameter.value());
        }
        sheet.value(row, 0, labels.version());
        sheet.value(row++, 1, document.templateVersion());
        return row + 1;
    }

    private static void cell(Worksheet sheet, int row, int column, Object value, ReportFormat format) {
        switch (value) {
            case null -> { }
            case BigDecimal d -> sheet.value(row, column, d);
            case Number n -> sheet.value(row, column, n);
            case Boolean b -> sheet.value(row, column, b);
            case Instant i -> sheet.value(row, column, i.atZone(format.zone()).toLocalDateTime());
            case OffsetDateTime t -> sheet.value(row, column, t.toInstant().atZone(format.zone()).toLocalDateTime());
            case LocalDate d -> sheet.value(row, column, d);
            case Map<?, ?> m -> sheet.value(row, column, new TreeMap<>(m).toString());
            default -> sheet.value(row, column, String.valueOf(value));
        }
    }
}
