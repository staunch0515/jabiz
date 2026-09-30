package com.jabiz.imports;

/** Error codes of imports (docs/design/20-imports.md section 6); each is also the key of its localized message. */
public final class ImportCodes {

    /** The file cannot be read as the import's layout. */
    public static final String FILE_INVALID = "IMPORT_FILE_INVALID";
    /** The file has more records than allowed; params {@code max}. */
    public static final String TOO_MANY_ROWS = "IMPORT_TOO_MANY_ROWS";
    /** A required field has no column in the file and no constant; params {@code field}. */
    public static final String COLUMN_MISSING = "IMPORT_COLUMN_MISSING";
    /** The mapping names a column the file does not have; params {@code column}. */
    public static final String UNKNOWN_COLUMN = "IMPORT_UNKNOWN_COLUMN";
    /** The mapping names a field the import does not have; params {@code field}. */
    public static final String UNKNOWN_FIELD = "IMPORT_UNKNOWN_FIELD";
    /** A record has more cells than the file has columns. */
    public static final String EXTRA_CELLS = "IMPORT_EXTRA_CELLS";
    /** The value of a cell cannot be read as the field's type; params {@code value}. */
    public static final String VALUE_INVALID = "IMPORT_VALUE_INVALID";
    /** The record's external reference appears earlier in the file or was imported before; params {@code ref}. */
    public static final String DUPLICATE_REF = "IMPORT_DUPLICATE_REF";
    /** The file was not uploaded under the import's file policy; params {@code policy}. */
    public static final String WRONG_FILE = "IMPORT_WRONG_FILE";
    /** A row's process refused it for a reason other than broken rules; params {@code detail}. */
    public static final String ROW_FAILED = "IMPORT_ROW_FAILED";
    /** The import has no rows to process. */
    public static final String EMPTY = "IMPORT_EMPTY";
    /** A committed import rejected: at least one row failed; nothing was written. */
    public static final String REJECTED = "IMPORT_REJECTED";

    private ImportCodes() {}
}
