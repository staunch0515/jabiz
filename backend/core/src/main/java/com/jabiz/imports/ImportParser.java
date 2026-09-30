package com.jabiz.imports;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads an import file into records (docs/design/20-imports.md section 2). Synchronous and pure Java: the platform
 * calls it off the request threads. Implementations must honour {@link ParseLimits} and report a file they cannot
 * read with {@link ImportFileException}, never by returning part of it.
 */
@FunctionalInterface
public interface ImportParser {

    /** Receives what a parser reads, in file order. */
    interface Sink {
        /** The table's columns, once, before the first record (in the file's order). */
        void columns(java.util.List<String> names);

        /** A value outside the records, such as a statement's opening balance. */
        void header(String name, String value);

        void record(RawRecord record);
    }

    void parse(Path file, ParseLimits limits, Sink sink) throws IOException;
}
