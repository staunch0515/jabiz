package com.jabiz.imports;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** RFC 4180 with a configurable delimiter; lines may end with CRLF or LF, blank lines are skipped. */
final class CsvParser implements ImportParser {

    private final ImportFormat.Csv format;

    CsvParser(ImportFormat.Csv format) {
        this.format = format;
    }

    @Override
    public void parse(Path file, ParseLimits limits, Sink sink) throws IOException {
        try (Reader reader = TextFiles.open(file, format.charset())) {
            Lines lines = new Lines(reader, format, limits);
            for (int i = 0; i < format.skipLines(); i++) {
                if (lines.next() == null) {
                    break;
                }
            }
            List<String> columns = null;
            int records = 0;
            for (List<String> cells; (cells = lines.next()) != null; ) {
                if (cells.stream().allMatch(String::isBlank)) {
                    continue;
                }
                if (columns == null) {
                    columns = format.header() ? header(cells, lines.line) : positions(cells.size());
                    sink.columns(columns);
                    if (format.header()) {
                        continue;
                    }
                }
                if (++records > limits.maxRecords()) {
                    throw tooMany(limits);
                }
                sink.record(record(records, "line " + lines.line, columns, cells));
            }
            if (columns == null) {
                sink.columns(List.of());
            }
        }
    }

    static ImportFileException tooMany(ParseLimits limits) {
        return new ImportFileException(ImportCodes.TOO_MANY_ROWS, null, "The file has more than "
            + limits.maxRecords() + " records", Map.of("max", limits.maxRecords()));
    }

    static List<String> positions(int count) {
        List<String> names = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            names.add(String.valueOf(i));
        }
        return names;
    }

    static List<String> header(List<String> cells, int line) {
        List<String> names = new ArrayList<>(cells.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < cells.size(); i++) {
            String name = cells.get(i).strip();
            if (name.isEmpty()) {
                name = String.valueOf(i + 1);
            }
            if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new ImportFileException("line " + line, "Column '" + name + "' appears twice in the header");
            }
            names.add(name);
        }
        return names;
    }

    static RawRecord record(int number, String location, List<String> columns, List<String> cells) {
        Map<String, String> values = new LinkedHashMap<>();
        String problem = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i < columns.size()) {
                values.put(columns.get(i), cells.get(i));
            } else if (!cells.get(i).isBlank()) {
                problem = "The record has " + cells.size() + " cells; the file has " + columns.size() + " columns";
            }
        }
        return new RawRecord(number, location, values, Set.of(), problem);
    }

    /** Reads records, each a list of cells; tracks the line a record starts on. */
    private static final class Lines {
        private final Reader reader;
        private final ImportFormat.Csv format;
        private final ParseLimits limits;
        private int nextLine = 1;
        private int line;
        private int pending = -2;

        Lines(Reader reader, ImportFormat.Csv format, ParseLimits limits) {
            this.reader = reader;
            this.format = format;
            this.limits = limits;
        }

        private int read() throws IOException {
            if (pending != -2) {
                int c = pending;
                pending = -2;
                return c;
            }
            int c = TextFiles.read(reader);
            if (c != -1 && TextFiles.forbidden(c)) {
                throw new ImportFileException("line " + nextLine, "The file contains a control character");
            }
            return c;
        }

        /** The next record's cells, or null at the end. */
        List<String> next() throws IOException {
            int c = read();
            if (c == -1) {
                return null;
            }
            line = nextLine;
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            boolean wasQuoted = false;
            while (true) {
                if (quoted) {
                    if (c == -1) {
                        throw new ImportFileException("line " + line, "A quoted cell is not closed");
                    }
                    if (c == format.quote()) {
                        int after = read();
                        if (after == format.quote()) {
                            append(cell, (char) c);
                        } else {
                            quoted = false;
                            c = after;
                            continue;
                        }
                    } else {
                        if (c == '\n') {
                            nextLine++;
                        }
                        append(cell, (char) c);
                    }
                } else if (c == -1 || c == '\n' || c == '\r') {
                    if (c == '\r') {
                        int after = read();
                        if (after != '\n') {
                            pending = after;
                        }
                    }
                    if (c != -1) {
                        nextLine++;
                    }
                    cells.add(wasQuoted ? cell.toString() : cell.toString().strip());
                    return cells;
                } else if (c == format.delimiter()) {
                    cells.add(wasQuoted ? cell.toString() : cell.toString().strip());
                    if (cells.size() > limits.maxColumns()) {
                        throw new ImportFileException("line " + line, "A record has more than "
                            + limits.maxColumns() + " cells");
                    }
                    cell.setLength(0);
                    wasQuoted = false;
                } else if (c == format.quote() && cell.toString().isBlank() && !wasQuoted) {
                    cell.setLength(0);
                    quoted = true;
                    wasQuoted = true;
                } else {
                    append(cell, (char) c);
                }
                c = read();
            }
        }

        private void append(StringBuilder cell, char c) {
            if (cell.length() >= limits.maxCellLength()) {
                throw new ImportFileException("line " + line, "A cell is longer than " + limits.maxCellLength()
                    + " characters");
            }
            cell.append(c);
        }
    }
}
