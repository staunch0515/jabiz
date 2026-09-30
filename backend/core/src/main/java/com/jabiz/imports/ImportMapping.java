package com.jabiz.imports;

import java.util.Map;

/**
 * How a file's columns feed an import's fields (docs/design/20-imports.md section 4): chosen when importing, and
 * saved under a name for files of the same layout. What it leaves out, the definition decides.
 *
 * @param columns   field to the file's column
 * @param constants field to a value used for every row, as text read like a cell
 * @param options   adjustments of a CSV or XLSX layout
 */
public record ImportMapping(Map<String, String> columns, Map<String, String> constants,
    ImportFormat.Options options) {

    public static final ImportMapping DEFAULT = new ImportMapping(Map.of(), Map.of(), ImportFormat.Options.NONE);

    public ImportMapping {
        columns = columns == null ? Map.of() : Map.copyOf(columns);
        constants = constants == null ? Map.of() : Map.copyOf(constants);
        options = options == null ? ImportFormat.Options.NONE : options;
    }
}
