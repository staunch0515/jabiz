package com.jabiz.quizbuks.setup;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code QbSetupRecord}: what {@code QB_SETUP} has ever made or found, one row per item ({@code role:QB_TAKER},
 * {@code grant:QB_TAKER:qb.play}, {@code account:1110}, {@code param:qb.ai.model}, {@code country:JP}). QB_SETUP adds
 * an item only when it has no record: a role an administrator deleted or a permission revoked stays gone, although
 * the current data no longer shows it ever existed. Written once, by QB_SETUP only.
 */
@Configuration
public class QbSetupRecords {

    public static final String RECORD = "QbSetupRecord";
    public static final String DATASET = "urn:jabiz:dataset:default:QbSetupRecord";

    public static final EntityDefinition RECORD_ENTITY = EntityDefinition.define(RECORD, eb -> {
        eb.physicalTable("qb_setup_record_version");
        eb.primaryKey("recordId");
        eb.field("recordId", f -> f.physicalColumn("record_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:setup-record"));
        eb.field("itemKey", f -> f.physicalColumn("item_key").immutable(true).required(true).asText(300));
        eb.unique("uk_qb_setup_record_item", "itemKey");
        eb.display("itemKey");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("itemKey", "effectStartTime")
            .filters("itemKey")
            .sorts("itemKey", "effectStartTime")
            .defaultSort("itemKey", true));
    });

    static String role(String code) {
        return "role:" + code;
    }

    static String grant(String role, String permission) {
        return "grant:" + role + ":" + permission;
    }

    static String account(String code) {
        return "account:" + code;
    }

    static String param(String key) {
        return "param:" + key;
    }

    static String country(String code) {
        return "country:" + code;
    }

    @Bean
    EntityDefinition qbSetupRecordEntity() {
        return RECORD_ENTITY;
    }

    @Bean
    DatasetDefinition qbSetupRecordDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(RECORD)
            .asDefault()
            .permissions(QbPermissions.SETUP, QbPermissions.SETUP)
            // A first run records every role, grant, account, parameter and country at once.
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(1000))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
