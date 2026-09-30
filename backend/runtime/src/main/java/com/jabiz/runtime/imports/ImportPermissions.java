package com.jabiz.runtime.imports;

/**
 * Platform permissions of imports (docs/design/20-imports.md section 6). Importing itself needs the import's own
 * permission; these guard the platform's parts.
 */
public final class ImportPermissions {

    /** Declared by the internal process {@code IMPORT_RUN}; callers need the import's permission instead. */
    public static final String RUN = "import.run";
    /** Reading saved mappings through the generic dataset API (the import pages need the import's permission). */
    public static final String MAPPING_READ = "import.mapping.read";
    /** Declared by the internal mapping processes; callers need the import's mapping permission instead. */
    public static final String MAPPING_WRITE = "import.mapping.write";

    private ImportPermissions() {}
}
