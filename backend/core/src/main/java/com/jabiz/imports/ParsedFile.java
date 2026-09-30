package com.jabiz.imports;

import java.util.List;
import java.util.Map;

/**
 * A file as read: its columns in file order, the values outside the records, and the records (blank ones left out).
 */
public record ParsedFile(List<String> columns, Map<String, String> header, List<RawRecord> records) {

    public ParsedFile {
        columns = List.copyOf(columns);
        header = Map.copyOf(header);
        records = List.copyOf(records);
    }
}
