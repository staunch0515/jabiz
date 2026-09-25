package com.jabiz.dataset;

import java.time.Duration;
import java.util.Objects;

/**
 * Access policy of a dataset. The soft-delete policy applies to the dataset's target entity.
 *
 * @param readOnly            reject all writes
 * @param softDelete          mark rows as deleted instead of removing them; deleted rows are hidden from reads
 * @param softDeleteField     logical Bool field that marks a row as deleted (required when softDelete is true)
 * @param softDeleteTimeField optional logical {@code Temporal(SYSTEM_RECORDED)} field set to the deletion time
 * @param maxQueryBatchSize   upper bound of rows returned by one query
 * @param maxWriteBatchSize   upper bound of changes accepted by one commit
 * @param queryTimeout        upper bound of query duration
 */
public record DatasetPolicy(
    boolean readOnly,
    boolean softDelete,
    String softDeleteField,
    String softDeleteTimeField,
    int maxQueryBatchSize,
    int maxWriteBatchSize,
    Duration queryTimeout
) {
    public DatasetPolicy {
        if (softDelete && (softDeleteField == null || softDeleteField.isBlank())) {
            throw new IllegalArgumentException("softDeleteField is required when softDelete is enabled");
        }
        if (!softDelete && softDeleteTimeField != null) {
            throw new IllegalArgumentException("softDeleteTimeField requires softDelete");
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
