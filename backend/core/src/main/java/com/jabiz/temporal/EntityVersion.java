package com.jabiz.temporal;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One stored version of a temporal entity together with its {@code op_process_item} entry.
 *
 * @param versionNo     number of the version within the entity, in recording order
 * @param effectiveFrom business time from which the version is in effect ({@code effect_start_time})
 * @param recordedAt    time the version was recorded ({@code created_time}, the operation time)
 * @param deleted       whether the version is a tombstone
 * @param state         values of the state fields ({@link com.jabiz.entity.EntityDefinition#stateFields()});
 *                      null values are kept as entries
 * @param changedFields fields the version changed ({@code op_process_item.changed_fields})
 * @param action        why the version was written
 * @param baseVersionNo version the change was based on, null for an insertion
 * @param processSeqId  operation that wrote the version
 */
public record EntityVersion(
    long versionNo,
    Instant effectiveFrom,
    Instant recordedAt,
    boolean deleted,
    Map<String, Object> state,
    Set<String> changedFields,
    VersionAction action,
    Long baseVersionNo,
    long processSeqId
) {
    public EntityVersion {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        Objects.requireNonNull(action, "action must not be null");
        // Map.copyOf rejects null values, which are legitimate state.
        state = Collections.unmodifiableMap(new LinkedHashMap<>(state));
        changedFields = Set.copyOf(changedFields);
    }

    /**
     * Fields this version contributes when a write with an earlier effective time is carried over it
     * (decision D1): a cancellation contributes none, since its content is the state that precedes the
     * version it cancels.
     */
    public Set<String> rebaseFields() {
        return action == VersionAction.CANCEL ? Set.of() : changedFields;
    }
}
