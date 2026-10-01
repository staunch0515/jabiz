package com.jabiz.finance.payroll;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.gl.GlEntities;

/**
 * {@code FinPayrollMapping} (FIN-DI-004): how a code of the payroll provider's file is booked — the account, the
 * side and, optionally, the department. Kept by the controller through its dataset; temporal, so the mapping a past
 * import used stays in the history. A mapping to a bank account is the controller's standing exception for payroll
 * entries to post there (FIN-GL-005); other control accounts are refused at import.
 */
public final class PayrollEntities {

    public static final String MAPPING = "FinPayrollMapping";
    public static final String MAPPING_DATASET = "urn:jabiz:dataset:default:FinPayrollMapping";

    public static final EntityDefinition MAPPING_ENTITY = EntityDefinition.define(MAPPING, eb -> {
        eb.physicalTable("fi_payroll_mapping_version");
        eb.primaryKey("mappingId");
        eb.field("mappingId", f -> f.physicalColumn("mapping_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:payroll-mapping"));
        eb.field("providerCode", f -> f.physicalColumn("provider_code").immutable(true).required(true).asText(40));
        eb.field("accountCode", f -> f.physicalColumn("account_code").required(true).asText(20));
        eb.field("side", f -> f.physicalColumn("side").required(true)
            .asCode(GlEntities.NORMAL_BALANCES, "DEBIT", "CREDIT"));
        eb.field("department", f -> f.physicalColumn("department").asText(20));
        eb.field("description", f -> f.physicalColumn("description").asText(200));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_fi_payroll_mapping_code", "providerCode");
        eb.display("providerCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("providerCode", "accountCode", "side", "department", "description", "active")
            .filters("providerCode", "accountCode", "active")
            .sorts("providerCode", "accountCode")
            .defaultSort("providerCode", true));
    });

    private PayrollEntities() {}
}
