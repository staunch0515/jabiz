package com.jabiz.finance.bank;

import com.jabiz.entity.EntityDefinition;

/**
 * What the bank says (docs/finance/00-design.md section 10; FIN-BK-003, FIN-DI-001):
 * <ul>
 *   <li>{@code FinBankStatement}: one statement of a bank account, from a day to a day, with its opening and closing
 *       balances; recorded once per account and closing day, written once.</li>
 *   <li>{@code FinStatementLine}: a line of it, written once and stored once per account and line key (the bank's
 *       reference, or a hash where the bank gives none), however often and in whatever layout it is imported.</li>
 *   <li>{@code FinBankOpening}: where an account's reconciliation starts at the cutover: the statement balance that
 *       day and the book balance of the opening entry; once per account.</li>
 *   <li>{@code FinBankOpeningItem}: the legacy system's outstanding items at the cutover (a check not yet cashed),
 *       not posted (their amounts are in the opening entry) but matched to the statement like book items.</li>
 * </ul>
 * Amounts are signed as the bank sees them: deposits positive, payments negative.
 */
public final class StatementEntities {

    public static final String STATEMENT = "FinBankStatement";
    public static final String LINE = "FinStatementLine";
    public static final String OPENING = "FinBankOpening";
    public static final String OPENING_ITEM = "FinBankOpeningItem";

    public static final String STATEMENT_DATASET = "urn:jabiz:dataset:default:FinBankStatement";
    public static final String LINE_DATASET = "urn:jabiz:dataset:default:FinStatementLine";
    public static final String OPENING_DATASET = "urn:jabiz:dataset:default:FinBankOpening";
    public static final String OPENING_ITEM_DATASET = "urn:jabiz:dataset:default:FinBankOpeningItem";

    public static final EntityDefinition STATEMENT_ENTITY = EntityDefinition.define(STATEMENT, eb -> {
        eb.physicalTable("fi_bank_statement_version");
        eb.primaryKey("statementId");
        eb.field("statementId", f -> f.physicalColumn("statement_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bank-statement"));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("fromDate", f -> f.physicalColumn("from_date").immutable(true).required(true).asDate());
        eb.field("toDate", f -> f.physicalColumn("to_date").immutable(true).required(true).asDate());
        eb.field("openingBalance", f -> f.physicalColumn("opening_balance").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("closingBalance", f -> f.physicalColumn("closing_balance").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("lineCount", f -> f.physicalColumn("line_count").immutable(true).required(true).asNumeric(6, 0));
        eb.field("format", f -> f.physicalColumn("format").immutable(true).required(true)
            .asCode(BankEntities.STATEMENT_FORMATS, BankEntities.CSV, BankEntities.BAI2, BankEntities.CAMT053));
        eb.unique("uk_fi_bank_statement_day", "bankCode", "toDate");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("bankCode", "fromDate", "toDate", "openingBalance", "closingBalance", "lineCount", "format")
            .filters("bankCode", "toDate")
            .sorts("toDate", "bankCode")
            .defaultSort("toDate", false));
    });

    public static final EntityDefinition LINE_ENTITY = EntityDefinition.define(LINE, eb -> {
        eb.physicalTable("fi_statement_line_version");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:statement-line"));
        eb.field("statementId", f -> f.physicalColumn("statement_id").immutable(true).required(true)
            .asReference(STATEMENT));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("lineNo", f -> f.physicalColumn("line_no").immutable(true).required(true).asNumeric(6, 0));
        eb.field("valueDate", f -> f.physicalColumn("value_date").immutable(true).required(true).asDate());
        eb.field("bankReference", f -> f.physicalColumn("bank_reference").immutable(true).asText(60));
        eb.field("description", f -> f.physicalColumn("description").immutable(true).asText(500));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        // The bank's code of the kind of transaction (BAI2 type code, camt.053 bank transaction code), if given.
        eb.field("typeCode", f -> f.physicalColumn("type_code").immutable(true).asText(40));
        eb.field("lineKey", f -> f.physicalColumn("line_key").immutable(true).required(true).asText(100));
        eb.unique("uk_fi_statement_line_key", "bankCode", "lineKey");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("bankCode", "lineNo", "valueDate", "bankReference", "description", "amount", "typeCode")
            .filters("statementId", "bankCode", "valueDate", "bankReference", "lineKey")
            .sorts("valueDate", "lineNo")
            .defaultSort("valueDate", true));
    });

    public static final EntityDefinition OPENING_ENTITY = EntityDefinition.define(OPENING, eb -> {
        eb.physicalTable("fi_bank_opening_version");
        eb.primaryKey("openingId");
        eb.field("openingId", f -> f.physicalColumn("opening_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bank-opening"));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("cutoverDate", f -> f.physicalColumn("cutover_date").immutable(true).required(true).asDate());
        eb.field("statementBalance", f -> f.physicalColumn("statement_balance").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("bookBalance", f -> f.physicalColumn("book_balance").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("itemCount", f -> f.physicalColumn("item_count").immutable(true).required(true).asNumeric(6, 0));
        eb.unique("uk_fi_bank_opening_bank", "bankCode");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("bankCode", "cutoverDate", "statementBalance", "bookBalance", "itemCount")
            .filters("bankCode")
            .sorts("bankCode")
            .defaultSort("bankCode", true));
    });

    public static final EntityDefinition OPENING_ITEM_ENTITY = EntityDefinition.define(OPENING_ITEM, eb -> {
        eb.physicalTable("fi_bank_opening_item_version");
        eb.primaryKey("itemId");
        eb.field("itemId", f -> f.physicalColumn("item_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bank-opening-item"));
        eb.field("openingId", f -> f.physicalColumn("opening_id").immutable(true).required(true)
            .asReference(OPENING));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("itemDate", f -> f.physicalColumn("item_date").immutable(true).required(true).asDate());
        eb.field("reference", f -> f.physicalColumn("reference").immutable(true).required(true).asText(40));
        eb.field("description", f -> f.physicalColumn("description").immutable(true).asText(500));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.unique("uk_fi_bank_opening_item_ref", "bankCode", "reference");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("bankCode", "itemDate", "reference", "description", "amount")
            .filters("bankCode", "reference", "itemDate")
            .sorts("itemDate", "reference")
            .defaultSort("itemDate", true));
    });

    private StatementEntities() {}
}
