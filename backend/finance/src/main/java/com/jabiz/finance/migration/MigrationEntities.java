package com.jabiz.finance.migration;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * The migration's records (FIN-DI-003; docs/finance/00-design.md section 13): {@code FinMigrationDecision}, one
 * recorded decision on a data-quality problem of the legacy data, such as "legacy account 1199 is our 1200". A
 * decision is made, and changed, only through {@code FIN_MIGRATION_DECIDE}; who decided and when stay on it and in
 * its history, and the migration report lists them.
 */
public final class MigrationEntities {

    public static final String DECISION = "FinMigrationDecision";
    public static final String DECISION_DATASET = "urn:jabiz:dataset:default:FinMigrationDecision";
    public static final String DECISION_KINDS = "urn:jabiz:dict:finance:migration-decision-kind";

    /** A legacy account code read as one of ours. Merging duplicate customers comes with the receivables (F3). */
    public static final String ACCOUNT = "ACCOUNT";
    public static final List<String> DECISION_KIND_VALUES = List.of(ACCOUNT);

    public static final EntityDefinition DECISION_ENTITY = EntityDefinition.define(DECISION, eb -> {
        eb.physicalTable("fi_migration_decision_version");
        eb.primaryKey("decisionId");
        eb.field("decisionId", f -> f.physicalColumn("decision_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:migration-decision"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(DECISION_KINDS, DECISION_KIND_VALUES.toArray(String[]::new)));
        eb.field("legacyValue", f -> f.physicalColumn("legacy_value").immutable(true).required(true).asText(100));
        eb.field("decidedValue", f -> f.physicalColumn("decided_value").required(true).processOnly().asText(100));
        eb.field("reason", f -> f.physicalColumn("reason").required(true).processOnly().asText(500));
        eb.field("decidedBy", f -> f.physicalColumn("decided_by").required(true).processOnly().asText(100));
        eb.field("decidedAt", f -> f.physicalColumn("decided_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.unique("uk_fi_migration_decision", "kind", "legacyValue");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("kind", "legacyValue", "decidedValue", "reason", "decidedBy", "decidedAt")
            .filters("kind", "legacyValue", "decidedBy")
            .sorts("legacyValue", "decidedAt")
            .defaultSort("decidedAt", false));
    });

    private MigrationEntities() {}
}
