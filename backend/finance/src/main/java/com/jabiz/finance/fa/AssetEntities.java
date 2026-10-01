package com.jabiz.finance.fa;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.ledger.LedgerEntities;

/**
 * The fixed asset register, as little of it as payables need (F4 plan decision D4): a bill line coded to a fixed
 * asset cost account makes an asset with its cost, account, in-service date and the bill it came from (FIN-AP-007).
 * Depreciation, disposals and the asset import are F6's; the import matches an existing asset by its number.
 * Written only by the bills' posting.
 */
public final class AssetEntities {

    public static final String ASSET = "FinAsset";
    public static final String ASSET_DATASET = "urn:jabiz:dataset:default:FinAsset";
    public static final String ASSET_NUMBERS = "fin.fa.asset";

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
        eb.field("sourceBillId", f -> f.physicalColumn("source_bill_id").immutable(true)
            .asReference(com.jabiz.finance.ap.BillEntities.BILL));
        eb.field("sourceBillNo", f -> f.physicalColumn("source_bill_no").immutable(true).asText(40));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).asText(20));
        eb.field("transactionId", f -> f.physicalColumn("transaction_id").immutable(true)
            .asReference(LedgerEntities.TRANSACTION));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_fi_asset_no", "assetNo");
        eb.display("assetNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("assetNo", "description", "costAccount", "cost", "inServiceDate", "sourceBillNo", "vendorCode",
                "active")
            .filters("assetNo", "costAccount", "sourceBillId", "vendorCode", "active")
            .sorts("assetNo", "inServiceDate")
            .defaultSort("assetNo", true));
    });

    private AssetEntities() {}
}
