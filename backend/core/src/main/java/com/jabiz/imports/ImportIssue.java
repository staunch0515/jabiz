package com.jabiz.imports;

import java.util.Map;
import java.util.Objects;

/**
 * Something wrong with an import: with a file as a whole ({@code row} 0), or with one row, possibly one field.
 *
 * @param row      1-based row number; 0 for the file
 * @param location where in the file, for people; null for the file
 * @param field    the import field concerned; null when none
 * @param column   the file's column the field was read from; null when none
 * @param code     error code, also the key of the localized message
 * @param message  developer-facing description in English
 * @param params   values for the message's placeholders
 */
public record ImportIssue(int row, String location, String field, String column, String code, String message,
    Map<String, Object> params) {

    public ImportIssue {
        Objects.requireNonNull(code, "code must not be null");
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static ImportIssue ofFile(String code, String message, Map<String, Object> params) {
        return new ImportIssue(0, null, null, null, code, message, params);
    }
}
