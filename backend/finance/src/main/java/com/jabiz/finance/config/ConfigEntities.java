package com.jabiz.finance.config;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * The configuration packages proposed in this environment (FIN-SC-005; ROADMAP F10d): the package as given, its hash
 * and differences, who proposed it and who published or withdrew it, when. Written only by {@link ConfigProcesses}.
 */
public final class ConfigEntities {

    public static final String IMPORT = "FinConfigImport";
    public static final String IMPORT_DATASET = "urn:jabiz:dataset:default:FinConfigImport";
    public static final String STATUSES = "urn:jabiz:dict:finance:config-import-status";

    /** Proposed and waiting for another person; published, its changes made; withdrawn, nothing changed. */
    public static final String PROPOSED = "PROPOSED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final List<String> STATUS_VALUES = List.of(PROPOSED, PUBLISHED, WITHDRAWN);

    /** A package's text, at most, in characters: a chart of some thousands of accounts. */
    public static final int MAX_PACKAGE = 2_000_000;

    /**
     * The configuration as the packages read it: read-only datasets of the entities that hold it, each read whole
     * (up to {@code ConfigProcesses.CAP} rows; their default datasets read at most 500 in one query).
     */
    public static final String READ_ACCOUNTS = "urn:jabiz:dataset:default:FinConfigAccount";
    public static final String READ_LEDGER_ACCOUNTS = "urn:jabiz:dataset:default:FinConfigLedgerAccount";
    public static final String READ_JURISDICTIONS = "urn:jabiz:dataset:default:FinConfigTaxJurisdiction";
    public static final String READ_RATES = "urn:jabiz:dataset:default:FinConfigTaxRate";
    public static final String READ_CODES = "urn:jabiz:dataset:default:FinConfigTaxCode";
    public static final String READ_LAYOUTS = "urn:jabiz:dataset:default:FinConfigLayout";
    public static final String READ_ROWS = "urn:jabiz:dataset:default:FinConfigLayoutRow";
    public static final String READ_SETTINGS = "urn:jabiz:dataset:default:FinConfigReportSettings";

    public static final EntityDefinition IMPORT_ENTITY = EntityDefinition.define(IMPORT, eb -> {
        eb.physicalTable("fi_config_import_version");
        eb.primaryKey("importId");
        eb.field("importId", f -> f.physicalColumn("import_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:config-import"));
        // The environment the package came from, as its exporter named it.
        eb.field("source", f -> f.physicalColumn("source").immutable(true).required(true).asText(100));
        eb.field("exportedAt", f -> f.physicalColumn("exported_at").immutable(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("packageHash", f -> f.physicalColumn("package_hash").immutable(true).required(true).asText(64));
        // Kept to be applied as proposed; out of the audit trail, which records each change it makes instead.
        eb.field("packageText", f -> f.physicalColumn("package_text").immutable(true).required(true)
            .asText(MAX_PACKAGE, true).auditMasked());
        // The differences as found when proposed, then as applied when published (JSON).
        eb.field("differences", f -> f.physicalColumn("differences").processOnly().required(true)
            .asText(MAX_PACKAGE, true));
        eb.field("changes", f -> f.physicalColumn("changes").processOnly().required(true).asNumeric(9, 0));
        eb.field("status", f -> f.physicalColumn("status").processOnly().required(true)
            .asCode(STATUSES, STATUS_VALUES.toArray(String[]::new)));
        eb.field("proposedBy", f -> f.physicalColumn("proposed_by").immutable(true).required(true).asText(100));
        eb.field("proposedAt", f -> f.physicalColumn("proposed_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("publishedBy", f -> f.physicalColumn("published_by").processOnly().asText(100));
        eb.field("publishedAt", f -> f.physicalColumn("published_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("withdrawnBy", f -> f.physicalColumn("withdrawn_by").processOnly().asText(100));
        eb.field("withdrawnAt", f -> f.physicalColumn("withdrawn_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.display("packageHash");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("source", "exportedAt", "packageHash", "changes", "status", "proposedBy", "proposedAt",
                "publishedBy", "publishedAt")
            .filters("source", "packageHash", "status", "proposedBy", "publishedBy")
            .sorts("proposedAt", "publishedAt")
            .defaultSort("proposedAt", false));
    });

    private ConfigEntities() {}
}
