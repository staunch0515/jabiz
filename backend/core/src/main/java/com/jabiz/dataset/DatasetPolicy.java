package com.jabiz.dataset;

import java.time.Duration;
import java.util.Objects;

/**
 * Access policy of a dataset. The soft-delete policy applies to the dataset's target entity.
 *
 * @param readOnly            reject all writes
 * @param softDelete          mark rows as deleted instead of removing them; deleted rows are hidden from reads
 * @param softDeleteColumn    boolean column that marks a row as deleted (required when softDelete is true)
 * @param softDeleteTimeColumn optional timestamp column set to the deletion time
 * @param maxQueryBatchSize   upper bound of rows returned by one query
 * @param maxWriteBatchSize   upper bound of changes accepted by one commit
 * @param queryTimeout        upper bound of query duration
 * @param temporalTracking    reserved for bitemporal tracking
 */
public record DatasetPolicy(
    boolean readOnly,
    boolean softDelete,
    String softDeleteColumn,
    String softDeleteTimeColumn,
    int maxQueryBatchSize,
    int maxWriteBatchSize,
    Duration queryTimeout,
    boolean temporalTracking
) {
    public DatasetPolicy {
        if (softDelete && (softDeleteColumn == null || softDeleteColumn.isBlank())) {
            throw new IllegalArgumentException("softDeleteColumn is required when softDelete is enabled");
        }
        if (maxQueryBatchSize <= 0) {
            throw new IllegalArgumentException("maxQueryBatchSize must be positive");
        }
        if (maxWriteBatchSize <= 0) {
            throw new IllegalArgumentException("maxWriteBatchSize must be positive");
        }
        Objects.requireNonNull(queryTimeout, "queryTimeout must not be null");
    }
}
