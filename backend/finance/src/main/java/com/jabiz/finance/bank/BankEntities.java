package com.jabiz.finance.bank;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.Rules;
import com.jabiz.finance.FinancePermissions;

import java.math.BigDecimal;

/**
 * The company's own bank accounts (docs/finance/00-design.md section 9, decision D3 of the F4 plan): what payment
 * runs pay from and what their files name. Only what payments need is kept now: the ledger account, the routing and
 * account numbers, the ACH company identification and the next check number; statements and reconciliation add theirs
 * in F5. The account number is masked but to holders of {@code fin.bank.read} (FIN-SC-004).
 */
public final class BankEntities {

    public static final String BANK_ACCOUNT = "FinBankAccount";
    public static final String BANK_ACCOUNT_DATASET = "urn:jabiz:dataset:default:FinBankAccount";

    public static final String BANK_CODE_PATTERN = "[A-Z0-9][A-Z0-9_-]{0,19}";

    public static final EntityDefinition BANK_ACCOUNT_ENTITY = EntityDefinition.define(BANK_ACCOUNT, eb -> {
        eb.physicalTable("fi_bank_account_version");
        eb.primaryKey("bankAccountId");
        eb.field("bankAccountId", f -> f.physicalColumn("bank_account_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:bank-account"));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20)
            .apply(Rules.pattern("FIN_BANK_CODE_FORMAT", BANK_CODE_PATTERN)));
        eb.field("bankName", f -> f.physicalColumn("bank_name").required(true).asText(100)
            .apply(Rules.notBlank("FIN_BANK_NAME_BLANK")));
        // The ledger's cash account of this bank account: a BANK control account.
        eb.field("glAccount", f -> f.physicalColumn("gl_account").required(true).asText(20));
        eb.field("currency", f -> f.physicalColumn("currency").required(true).asText(3)
            .apply(Rules.pattern("FIN_CURRENCY_CODE_FORMAT", "[A-Z]{3}")));
        eb.field("routingNumber", f -> f.physicalColumn("routing_number").required(true).asText(9)
            .apply(Rules.pattern("FIN_ROUTING_FORMAT", "[0-9]{9}")));
        eb.field("accountNumber", f -> f.physicalColumn("account_number").required(true).asText(17)
            .masked(FinancePermissions.BANK_READ, MaskStyle.LAST4));
        // The company's ACH identification and name as its bank gave them (NACHA batch header).
        eb.field("achCompanyId", f -> f.physicalColumn("ach_company_id").asText(10));
        eb.field("achCompanyName", f -> f.physicalColumn("ach_company_name").asText(16));
        eb.field("nextCheckNo", f -> f.physicalColumn("next_check_no").processOnly().asNumeric(10, 0)
            .apply(Rules.range("FIN_CHECK_NO_RANGE", BigDecimal.ONE, null)));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_fi_bank_account_code", "bankCode");
        eb.display("bankCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("bankCode", "bankName", "glAccount", "currency", "routingNumber", "accountNumber",
                "achCompanyId", "nextCheckNo", "active")
            .filters("bankCode", "glAccount", "active")
            .sorts("bankCode")
            .defaultSort("bankCode", true));
    });

    private BankEntities() {}
}
