package com.jabiz.quizbuks.operator;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * F-M12 (docs/quizbuks/plans/impl/F-M12.md §2): the lines of an operator's settlement, written once. A settlement
 * counts every fee and every part of a payment made with granted credit exactly once, and writes each it counted as a
 * line; the unique index on {@code (lineKind, sourceId, chargeId)} keeps a fee or a payment from being counted twice
 * however the settlements run (two instances at once, a settlement made up late), and shows the operator, line by
 * line, what its statement is made of.
 *
 * <ul>
 *   <li>{@value #CHARGE}: a fee counted. Every kind is written; what the operator does not share in is
 *       {@code excluded} (the whole fee for a kind outside {@code qb.operator.share.kinds}). {@code chargeId} is the
 *       fee itself, as {@code sourceId}: a unique index does not hold for a null.</li>
 *   <li>{@value #VOID}: a counted fee whose invoice was voided since, negative, at the rates it was counted at.</li>
 *   <li>{@value #GIFT}: one part of a payment made with granted credit (a part of {@code QbFeePayment}; negative when
 *       given back) that paid, or gave back, the fee {@code chargeId}.</li>
 * </ul>
 * Amounts are yen before tax and signed, but {@code giftGross} and {@code recoverGross}, which are with tax.
 */
public final class QbSettlementLines {

    public static final String LINE = "QbOperatorSettlementLine";
    /** The operator's own view of its lines (limited to its entry scope, D64). */
    public static final String OPERATOR_DATASET = "urn:jabiz:dataset:operator:" + LINE;
    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:settlement-line-kind";

    public static final String CHARGE = "CHARGE";
    public static final String VOID = "VOID";
    public static final String GIFT = "GIFT";
    public static final List<String> KINDS = List.of(CHARGE, VOID, GIFT);

    private QbSettlementLines() {}

    public static String defaultDataset() {
        return QbOperators.defaultDataset(LINE);
    }

    public static final EntityDefinition LINE_ENTITY = EntityDefinition.define(LINE, eb -> {
        eb.physicalTable("qb_operator_settlement_line_version");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:operator-settlement-line"));
        eb.field("settlementId", f -> f.physicalColumn("settlement_id").immutable(true).required(true).asText(36));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true).asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("month", f -> f.physicalColumn("month").immutable(true).required(true).asText(7));
        eb.field("lineKind", f -> f.physicalColumn("line_kind").immutable(true).required(true)
            .asCode(KIND_DICTIONARY, KINDS.toArray(String[]::new)));
        eb.field("sourceId", f -> f.physicalColumn("source_id").immutable(true).required(true).asText(36));
        eb.field("chargeId", f -> f.physicalColumn("charge_id").immutable(true).required(true).asText(36));
        eb.field("feeKind", f -> f.physicalColumn("fee_kind").immutable(true).required(true).asText(20));
        eb.field("period", f -> f.physicalColumn("period").immutable(true).required(true).asText(7));
        // The fee's period was before the settlement's month: counted late.
        eb.field("late", f -> f.physicalColumn("late").immutable(true).required(true).asBool());
        // When the fee was charged (CHARGE, VOID) or the part was paid (GIFT).
        eb.field("occurredAt", f -> f.physicalColumn("occurred_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("excluded", f -> f.physicalColumn("excluded").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("giftGross", f -> f.physicalColumn("gift_gross").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("grantSource", f -> f.physicalColumn("grant_source").immutable(true).asText(20));
        eb.field("grantId", f -> f.physicalColumn("grant_id").immutable(true).asText(36));
        eb.field("inBase", f -> f.physicalColumn("in_base").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("recoverGross", f -> f.physicalColumn("recover_gross").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("noShare", f -> f.physicalColumn("no_share").immutable(true).required(true).asBool());
        eb.field("shareRate", f -> f.physicalColumn("share_rate").immutable(true).required(true).asNumeric(5, 4));
        eb.field("recoveryRate", f -> f.physicalColumn("recovery_rate").immutable(true).required(true)
            .asNumeric(5, 4));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("month", "lineKind", "sponsorId", "feeKind", "period", "late", "amount", "excluded", "inBase",
                "recoverGross", "occurredAt")
            .filters("settlementId", "operatorId", "sponsorId", "month", "lineKind", "feeKind", "chargeId", "late")
            .sorts("occurredAt", "amount")
            .defaultSort("occurredAt", false));
    });
}
