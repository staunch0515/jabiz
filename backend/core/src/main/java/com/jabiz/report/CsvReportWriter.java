package com.jabiz.report;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a report as CSV (RFC 4180, docs/design/19-reports.md section 4): UTF-8 with a byte order mark so that
 * spreadsheet programs read it as UTF-8, CRLF line ends, a header row of column labels, then the rows. Numbers are
 * plain decimals with the column's decimals ({@code -1234.50}, no grouping) and times ISO-8601 instants, so the file reads back exactly; the page
 * header of the other formats is left out, the data being the point of a CSV.
 *
 * <p>A text that a spreadsheet would take for a formula ({@code =}, {@code +}, {@code -}, {@code @}, tab or carriage
 * return first) is written with a leading apostrophe: data entered by users must not run as a formula in the reader's
 * spreadsheet (CSV injection). Numbers are never text, so negative amounts stay numbers.
 */
public final class CsvReportWriter {

    private static final String CRLF = "\r\n";

    private CsvReportWriter() {}

    public static void write(ReportDocument document, OutputStream out) {
        try {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write('﻿');
            line(writer, document.columns().stream().map(ReportColumn::label).toList());
            List<ReportColumn> columns = document.columns();
            for (List<Object> row : document.rows()) {
                List<String> fields = new java.util.ArrayList<>(row.size());
                for (int c = 0; c < row.size(); c++) {
                    Object value = row.get(c);
                    fields.add(value instanceof BigDecimal d && columns.get(c).numeric()
                        ? scaled(d, columns.get(c).scale()) : text(value));
                }
                line(writer, fields);
            }
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text of a value: exact decimals, instants in UTC, maps (multilingual texts) with sorted keys. */
    static String text(Object value) {
        return switch (value) {
            case null -> "";
            case BigDecimal d -> d.toPlainString();
            case Instant i -> i.toString();
            case OffsetDateTime t -> t.toInstant().toString();
            case Map<?, ?> m -> inert(new TreeMap<>(m).toString());
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            default -> inert(String.valueOf(value));
        };
    }

    /**
     * An amount with the column's decimals ({@code 300} of a two-decimal column is {@code 300.00}, the database's
     * {@code 300.0000} of a whole-number column is {@code 300}); digits beyond them are kept, never rounded away.
     */
    static String scaled(BigDecimal value, int scale) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.setScale(Math.max(stripped.scale(), scale)).toPlainString();
    }

    /** The text with an apostrophe in front when a spreadsheet would read it as a formula. */
    static String inert(String text) {
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            return "'" + text;
        }
        return text;
    }

    private static void line(Writer writer, List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(quoted(fields.get(i)));
        }
        writer.write(CRLF);
    }

    /** A field in quotes when it holds a separator, a quote or a line break; quotes doubled. */
    static String quoted(String field) {
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0 && field.indexOf('\n') < 0 && field.indexOf('\r') < 0) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
