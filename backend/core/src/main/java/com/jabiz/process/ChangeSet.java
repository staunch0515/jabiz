package com.jabiz.process;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The changes a process registers instead of writing them itself (docs/design/06-process.md section 3.1). The
 * platform commits them, in registration order, when the last in-transaction step has run (or when a
 * {@code SaveChanges} step runs), through the datasets they name; the entity's default dataset when none is named.
 * Temporal entities get new versions (docs/design/04-temporal-append-only.md).
 *
 * <p>Thread-safe: steps may run on different threads.
 */
public final class ChangeSet {

    public enum Action { INSERT, UPDATE, DELETE, CANCEL_SCHEDULED }

    /**
     * One registered change.
     *
     * @param datasetId     dataset to write through; null for the entity's default dataset
     * @param id            primary key; for an insert, null when the key is neither given nor generated
     * @param version       version the change is based on (ignored for inserts)
     * @param attributes    values by logical field name
     * @param effectiveTime temporal entities only: when the change takes effect; null for the operation time
     */
    public record Change(String datasetId, Action action, String entityType, Object id, long version,
        Map<String, Object> attributes, Instant effectiveTime) {

        public Change {
            Objects.requireNonNull(action, "action must not be null");
            requireText(entityType, "entityType");
            attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        /** Without the values, which may be personal or secret: safe in logs. */
        @Override
        public String toString() {
            return "Change[" + action + " " + entityType + " " + id + " v" + version + ", fields="
                + attributes.keySet() + "]";
        }
    }

    /** Stored state of a committed insert or update. */
    public record Saved(String entityType, Object id, long version, Map<String, Object> attributes) {

        public Saved {
            attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        /** Without the values, which may be personal or secret: safe in logs. */
        @Override
        public String toString() {
            return "Saved[" + entityType + " " + id + " v" + version + ", fields=" + attributes.keySet() + "]";
        }
    }

    /** Registers changes written through one dataset and, optionally, at one effective time. */
    public final class Target {

        private final String datasetId;
        private final Instant effectiveTime;

        private Target(String datasetId, Instant effectiveTime) {
            this.datasetId = datasetId;
            this.effectiveTime = effectiveTime;
        }

        /** Same dataset, changes taking effect at {@code time} (temporal entities; later schedules, earlier corrects). */
        public Target effectiveAt(Instant time) {
            return new Target(datasetId, Objects.requireNonNull(time, "time must not be null"));
        }

        /**
         * Registers an insert.
         *
         * @return the primary key: the one in {@code attributes}, else a generated one when the entity's key is
         *         generated, else null
         */
        public Object insert(String entityType, Map<String, Object> attributes) {
            requireText(entityType, "entityType");
            Map<String, Object> values = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
            Object id = ids.assign(entityType, values);
            add(new Change(datasetId, Action.INSERT, entityType, id, 0L, values, effectiveTime));
            return id;
        }

        public void update(String entityType, Object id, long version, Map<String, Object> attributes) {
            add(new Change(datasetId, Action.UPDATE, entityType, requireId(id), version, attributes, effectiveTime));
        }

        public void delete(String entityType, Object id, long version) {
            add(new Change(datasetId, Action.DELETE, entityType, requireId(id), version, Map.of(), effectiveTime));
        }

        /** Cancels the version scheduled at this target's effective time; {@code version} is that version's number. */
        public void cancelScheduled(String entityType, Object id, long version) {
            if (effectiveTime == null) {
                throw new IllegalStateException("Cancelling a scheduled version needs its effective time: "
                    + "use effectiveAt(...) first");
            }
            add(new Change(datasetId, Action.CANCEL_SCHEDULED, entityType, requireId(id), version, Map.of(),
                effectiveTime));
        }
    }

    private final IdAssigner ids;
    private final Target defaults = new Target(null, null);
    private final List<Change> pending = new ArrayList<>();
    private final List<Saved> saved = new ArrayList<>();

    public ChangeSet(IdAssigner ids) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    /** Changes written through the given dataset instead of the entity's default dataset. */
    public Target in(String datasetId) {
        return new Target(requireText(datasetId, "datasetId"), null);
    }

    /** Changes taking effect at {@code time} (temporal entities), through the default datasets. */
    public Target effectiveAt(Instant time) {
        return defaults.effectiveAt(time);
    }

    /** See {@link Target#insert}. */
    public Object insert(String entityType, Map<String, Object> attributes) {
        return defaults.insert(entityType, attributes);
    }

    public void update(String entityType, Object id, long version, Map<String, Object> attributes) {
        defaults.update(entityType, id, version, attributes);
    }

    public void delete(String entityType, Object id, long version) {
        defaults.delete(entityType, id, version);
    }

    /** Changes registered and not committed yet, in registration order. */
    public synchronized List<Change> pending() {
        return List.copyOf(pending);
    }

    public synchronized boolean isEmpty() {
        return pending.isEmpty();
    }

    /** States stored by the commits so far, in commit order (deleted entities are not included). */
    public synchronized List<Saved> saved() {
        return List.copyOf(saved);
    }

    /** Platform use: removes and returns the pending changes, to commit them. */
    public synchronized List<Change> drain() {
        List<Change> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    /** Platform use: records what a commit stored. */
    public synchronized void recordSaved(List<Saved> states) {
        saved.addAll(states);
    }

    private synchronized void add(Change change) {
        pending.add(change);
    }

    private static Object requireId(Object id) {
        return Objects.requireNonNull(id, "id must not be null");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
