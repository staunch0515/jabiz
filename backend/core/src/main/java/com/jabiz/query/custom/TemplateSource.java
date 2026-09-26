package com.jabiz.query.custom;

/**
 * Where a query was declared, for reporting problems: the {@code .sql} file (relative to the classpath) and the line
 * its SQL starts on, or the declaring class for the Java DSL.
 *
 * @param firstLine line of the file on which the SQL template starts (1-based)
 */
public record TemplateSource(String path, int firstLine) {

    public TemplateSource {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (firstLine < 1) {
            throw new IllegalArgumentException("firstLine must be positive");
        }
    }

    /** Location of an offset of the SQL template, as {@code path:line}. */
    public String locate(String sqlTemplate, int offset) {
        int line = firstLine;
        for (int i = 0; i < Math.min(offset, sqlTemplate.length()); i++) {
            if (sqlTemplate.charAt(i) == '\n') {
                line++;
            }
        }
        return path + ":" + line;
    }
}
