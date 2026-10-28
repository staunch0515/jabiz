package com.jabiz.runtime.erasure;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The erasures and their purges as read-only platform entities (decision D62): {@code Erasure} (one row of
 * {@code sys_erasure}) and {@code ErasurePurge} ({@code sys_erasure_purge}). The processes write these tables
 * themselves, append-only; the datasets need {@code audit.read}, and the job that takes up unpurged erasures selects
 * through them.
 */
@Configuration
public class ErasureEntities {

    public static final String ERASURE = "Erasure";
    public static final String PURGE = "ErasurePurge";
    public static final String ERASURE_DATASET = "urn:jabiz:dataset:platform:Erasure";
    public static final String PURGE_DATASET = "urn:jabiz:dataset:platform:ErasurePurge";

    static final String READ = "audit.read";
    static final String WRITE = "audit.archive.write";

    public static final EntityDefinition ERASURE_ENTITY = EntityDefinition.define(ERASURE, eb -> {
        eb.physicalTable("sys_erasure");
        eb.primaryKey("erasureId");
        eb.field("erasureId", f -> f.physicalColumn("erasure_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:erasure"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(36));
        eb.field("erasedTime", f -> f.physicalColumn("erased_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).asText(100));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("userId", "erasedTime", "requestedBy")
            .filters("userId", "erasedTime", "requestedBy")
            .sorts("erasedTime")
            .defaultSort("erasedTime", false));
    });

    public static final EntityDefinition PURGE_ENTITY = EntityDefinition.define(PURGE, eb -> {
        eb.physicalTable("sys_erasure_purge");
        eb.primaryKey("erasureId");
        eb.field("erasureId", f -> f.physicalColumn("erasure_id").immutable(true).required(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:erasure"));
        eb.field("purgedTime", f -> f.physicalColumn("purged_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("rowCount", f -> f.physicalColumn("row_count").immutable(true).required(true).asNumeric(9, 0));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("erasureId", "purgedTime", "rowCount")
            .filters("erasureId", "purgedTime")
            .sorts("purgedTime")
            .defaultSort("purgedTime", false));
    });

    @Bean
    EntityDefinition erasureEntity() {
        return ERASURE_ENTITY;
    }

    @Bean
    EntityDefinition erasurePurgeEntity() {
        return PURGE_ENTITY;
    }

    @Bean
    DatasetDefinition erasureDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ERASURE_DATASET, ERASURE, poolRef);
    }

    @Bean
    DatasetDefinition erasurePurgeDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(PURGE_DATASET, PURGE, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String poolRef) {
        // Read-only: ACCOUNT_ERASE and ERASURE_PURGE write these tables themselves, append-only.
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(READ, WRITE)
            .policy(p -> p.readOnly(true).maxQueryBatchSize(1000))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
