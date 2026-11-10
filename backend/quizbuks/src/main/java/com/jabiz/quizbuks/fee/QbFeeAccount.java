package com.jabiz.quizbuks.fee;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * The account that pays the platform's fees (F-M1, docs/quizbuks/plans/impl/F-M.md §M1): a sponsor's fee credit
 * (platform fee Kudos, account 2190, wallet {@code FEE_CREDIT}) and their cash (account 2160, wallet {@code CASH}),
 * both changed under the sponsor's wallet lock, spent in one order: the fee credit first (the earliest to expire first,
 * granted on the same day: the earlier grant), then the cash in the order it arrived ({@code fee.FeeAllocation}).
 *
 * <ul>
 *   <li>{@value #GRANT}: one grant of fee credit, a lot (an ordinary table: its remaining amount changes): where it
 *       came from and what it stands for (unique for the sponsor), the amount and what is left, until when it may be
 *       spent, and the sponsor's operator when it was granted; what expired of it unused (voided). F-M2 grants and
 *       voids it ({@code fee.FeeCredits}); F-M1 spends it.</li>
 *   <li>{@value #CASH_LOT}: one sum of money that arrived in the cash account, a lot (an ordinary table): where it came
 *       from ({@value #CARD}, {@value #BANK}, {@value #OPERATOR}, {@value #OTHER}), the amount and what is left, when it
 *       arrived and the record it arrived with. Every movement of 2160 moves the lots with it, so that 2160 for a
 *       sponsor is always the sum of their lots' remaining amounts (the daily check {@code CASH_LOTS}).</li>
 *   <li>{@value #PAYMENT}: one part of a payment of a fee or an invoice, written once (append-only): the sub-account
 *       and the lot it was taken from, the amount, and the posting. A payment given back (an invoice voided) is a
 *       part of its own with the amount negated, so the parts always add up to what the fees were paid with.</li>
 * </ul>
 */
public final class QbFeeAccount {

    public static final String GRANT = "QbFeeCreditGrant";
    public static final String CASH_LOT = "QbCashLot";
    public static final String PAYMENT = "QbFeePayment";

    public static final String SPONSOR_GRANTS = "urn:jabiz:dataset:sponsor:" + GRANT;
    public static final String SPONSOR_CASH_LOTS = "urn:jabiz:dataset:sponsor:" + CASH_LOT;
    public static final String SPONSOR_PAYMENTS = "urn:jabiz:dataset:sponsor:" + PAYMENT;

    public static final String GRANT_SOURCE_DICTIONARY = "urn:jabiz:dict:quizbuks:fee-credit-source";
    public static final String CASH_SOURCE_DICTIONARY = "urn:jabiz:dict:quizbuks:cash-source";
    public static final String SUB_ACCOUNT_DICTIONARY = "urn:jabiz:dict:quizbuks:fee-sub-account";

    /** Where a grant of fee credit came from (F-M2). */
    public static final String ONBOARDING = "ONBOARDING";
    public static final String LIGHT = "LIGHT";
    public static final String MANUAL = "MANUAL";
    public static final String CERTIFIED_TOP_UP = "CERTIFIED_TOP_UP";
    public static final List<String> GRANT_SOURCES = List.of(ONBOARDING, LIGHT, MANUAL, CERTIFIED_TOP_UP);

    /** Where a sum of cash came from: a card top-up, a bank transfer, an operator (F-M4), anything else. */
    public static final String CARD = "CARD";
    public static final String BANK = "BANK";
    public static final String OPERATOR = "OPERATOR";
    public static final String OTHER = "OTHER";
    public static final List<String> CASH_SOURCES = List.of(CARD, BANK, OPERATOR, OTHER);

    /** The sub-accounts a payment is taken from. */
    public static final String FEE_CREDIT = "FEE_CREDIT";
    public static final String CASH = "CASH";
    public static final List<String> SUB_ACCOUNTS = List.of(FEE_CREDIT, CASH);

    /** The record a cash lot of before F-M1 stands for (the migration's one lot per sponsor). */
    public static final String MIGRATION = "MIGRATION";

    public static final int SOURCE_KEY_LENGTH = 100;

    private QbFeeAccount() {}

    /** The default (processes' and administrators' read-only) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return QbFees.defaultDataset(entity);
    }

    public static final EntityDefinition GRANT_ENTITY = EntityDefinition.define(GRANT, eb -> {
        eb.physicalTable("qb_fee_credit_grant");
        eb.primaryKey("grantId");
        eb.field("grantId", f -> f.physicalColumn("grant_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-credit-grant"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("source", f -> f.physicalColumn("source").immutable(true).required(true).processOnly()
            .asCode(GRANT_SOURCE_DICTIONARY, GRANT_SOURCES.toArray(String[]::new)));
        // What it stands for, once per sponsor: the onboarding, a lit node, a request granted by hand.
        eb.field("sourceKey", f -> f.physicalColumn("source_key").immutable(true).required(true).processOnly()
            .asText(SOURCE_KEY_LENGTH));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).processOnly().asText(36));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("remaining", f -> f.physicalColumn("remaining").required(true).processOnly().asMonetary("JPY", 0));
        eb.field("grantedAt", f -> f.physicalColumn("granted_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("expiresAt", f -> f.physicalColumn("expires_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // F-M2: what the daily job voided when the lot expired (its remaining amount then), and when; when its sponsor
        // was told it would expire (once a lot).
        eb.field("voidedAmount", f -> f.physicalColumn("voided_amount").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("voidedAt", f -> f.physicalColumn("voided_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("remindedAt", f -> f.physicalColumn("reminded_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        // F-M3: the onboarding's and its top-up's certified amount when granted (what a verification tops up to; the
        // amount itself when granted certified); none for the other sources and the lots granted before V111.
        eb.field("certifiedAmount", f -> f.physicalColumn("certified_amount").immutable(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_fee_credit_grant_source", "sponsorId", "sourceKey");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "source", "amount", "remaining", "voidedAmount", "grantedAt", "expiresAt")
            .filters("sponsorId", "source", "operatorId", "grantedAt", "expiresAt", "voidedAt")
            .sorts("expiresAt", "grantedAt")
            .defaultSort("expiresAt", true));
    });

    public static final EntityDefinition CASH_LOT_ENTITY = EntityDefinition.define(CASH_LOT, eb -> {
        eb.physicalTable("qb_cash_lot");
        eb.primaryKey("lotId");
        eb.field("lotId", f -> f.physicalColumn("lot_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:cash-lot"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("source", f -> f.physicalColumn("source").immutable(true).required(true).processOnly()
            .asCode(CASH_SOURCE_DICTIONARY, CASH_SOURCES.toArray(String[]::new)));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("remaining", f -> f.physicalColumn("remaining").required(true).processOnly().asMonetary("JPY", 0));
        eb.field("arrivedAt", f -> f.physicalColumn("arrived_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // The record it arrived with (a top-up, an exchange, an entry-fee settlement), for tracing it.
        eb.field("sourceEntity", f -> f.physicalColumn("source_entity").immutable(true).processOnly().asText(40));
        eb.field("sourceId", f -> f.physicalColumn("source_id").immutable(true).processOnly().asText(64));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "source", "amount", "remaining", "arrivedAt")
            .filters("sponsorId", "source", "arrivedAt")
            .sorts("arrivedAt")
            .defaultSort("arrivedAt", true));
    });

    public static final EntityDefinition PAYMENT_ENTITY = EntityDefinition.define(PAYMENT, eb -> {
        eb.physicalTable("qb_fee_payment_version");
        eb.primaryKey("paymentId");
        eb.field("paymentId", f -> f.physicalColumn("payment_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-payment"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        // What was paid: a charge (a prepaid one's, or one paid early) or an invoice; the posting names it as source.
        eb.field("targetEntity", f -> f.physicalColumn("target_entity").immutable(true).required(true).asText(40));
        eb.field("targetId", f -> f.physicalColumn("target_id").immutable(true).required(true).asText(36));
        eb.field("subAccount", f -> f.physicalColumn("sub_account").immutable(true).required(true)
            .asCode(SUB_ACCOUNT_DICTIONARY, SUB_ACCOUNTS.toArray(String[]::new)));
        eb.field("grantId", f -> f.physicalColumn("grant_id").immutable(true).asText(36));
        eb.field("cashLotId", f -> f.physicalColumn("cash_lot_id").immutable(true).asText(36));
        // Negative: given back (an invoice voided).
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("paidAt", f -> f.physicalColumn("paid_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("transactionId", f -> f.physicalColumn("transaction_id").immutable(true).required(true).asText(64));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "targetEntity", "targetId", "subAccount", "amount", "paidAt")
            .filters("sponsorId", "targetEntity", "targetId", "subAccount", "grantId", "cashLotId", "paidAt")
            .sorts("paidAt")
            .defaultSort("paidAt", false));
    });
}
