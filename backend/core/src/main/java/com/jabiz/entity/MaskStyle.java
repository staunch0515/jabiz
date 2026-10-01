package com.jabiz.entity;

import java.util.regex.Pattern;

/**
 * How a {@linkplain FieldBuilder#masked masked field} is shown to everyone, holders of its permission included, until
 * a holder asks for one value in plain text (docs/design/10-security.md section 13.1). The masked form is the same in
 * Java (read APIs, history, audit) and in SQL (templates, exports), so a value looks alike wherever it appears.
 */
public enum MaskStyle {
    /**
     * {@code ****} followed by the last four characters, the usual form of account and tax numbers; values shorter than
     * {@value #MIN_LENGTH_FOR_LAST4} characters are masked entirely, since their last four would give most of them away.
     */
    LAST4,
    /** {@code ****} whatever the value. */
    ALL,
    /**
     * A United States taxpayer identification number as people expect to see it: an employer number written
     * {@code 12-3456789} as {@code **-***6789}, any other nine digits (a social security or individual number) as
     * {@code ***-**-6789}; anything that is not nine digits as {@code ****}.
     */
    TAX_ID;

    /** What every masked value starts with; a value starting with it is never accepted as input. */
    public static final String PREFIX = "****";

    /** Shortest value of which {@link #LAST4} shows the last four characters. */
    public static final int MIN_LENGTH_FOR_LAST4 = 8;

    private static final Pattern EMPLOYER_NUMBER = Pattern.compile("\\d{2}-\\d{7}");
    private static final Pattern NINE_DIGITS = Pattern.compile("\\d{3}-?\\d{2}-?\\d{4}");
    private static final Pattern MASKED_TAX_ID =
        Pattern.compile("\\*\\*-\\*\\*\\*\\d{4}|\\*\\*\\*-\\*\\*-\\d{4}");

    /** The masked form of a value; null stays null, so that an empty field still reads as empty. */
    public String apply(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        if (this == LAST4 && text.codePointCount(0, text.length()) >= MIN_LENGTH_FOR_LAST4) {
            return PREFIX + text.substring(text.offsetByCodePoints(text.length(), -4));
        }
        if (this == TAX_ID) {
            if (EMPLOYER_NUMBER.matcher(text).matches()) {
                return "**-***" + text.substring(text.length() - 4);
            }
            if (NINE_DIGITS.matcher(text).matches()) {
                return "***-**-" + text.substring(text.length() - 4);
            }
        }
        return PREFIX;
    }

    /**
     * The same masked form as a PostgreSQL expression over a text column, so rows of SQL templates never carry the
     * plain value to callers without the permission.
     *
     * @param column a column name already checked by {@code SqlIdentifiers.require}
     */
    public String sql(String column) {
        return switch (this) {
            case LAST4 -> "CASE WHEN " + column + " IS NULL THEN NULL WHEN char_length(" + column + ") >= "
                + MIN_LENGTH_FOR_LAST4 + " THEN '" + PREFIX + "' || right(" + column + ", 4) ELSE '" + PREFIX + "' END";
            case ALL -> "CASE WHEN " + column + " IS NULL THEN NULL ELSE '" + PREFIX + "' END";
            case TAX_ID -> "CASE WHEN " + column + " IS NULL THEN NULL"
                + " WHEN " + column + " ~ '^[0-9]{2}-[0-9]{7}$' THEN '**-***' || right(" + column + ", 4)"
                + " WHEN " + column + " ~ '^[0-9]{3}-?[0-9]{2}-?[0-9]{4}$' THEN '***-**-' || right(" + column + ", 4)"
                + " ELSE '" + PREFIX + "' END";
        };
    }

    /** Whether the value looks like a masked one, which is never written back (it would replace the plain value). */
    public static boolean looksMasked(Object value) {
        return value instanceof String text && (text.startsWith(PREFIX) || MASKED_TAX_ID.matcher(text).matches());
    }
}
