package com.jabiz.imports;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records of an XML document by path (see {@link ImportFormat.Xml}): element names without namespaces; the first
 * occurrence of a path within a record gives the value, trimmed.
 */
final class XmlParser implements ImportParser {

    private final ImportFormat.Xml format;
    private final List<String> recordPath;
    private final Map<String, String> byPath = new HashMap<>();

    XmlParser(ImportFormat.Xml format) {
        this.format = format;
        this.recordPath = List.of(format.recordPath().split("/"));
        format.columns().forEach((column, path) -> byPath.put(normalize(path), column));
    }

    private static String normalize(String path) {
        String p = path.strip();
        return p.startsWith("/") ? p.substring(1) : p;
    }

    @Override
    public void parse(Path file, ParseLimits limits, Sink sink) throws IOException {
        sink.columns(List.copyOf(format.columns().keySet()));
        Map<String, String> headerByPath = new HashMap<>();
        format.header().forEach((name, path) -> headerByPath.put(normalize(path), name));
        Map<String, String> headerSeen = new HashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = SafeXml.open(in);
            List<String> stack = new ArrayList<>();
            Map<String, String> cells = null;
            StringBuilder text = new StringBuilder();
            int records = 0;
            while (reader.hasNext()) {
                int event = SafeXml.next(reader, "record " + (records + 1));
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        stack.add(reader.getLocalName());
                        if (stack.size() > 64) {
                            throw new ImportFileException(null, "Elements are nested too deeply");
                        }
                        text.setLength(0);
                        if (cells == null && stack.equals(recordPath)) {
                            if (++records > limits.maxRecords()) {
                                throw CsvParser.tooMany(limits);
                            }
                            cells = new LinkedHashMap<>();
                        }
                        String base = cells != null ? relative(stack) : String.join("/", stack);
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String path = (base.isEmpty() ? "" : base + "/") + "@" + reader.getAttributeLocalName(i);
                            take(cells, headerByPath, headerSeen, path, reader.getAttributeValue(i));
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (text.length() + reader.getTextLength() > limits.maxCellLength()) {
                            throw new ImportFileException("record " + records, "A value is longer than "
                                + limits.maxCellLength() + " characters");
                        }
                        text.append(reader.getText());
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (cells != null && stack.equals(recordPath)) {
                            sink.record(new RawRecord(records, "record " + records, cells, Set.of(), null));
                            cells = null;
                        } else {
                            String path = cells != null ? relative(stack) : String.join("/", stack);
                            take(cells, headerByPath, headerSeen, path, text.toString());
                        }
                        text.setLength(0);
                        stack.remove(stack.size() - 1);
                    }
                    default -> { }
                }
            }
            headerSeen.forEach(sink::header);
        } catch (XMLStreamException e) {
            throw new ImportFileException(null, "The file is not well-formed XML: " + e.getMessage());
        }
    }

    private String relative(List<String> stack) {
        return String.join("/", stack.subList(recordPath.size(), stack.size()));
    }

    private void take(Map<String, String> cells, Map<String, String> headerByPath, Map<String, String> headerSeen,
        String path, String value) {
        if (cells != null) {
            String column = byPath.get(path);
            if (column != null) {
                cells.putIfAbsent(column, value.strip());
            }
        } else {
            String name = headerByPath.get(path);
            if (name != null) {
                headerSeen.putIfAbsent(name, value.strip());
            }
        }
    }
}
