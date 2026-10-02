package com.jabiz.finance.fa;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.ledger.LedgerEntities;

import java.util.List;

/**
 * The fixed asset register (docs/finance/00-design.md section 11; ROADMAP F6a): asset classes with their accounts and
 * defaults (FIN-FA-001), the assets (FIN-FA-002) and the asset settings. An asset comes from a bill line coded to a
 * class's cost account (F4b, FIN-AP-007), from the asset module's own acquisition, or from the register brought over
 * at the cutover; it takes its class's defaults. Its history of changes is its versions. Written only by the
 * processes ({@link AssetProcesses}, {@link AssetClassProcesses}, {@link AssetOpeningProcesses}).
 */
public final class AssetEntities {

    public static final String ASSET = "FinAsset";
    public static final String ASSET_DATASET = "urn:jabiz:dataset:default:FinAsset";
    public static final String ASSET_NUMBERS = "fin.fa.asset";

    public static final String ASSET_CLASS = "FinAssetClass";
    public static final String ASSET_CLASS_DATASET = "urn:jabiz:dataset:default:FinAssetClass";

    public static final String SETTINGS = "FinFaSettings";
    public static final String SETTINGS_DATASET = "urn:jabiz:dataset:default:FinFaSettings";
    public static final String SETTINGS_KEY = "FA";

    public static final String CLASS_CODE_PATTERN = "[A-Z0-9][A-Z0-9_-]{0,19}";

    public static final String METHODS = "urn:jabiz:dict:finance:depreciation-method";
    public static final String SL = "SL";
    public static final String DDB = "DDB";
    public static final String DB150 = "DB150";
    public static final String UOP = "UOP";
    public static final List<String> METHOD_VALUES = List.of(SL, DDB, DB150, UOP);

    public static final String CONVENTIONS = "urn:jabiz:dict:finance:depreciation-convention";
    public static final String FULL_MONTH = "FULL_MONTH";
    public static final String MID_MONTH = "MID_MONTH";
    public static final String NEXT_MONTH = "NEXT_MONTH";
    public static final List<String> CONVENTION_VALUES = List.of(FULL_MONTH, MID_MONTH, NEXT_MONTH);

    public static final String STATUSES = "urn:jabiz:dict:finance:asset-status";
    public static final String IN_SERVICE = "IN_SERVICE";
    public static final String FULLY_DEPRECIATED = "FULLY_DEPRECIATED";
    public static final String DISPOSED = "DISPOSED";
    public static final List<String> STATUS_VALUES = List.of(IN_SERVICE, FULLY_DEPRECIATED, DISPOSED);

    public static final String SOURCES = "urn:jabiz:dict:finance:asset-source";
    public static final String BILL = "BILL";
    public static final String ACQUISITION = "ACQUISITION";
    public static final String OPENING = "OPENING";
    public static final List<String> SOURCE_VALUES = List.of(BILL, ACQUISITION, OPENING);

    public static final EntityDefinition CLASS_ENTITY = EntityDefinition.define(ASSET_CLASS, eb -> {
        eb.physicalTable("fi_asset_class_version");
        eb.primaryKey("classId");
        eb.field("classId", f -> f.physicalColumn("class_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:asset-class"));
        eb.field("classCode", f -> f.physicalColumn("class_code").immutable(true).required(true).asText(20));
        eb.field("className", f -> f.physicalColumn("class_name").required(true).asText(100));
        // One class to a cost account: a bill line coded to it makes an asset of this class.
        eb.field("costAccount", f -> f.physicalColumn("cost_account").required(true).asText(20));
        eb.field("accumulatedAccount", f -> f.physicalColumn("accumulated_account").required(true).asText(20));
        eb.field("expenseAccount", f -> f.physicalColumn("expense_account").required(true).asText(20));
        eb.field("method", f -> f.physicalColumn("method").required(true).asCode(METHODS, values(METHOD_VALUES)));
        eb.field("lifeMonths", f -> f.physicalColumn("life_months").required(true).asNumeric(4, 0));
        eb.field("convention", f -> f.physicalColumn("convention").required(true)
            .asCode(CONVENTIONS, values(CONVENTION_VALUES)));
        // Below it, a purchase is an expense, not an asset (FIN-FA-001).
        eb.field("threshold", f -> f.physicalColumn("threshold").required(true).asNumeric(15, 2));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_fi_asset_class_code", "classCode");
        eb.unique("uk_fi_asset_class_cost", "costAccount");
        eb.display("classCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("classCode", "className", "costAccount", "accumulatedAccount", "expenseAccount", "method",
                "lifeMonths", "convention", "threshold", "active")
            .filters("classCode", "costAccount", "active")
            .sorts("classCode")
            .defaultSort("classCode", true));
    });

    public static final EntityDefinition SETTINGS_ENTITY = EntityDefinition.define(SETTINGS, eb -> {
        eb.physicalTable("fi_fa_settings_version");
        eb.primaryKey("settingsId");
        eb.field("settingsId", f -> f.physicalColumn("settings_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:fa-settings"));
        eb.field("settingsKey", f -> f.physicalColumn("settings_key").immutable(true).required(true).asText(10));
        // Where a disposal's gain or loss goes (FIN-FA-007): the sample chart has none, the controller adds one.
        eb.field("gainLossAccount", f -> f.physicalColumn("gain_loss_account").asText(20));
        eb.unique("uk_fi_fa_settings_key", "settingsKey");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv.columns("settingsKey", "gainLossAccount").filters("settingsKey"));
    });

    public static final EntityDefinition ASSET_ENTITY = EntityDefinition.define(ASSET, eb -> {
        eb.physicalTable("fi_asset_version");
        eb.primaryKey("assetId");
        eb.field("assetId", f -> f.physicalColumn("asset_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:asset"));
        eb.field("assetNo", f -> f.physicalColumn("asset_no").immutable(true).required(true).asText(20));
        eb.field("description", f -> f.physicalColumn("description").required(true).asText(500));
        eb.field("costAccount", f -> f.physicalColumn("cost_account").required(true).asText(20));
        eb.field("cost", f -> f.physicalColumn("cost").required(true).asNumeric(15, 2));
        eb.field("inServiceDate", f -> f.physicalColumn("in_service_date").required(true).asDate());
        eb.field("department", f -> f.physicalColumn("department").asText(20));
        eb.field("location", f -> f.physicalColumn("location").asText(20));
        eb.field("custodian", f -> f.physicalColumn("custodian").asText(100));
        eb.field("sourceBillId", f -> f.physicalColumn("source_bill_id").immutable(true)
            .asReference(com.jabiz.finance.ap.BillEntities.BILL));
        eb.field("sourceBillNo", f -> f.physicalColumn("source_bill_no").immutable(true).asText(40));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).asText(20));
        eb.field("transactionId", f -> f.physicalColumn("transaction_id").immutable(true)
            .asReference(LedgerEntities.TRANSACTION));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        // F6: how it is depreciated, taken from its class unless set otherwise; no class, no depreciation.
        eb.field("source", f -> f.physicalColumn("source").asCode(SOURCES, values(SOURCE_VALUES)));
        eb.field("classCode", f -> f.physicalColumn("class_code").asText(20));
        eb.field("method", f -> f.physicalColumn("method").asCode(METHODS, values(METHOD_VALUES)));
        eb.field("lifeMonths", f -> f.physicalColumn("life_months").asNumeric(4, 0));
        eb.field("salvage", f -> f.physicalColumn("salvage").asNumeric(15, 2));
        eb.field("convention", f -> f.physicalColumn("convention").asCode(CONVENTIONS, values(CONVENTION_VALUES)));
        // Units of production: the units the asset is expected to give over its life.
        eb.field("totalUnits", f -> f.physicalColumn("total_units").asNumeric(15, 2));
        eb.field("status", f -> f.physicalColumn("status").processOnly().asCode(STATUSES, values(STATUS_VALUES)));
        // Brought over at the cutover: what was accumulated by then, and the first month this register depreciates.
        eb.field("openingAccumulated", f -> f.physicalColumn("opening_accumulated").immutable(true)
            .asNumeric(15, 2));
        eb.field("openingPeriod", f -> f.physicalColumn("opening_period").immutable(true).asText(7));
        // The last month a depreciation run took, and what all runs took (F6b).
        eb.field("depreciatedThrough", f -> f.physicalColumn("depreciated_through").processOnly().asText(7));
        eb.field("accumulated", f -> f.physicalColumn("accumulated").processOnly().asNumeric(15, 2));
        eb.unique("uk_fi_asset_no", "assetNo");
        eb.display("assetNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("assetNo", "description", "classCode", "costAccount", "cost", "inServiceDate", "method",
                "lifeMonths", "status", "accumulated", "sourceBillNo", "location", "custodian", "active")
            .filters("assetNo", "classCode", "costAccount", "sourceBillId", "vendorCode", "status", "source",
                "active")
            .sorts("assetNo", "inServiceDate", "cost")
            .defaultSort("assetNo", true));
    });

    static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    private AssetEntities() {}
}
