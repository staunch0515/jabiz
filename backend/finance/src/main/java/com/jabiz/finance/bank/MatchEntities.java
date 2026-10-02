package com.jabiz.finance.bank;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * How statement lines and book items come together (docs/finance/00-design.md section 10; FIN-BK-004…006):
 * <ul>
 *   <li>{@code FinBankMatch}: one match of one bank account, written once. Undoing it is another record (action
 *       {@code UNMATCH}) that names it, so the history keeps both, with who and when (FIN-BK-006).</li>
 *   <li>{@code FinBankMatchItem}: what a match brought together: statement lines on one side, book items on the other
 *       (a cash account's ledger transaction, or an outstanding item of the cutover), with the amount and label they
 *       had. Each item of a match carries the round of its reference in its bank account (how often it was matched
 *       there before, plus one), so two matches of one line at once cannot both be written; a transfer is matched in
 *       each of its two accounts.</li>
 *   <li>{@code FinBankEntryRule}: words of a statement line that make it an entry of its own (a fee, interest), with
 *       the account and the document prefix it takes (FIN-BK-005).</li>
 *   <li>{@code FinBankEntry}: an entry made from a statement line, written once, numbered {@code BANK-FEE-2601}.</li>
 * </ul>
 */
public final class MatchEntities {

    public static final String MATCH = "FinBankMatch";
    public static final String ITEM = "FinBankMatchItem";
    public static final String RULE = "FinBankEntryRule";
    public static final String ENTRY = "FinBankEntry";

    public static final String MATCH_DATASET = "urn:jabiz:dataset:default:FinBankMatch";
    public static final String ITEM_DATASET = "urn:jabiz:dataset:default:FinBankMatchItem";
    public static final String RULE_DATASET = "urn:jabiz:dataset:default:FinBankEntryRule";
    public static final String ENTRY_DATASET = "urn:jabiz:dataset:default:FinBankEntry";

    public static final String ACTIONS = "urn:jabiz:dict:finance:bank-match-action";
    public static final String METHODS = "urn:jabiz:dict:finance:bank-match-method";
    public static final String SIDES = "urn:jabiz:dict:finance:bank-match-side";
    public static final String KINDS = "urn:jabiz:dict:finance:bank-match-kind";
    public static final String DIRECTIONS = "urn:jabiz:dict:finance:bank-entry-direction";

    public static final String MATCHED = "MATCH";
    public static final String UNMATCHED = "UNMATCH";

    /** Proposed by matching and accepted; chosen by hand; made with an entry from the line. */
    public static final String AUTO = "AUTO";
    public static final String MANUAL = "MANUAL";
    public static final String FROM_ENTRY = "ENTRY";
    public static final List<String> METHOD_VALUES = List.of(AUTO, MANUAL, FROM_ENTRY);

    public static final String STATEMENT = "STATEMENT";
    public static final String BOOK = "BOOK";

    /** A statement line; a cash account's ledger transaction; an outstanding item at the cutover. */
    public static final String LINE = "LINE";
    public static final String LEDGER = "LEDGER";
    public static final String OPENING = "OPENING";

    /** What a rule takes: money out (fees), money in (interest earned), either. */
    public static final String PAYMENTS = "PAYMENT";
    public static final String DEPOSITS = "DEPOSIT";
    public static final String EITHER = "ANY";

    public static final EntityDefinition MATCH_ENTITY = EntityDefinition.define(MATCH, eb -> {
        eb.physicalTable("fi_bank_match_version");
        eb.primaryKey("matchId");
        eb.field("matchId", f -> f.physicalColumn("match_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bank-match"));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("action", f -> f.physicalColumn("action").immutable(true).required(true)
            .asCode(ACTIONS, MATCHED, UNMATCHED));
        eb.field("reversesMatchId", f -> f.physicalColumn("reverses_match_id").immutable(true).asReference(MATCH));
        eb.field("method", f -> f.physicalColumn("method").immutable(true).asCode(METHODS, AUTO, MANUAL, FROM_ENTRY));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).asNumeric(15, 2));
        // How sure matching was, 0 to 100, and why; for a match by hand or an undo, the person's reason.
        eb.field("confidence", f -> f.physicalColumn("confidence").immutable(true).asNumeric(3, 0));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).asText(500));
        eb.field("actor", f -> f.physicalColumn("actor").immutable(true).required(true).asText(100));
        eb.field("actionTime", f -> f.physicalColumn("action_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("bankCode", "action", "method", "amount", "confidence", "reason", "actor", "actionTime",
                "reversesMatchId")
            .filters("bankCode", "action", "reversesMatchId", "actor")
            .sorts("actionTime")
            .defaultSort("actionTime", false));
    });

    public static final EntityDefinition ITEM_ENTITY = EntityDefinition.define(ITEM, eb -> {
        eb.physicalTable("fi_bank_match_item_version");
        eb.primaryKey("itemId");
        eb.field("itemId", f -> f.physicalColumn("item_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bank-match-item"));
        eb.field("matchId", f -> f.physicalColumn("match_id").immutable(true).required(true).asReference(MATCH));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("side", f -> f.physicalColumn("side").immutable(true).required(true)
            .asCode(SIDES, STATEMENT, BOOK));
        eb.field("refKind", f -> f.physicalColumn("ref_kind").immutable(true).required(true)
            .asCode(KINDS, LINE, LEDGER, OPENING));
        eb.field("refId", f -> f.physicalColumn("ref_id").immutable(true).required(true).asText(36));
        eb.field("round", f -> f.physicalColumn("round").immutable(true).required(true).asNumeric(6, 0));
        eb.field("itemDate", f -> f.physicalColumn("item_date").immutable(true).required(true).asDate());
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        // The bank reference or the document number as it was when matched, for the history.
        eb.field("label", f -> f.physicalColumn("label").immutable(true).asText(100));
        eb.unique("uk_fi_bank_match_item_round", "bankCode", "refKind", "refId", "round");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("matchId", "side", "refKind", "refId", "itemDate", "amount", "label")
            .filters("matchId", "bankCode", "side", "refKind", "refId")
            .sorts("itemDate")
            .defaultSort("itemDate", true));
    });

    public static final EntityDefinition RULE_ENTITY = EntityDefinition.define(RULE, eb -> {
        eb.physicalTable("fi_bank_entry_rule_version");
        eb.primaryKey("ruleId");
        eb.field("ruleId", f -> f.physicalColumn("rule_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bank-entry-rule"));
        eb.field("ruleCode", f -> f.physicalColumn("rule_code").immutable(true).required(true).asText(20));
        // Words of the line's description, separated by commas: a line with any of them takes the rule.
        eb.field("keywords", f -> f.physicalColumn("keywords").required(true).asText(200));
        eb.field("direction", f -> f.physicalColumn("direction").required(true)
            .asCode(DIRECTIONS, PAYMENTS, DEPOSITS, EITHER));
        eb.field("account", f -> f.physicalColumn("account").required(true).asText(20));
        eb.field("documentPrefix", f -> f.physicalColumn("document_prefix").required(true).asText(15));
        eb.field("description", f -> f.physicalColumn("description").asText(200));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_fi_bank_entry_rule_code", "ruleCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("ruleCode", "keywords", "direction", "account", "documentPrefix", "description", "active")
            .filters("ruleCode", "active")
            .sorts("ruleCode")
            .defaultSort("ruleCode", true));
    });

    public static final EntityDefinition ENTRY_ENTITY = EntityDefinition.define(ENTRY, eb -> {
        eb.physicalTable("fi_bank_entry_version");
        eb.primaryKey("entryId");
        eb.field("entryId", f -> f.physicalColumn("entry_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bank-entry"));
        eb.field("entryNo", f -> f.physicalColumn("entry_no").immutable(true).required(true).asText(30));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true)
            .asReference(StatementEntities.LINE));
        eb.field("entryDate", f -> f.physicalColumn("entry_date").immutable(true).required(true).asDate());
        eb.field("account", f -> f.physicalColumn("account").immutable(true).required(true).asText(20));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.field("description", f -> f.physicalColumn("description").immutable(true).asText(500));
        eb.field("ruleCode", f -> f.physicalColumn("rule_code").immutable(true).asText(20));
        eb.unique("uk_fi_bank_entry_no", "entryNo");
        eb.unique("uk_fi_bank_entry_line", "lineId");
        eb.display("entryNo");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("entryNo", "bankCode", "entryDate", "account", "amount", "description", "ruleCode")
            .filters("entryNo", "bankCode", "lineId", "entryDate")
            .sorts("entryDate", "entryNo")
            .defaultSort("entryDate", false));
    });

    private MatchEntities() {}
}
