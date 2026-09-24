package com.jabiz.dataset;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A scoped, policy-controlled view over the storage of one target entity type.
 *
 * Dataset-level rules (table override, default partition filter, soft delete) apply to the
 * target entity. Other entity types handled through the same dataset use their own
 * tables without those rules.
 *
 * @param defaultPartitionFilter logical field name -> required value, applied to every read of,
 *                               and enforced on every write to, the target entity
 */
public record DatasetDefinition(
    String resourceId,
    String targetEntityType,
    StorageRouting storage,
    DatasetPolicy policy,
    Map<String, Object> defaultPartitionFilter
) {
    public DatasetDefinition {
        requireNotBlank(resourceId, "resourceId");
        requireNotBlank(targetEntityType, "targetEntityType");
        Objects.requireNonNull(storage, "storage must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Map<String, Object> copy = new LinkedHashMap<>();
        if (defaultPartitionFilter != null) {
            defaultPartitionFilter.forEach((k, v) ->
                copy.put(k, Objects.requireNonNull(v, "partition filter value must not be null: " + k)));
        }
        defaultPartitionFilter = Collections.unmodifiableMap(copy);
    }

    public static DatasetDefinition define(String resourceId, Consumer<Builder> consumer) {
        Builder builder = new Builder(resourceId);
        consumer.accept(builder);
        return builder.build();
    }

    /** True if dataset-level rules apply to the given entity type. */
    public boolean isTarget(String entityType) {
        return targetEntityType.equals(entityType);
    }

    private static void requireNotBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
    }

    public static class Builder {
        private final String resourceId;
        private String targetEntityType;
        private StorageRouting storage;
        private DatasetPolicy policy = new PolicyBuilder().build();
        private Map<String, Object> partitionFilter = Map.of();

        public Builder(String resourceId) { this.resourceId = resourceId; }

        public Builder targetEntityType(String entityType) { this.targetEntityType = entityType; return this; }

        public Builder storage(Consumer<StorageBuilder> c) {
            StorageBuilder sb = new StorageBuilder();
            c.accept(sb);
            this.storage = sb.build();
            return this;
        }

        public Builder policy(Consumer<PolicyBuilder> c) {
            PolicyBuilder pb = new PolicyBuilder();
            c.accept(pb);
            this.policy = pb.build();
            return this;
        }

        public Builder defaultPartitionFilter(Map<String, Object> filter) { this.partitionFilter = filter; return this; }

        public DatasetDefinition build() {
            return new DatasetDefinition(resourceId, targetEntityType, storage, policy, partitionFilter);
        }
    }

    public static class StorageBuilder {
        private String driver;
        private String connectionPoolRef;
        private String physicalTableOverride;
        private String readReplicaRef;

        public StorageBuilder driver(String d) { this.driver = d; return this; }
        public StorageBuilder connectionPoolRef(String r) { this.connectionPoolRef = r; return this; }
        public StorageBuilder physicalTableOverride(String t) { this.physicalTableOverride = t; return this; }
        public StorageBuilder readReplicaRef(String r) { this.readReplicaRef = r; return this; }

        public StorageRouting build() {
            return new StorageRouting(driver, connectionPoolRef, physicalTableOverride, readReplicaRef);
        }
    }

    public static class PolicyBuilder {
        private boolean readOnly;
        private boolean softDelete;
        private String softDeleteColumn;
        private String softDeleteTimeColumn;
        private int queryBatch = 100;
        private int writeBatch = 100;
        private Duration timeout = Duration.ofSeconds(5);
        private boolean temporal;

        public PolicyBuilder readOnly(boolean ro) { this.readOnly = ro; return this; }

        public PolicyBuilder softDelete(boolean sd, String column) {
            this.softDelete = sd;
            this.softDeleteColumn = column;
            return this;
        }

        public PolicyBuilder softDeleteTimeColumn(String column) { this.softDeleteTimeColumn = column; return this; }
        public PolicyBuilder maxQueryBatchSize(int b) { this.queryBatch = b; return this; }
        public PolicyBuilder maxWriteBatchSize(int b) { this.writeBatch = b; return this; }
        public PolicyBuilder queryTimeout(Duration d) { this.timeout = d; return this; }
        public PolicyBuilder temporalTracking(boolean t) { this.temporal = t; return this; }

        public DatasetPolicy build() {
            return new DatasetPolicy(readOnly, softDelete, softDeleteColumn, softDeleteTimeColumn,
                queryBatch, writeBatch, timeout, temporal);
        }
    }
}
