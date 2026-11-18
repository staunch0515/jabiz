package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.runtime.firstlaunch.FirstLaunchProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The first launch's accounts ({@code sys_first_launch_account}) and the run's events ({@code sys_first_launch_event})
 * as read-only platform entities, through which the job {@code jabiz.first-launch} selects its items
 * (docs/design/23-first-launch.md section 8). The first launch writes both tables itself, append-only; neither holds
 * an address or a name.
 */
@Configuration
public class FirstLaunchAccountEntities {

    public static final String ACCOUNT = "FirstLaunchAccount";
    public static final String EVENT = "FirstLaunchEvent";
    public static final String ACCOUNT_DATASET = "urn:jabiz:dataset:platform:FirstLaunchAccount";
    public static final String EVENT_DATASET = "urn:jabiz:dataset:platform:FirstLaunchEvent";

    public static final EntityDefinition ACCOUNT_ENTITY = EntityDefinition.define(ACCOUNT, eb -> {
        eb.physicalTable(FirstLaunchAccountStore.TABLE);
        eb.primaryKey("userId");
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:first-launch-account"));
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).asText(36));
        eb.field("position", f -> f.physicalColumn("position").immutable(true).required(true).asNumeric(9, 0));
        eb.field("roles", f -> f.physicalColumn("roles").immutable(true).required(true).asText(4000));
        eb.field("report", f -> f.physicalColumn("report").immutable(true).required(true).asBool());
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("position", "userId", "roles", "report")
            .filters("userId")
            .sorts("position")
            .defaultSort("position", true));
    });

    public static final EntityDefinition EVENT_ENTITY = EntityDefinition.define(EVENT, eb -> {
        eb.physicalTable("sys_first_launch_event");
        eb.primaryKey("eventId");
        eb.field("eventId", f -> f.physicalColumn("event_id").immutable(true).required(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:first-launch-event"));
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).asText(36));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).asText(20));
        eb.field("stepKey", f -> f.physicalColumn("step_key").immutable(true).asText(200));
        eb.field("reasonCode", f -> f.physicalColumn("reason_code").immutable(true).asText(100));
        eb.field("configHash", f -> f.physicalColumn("config_hash").immutable(true).required(true).asText(64));
        eb.field("eventTime", f -> f.physicalColumn("event_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("runId", "kind", "stepKey", "reasonCode", "eventTime")
            .filters("runId", "kind")
            .sorts("eventTime")
            .defaultSort("eventTime", false));
    });

    @Bean
    EntityDefinition firstLaunchAccountEntity() {
        return ACCOUNT_ENTITY;
    }

    @Bean
    EntityDefinition firstLaunchEventEntity() {
        return EVENT_ENTITY;
    }

    @Bean
    DatasetDefinition firstLaunchAccountDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ACCOUNT_DATASET, ACCOUNT, poolRef);
    }

    @Bean
    DatasetDefinition firstLaunchEventDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(EVENT_DATASET, EVENT, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String poolRef) {
        // Read-only: the first launch writes these tables itself, append-only.
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(FirstLaunchProcesses.PERMISSION, FirstLaunchProcesses.PERMISSION)
            .policy(p -> p.readOnly(true).maxQueryBatchSize(1000))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
