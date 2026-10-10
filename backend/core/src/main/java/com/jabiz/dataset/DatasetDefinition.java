package com.jabiz.dataset;

import com.jabiz.entity.ListViewDefinition;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A scoped, policy-controlled view over the storage of one target entity type (docs/design/03-dataset.md).
 * An entity may have several datasets; exactly one of them is its default, used for generic lookups.
 *
 * Dataset-level rules (table override, scope, soft delete) apply to the target entity. Other entity types
 * reached through the same dataset (reference checks) use their own default dataset.
 *
 * @param defaultView whether this is the default dataset of its target entity
 * @param listView    name of the target entity's list view that whitelists filters and sorts of queries
 * @param publicRead  what anonymous visitors may read through public templates, or null when the dataset is not
 *                    public (docs/design/15-public-access.md section 2)
 */
public record DatasetDefinition(
    String resourceId,
    String targetEntityType,
    StorageRouting storage,
    DatasetPolicy policy,
    DatasetScope scope,
    boolean defaultView,
    DatasetPermissions permissions,
    String listView,
    PublicRead publicRead
) {
    public DatasetDefinition {
        requireNotBlank(resourceId, "resourceId");
        requireNotBlank(targetEntityType, "targetEntityType");
        Objects.requireNonNull(storage, "storage must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        scope = scope == null ? DatasetScope.NONE : scope;
        permissions = permissions == null ? DatasetPermissions.UNDECLARED : permissions;
        listView = listView == null || listView.isBlank() ? ListViewDefinition.DEFAULT : listView;
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

    public boolean isDefault() {
        return defaultView;
    }

    /** True if anonymous visitors may read this dataset through public templates. */
    public boolean isPublic() {
        return publicRead != null;
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
        private DatasetScope scope = DatasetScope.NONE;
        private boolean defaultView;
        private DatasetPermissions permissions = DatasetPermissions.UNDECLARED;
        private String listView;
        private PublicRead publicRead;

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

        /** Declares the range of the dataset (docs/design/03-dataset.md section 2.2). */
        public Builder scope(Consumer<DatasetScope.Builder> c) {
            DatasetScope.Builder sb = new DatasetScope.Builder();
            c.accept(sb);
            this.scope = sb.build();
            return this;
        }

        /** Makes this the default dataset of its target entity. */
        public Builder asDefault() { this.defaultView = true; return this; }

        /** Permission codes needed to read and to write through this dataset. */
        public Builder permissions(String read, String write) {
            this.permissions = new DatasetPermissions(read, write);
            return this;
        }

        /** List view of the target entity whose whitelists apply to queries; defaults to {@code "default"}. */
        public Builder listView(String name) { this.listView = name; return this; }

        /**
         * Makes the dataset public (docs/design/15-public-access.md section 2): anonymous visitors read the whitelisted
         * fields of the rows in its fixed scope through public templates. The dataset becomes read-only and loses
         * time travel, whatever its policy says.
         */
        public Builder publicRead(Consumer<PublicRead.Builder> c) {
            PublicRead.Builder pb = new PublicRead.Builder();
            c.accept(pb);
            this.publicRead = pb.build();
            return this;
        }

        public DatasetDefinition build() {
            DatasetPolicy effective = policy;
            if (publicRead != null) {
                effective = new DatasetPolicy(true, policy.softDelete(), policy.softDeleteField(),
                    policy.softDeleteTimeField(), policy.maxQueryBatchSize(), policy.maxWriteBatchSize(),
                    policy.queryTimeout(), false, false, policy.writeMfa(), policy.writeVerifiedEmail());
            }
            return new DatasetDefinition(resourceId, targetEntityType, storage, effective, scope, defaultView,
                permissions, listView, publicRead);
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
        private String softDeleteField;
        private String softDeleteTimeField;
        private int queryBatch = 100;
        private int writeBatch = 100;
        private Duration timeout = Duration.ofSeconds(5);
        private boolean allowTimeTravel = true;
        private boolean processOnlyWrites;
        private com.jabiz.security.MfaRequirement writeMfa = com.jabiz.security.MfaRequirement.NONE;
        private boolean writeVerifiedEmail;

        public PolicyBuilder readOnly(boolean ro) { this.readOnly = ro; return this; }

        /** Deletes mark rows through the given logical Bool field instead of removing them. */
        public PolicyBuilder softDelete(String field) {
            this.softDelete = true;
            this.softDeleteField = field;
            return this;
        }

        /** Logical {@code Temporal(SYSTEM_RECORDED)} field that receives the deletion time. */
        public PolicyBuilder softDeleteTimeField(String field) { this.softDeleteTimeField = field; return this; }
        public PolicyBuilder maxQueryBatchSize(int b) { this.queryBatch = b; return this; }
        public PolicyBuilder maxWriteBatchSize(int b) { this.writeBatch = b; return this; }
        public PolicyBuilder queryTimeout(Duration d) { this.timeout = d; return this; }

        /**
         * Whether callers may read temporal entities at another point in time ({@code asOf}, {@code knownAt}) and
         * read their history; when false they see the current state only.
         */
        public PolicyBuilder allowTimeTravel(boolean allowed) { this.allowTimeTravel = allowed; return this; }

        /**
         * Only processes write through the dataset: the dataset API and the generic entity processes are refused,
         * so rules that span several instances (such as balanced ledger transactions) cannot be bypassed.
         */
        public PolicyBuilder processOnlyWrites() { this.processOnlyWrites = true; return this; }

        /**
         * Writers through the dataset API and the entity API need a recent second factor
         * (docs/design/10-security.md section 10).
         */
        public PolicyBuilder writeRequiresMfa(com.jabiz.security.MfaRequirement requirement) {
            this.writeMfa = Objects.requireNonNull(requirement, "requirement must not be null");
            return this;
        }

        /**
         * Writers through the dataset API and the entity API need a verified e-mail address
         * (docs/design/10-security.md section 15; decision D36 item 3). Reading is not governed by it.
         */
        public PolicyBuilder requiresVerifiedEmail() {
            this.writeVerifiedEmail = true;
            return this;
        }

        public DatasetPolicy build() {
            return new DatasetPolicy(readOnly, softDelete, softDeleteField, softDeleteTimeField,
                queryBatch, writeBatch, timeout, allowTimeTravel, processOnlyWrites, writeMfa, writeVerifiedEmail);
        }
    }
}
