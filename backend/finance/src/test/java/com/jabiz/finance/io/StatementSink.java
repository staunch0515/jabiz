package com.jabiz.finance.io;

import com.jabiz.imports.ImportParser;
import com.jabiz.imports.RawRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a statement parser hands over, kept for the tests. */
final class StatementSink implements ImportParser.Sink {
    final List<String> columns = new ArrayList<>();
    final Map<String, String> header = new LinkedHashMap<>();
    final List<RawRecord> records = new ArrayList<>();

    @Override
    public void columns(List<String> names) {
        columns.addAll(names);
    }

    @Override
    public void header(String name, String value) {
        header.put(name, value);
    }

    @Override
    public void record(RawRecord record) {
        records.add(record);
    }

    List<String> cells(String column) {
        return records.stream().map(r -> r.cells().get(column)).toList();
    }
}
