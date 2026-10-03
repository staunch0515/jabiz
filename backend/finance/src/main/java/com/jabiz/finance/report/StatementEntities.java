package com.jabiz.finance.report;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * Statement layouts (FIN-RP-002 acceptance 2, FIN-RP-011; docs/finance/00-design.md section 14.3): which accounts make
 * each line of the balance sheet, the income statement and the statement of equity. A layout is written once per
 * version with its rows; a statement names the version it was issued with, so it is reproduced with that one.
 */
public final class StatementEntities {

    public static final String LAYOUT = "FinStatementLayout";
    public static final String LAYOUT_DATASET = "urn:jabiz:dataset:default:FinStatementLayout";
    public static final String ROW = "FinStatementLayoutRow";
    public static final String ROW_DATASET = "urn:jabiz:dataset:default:FinStatementLayoutRow";

    public static final String STATEMENTS = "urn:jabiz:dict:finance:statement";
    public static final String BALANCE_SHEET = "BALANCE_SHEET";
    public static final String INCOME_STATEMENT = "INCOME_STATEMENT";
    public static final String EQUITY = "EQUITY";
    public static final List<String> STATEMENT_VALUES = List.of(BALANCE_SHEET, INCOME_STATEMENT, EQUITY);

    public static final String ROW_KINDS = "urn:jabiz:dict:finance:statement-row-kind";
    /** A title only. */
    public static final String HEADING = "HEADING";
    /** Accounts reported on the line: every account with a balance must be on one (else unmapped). */
    public static final String LINE = "LINE";
    /** A total over accounts already on lines. */
    public static final String TOTAL = "TOTAL";
    public static final List<String> ROW_KIND_VALUES = List.of(HEADING, LINE, TOTAL);

    public static final EntityDefinition LAYOUT_ENTITY = EntityDefinition.define(LAYOUT, eb -> {
        eb.physicalTable("fi_statement_layout_version");
        eb.primaryKey("layoutId");
        eb.field("layoutId", f -> f.physicalColumn("layout_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:statement-layout"));
        eb.field("layoutCode", f -> f.physicalColumn("layout_code").immutable(true).required(true).asText(10));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).required(true).asNumeric(4, 0));
        eb.field("statement", f -> f.physicalColumn("statement").immutable(true).required(true)
            .asCode(STATEMENTS, BALANCE_SHEET, INCOME_STATEMENT, EQUITY));
        eb.field("title", f -> f.physicalColumn("title").immutable(true).required(true).asText(200));
        eb.field("publishedBy", f -> f.physicalColumn("published_by").immutable(true).required(true).asText(100));
        eb.field("publishedAt", f -> f.physicalColumn("published_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.unique("uk_fi_statement_layout_version", "layoutCode", "version");
        eb.display("layoutCode");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("layoutCode", "version", "statement", "title", "publishedBy", "publishedAt")
            .filters("layoutCode", "statement")
            .sorts("layoutCode", "version")
            .defaultSort("layoutCode", true));
    });

    public static final EntityDefinition ROW_ENTITY = EntityDefinition.define(ROW, eb -> {
        eb.physicalTable("fi_statement_layout_row_version");
        eb.primaryKey("rowId");
        eb.field("rowId", f -> f.physicalColumn("layout_row_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:statement-layout-row"));
        eb.field("layoutId", f -> f.physicalColumn("layout_id").immutable(true).required(true).asReference(LAYOUT));
        eb.field("layoutCode", f -> f.physicalColumn("layout_code").immutable(true).required(true).asText(10));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).required(true).asNumeric(4, 0));
        eb.field("seq", f -> f.physicalColumn("seq").immutable(true).required(true).asNumeric(4, 0));
        eb.field("lineCode", f -> f.physicalColumn("line_code").immutable(true).required(true).asText(30));
        eb.field("label", f -> f.physicalColumn("label").immutable(true).required(true).asText(200));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(ROW_KINDS, HEADING, LINE, TOTAL));
        // Account code ranges, compared as text: "1000-1199,1300".
        eb.field("accounts", f -> f.physicalColumn("accounts").immutable(true).asText(500));
        // 1 shows debits positive, -1 credits.
        eb.field("sign", f -> f.physicalColumn("sign").immutable(true).required(true).asNumeric(1, 0));
        eb.field("detail", f -> f.physicalColumn("detail").immutable(true).required(true).asBool());
        eb.field("omitZero", f -> f.physicalColumn("omit_zero").immutable(true).required(true).asBool());
        // What {note} in the label stands for: the balance of these accounts, unsigned.
        eb.field("noteAccounts", f -> f.physicalColumn("note_accounts").immutable(true).asText(500));
        eb.unique("uk_fi_statement_layout_row", "layoutId", "lineCode");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("layoutCode", "version", "seq", "lineCode", "label", "kind", "accounts", "sign", "detail")
            .filters("layoutCode", "version", "kind")
            .sorts("layoutCode", "version", "seq")
            .defaultSort("seq", true));
    });

    private StatementEntities() {}
}
