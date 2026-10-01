package com.jabiz.entity;

import java.util.HashMap;
import java.util.Map;

/** Configures {@link TemporalSpec}: {@code eb.temporal(t -> t.allowScheduled(true).column("versionNo", "ver"))}. */
public final class TemporalBuilder {
    private boolean allowScheduled = false;
    private boolean writeOnce = false;
    private String rowIdColumn = TemporalSpec.DEFAULT_ROW_ID_COLUMN;
    private final Map<String, String> columns = new HashMap<>(TemporalSpec.DEFAULT_COLUMNS);

    TemporalBuilder() {}

    /** Allows versions whose effective time lies after the time of the writing operation. */
    public TemporalBuilder allowScheduled(boolean allowed) {
        this.allowScheduled = allowed;
        return this;
    }

    /**
     * Every instance has exactly one version (decision D29): inserted, never updated, deleted or reverted, as the
     * lines of a ledger. Reads then take the version directly instead of the latest of many, and the table needs a
     * unique index on the primary key alone (checked at startup).
     */
    public TemporalBuilder writeOnce() {
        this.writeOnce = true;
        return this;
    }

    /** Maps a system field ({@link TemporalSpec#SYSTEM_FIELDS}) to a physical column. */
    public TemporalBuilder column(String systemField, String physicalColumn) {
        if (!TemporalSpec.isSystemField(systemField)) {
            throw new IllegalArgumentException("'" + systemField + "' is not a temporal system field; expected one of "
                + TemporalSpec.SYSTEM_FIELDS);
        }
        columns.put(systemField, physicalColumn);
        return this;
    }

    /** Physical column of the per-version row identity. */
    public TemporalBuilder rowIdColumn(String column) {
        this.rowIdColumn = column;
        return this;
    }

    TemporalSpec build() {
        return new TemporalSpec(allowScheduled, rowIdColumn, writeOnce);
    }

    Map<String, String> columns() {
        return columns;
    }
}
