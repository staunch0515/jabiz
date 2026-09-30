package com.jabiz.runtime.imports;

import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ParseLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings of imports (docs/design/20-imports.md section 6): {@code jabiz.imports.max-rows}, the most records a file
 * may have (default 20000; an import may declare fewer). A larger file is refused, never cut short.
 */
@Component
public class ImportSettings {

    private final int maxRows;

    public ImportSettings(@Value("${jabiz.imports.max-rows:20000}") int maxRows) {
        if (maxRows < 1) {
            throw new IllegalArgumentException("jabiz.imports.max-rows must be positive, was " + maxRows);
        }
        this.maxRows = maxRows;
    }

    public int maxRows() {
        return maxRows;
    }

    /** The limits a file of the import is read with. */
    public ParseLimits limits(ImportDefinition<?> definition) {
        int rows = definition.maxRows() == null ? maxRows : Math.min(maxRows, definition.maxRows());
        return ParseLimits.DEFAULT.withMaxRecords(rows);
    }
}
