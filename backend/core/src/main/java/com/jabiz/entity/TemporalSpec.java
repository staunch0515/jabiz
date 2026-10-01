package com.jabiz.entity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Append-only bitemporal settings of an entity (docs/design/04-temporal-append-only.md).
 *
 * <p>A temporal entity gets the system fields listed in {@link #SYSTEM_FIELDS}; their logical names are fixed,
 * their physical columns may be mapped. Besides them every table holds {@code rowIdColumn}, one identity per
 * version row, which is never exposed.
 *
 * @param allowScheduled whether versions may take effect after the time of the operation that writes them
 * @param rowIdColumn    physical column of the per-version row identity
 * @param writeOnce      every instance has exactly one version (decision D29): it is inserted and never updated,
 *                       deleted or reverted, so reads take its version without picking the latest one
 */
public record TemporalSpec(boolean allowScheduled, String rowIdColumn, boolean writeOnce) {

    public static final String VERSION_NO = "versionNo";
    public static final String EFFECT_START_TIME = "effectStartTime";
    public static final String CREATED_TIME = "createdTime";
    public static final String PROCESS_SEQ_ID = "processSeqId";
    public static final String DELETED = "deleted";

    /** Logical names of the system fields, in the order they are added to the definition. */
    public static final List<String> SYSTEM_FIELDS =
        List.of(VERSION_NO, EFFECT_START_TIME, CREATED_TIME, PROCESS_SEQ_ID, DELETED);

    /** Default physical column of each system field. */
    public static final Map<String, String> DEFAULT_COLUMNS = Map.of(
        VERSION_NO, "version_no",
        EFFECT_START_TIME, "effect_start_time",
        CREATED_TIME, "created_time",
        PROCESS_SEQ_ID, "process_seq_id",
        DELETED, "is_deleted");

    public static final String DEFAULT_ROW_ID_COLUMN = "row_id";

    public TemporalSpec {
        Objects.requireNonNull(rowIdColumn, "rowIdColumn must not be null");
    }

    /** Settings of an entity whose instances may have many versions. */
    public TemporalSpec(boolean allowScheduled, String rowIdColumn) {
        this(allowScheduled, rowIdColumn, false);
    }

    public static boolean isSystemField(String logicalName) {
        return SYSTEM_FIELDS.contains(logicalName);
    }
}
