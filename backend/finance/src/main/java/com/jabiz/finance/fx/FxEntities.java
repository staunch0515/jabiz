package com.jabiz.finance.fx;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

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
    public static final String RUN = "FinFxRevaluationRun";
    public static final String RUN_DATASET = "urn:jabiz:dataset:default:FinFxRevaluationRun";
    public static final String LINE = "FinFxRevaluationLine";
    public static final String LINE_DATASET = "urn:jabiz:dataset:default:FinFxRevaluationLine";

    /** The kinds of items a revaluation remeasures. */
    public static final String RECEIVABLE = "RECEIVABLE";
    public static final String PAYABLE = "PAYABLE";
    public static final String BANK = "BANK";

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

    /**
     * A period end's remeasurement (FIN-FX-005; F7 plan decision D6): one per period, posted on its last day and
     * reversed on the next. {@code runTime} is when it was computed: run as recorded then, its items and rates give
     * its lines again (D7).
     */
    public static final EntityDefinition RUN_ENTITY = EntityDefinition.define(RUN, eb -> {
        eb.physicalTable("fi_fx_revaluation_run_version");
        eb.primaryKey("runId");
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:fx-revaluation-run"));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        eb.field("runNo", f -> f.physicalColumn("run_no").immutable(true).required(true).asText(20));
        eb.field("revaluationDate", f -> f.physicalColumn("revaluation_date").immutable(true).required(true)
            .asDate());
        eb.field("reversalDate", f -> f.physicalColumn("reversal_date").immutable(true).required(true).asDate());
        eb.field("rateType", f -> f.physicalColumn("rate_type").immutable(true).required(true).asText(10));
        eb.field("toleranceDays", f -> f.physicalColumn("tolerance_days").immutable(true).required(true)
            .asNumeric(2, 0));
        // The net gain (positive) or loss.
        eb.field("total", f -> f.physicalColumn("total").immutable(true).required(true).asNumeric(15, 2));
        eb.field("lineCount", f -> f.physicalColumn("line_count").immutable(true).required(true).asNumeric(6, 0));
        eb.field("actor", f -> f.physicalColumn("actor").immutable(true).required(true).asText(100));
        eb.field("runTime", f -> f.physicalColumn("run_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.unique("uk_fi_fx_revaluation_period", "periodKey");
        eb.display("runNo");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("runNo", "periodKey", "revaluationDate", "reversalDate", "rateType", "total", "lineCount",
                "actor", "runTime")
            .filters("periodKey", "runNo")
            .sorts("periodKey", "runTime")
            .defaultSort("periodKey", false));
    });

    /** One item remeasured: signed as the books carry it (receivables and banks positive, payables negative). */
    public static final EntityDefinition LINE_ENTITY = EntityDefinition.define(LINE, eb -> {
        eb.physicalTable("fi_fx_revaluation_line_version");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:fx-revaluation-line"));
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).asReference(RUN));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).asText(10));
        eb.field("documentId", f -> f.physicalColumn("document_id").immutable(true).required(true).asText(36));
        eb.field("documentNo", f -> f.physicalColumn("document_no").immutable(true).required(true).asText(40));
        eb.field("partyCode", f -> f.physicalColumn("party_code").immutable(true).asText(20));
        eb.field("currency", f -> f.physicalColumn("currency").immutable(true).required(true).asText(3));
        eb.field("account", f -> f.physicalColumn("account").immutable(true).required(true).asText(20));
        eb.field("openAmount", f -> f.physicalColumn("open_amount").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("carryingUsd", f -> f.physicalColumn("carrying_usd").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("rateDate", f -> f.physicalColumn("rate_date").immutable(true).required(true).asDate());
        eb.field("rateType", f -> f.physicalColumn("rate_type").immutable(true).required(true).asText(10));
        eb.field("rate", f -> f.physicalColumn("rate").immutable(true).required(true).asNumeric(19, 10));
        eb.field("revaluedUsd", f -> f.physicalColumn("revalued_usd").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("difference", f -> f.physicalColumn("difference").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("periodKey", "kind", "documentNo", "partyCode", "currency", "openAmount", "carryingUsd",
                "rate", "revaluedUsd", "difference")
            .filters("runId", "periodKey", "kind", "documentId", "currency")
            .sorts("periodKey", "documentNo")
            .defaultSort("periodKey", false));
    });

    private FxEntities() {}
}
