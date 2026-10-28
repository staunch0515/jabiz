package com.jabiz.runtime.erasure;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The requests to delete an account and their ends as read-only platform entities (docs/design/10-security.md section
 * 17.5): {@code AccountDeletion} (one row of {@code sys_account_deletion}) and {@code AccountDeletionEnd}
 * ({@code sys_account_deletion_end}). The processes write these tables themselves, append-only; administrators who
 * may erase accounts read them, and the job that carries out due requests selects through them.
 */
@Configuration
public class AccountDeletionEntities {

    public static final String DELETION = "AccountDeletion";
    public static final String END = "AccountDeletionEnd";
    public static final String DELETION_DATASET = "urn:jabiz:dataset:platform:AccountDeletion";
    public static final String END_DATASET = "urn:jabiz:dataset:platform:AccountDeletionEnd";

    public static final EntityDefinition DELETION_ENTITY = EntityDefinition.define(DELETION, eb -> {
        eb.physicalTable("sys_account_deletion");
        eb.primaryKey("requestId");
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:account-deletion"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(36));
        eb.field("requestedTime", f -> f.physicalColumn("requested_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("dueTime", f -> f.physicalColumn("due_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("channel", f -> f.physicalColumn("channel").immutable(true).required(true).asText(10));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).asText(100));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("userId", "requestedTime", "dueTime", "channel", "requestedBy")
            .filters("userId", "requestedTime", "dueTime", "channel")
            .sorts("requestedTime", "dueTime")
            .defaultSort("requestedTime", false));
    });

    public static final EntityDefinition END_ENTITY = EntityDefinition.define(END, eb -> {
        eb.physicalTable("sys_account_deletion_end");
        eb.primaryKey("requestId");
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:account-deletion"));
        eb.field("endedTime", f -> f.physicalColumn("ended_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("outcome", f -> f.physicalColumn("outcome").immutable(true).required(true).asText(10));
        eb.field("erasureId", f -> f.physicalColumn("erasure_id").immutable(true).asText(36));
        eb.field("endedBy", f -> f.physicalColumn("ended_by").immutable(true).required(true).asText(100));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("requestId", "endedTime", "outcome", "endedBy")
            .filters("requestId", "endedTime", "outcome")
            .sorts("endedTime")
            .defaultSort("endedTime", false));
    });

    @Bean
    EntityDefinition accountDeletionEntity() {
        return DELETION_ENTITY;
    }

    @Bean
    EntityDefinition accountDeletionEndEntity() {
        return END_ENTITY;
    }

    @Bean
    DatasetDefinition accountDeletionDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(DELETION_DATASET, DELETION, poolRef);
    }

    @Bean
    DatasetDefinition accountDeletionEndDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(END_DATASET, END, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String poolRef) {
        // Read-only: the deletion processes write these tables themselves, append-only.
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(ErasurePermissions.ERASE, ErasurePermissions.ERASE)
            .policy(p -> p.readOnly(true).maxQueryBatchSize(1000))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
