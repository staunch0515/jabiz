package com.jabiz.imports;

import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import com.jabiz.i18n.PlatformErrorCodes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a cell's text as a field's type (docs/design/20-imports.md section 3). Lenient where exports differ harmlessly
 * (thousands separators, a currency sign, negatives in parentheses or with a trailing minus, dates in the import's
 * patterns) and strict where a value could be misread: the decimal separator is always a point, and an amount with
 * more decimals than its currency has is refused, not rounded.
 */
public final class ImportValues {

    /** A problem reading one value; {@link #code} is the error code. */
    public static final class Invalid extends Exception {
        private final String code;
        private final Map<String, Object> params;

        Invalid(String code, String message, Map<String, Object> params) {
            super(message, null, false, false);
            this.code = code;
            this.params = Map.copyOf(params);
        }

        public String code() {
            return code;
        }

        public Map<String, Object> params() {
            return params;
        }
    }

    private static final Pattern NUMBER = Pattern.compile(
        "(?<open>\\()?\\s*(?<sign>[+-])?\\s*[$€£¥]?\\s*(?<digits>\\d{1,3}(,\\d{3})+|\\d+)?(?<fraction>\\.\\d+)?"
            + "\\s*(?<trailing>-)?\\s*(?<close>\\))?");

    private ImportValues() {}

    /**
     * The value of {@code text} for a field of {@code kind}; null for blank text.
     *
     * @param zone         where a date or date-time without offset is
     * @param datePatterns further accepted date (or date-time) patterns, besides ISO
     */
    public static Object read(SemanticKind kind, String text, ZoneId zone, List<DateTimeFormatter> datePatterns)
        throws Invalid {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.strip();
        return switch (kind) {
            case SemanticKind.Text t -> {
                if (t.maxLength() != null && text.length() > t.maxLength()) {
                    throw new Invalid(PlatformErrorCodes.TOO_LONG, "longer than " + t.maxLength() + " characters",
                        Map.of("max", t.maxLength()));
                }
                yield text;
            }
            case SemanticKind.Code c -> {
                if (!c.allowedValues().isEmpty() && !c.allowedValues().contains(value)) {
                    throw new Invalid(PlatformErrorCodes.NOT_IN_DICTIONARY, "'" + value + "' is not one of "
                        + c.allowedValues(), Map.of("value", value));
                }
                yield value;
            }
            case SemanticKind.Monetary m -> {
                BigDecimal amount = decimal(value);
                if (amount.stripTrailingZeros().scale() > m.scale()) {
                    throw new Invalid(PlatformErrorCodes.MONETARY_SCALE, "more than " + m.scale()
                        + " decimals for " + m.currency(), Map.of("scale", m.scale(), "currency", m.currency()));
                }
                yield amount.setScale(Math.max(m.scale(), 0));
            }
            case SemanticKind.Numeric n -> {
                BigDecimal number = decimal(value);
                BigDecimal stripped = number.stripTrailingZeros();
                int integerDigits = stripped.precision() - stripped.scale();
                if (stripped.scale() > n.scale() || integerDigits > n.precision() - n.scale()) {
                    throw new Invalid(PlatformErrorCodes.NUMERIC_PRECISION, "does not fit numeric(" + n.precision()
                        + ", " + n.scale() + ")", Map.of("precision", n.precision(), "scale", n.scale()));
                }
                yield number.setScale(n.scale());
            }
            case SemanticKind.Temporal t -> instant(value, zone, datePatterns);
            case SemanticKind.Bool b -> bool(value);
            case SemanticKind.Version v -> {
                try {
                    yield FieldValueCoercer.toLong(value.replace(",", ""));
                } catch (IllegalArgumentException e) {
                    throw invalid(value);
                }
            }
            case SemanticKind.Reference r -> value;
            default -> throw new IllegalStateException("Kind " + kind + " is not read from files");
        };
    }

    /** A decimal number as exports write it; the decimal separator is a point. */
    public static BigDecimal decimal(String text) throws Invalid {
        Matcher m = NUMBER.matcher(text.strip());
        if (!m.matches() || m.group("digits") == null && m.group("fraction") == null
            || (m.group("open") == null) != (m.group("close") == null)) {
            throw invalid(text);
        }
        boolean negative = m.group("open") != null || "-".equals(m.group("sign")) || m.group("trailing") != null;
        if (m.group("open") != null && m.group("sign") != null || m.group("sign") != null && m.group("trailing") != null) {
            throw invalid(text);
        }
        String digits = m.group("digits") == null ? "0" : m.group("digits").replace(",", "");
        BigDecimal number = new BigDecimal(digits + (m.group("fraction") == null ? "" : m.group("fraction")));
        return negative ? number.negate() : number;
    }

    private static Instant instant(String value, ZoneId zone, List<DateTimeFormatter> patterns) throws Invalid {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // not UTC; try an offset
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // no offset; try local forms below
        }
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // try a date
        }
        try {
            return LocalDate.parse(value).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the import's patterns
        }
        for (DateTimeFormatter pattern : patterns) {
            try {
                var parsed = pattern.parseBest(value, LocalDateTime::from, LocalDate::from);
                return parsed instanceof LocalDateTime dateTime ? dateTime.atZone(zone).toInstant()
                    : ((LocalDate) parsed).atStartOfDay(zone).toInstant();
            } catch (DateTimeParseException ignored) {
                // next pattern
            }
        }
        throw invalid(value);
    }

    private static Boolean bool(String value) throws Invalid {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> throw invalid(value);
        };
    }

    private static Invalid invalid(String value) {
        return new Invalid(ImportCodes.VALUE_INVALID, "'" + value + "' cannot be read", Map.of("value", value));
    }
}
