package com.jabiz.imports;

import java.util.Map;

/**
 * The file cannot be read as the import's layout, or goes beyond a limit; nothing of it is imported.
 *
 * @see ImportCodes
 */
public class ImportFileException extends RuntimeException {

    private final String code;
    private final String location;
    private final Map<String, Object> params;

    public ImportFileException(String code, String location, String message, Map<String, Object> params) {
        super(location == null ? message : location + ": " + message);
        this.code = code;
        this.location = location;
        // The description goes along as {detail}, for the localized message.
        java.util.Map<String, Object> all = new java.util.LinkedHashMap<>();
        all.put("detail", message);
        if (params != null) {
            all.putAll(params);
        }
        this.params = Map.copyOf(all);
    }

    public ImportFileException(String location, String message) {
        this(ImportCodes.FILE_INVALID, location, message, Map.of());
    }

    public String code() {
        return code;
    }

    /** Where in the file; null for the file as a whole. */
    public String location() {
        return location;
    }

    public Map<String, Object> params() {
        return params;
    }
}
