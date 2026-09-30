package com.jabiz.imports;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Lines cut by position; blank lines are skipped, a short line has blank cells at its end. */
final class FixedWidthParser implements ImportParser {

    private final ImportFormat.FixedWidth format;

    FixedWidthParser(ImportFormat.FixedWidth format) {
        this.format = format;
    }

    @Override
    public void parse(Path file, ParseLimits limits, Sink sink) throws IOException {
        sink.columns(format.columns().stream().map(ImportFormat.FixedWidth.Slice::name).toList());
        try (Reader reader = TextFiles.open(file, format.charset())) {
            BufferedReader lines = new BufferedReader(reader);
            int lineNumber = 0;
            int records = 0;
            for (String line; (line = readLine(lines, lineNumber + 1, limits)) != null; ) {
                lineNumber++;
                if (lineNumber <= format.skipLines() || line.isBlank()) {
                    continue;
                }
                for (int i = 0; i < line.length(); i++) {
                    if (TextFiles.forbidden(line.charAt(i))) {
                        throw new ImportFileException("line " + lineNumber, "The file contains a control character");
                    }
                }
                if (++records > limits.maxRecords()) {
                    throw CsvParser.tooMany(limits);
                }
                Map<String, String> cells = new LinkedHashMap<>();
                for (ImportFormat.FixedWidth.Slice slice : format.columns()) {
                    int start = Math.min(slice.start() - 1, line.length());
                    int end = Math.min(start + slice.length(), line.length());
                    cells.put(slice.name(), line.substring(start, end).strip());
                }
                sink.record(new RawRecord(records, "line " + lineNumber, cells, Set.of(), null));
            }
        }
    }

    private static String readLine(BufferedReader lines, int number, ParseLimits limits) throws IOException {
        try {
            String line = lines.readLine();
            if (line != null && line.length() > limits.maxCellLength()) {
                throw new ImportFileException("line " + number, "A line is longer than " + limits.maxCellLength()
                    + " characters");
            }
            return line;
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new ImportFileException(null, "The file is not valid text in the expected encoding");
        }
    }
}
