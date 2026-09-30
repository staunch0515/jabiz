package com.jabiz.imports;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The parser of a format, and reading a whole file into a {@link ParsedFile}. */
public final class ImportParsers {

    private ImportParsers() {}

    public static ImportParser of(ImportFormat format) {
        return switch (format) {
            case ImportFormat.Csv csv -> new CsvParser(csv);
            case ImportFormat.FixedWidth fixed -> new FixedWidthParser(fixed);
            case ImportFormat.Xlsx xlsx -> new XlsxParser(xlsx);
            case ImportFormat.Xml xml -> new XmlParser(xml);
            case ImportFormat.Custom custom -> custom.parser();
        };
    }

    /**
     * Reads the file.
     *
     * @throws ImportFileException when the file cannot be read as {@code format} or goes beyond {@code limits}
     */
    public static ParsedFile parse(ImportFormat format, Path file, ParseLimits limits) throws IOException {
        List<String> columns = new ArrayList<>();
        Map<String, String> header = new LinkedHashMap<>();
        List<RawRecord> records = new ArrayList<>();
        boolean[] named = {false};
        try {
            of(format).parse(file, limits, new ImportParser.Sink() {
                @Override
                public void columns(List<String> names) {
                    if (!named[0]) {
                        columns.addAll(names);
                        named[0] = true;
                    }
                }

                @Override
                public void header(String name, String value) {
                    header.put(name, value);
                }

                @Override
                public void record(RawRecord record) {
                    if (records.size() >= limits.maxRecords()) {
                        throw CsvParser.tooMany(limits);
                    }
                    if (!record.blank() || record.problem() != null) {
                        records.add(record);
                    }
                }
            });
        } catch (ImportFileException | IOException e) {
            throw e;
        } catch (RuntimeException e) {
            // A parser of the application failing unexpectedly still refuses the file, and says so.
            throw new ImportFileException(null, "The file could not be read: " + e.getMessage());
        }
        return new ParsedFile(columns, header, records);
    }
}
