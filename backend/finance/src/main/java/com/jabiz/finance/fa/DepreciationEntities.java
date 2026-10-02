package com.jabiz.finance.fa;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * What happens to assets after they are registered (docs/finance/00-design.md section 11; ROADMAP F6b): the monthly
 * depreciation runs and their lines per asset (FIN-FA-005), changes in estimate (FIN-FA-006), disposals (FIN-FA-007)
 * and the units used of assets depreciated by units of production. A run keeps its versions (posted, then perhaps
 * reversed), and its posting is found by its number among the postings; its lines, the changes and the disposals are
 * written once; the units used are corrected until their month is depreciated. Written only by their processes.
 */
public final class DepreciationEntities {

    public static final String RUN = "FinDepreciationRun";
    public static final String RUN_DATASET = "urn:jabiz:dataset:default:FinDepreciationRun";
    public static final String LINE = "FinDepreciationLine";
    public static final String LINE_DATASET = "urn:jabiz:dataset:default:FinDepreciationLine";
    public static final String CHANGE = "FinAssetChange";
    public static final String CHANGE_DATASET = "urn:jabiz:dataset:default:FinAssetChange";
    public static final String DISPOSAL = "FinAssetDisposal";
    public static final String DISPOSAL_DATASET = "urn:jabiz:dataset:default:FinAssetDisposal";
    public static final String USAGE = "FinAssetUsage";
    public static final String USAGE_DATASET = "urn:jabiz:dataset:default:FinAssetUsage";

    public static final String RUN_STATUSES = "urn:jabiz:dict:finance:depreciation-run-status";
    public static final String POSTED = "POSTED";
    public static final String REVERSED = "REVERSED";

    public static final String DISPOSAL_KINDS = "urn:jabiz:dict:finance:disposal-kind";
    public static final String SALE = "SALE";
    public static final String SCRAP = "SCRAP";
    public static final String WRITE_OFF = "WRITE_OFF";
    public static final List<String> DISPOSAL_KIND_VALUES = List.of(SALE, SCRAP, WRITE_OFF);

    public static final EntityDefinition RUN_ENTITY = EntityDefinition.define(RUN, eb -> {
        eb.physicalTable("fi_depreciation_run_version");
        eb.primaryKey("runId");
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:depreciation-run"));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        // A period is run again only after its run was reversed: the next round.
        eb.field("round", f -> f.physicalColumn("round").immutable(true).required(true).asNumeric(4, 0));
        eb.field("runNo", f -> f.physicalColumn("run_no").immutable(true).required(true).asText(20));
        eb.field("postingDate", f -> f.physicalColumn("posting_date").immutable(true).required(true).asDate());
        eb.field("total", f -> f.physicalColumn("total").immutable(true).required(true).asNumeric(15, 2));
        eb.field("assetCount", f -> f.physicalColumn("asset_count").immutable(true).required(true).asNumeric(6, 0));
        eb.field("status", f -> f.physicalColumn("status").processOnly().required(true)
            .asCode(RUN_STATUSES, POSTED, REVERSED));
        eb.field("actor", f -> f.physicalColumn("actor").immutable(true).required(true).asText(100));
        eb.field("runTime", f -> f.physicalColumn("run_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("reversedBy", f -> f.physicalColumn("reversed_by").processOnly().asText(100));
        eb.field("reason", f -> f.physicalColumn("reason").processOnly().asText(500));
        eb.unique("uk_fi_depreciation_run_round", "periodKey", "round");
        eb.display("runNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("runNo", "periodKey", "postingDate", "total", "assetCount", "status", "actor", "runTime",
                "reversedBy", "reason")
            .filters("periodKey", "status", "runNo")
            .sorts("periodKey", "runTime")
            .defaultSort("periodKey", false));
    });

    public static final EntityDefinition LINE_ENTITY = EntityDefinition.define(LINE, eb -> {
        eb.physicalTable("fi_depreciation_line_version");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:depreciation-line"));
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).asReference(RUN));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        eb.field("assetId", f -> f.physicalColumn("asset_id").immutable(true).required(true)
            .asReference(AssetEntities.ASSET));
        eb.field("assetNo", f -> f.physicalColumn("asset_no").immutable(true).required(true).asText(20));
        eb.field("classCode", f -> f.physicalColumn("class_code").immutable(true).required(true).asText(20));
        eb.field("expenseAccount", f -> f.physicalColumn("expense_account").immutable(true).required(true)
            .asText(20));
        eb.field("accumulatedAccount", f -> f.physicalColumn("accumulated_account").immutable(true).required(true)
            .asText(20));
        eb.field("department", f -> f.physicalColumn("department").immutable(true).asText(20));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        // What the asset had accumulated after this month.
        eb.field("accumulated", f -> f.physicalColumn("accumulated").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("units", f -> f.physicalColumn("units").immutable(true).asNumeric(15, 2));
        // Where the asset was before this run, for a reversal to put it back.
        eb.field("previousThrough", f -> f.physicalColumn("previous_through").immutable(true).asText(7));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("periodKey", "assetNo", "classCode", "expenseAccount", "accumulatedAccount", "department",
                "amount", "accumulated", "units")
            .filters("runId", "periodKey", "assetId", "assetNo", "classCode")
            .sorts("periodKey", "assetNo")
            .defaultSort("assetNo", true));
    });

    public static final EntityDefinition CHANGE_ENTITY = EntityDefinition.define(CHANGE, eb -> {
        eb.physicalTable("fi_asset_change_version");
        eb.primaryKey("changeId");
        eb.field("changeId", f -> f.physicalColumn("change_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:asset-change"));
        eb.field("assetId", f -> f.physicalColumn("asset_id").immutable(true).required(true)
            .asReference(AssetEntities.ASSET));
        eb.field("assetNo", f -> f.physicalColumn("asset_no").immutable(true).required(true).asText(20));
        // From this month on, prospectively (FIN-FA-006).
        eb.field("fromPeriod", f -> f.physicalColumn("from_period").immutable(true).required(true).asText(7));
        eb.field("oldLifeMonths", f -> f.physicalColumn("old_life_months").immutable(true).required(true)
            .asNumeric(4, 0));
        eb.field("newLifeMonths", f -> f.physicalColumn("new_life_months").immutable(true).required(true)
            .asNumeric(4, 0));
        eb.field("oldSalvage", f -> f.physicalColumn("old_salvage").immutable(true).required(true).asNumeric(15, 2));
        eb.field("newSalvage", f -> f.physicalColumn("new_salvage").immutable(true).required(true).asNumeric(15, 2));
        // What the asset had accumulated when the change took effect: the new terms start from it.
        eb.field("accumulatedAt", f -> f.physicalColumn("accumulated_at").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).asText(500));
        eb.field("actor", f -> f.physicalColumn("actor").immutable(true).required(true).asText(100));
        eb.field("changeTime", f -> f.physicalColumn("change_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.unique("uk_fi_asset_change_period", "assetId", "fromPeriod");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("assetNo", "fromPeriod", "oldLifeMonths", "newLifeMonths", "oldSalvage", "newSalvage",
                "accumulatedAt", "reason", "actor", "changeTime")
            .filters("assetId", "assetNo", "fromPeriod")
            .sorts("fromPeriod")
            .defaultSort("fromPeriod", false));
    });

    public static final EntityDefinition DISPOSAL_ENTITY = EntityDefinition.define(DISPOSAL, eb -> {
        eb.physicalTable("fi_asset_disposal_version");
        eb.primaryKey("disposalId");
        eb.field("disposalId", f -> f.physicalColumn("disposal_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:asset-disposal"));
        eb.field("assetId", f -> f.physicalColumn("asset_id").immutable(true).required(true)
            .asReference(AssetEntities.ASSET));
        eb.field("assetNo", f -> f.physicalColumn("asset_no").immutable(true).required(true).asText(20));
        eb.field("disposalDate", f -> f.physicalColumn("disposal_date").immutable(true).required(true).asDate());
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(DISPOSAL_KINDS, SALE, SCRAP, WRITE_OFF));
        eb.field("proceeds", f -> f.physicalColumn("proceeds").immutable(true).required(true).asNumeric(15, 2));
        eb.field("proceedsAccount", f -> f.physicalColumn("proceeds_account").immutable(true).asText(20));
        eb.field("cost", f -> f.physicalColumn("cost").immutable(true).required(true).asNumeric(15, 2));
        // All it had accumulated, the disposal month's depreciation by the convention included.
        eb.field("accumulated", f -> f.physicalColumn("accumulated").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("monthDepreciation", f -> f.physicalColumn("month_depreciation").immutable(true).required(true)
            .asNumeric(15, 2));
        // Proceeds less net book value: a gain positive, a loss negative.
        eb.field("gainLoss", f -> f.physicalColumn("gain_loss").immutable(true).required(true).asNumeric(15, 2));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).asText(500));
        eb.field("documentNo", f -> f.physicalColumn("document_no").immutable(true).required(true).asText(40));
        eb.field("actor", f -> f.physicalColumn("actor").immutable(true).required(true).asText(100));
        eb.field("disposalTime", f -> f.physicalColumn("disposal_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.unique("uk_fi_asset_disposal_asset", "assetId");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("assetNo", "disposalDate", "kind", "proceeds", "cost", "accumulated", "monthDepreciation",
                "gainLoss", "documentNo", "reason", "actor")
            .filters("assetId", "assetNo", "disposalDate", "kind")
            .sorts("disposalDate")
            .defaultSort("disposalDate", false));
    });

    public static final EntityDefinition USAGE_ENTITY = EntityDefinition.define(USAGE, eb -> {
        eb.physicalTable("fi_asset_usage_version");
        eb.primaryKey("usageId");
        eb.field("usageId", f -> f.physicalColumn("usage_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:asset-usage"));
        eb.field("assetId", f -> f.physicalColumn("asset_id").immutable(true).required(true)
            .asReference(AssetEntities.ASSET));
        eb.field("assetNo", f -> f.physicalColumn("asset_no").immutable(true).required(true).asText(20));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        eb.field("units", f -> f.physicalColumn("units").required(true).asNumeric(15, 2));
        eb.field("actor", f -> f.physicalColumn("actor").required(true).asText(100));
        eb.field("recordTime", f -> f.physicalColumn("record_time").required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.unique("uk_fi_asset_usage_period", "assetId", "periodKey");
        // Corrected until its month is depreciated; its versions keep what it was.
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("assetNo", "periodKey", "units", "actor", "recordTime")
            .filters("assetId", "assetNo", "periodKey")
            .sorts("periodKey")
            .defaultSort("periodKey", false));
    });

    private DepreciationEntities() {}
}
