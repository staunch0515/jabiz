package com.jabiz.runtime.storage;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one database transaction of a {@link StorageEngine} knows about the instances it has written and read
 * (ROADMAP phase 16p, decision D46 rules 3 and 4): the <em>write set</em>, the instances the platform inserted in it,
 * and the <em>read set</em>, the instances it read in full through a dataset. A reference check may rely on either
 * instead of looking the target up again, and an update of a plain entity may take a read instance as its current
 * state. Any platform write of an instance removes what is known of it, and a rollback to a savepoint restores what was
 * known when the savepoint was set.
 *
 * <p>It also counts the statements the transaction sent, for the observation of a process.
 *
 * <p>Kept in the Reactor context of the transaction ({@link StorageEngine#transactionState}); never shared between
 * transactions, so nothing here outlives a commit (no cache across transactions, decision D46 rule 6).
 */
public final class TransactionState {

    /** An instance: its entity type and its key in canonical form. */
    public record Key(String entityType, Object id) {
        public Key {
            Objects.requireNonNull(entityType, "entityType must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /** What is known of an instance. */
    public sealed interface Known permits Inserted, Read {}

    /**
     * Inserted by the platform in this transaction, in effect now, with the given state (its fields by name).
     */
    public record Inserted(Map<String, Object> state) implements Known {
        public Inserted {
            state = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(state));
        }
    }

    /**
     * Read in this transaction through {@code datasetId}, within the scope resolved as {@code scope}, as it is now
     * (not at another time); {@code instance} is the whole instance as read, an {@code EntityInstance}.
     *
     * @param full whether every field of the entity was read, so that the instance is its whole current state
     */
    public record Read(String datasetId, Map<String, Object> scope, Object instance, long version, boolean full)
        implements Known {
        public Read {
            Objects.requireNonNull(datasetId, "datasetId must not be null");
            scope = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(scope));
            Objects.requireNonNull(instance, "instance must not be null");
        }
    }

    private final Map<Key, Known> known;
    private long statements;

    public TransactionState() {
        this(new HashMap<>(), 0);
    }

    private TransactionState(Map<Key, Known> known, long statements) {
        this.known = known;
        this.statements = statements;
    }

    /** Records an insertion; what was known of the instance before is replaced. */
    public synchronized void inserted(String entityType, Object id, Map<String, Object> state) {
        Key key = new Key(entityType, id);
        if (known.size() < MAX_KNOWN || known.containsKey(key)) {
            known.put(key, new Inserted(state));
        }
    }

    /**
     * Records a read. It replaces what was known: nothing wrote the instance since (or that would be forgotten), so
     * the latest read describes the same state, through the dataset it came by.
     */
    public synchronized void read(String entityType, Object id, Read read) {
        Key key = new Key(entityType, id);
        if (known.size() < MAX_KNOWN || known.containsKey(key)) {
            known.put(key, read);
        }
    }

    /**
     * Most instances a transaction keeps, read or inserted: a process that reads or writes very many rows does not hold
     * them all in memory; those past the bound are looked up again when needed, as before phase 16p.
     */
    public static final int MAX_KNOWN = 10_000;

    /**
     * Forgets everything: another transaction committed meanwhile, writing what this one cannot tell
     * ({@link StorageEngine#inNewTransaction}).
     */
    public synchronized void forgetAll() {
        known.clear();
    }

    /** Forgets the instance: the platform wrote it (updated, deleted, cancelled, reverted). */
    public synchronized void written(String entityType, Object id) {
        known.remove(new Key(entityType, id));
    }

    public synchronized Optional<Known> find(String entityType, Object id) {
        return id == null ? Optional.empty() : Optional.ofNullable(known.get(new Key(entityType, id)));
    }

    /** A copy of what is known now, to restore when a savepoint is rolled back. */
    public synchronized TransactionState snapshot() {
        return new TransactionState(new HashMap<>(known), statements);
    }

    /** Back to what {@code snapshot} knew; the statements counted meanwhile stay counted (they were sent). */
    public synchronized void restore(TransactionState snapshot) {
        known.clear();
        known.putAll(snapshot.known);
    }

    /**
     * Operation numbers drawn ahead for the sub-processes of the transaction (ROADMAP phase 16p-2, T9): with a number at
     * hand, a sub-process's {@code op_process} row is held and written with the next statement instead of on its own.
     * A rollback to a savepoint does not give them back; a number not used is a gap, as of any rolled back operation.
     */
    private final Deque<Long> reservedSequence = new ArrayDeque<>();

    /**
     * The operation numbers drawn ahead by a root operation that starts in this transaction, in the order they are to
     * be used; whatever an earlier root of the same transaction left is dropped, so that a sub-process never takes a
     * number drawn before its own root's (number order is execution order).
     */
    public synchronized void reserveSequence(Collection<Long> numbers) {
        reservedSequence.clear();
        reservedSequence.addAll(numbers);
    }

    /**
     * The next operation number drawn ahead that is greater than {@code parent} (the number of the operation that starts
     * the sub-process), if one is left; smaller ones are dropped.
     */
    public synchronized Optional<Long> takeReservedSequence(long parent) {
        Long next;
        while ((next = reservedSequence.pollFirst()) != null) {
            if (next > parent) {
                return Optional.of(next);
            }
        }
        return Optional.empty();
    }

    public synchronized void countStatement() {
        statements++;
    }

    /** Statements the transaction has sent so far through its engine. */
    public synchronized long statements() {
        return statements;
    }
}
