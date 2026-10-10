package com.jabiz.dataset;

import com.jabiz.security.MfaRequirement;

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
 * @param allowTimeTravel     whether callers may read temporal entities at other points in time and read their
 *                            history (docs/design/03-dataset.md section 2.5)
 * @param processOnlyWrites   writes come only from processes ({@code ChangeSet}); the dataset API and the generic
 *                            entity processes refuse them, and operations that wrote through it cannot be reverted
 *                            (docs/design/03-dataset.md section 2.6)
 * @param writeMfa            whether writers need a recent second factor (docs/design/10-security.md section 10)
 * @param writeVerifiedEmail  whether writers need a verified e-mail address (docs/design/10-security.md section 15;
 *                            decision D36); like {@code writeMfa} it governs writes only
 */
public record DatasetPolicy(
    boolean readOnly,
    boolean softDelete,
    String softDeleteField,
    String softDeleteTimeField,
    int maxQueryBatchSize,
    int maxWriteBatchSize,
    Duration queryTimeout,
    boolean allowTimeTravel,
    boolean processOnlyWrites,
    MfaRequirement writeMfa,
    boolean writeVerifiedEmail
) {

    /** A policy whose writers need no verified e-mail address. */
    public DatasetPolicy(boolean readOnly, boolean softDelete, String softDeleteField, String softDeleteTimeField,
        int maxQueryBatchSize, int maxWriteBatchSize, Duration queryTimeout, boolean allowTimeTravel,
        boolean processOnlyWrites, MfaRequirement writeMfa) {
        this(readOnly, softDelete, softDeleteField, softDeleteTimeField, maxQueryBatchSize, maxWriteBatchSize,
            queryTimeout, allowTimeTravel, processOnlyWrites, writeMfa, false);
    }
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
        writeMfa = writeMfa == null ? MfaRequirement.NONE : writeMfa;
        if (readOnly && processOnlyWrites) {
            throw new IllegalArgumentException("A read-only dataset takes no writes, not even from processes");
        }
    }
}
