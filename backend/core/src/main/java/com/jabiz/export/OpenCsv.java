package com.jabiz.export;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The CSV of the open-format export (docs/design/21-audit-retention.md section 4): RFC 4180, lines ended by CRLF, a
 * cell quoted when it holds a comma, quote, line break or leading or trailing space. Values keep their meaning without
 * the system: decimals as plain text with their scale, times as UTC ISO-8601, structures as JSON; nothing is escaped
 * for spreadsheets, since the archive is data and a formula prefix would change it.
 */
public final class OpenCsv {

    private OpenCsv() {}

    /** One line, CRLF included. */
    public static String line(List<String> cells) {
        return cells.stream().map(OpenCsv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    /**
     * A value as text: empty for null, {@code toPlainString()} for decimals, UTC ISO-8601 for times, JSON (by
     * {@code json}) for maps and collections, {@code toString()} otherwise.
     */
    public static String value(Object value, Function<Object, String> json) {
        return switch (value) {
            case null -> "";
            case BigDecimal decimal -> decimal.toPlainString();
            case Instant instant -> instant.toString();
            case OffsetDateTime time -> time.toInstant().toString();
            case ZonedDateTime time -> time.toInstant().toString();
            case LocalDate date -> date.toString();
            case Map<?, ?> map -> json.apply(map);
            case Collection<?> collection -> json.apply(collection);
            default -> value.toString();
        };
    }

    static String cell(String text) {
        if (text == null) {
            return "";
        }
        boolean quote = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
            || (!text.isEmpty() && (Character.isWhitespace(text.charAt(0))
                || Character.isWhitespace(text.charAt(text.length() - 1))));
        return quote ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
