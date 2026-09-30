package com.jabiz.imports;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How an import file is laid out (docs/design/20-imports.md section 2): the platform reads CSV, fixed-width text,
 * XLSX workbooks and XML; an application brings its own {@link ImportParser} for anything else (BAI2, camt.053).
 * CSV and XLSX layouts can be adjusted per import by a mapping ({@link Options}); the others are fixed by the
 * definition.
 */
public sealed interface ImportFormat {

    /** The adjustable parts of a CSV or XLSX layout; null keeps the definition's value. */
    record Options(Character delimiter, Boolean header, Integer skipLines, String charset, String sheet,
        Integer headerRow) {

        public static final Options NONE = new Options(null, null, null, null, null, null);
    }

    /** This layout with the given adjustments; formats that cannot be adjusted return themselves. */
    default ImportFormat adjusted(Options options) {
        return this;
    }

    /** Whether a mapping may adjust this layout. */
    default boolean adjustable() {
        return false;
    }

    /**
     * Delimited text (RFC 4180): quoted cells may hold the delimiter, quotes (doubled) and line breaks.
     *
     * @param header    whether the first line (after {@code skipLines}) names the columns; otherwise the columns
     *                  are named by position, {@code 1}, {@code 2} and so on
     * @param skipLines lines before the header (or the first record) that are not part of the table
     * @param charset   the file's encoding; UTF-8 by default (a leading byte order mark is ignored)
     */
    record Csv(char delimiter, char quote, boolean header, int skipLines, Charset charset) implements ImportFormat {
        public Csv {
            if (delimiter == quote || delimiter == '\n' || delimiter == '\r' || quote == '\n' || quote == '\r') {
                throw new IllegalArgumentException("CSV delimiter and quote must differ and not be line breaks");
            }
            if (skipLines < 0) {
                throw new IllegalArgumentException("skipLines must not be negative");
            }
            Objects.requireNonNull(charset, "charset must not be null");
        }

        public Csv delimiter(char value) {
            return new Csv(value, quote, header, skipLines, charset);
        }

        public Csv header(boolean value) {
            return new Csv(delimiter, quote, value, skipLines, charset);
        }

        public Csv skipLines(int value) {
            return new Csv(delimiter, quote, header, value, charset);
        }

        public Csv charset(Charset value) {
            return new Csv(delimiter, quote, header, skipLines, value);
        }

        @Override
        public ImportFormat adjusted(Options o) {
            return new Csv(o.delimiter() == null ? delimiter : o.delimiter(), quote,
                o.header() == null ? header : o.header(), o.skipLines() == null ? skipLines : o.skipLines(),
                o.charset() == null ? charset : ImportFormat.charset(o.charset()));
        }

        @Override
        public boolean adjustable() {
            return true;
        }
    }

    /**
     * Lines cut into columns by position.
     *
     * @param columns   the columns, in order
     * @param skipLines lines at the start that are not records
     */
    record FixedWidth(List<Slice> columns, int skipLines, Charset charset) implements ImportFormat {

        /** A column: {@code length} characters from {@code start} (1-based); the value is trimmed. */
        public record Slice(String name, int start, int length) {
            public Slice {
                Objects.requireNonNull(name, "name must not be null");
                if (start < 1 || length < 1) {
                    throw new IllegalArgumentException("Column " + name + ": start and length must be positive");
                }
            }
        }

        public FixedWidth {
            columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("A fixed-width layout needs at least one column");
            }
            if (columns.stream().map(Slice::name).distinct().count() != columns.size()) {
                throw new IllegalArgumentException("Fixed-width column names must be unique");
            }
            if (skipLines < 0) {
                throw new IllegalArgumentException("skipLines must not be negative");
            }
            Objects.requireNonNull(charset, "charset must not be null");
        }
    }

    /**
     * A worksheet of an XLSX workbook. Formulas are read as the value Excel last calculated and stored; numbers are
     * read to 15 significant digits (what Excel keeps) and cells formatted as dates as ISO dates.
     *
     * @param sheet     the worksheet's name; null for the first one
     * @param header    whether the header row names the columns; otherwise columns are named by letter
     * @param headerRow the 1-based row of the header (or the row before the first record when there is none)
     */
    record Xlsx(String sheet, boolean header, int headerRow) implements ImportFormat {
        public Xlsx {
            if (headerRow < 0) {
                throw new IllegalArgumentException("headerRow must not be negative");
            }
        }

        public Xlsx sheet(String value) {
            return new Xlsx(value, header, headerRow);
        }

        public Xlsx headerRow(int value) {
            return new Xlsx(sheet, header, value);
        }

        @Override
        public ImportFormat adjusted(Options o) {
            return new Xlsx(o.sheet() == null ? sheet : o.sheet(), o.header() == null ? header : o.header(),
                o.headerRow() == null ? headerRow : o.headerRow());
        }

        @Override
        public boolean adjustable() {
            return true;
        }
    }

    /**
     * Records of an XML document. Paths are element names separated by {@code /}, without namespaces; an attribute is
     * {@code @name}. DTDs are refused.
     *
     * @param recordPath the path from the root element to each record, for example
     *                   {@code Document/BkToCstmrStmt/Stmt/Ntry}
     * @param columns    column name to a path relative to the record ({@code Amt}, {@code Amt/@Ccy}); the first
     *                   occurrence counts
     * @param header     name to an absolute path of a value outside the records (a statement's balances), for
     *                   checks of the whole file
     */
    record Xml(String recordPath, Map<String, String> columns, Map<String, String> header) implements ImportFormat {
        public Xml {
            Objects.requireNonNull(recordPath, "recordPath must not be null");
            if (recordPath.isBlank() || recordPath.startsWith("/") || recordPath.endsWith("/")) {
                throw new IllegalArgumentException("recordPath must be element names separated by '/'");
            }
            columns = Map.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("An XML layout needs at least one column");
            }
            header = header == null ? Map.of() : Map.copyOf(header);
        }
    }

    /** A layout the application reads itself. */
    record Custom(String name, ImportParser parser) implements ImportFormat {
        public Custom {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(parser, "parser must not be null");
        }
    }

    /** Comma-separated UTF-8 with a header line. */
    static Csv csv() {
        return new Csv(',', '"', true, 0, StandardCharsets.UTF_8);
    }

    static FixedWidth fixedWidth(FixedWidth.Slice... columns) {
        return new FixedWidth(List.of(columns), 0, StandardCharsets.UTF_8);
    }

    /** The first worksheet with a header in row 1. */
    static Xlsx xlsx() {
        return new Xlsx(null, true, 1);
    }

    /** @param columns alternately a column name and its relative path */
    static Xml xml(String recordPath, String... columns) {
        if (columns.length % 2 != 0) {
            throw new IllegalArgumentException("columns are name and path pairs");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < columns.length; i += 2) {
            map.put(columns[i], columns[i + 1]);
        }
        return new Xml(recordPath, map, Map.of());
    }

    static Custom custom(String name, ImportParser parser) {
        return new Custom(name, parser);
    }

    /** The charsets a CSV may be read in: UTF-8 and the single-byte encodings exported by older systems. */
    List<String> CHARSETS = List.of("UTF-8", "windows-1252", "ISO-8859-1");

    static Charset charset(String name) {
        for (String allowed : CHARSETS) {
            if (allowed.equalsIgnoreCase(name)) {
                return Charset.forName(allowed);
            }
        }
        throw new IllegalArgumentException("Charset " + name + " is not one of " + CHARSETS);
    }
}
