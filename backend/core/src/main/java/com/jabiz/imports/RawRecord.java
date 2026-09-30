package com.jabiz.imports;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One record as read from the file, before conversion.
 *
 * @param number   1-based among the file's records, the row number reports use
 * @param location where it is in the file, for people ({@code line 12}, {@code Sheet1 row 12}, {@code record 3})
 * @param cells    column name to the text read; a missing cell is absent, an empty one blank
 * @param formulas columns whose value was calculated by a spreadsheet formula
 * @param problem  what is wrong with the record as a whole (more cells than columns); null when nothing
 */
public record RawRecord(int number, String location, Map<String, String> cells, Set<String> formulas,
    String problem) {

    public RawRecord {
        Objects.requireNonNull(location, "location must not be null");
        cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        formulas = Set.copyOf(formulas);
    }

    public RawRecord(int number, String location, Map<String, String> cells) {
        this(number, location, cells, Set.of(), null);
    }

    /** Whether every cell is blank; such records are skipped. */
    public boolean blank() {
        return cells.values().stream().allMatch(value -> value == null || value.isBlank());
    }
}
