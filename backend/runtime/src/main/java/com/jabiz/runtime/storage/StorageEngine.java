package com.jabiz.runtime.storage;

import com.jabiz.query.BoundValue;
import com.jabiz.query.PhysicalQueryPlan;
import com.jabiz.query.RawQueryPlan;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reactive contract of a physical storage engine (R2DBC, or other backends that can
 * offer the same semantics). Rows are returned as maps keyed by column name; key lookup
 * is case-insensitive.
 *
 * <p>Values are bound as they are, except that a {@link java.util.Map} or a {@link JsonText} is stored as JSON;
 * JSON columns are read back as their text. A write the append-only guard of the database rejects (decision
 * D5) fails with {@link AppendOnlyViolationException}.
 */
public interface StorageEngine {

    /** Value of {@link #insert} that stores NULL even where the column has a default. */
    Object NULL = new Object() {
        @Override
        public String toString() {
            return "NULL";
        }
    };

    /**
     * Inserts a new record; null values are omitted so that column defaults apply, {@link #NULL} is inserted as an
     * explicit NULL instead.
     * A unique index violation fails with {@link UniqueKeyViolationException}, as do the updates below.
     */
    Mono<Void> insert(String table, Map<String, Object> record);

    /**
     * Optimistic-concurrency (compare-and-set) update. Applies {@code updates} and increments
     * the version column in the same statement, but only if the stored version equals
     * {@code expectedVersion}. A null value in {@code updates} sets the column to NULL.
     *
     * @return true if a row was updated, false on version mismatch or missing row
     */
    Mono<Boolean> casUpdate(
        String table,
        String pkColumn,
        Object id,
        long expectedVersion,
        String versionColumn,
        Map<String, Object> updates
    );

    /**
     * Physically deletes the row if its stored version equals {@code expectedVersion}.
     *
     * @return true if a row was deleted
     */
    Mono<Boolean> delete(String table, String pkColumn, Object id, String versionColumn, long expectedVersion);

    /**
     * Optimistic update as {@link #casUpdate}, and the given rows inserted only when it updated a row, in one statement
     * where the engine can (ROADMAP phase 16p, T7): the audit record and the change event of the update. A row of
     * {@code dependents} holds its values as {@link #insert} takes them; JSON values as {@link JsonText} or a map.
     *
     * @return true if a row was updated (and the rows inserted)
     */
    default Mono<Boolean> casUpdate(String table, String pkColumn, Object id, long expectedVersion,
        String versionColumn, Map<String, Object> updates, List<Insert> dependents) {
        return casUpdate(table, pkColumn, id, expectedVersion, versionColumn, updates)
            .flatMap(applied -> applied
                ? Flux.fromIterable(dependents).concatMap(row -> insert(row.table(), row.record())).then(Mono.just(true))
                : Mono.just(false));
    }

    /**
     * As {@link #casUpdate(String, String, Object, long, String, Map, List)}, but within {@link #withWriteBatch} an
     * engine that holds the writes of a whole transaction (ROADMAP phase 16p-2, T9) may hold this update too and write
     * it later, with other writes: it then answers true at once, and should the update change no row when it is written,
     * the transaction (or the savepoint it runs behind) fails; once that is rolled back, {@code notApplied} gives the
     * error to report, as the caller would have reported a false answer.
     */
    default Mono<Boolean> casUpdate(String table, String pkColumn, Object id, long expectedVersion,
        String versionColumn, Map<String, Object> updates, List<Insert> dependents,
        java.util.function.Supplier<Mono<Throwable>> notApplied) {
        return casUpdate(table, pkColumn, id, expectedVersion, versionColumn, updates, dependents);
    }

    /** A row to insert into a table, as {@link #insert} takes it. */
    record Insert(String table, Map<String, Object> record) {}

    /** Executes a compiled entity query. */
    Flux<Map<String, Object>> executeQuery(PhysicalQueryPlan plan);

    /** One read of {@link #executeQueries}: a compiled plan and the columns its rows are returned with. */
    record PlanRead(PhysicalQueryPlan plan, List<String> columns) {
        public PlanRead {
            columns = List.copyOf(columns);
        }
    }

    /**
     * Several compiled entity queries, each as {@link #executeQuery} runs it, with their rows by query; an engine that
     * can sends them as one statement (ROADMAP phase 16p, T5). A row holds the read's {@code columns}.
     */
    default Mono<List<List<Map<String, Object>>>> executeQueries(List<PlanRead> reads) {
        return Flux.fromIterable(reads)
            .concatMap(read -> executeQuery(read.plan()).collectList())
            .collectList()
            .map(List::copyOf);
    }

    /** Number of rows matching the plan's condition; sorting and paging of the plan are ignored. */
    Mono<Long> count(PhysicalQueryPlan plan);

    /**
     * Runs a query the platform itself built (operation records, versions of temporal entities, advisory locks)
     * in the same transaction as the other calls of the pipeline. Identifiers in {@code sql} come from metadata
     * only and all values are bound; no row limit is added.
     */
    Flux<Map<String, Object>> select(String sql, Map<String, BoundValue> params);

    /**
     * As {@link #select(String, Map)}, for a query that only reads, whose result can depend on rows held back in the
     * transaction only through {@code tables} (decision D46 rule 1): the engine judges by them, not by the names the
     * text mentions. The versions of a temporal entity with their operation items are one such query: an item is held
     * exactly as long as its version is (both are written together), so only held versions of the entity's own table
     * matter, not held items of other entities.
     */
    default Flux<Map<String, Object>> select(String sql, Map<String, BoundValue> params, Set<String> tables) {
        return select(sql, params);
    }

    /** Executes a fully rendered SQL statement as given; list values are bound as arrays. */
    Flux<Map<String, Object>> executeRawQuery(RawQueryPlan plan);

    /**
     * Runs the given work in a single transaction of this engine: it commits when the
     * work completes and rolls back when it fails or is cancelled. All storage calls made by
     * the work take part in the transaction.
     */
    <T> Mono<T> inTransaction(Mono<T> work);

    /**
     * Runs the given work in a transaction of its own, even inside another one, which is suspended meanwhile: what it
     * writes stays when the surrounding transaction rolls back (a failed attempt to send a mail, which is retried,
     * docs/design/18-numbering-approvals-tasks.md section 5.6). It takes a second connection while the first is held.
     */
    default <T> Mono<T> inNewTransaction(Mono<T> work) {
        return Mono.error(new UnsupportedOperationException(getClass().getName() + " has no independent transactions"));
    }

    /**
     * Runs the given work, within the current transaction, behind a savepoint: if the work fails, what it wrote is
     * undone and the transaction goes on (the import runs each row this way, docs/design/20-imports.md section 5).
     * The work's error is passed on. Only valid inside {@link #inTransaction}.
     */
    <T> Mono<T> inSavepoint(Mono<T> work);

    /**
     * Runs the given work, within the current transaction, with the rows it {@linkplain #insert inserts} collected and
     * written together, as few statements as the engine can (ROADMAP phase 16p, T6; decision D46 rule 1): before any
     * statement of the work that reads or writes a table they go to, before a savepoint and when the work ends. Every
     * read of the work therefore sees what statement by statement execution would have shown it. A conflict a row
     * causes is reported as its {@link InsertConflicts} maps it, when it is written. Engines without this run the work
     * as it is.
     *
     * <p>An engine that holds the writes of a whole transaction (ROADMAP phase 16p-2, T9) holds the rows of the work in
     * the transaction's buffer instead, past the end of the work: they are written with the next statement that has to
     * be sent and reads none of their tables, or on their own before one that does, before a savepoint, before
     * {@link #flushWrites} and before the transaction commits, or fails for a reason not the database's.
     */
    default <T> Mono<T> withWriteBatch(Mono<T> work) {
        return work;
    }

    /**
     * Writes whatever the current transaction holds back now (ROADMAP phase 16p-2, T9): before work whose effects
     * reach beyond the database (a blocking step that may call another system), so that a write that is going to fail
     * fails first, as it would have when it was sent on its own. Nothing for engines that hold nothing.
     */
    default Mono<Void> flushWrites() {
        return Mono.empty();
    }

    /**
     * What the current transaction of this engine knows of the instances it wrote and read (decision D46); empty
     * outside a transaction of this engine, and for engines that keep nothing.
     */
    default Optional<TransactionState> transactionState(ContextView context) {
        return Optional.empty();
    }

    /**
     * Maps a unique key violation an insert causes to the error of the write it belongs to, in place of the
     * {@link UniqueKeyViolationException}: the declared unique constraint of an entity is a validation error, a
     * duplicate key a conflict. Taken from the Reactor context of the insert ({@link #withInsertConflicts}), so that a
     * row written later, together with others, is still reported as its own write would report it.
     */
    @FunctionalInterface
    interface InsertConflicts {
        Throwable map(UniqueKeyViolationException conflict, String table, Map<String, Object> record);
    }

    /** {@code context} with the mapping of the conflicts of the inserts made within it. */
    static Context withInsertConflicts(Context context, InsertConflicts conflicts) {
        return context.put(InsertConflicts.class, conflicts);
    }
}
