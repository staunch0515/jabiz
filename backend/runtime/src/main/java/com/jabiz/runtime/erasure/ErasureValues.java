package com.jabiz.runtime.erasure;

/**
 * What the purge writes over a personal value (decision D62 item 4): NULL where the column allows it; else the mark
 * {@value #TEXT_MARK} in text, {@value #JSON_MARK} in JSON, and no bytes in binary columns. The audit records keep
 * {@value #TEXT_MARK} for a value that was there and {@code null} for one that was not.
 */
public final class ErasureValues {

    public static final String TEXT_MARK = "***";
    public static final String JSON_MARK = "{\"erased\": true}";

    private ErasureValues() {
    }

    /** The SQL value written over a column of the given type and nullability; null when no mark fits the type. */
    static String replacement(String dataType, boolean nullable) {
        if (nullable) {
            return "NULL";
        }
        return switch (dataType) {
            case "text", "character varying", "character" -> "'" + TEXT_MARK + "'";
            case "jsonb" -> "'" + JSON_MARK + "'::jsonb";
            case "json" -> "'" + JSON_MARK + "'::json";
            case "bytea" -> "'\\x'::bytea";
            default -> null;
        };
    }
}
