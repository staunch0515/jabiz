package com.jabiz.finance.bank;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.Rules;
import com.jabiz.finance.FinancePermissions;

import java.math.BigDecimal;

/**
 * The company's own bank accounts (docs/finance/00-design.md sections 9 and 10, decision D3 of the F4 plan): what
 * payment runs pay from and what their files name, and what statements are read in (F5). Each maps to exactly one
 * ledger cash account (FIN-BK-001). The account number is masked but to holders of {@code fin.bank.read}
 * (FIN-SC-004). The bank settings ({@code FinBankSettings}, one row) name the in-transit account of transfers between
 * dates, the matching window and the age of a stale check.
 */
public final class BankEntities {

    public static final String BANK_ACCOUNT = "FinBankAccount";
    public static final String BANK_ACCOUNT_DATASET = "urn:jabiz:dataset:default:FinBankAccount";

    public static final String SETTINGS = "FinBankSettings";
    public static final String SETTINGS_DATASET = "urn:jabiz:dataset:default:FinBankSettings";
    public static final String SETTINGS_KEY = "BANK";

    public static final String BANK_CODE_PATTERN = "[A-Z0-9][A-Z0-9_-]{0,19}";

    /** The statement layouts a bank account's statements come in (FIN-BK-003). */
    public static final String STATEMENT_FORMATS = "urn:jabiz:dict:finance:statement-format";
    public static final String CSV = "CSV";
    public static final String BAI2 = "BAI2";
    public static final String CAMT053 = "CAMT053";

    public static final int DEFAULT_MATCH_WINDOW_DAYS = 3;
    public static final int DEFAULT_STALE_CHECK_DAYS = 90;

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
        // The layout its statements are imported in; CSV when not set.
        eb.field("statementFormat", f -> f.physicalColumn("statement_format").asCode(STATEMENT_FORMATS, CSV, BAI2,
            CAMT053));
        eb.unique("uk_fi_bank_account_code", "bankCode");
        // One bank account per cash account: the account's reconciliation is the bank account's (FIN-BK-001).
        eb.unique("uk_fi_bank_account_gl", "glAccount");
        eb.display("bankCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("bankCode", "bankName", "glAccount", "currency", "routingNumber", "accountNumber",
                "achCompanyId", "nextCheckNo", "statementFormat", "active")
            .filters("bankCode", "glAccount", "active")
            .sorts("bankCode")
            .defaultSort("bankCode", true));
    });

    /** The bank settings: one row, key {@code BANK}, kept by the controller. */
    public static final EntityDefinition SETTINGS_ENTITY = EntityDefinition.define(SETTINGS, eb -> {
        eb.physicalTable("fi_bank_settings_version");
        eb.primaryKey("settingsId");
        eb.field("settingsId", f -> f.physicalColumn("settings_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bank-settings"));
        eb.field("settingsKey", f -> f.physicalColumn("settings_key").immutable(true).required(true).asText(10));
        // Money sent from one account and received in another on a later day (FIN-BK-002).
        eb.field("inTransitAccount", f -> f.physicalColumn("in_transit_account").asText(20));
        // How many days a statement line and a book item may lie apart and still be proposed as a match (FIN-BK-004).
        eb.field("matchWindowDays", f -> f.physicalColumn("match_window_days").required(true).asNumeric(3, 0)
            .apply(Rules.range("FIN_BANK_WINDOW_RANGE", BigDecimal.ZERO, BigDecimal.valueOf(31))));
        // How many days a check may stay outstanding before it is followed up (FIN-BK-009).
        eb.field("staleCheckDays", f -> f.physicalColumn("stale_check_days").required(true).asNumeric(4, 0)
            .apply(Rules.range("FIN_BANK_STALE_RANGE", BigDecimal.ONE, BigDecimal.valueOf(3650))));
        eb.unique("uk_fi_bank_settings_key", "settingsKey");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("inTransitAccount", "matchWindowDays", "staleCheckDays")
            .filters("settingsKey")
            .sorts("settingsKey")
            .defaultSort("settingsKey", true));
    });

    private BankEntities() {}
}
