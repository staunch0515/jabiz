package com.jabiz.integrity;

import java.util.Objects;

/**
 * One row as a seal holds it: its table, its key (the primary key as a JSON array) and the SHA-256 of its content.
 *
 * @param digest 64 lower-case hex characters
 */
public record SealedRow(String table, String key, String digest) implements Comparable<SealedRow> {

    public SealedRow {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(digest, "digest must not be null");
    }

    /** Seals order their rows by table, then key. */
    @Override
    public int compareTo(SealedRow other) {
        int byTable = table.compareTo(other.table);
        return byTable != 0 ? byTable : key.compareTo(other.key);
    }
}
