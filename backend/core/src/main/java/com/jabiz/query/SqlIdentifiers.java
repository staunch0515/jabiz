package com.jabiz.query;

import java.util.regex.Pattern;

/**
 * Guards against identifier injection: table and column names originate from metadata,
 * never from request data, but are still checked before being placed into SQL text.
 */
public final class SqlIdentifiers {

    private static final Pattern SIMPLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private SqlIdentifiers() {}

    /** Accepts a plain identifier or a schema-qualified one (schema.name). */
    public static String require(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("SQL identifier must not be blank");
        }
        for (String part : identifier.split("\\.", -1)) {
            if (!SIMPLE.matcher(part).matches()) {
                throw new IllegalArgumentException("Illegal SQL identifier: " + identifier);
            }
        }
        return identifier;
    }
}
