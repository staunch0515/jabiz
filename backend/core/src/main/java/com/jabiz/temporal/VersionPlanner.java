package com.jabiz.temporal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Plans the versions one write adds to a temporal entity: the written version itself, followed by the rebased
 * copies of every later version (decision D1, docs/design/09-decisions.md).
 *
 * <p>Versions are numbered after the highest number recorded so far. The copies take the effective time of
 * the version they carry over and win over it because their number is higher. A later version is carried over
 * as "previous state ⊕ the changes at its effective time" ({@link Timeline#changesAt}); a tombstone stays a
 * tombstone; a later version whose changes include a field the write also changes is a conflict, and so is a
 * later non-tombstone after a write that deletes the entity.
 * Conflicts are collected for the whole timeline so that callers can report all of them.
 */
public final class VersionPlanner {

    private VersionPlanner() {}

    /**
     * A version one write asks for.
     *
     * @param effectiveFrom    business time from which the version is in effect
     * @param state            complete state of the version
     * @param deleted          whether it is a tombstone
     * @param changedFields    fields recorded as changed ({@code op_process_item.changed_fields})
     * @param conflictFields   fields later versions must not have changed for the write to be carried over them;
     *                         null stands for all fields
     * @param action           why the version is written
     * @param baseVersionNo    version the write is based on, null for an insertion
     */
    public record Write(
        Instant effectiveFrom,
        Map<String, Object> state,
        boolean deleted,
        Set<String> changedFields,
        Set<String> conflictFields,
        VersionAction action,
        Long baseVersionNo
    ) {
        public Write {
            Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
            state = Collections.unmodifiableMap(new LinkedHashMap<>(state));
            changedFields = Set.copyOf(changedFields);
            conflictFields = conflictFields == null ? null : Set.copyOf(conflictFields);
        }

        /** A new entity; nothing can follow it, so it conflicts with everything. */
        public static Write insert(Instant effectiveFrom, Map<String, Object> state, Set<String> allFields) {
            return new Write(effectiveFrom, state, false, allFields, null, VersionAction.INSERT, null);
        }

        /** Changes {@code changes} on top of {@code base}, the version in effect at {@code effectiveFrom}. */
        public static Write update(EntityVersion base, Instant effectiveFrom, Map<String, Object> changes) {
            Map<String, Object> state = new LinkedHashMap<>(base.state());
            state.putAll(changes);
            return new Write(effectiveFrom, state, false, changes.keySet(), changes.keySet(), VersionAction.UPDATE,
                base.versionNo());
        }

        /** Tombstone on top of {@code base}, keeping its values. */
        public static Write delete(EntityVersion base, Instant effectiveFrom, Set<String> allFields) {
            return new Write(effectiveFrom, base.state(), true, allFields, null, VersionAction.DELETE,
                base.versionNo());
        }

        /**
         * Cancels the scheduled version {@code scheduled} (docs/design/04-temporal-append-only.md section 4.1):
         * at its effective time the entity returns to the state that precedes it, or ceases to exist when the
         * scheduled version was its insertion. The cancellation records the fields of the cancelled version, so
         * that reverting it can restore them, but carries nothing over later versions.
         */
        public static Write cancel(Timeline timeline, EntityVersion scheduled) {
            EntityVersion before = timeline.before(scheduled.effectiveFrom()).orElse(null);
            Map<String, Object> state = before == null ? scheduled.state() : before.state();
            boolean deleted = before == null || before.deleted();
            return new Write(scheduled.effectiveFrom(), state, deleted, scheduled.changedFields(), Set.of(),
                VersionAction.CANCEL, scheduled.versionNo());
        }

        /**
         * Restores {@code fields} of the entity to their values in {@code base} at the effective time of
         * {@code reverted} (decision D2). Without a base (the reverted version was the insertion) the entity is
         * deleted; otherwise it takes the deletion flag of the base, which brings back a deleted entity.
         *
         * @throws IllegalStateException if nothing is in effect at that time
         */
        public static Write revert(Timeline timeline, EntityVersion reverted, EntityVersion base, Set<String> fields) {
            EntityVersion current = timeline.at(reverted.effectiveFrom()).orElseThrow(() -> new IllegalStateException(
                "No version in effect at " + reverted.effectiveFrom()));
            Map<String, Object> state = new LinkedHashMap<>(current.state());
            if (base != null) {
                for (String field : fields) {
                    state.put(field, base.state().get(field));
                }
            }
            boolean deleted = base == null || base.deleted();
            return new Write(reverted.effectiveFrom(), state, deleted, fields, fields, VersionAction.REVERT,
                current.versionNo());
        }
    }

    /** A version to insert. */
    public record PlannedVersion(
        long versionNo,
        Instant effectiveFrom,
        boolean deleted,
        Map<String, Object> state,
        Set<String> changedFields,
        VersionAction action,
        Long baseVersionNo
    ) {
        public PlannedVersion {
            state = Collections.unmodifiableMap(new LinkedHashMap<>(state));
            changedFields = Set.copyOf(changedFields);
        }
    }

    /** A later version the write cannot be carried over. */
    public record Conflict(long versionNo, Instant effectiveFrom, long processSeqId, Set<String> fields) {}

    /** The versions to insert, in order, or the conflicts that forbid the write. */
    public record Plan(List<PlannedVersion> versions, List<Conflict> conflicts) {
        public Plan {
            versions = List.copyOf(versions);
            conflicts = List.copyOf(conflicts);
        }

        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }
    }

    public static Plan plan(Timeline timeline, Write write) {
        long next = timeline.maxVersionNo() + 1;
        List<PlannedVersion> planned = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();

        PlannedVersion previous = new PlannedVersion(next++, write.effectiveFrom(), write.deleted(), write.state(),
            write.changedFields(), write.action(), write.baseVersionNo());
        planned.add(previous);

        for (EntityVersion later : timeline.after(write.effectiveFrom())) {
            Set<String> contributed = timeline.changesAt(later.effectiveFrom());
            Map<String, Object> state = new LinkedHashMap<>(previous.state());
            boolean deleted;
            Set<String> recorded;
            if (later.deleted()) {
                deleted = true;
                recorded = later.changedFields();
            } else if (contributed.isEmpty()) {
                deleted = previous.deleted();
                recorded = Set.of();
            } else if (previous.deleted() || overlaps(write.conflictFields(), contributed)) {
                conflicts.add(new Conflict(later.versionNo(), later.effectiveFrom(), later.processSeqId(),
                    previous.deleted() ? contributed : overlap(write.conflictFields(), contributed)));
                continue;
            } else {
                for (String field : contributed) {
                    state.put(field, later.state().get(field));
                }
                deleted = false;
                recorded = contributed;
            }
            previous = new PlannedVersion(next++, later.effectiveFrom(), deleted, state, recorded,
                VersionAction.REBASE, later.versionNo());
            planned.add(previous);
        }
        return conflicts.isEmpty() ? new Plan(planned, List.of()) : new Plan(List.of(), conflicts);
    }

    /** Whether the write's conflict fields (null for all) meet the fields a later version changed. */
    private static boolean overlaps(Set<String> conflictFields, Set<String> changed) {
        if (conflictFields == null) {
            return !changed.isEmpty();
        }
        return changed.stream().anyMatch(conflictFields::contains);
    }

    private static Set<String> overlap(Set<String> conflictFields, Set<String> changed) {
        if (conflictFields == null) {
            return changed;
        }
        return Set.copyOf(changed.stream().filter(conflictFields::contains).toList());
    }
}
