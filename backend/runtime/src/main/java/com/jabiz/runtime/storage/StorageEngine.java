package com.jabiz.runtime.storage;

import com.jabiz.query.BoundValue;
import com.jabiz.query.PhysicalQueryPlan;
import com.jabiz.query.RawQueryPlan;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

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

    /** Executes a compiled entity query. */
    Flux<Map<String, Object>> executeQuery(PhysicalQueryPlan plan);

    /** Number of rows matching the plan's condition; sorting and paging of the plan are ignored. */
    Mono<Long> count(PhysicalQueryPlan plan);

    /**
     * Runs a query the platform itself built (operation records, versions of temporal entities, advisory locks)
     * in the same transaction as the other calls of the pipeline. Identifiers in {@code sql} come from metadata
     * only and all values are bound; no row limit is added.
     */
    Flux<Map<String, Object>> select(String sql, Map<String, BoundValue> params);

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
}
