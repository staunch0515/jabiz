package com.jabiz.runtime.storage;

import com.jabiz.query.PhysicalQueryPlan;
import com.jabiz.query.RawQueryPlan;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Reactive contract of a physical storage engine (R2DBC, or other backends that can
 * offer the same semantics). Rows are returned as maps keyed by column name; key lookup
 * is case-insensitive.
 */
public interface StorageEngine {

    /** Inserts a new record; null values are omitted so that column defaults apply. */
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

    /** Executes a fully rendered SQL statement. */
    Flux<Map<String, Object>> executeRawQuery(RawQueryPlan plan);

    /**
     * Runs the given work in a single transaction of this engine: it commits when the
     * work completes and rolls back when it fails or is cancelled. All storage calls made by
     * the work take part in the transaction.
     */
    <T> Mono<T> inTransaction(Mono<T> work);
}
