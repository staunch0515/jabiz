package com.jabiz.finance.fx;

import com.jabiz.entity.EntityDefinition;

import java.util.List;

/**
 * Foreign currency (docs/finance/00-design.md section 12; ROADMAP F7): the settings of settlement and remeasurement
 * (F7 plan decision D2): the accounts of realized and unrealized exchange gains and losses, the rate type a
 * revaluation uses and how many days back a document may take the latest spot rate (D1). Written by
 * {@link FxSettingsProcesses} only.
 */
public final class FxEntities {

    public static final String SETTINGS = "FinFxSettings";
    public static final String SETTINGS_DATASET = "urn:jabiz:dataset:default:FinFxSettings";
    public static final String SETTINGS_KEY = "FX";

    public static final String SPOT = "SPOT";
    public static final String CLOSING = "CLOSING";
    public static final String AVERAGE = "AVERAGE";
    public static final List<String> RATE_TYPES = List.of(SPOT, CLOSING, AVERAGE);

    /** The days back a spot rate is taken from when a day has none (D1). */
    public static final int DEFAULT_TOLERANCE_DAYS = 5;

    public static final EntityDefinition SETTINGS_ENTITY = EntityDefinition.define(SETTINGS, eb -> {
        eb.physicalTable("fi_fx_settings_version");
        eb.primaryKey("settingsId");
        eb.field("settingsId", f -> f.physicalColumn("settings_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:fx-settings"));
        eb.field("settingsKey", f -> f.physicalColumn("settings_key").immutable(true).required(true).asText(10));
        // Where a settlement's difference to the carrying amount goes (FIN-FX-004): 7200 in the sample.
        eb.field("realizedAccount", f -> f.physicalColumn("realized_account").asText(20));
        // Where a period end's remeasurement goes (FIN-FX-005): 7210 in the sample.
        eb.field("unrealizedAccount", f -> f.physicalColumn("unrealized_account").asText(20));
        eb.field("revaluationRateType", f -> f.physicalColumn("revaluation_rate_type").required(true).asText(10));
        eb.field("toleranceDays", f -> f.physicalColumn("tolerance_days").required(true).asNumeric(2, 0));
        eb.unique("uk_fi_fx_settings_key", "settingsKey");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("settingsKey", "realizedAccount", "unrealizedAccount", "revaluationRateType", "toleranceDays")
            .filters("settingsKey"));
    });

    private FxEntities() {}
}
