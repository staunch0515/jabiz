package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.runtime.EntityInstance;

import java.math.BigDecimal;

/**
 * A sponsor's top-up (docs/quizbuks/plans/Q6-payments.md, D-Q6-2): an ordinary table, since Stripe's notices find it
 * by values written later (the session, the payment) and its state changes within minutes; the platform's audit keeps
 * its history. Only the processes write it ({@code processOnlyWrites}), under the sponsor's wallet lock; everything but
 * the amount is {@code processOnly}. The database guards what must hold whatever a process does (V7): one top-up per
 * session, per payment and per ledger transaction, a paid top-up always with its posting, refunds never above the
 * amount.
 */
public final class QbTopUps {

    public static final String TOPUP = "QbTopUp";
    /** Event: a top-up was credited ({@code {topUpId, sponsorId, amount}}), for Q7's message and Q9's receipt. */
    public static final String PAID_EVENT = "qb.topup.paid";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:topup-status";
    /** A sponsor's own top-ups, read only. */
    public static final String SPONSOR_TOPUPS = "urn:jabiz:dataset:sponsor:" + TOPUP;

    public static final int URL_LENGTH = 2048;
    static final int STRIPE_ID_LENGTH = 255;

    public static final EntityDefinition TOPUP_ENTITY = EntityDefinition.define(TOPUP, eb -> {
        eb.physicalTable("qb_topup");
        eb.primaryKey("topUpId");
        eb.field("topUpId", f -> f.physicalColumn("topup_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:topup"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, TopUpStatus.ALL.toArray(String[]::new)));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // The checkout.
        eb.field("checkoutSessionId", f -> f.physicalColumn("checkout_session_id").processOnly()
            .asText(STRIPE_ID_LENGTH));
        eb.field("checkoutUrl", f -> f.physicalColumn("checkout_url").processOnly().asText(URL_LENGTH));
        eb.field("expiresAt", f -> f.physicalColumn("expires_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("failureCode", f -> f.physicalColumn("failure_code").processOnly().asText(100));
        // The payment.
        eb.field("paymentIntentId", f -> f.physicalColumn("payment_intent_id").processOnly()
            .asText(STRIPE_ID_LENGTH));
        eb.field("chargeId", f -> f.physicalColumn("charge_id").processOnly().asText(STRIPE_ID_LENGTH));
        eb.field("balanceTransactionId", f -> f.physicalColumn("balance_transaction_id").processOnly()
            .asText(STRIPE_ID_LENGTH));
        eb.field("livemode", f -> f.physicalColumn("livemode").processOnly().asBool());
        eb.field("paidAt", f -> f.physicalColumn("paid_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("confirmedBy", f -> f.physicalColumn("confirmed_by").processOnly().asText(64));
        eb.field("ledgerTransactionId", f -> f.physicalColumn("ledger_transaction_id").processOnly().asText(64));
        // Stripe's fee (F-04).
        eb.field("stripeFee", f -> f.physicalColumn("stripe_fee").processOnly().asMonetary("JPY", 0));
        eb.field("netAmount", f -> f.physicalColumn("net_amount").processOnly().asMonetary("JPY", 0));
        eb.field("feeTransactionId", f -> f.physicalColumn("fee_transaction_id").processOnly().asText(64));
        // What the customer paid in their own currency (F-04), when Stripe converted.
        eb.field("presentmentCurrency", f -> f.physicalColumn("presentment_currency").processOnly().asText(3));
        eb.field("presentmentAmount", f -> f.physicalColumn("presentment_amount").processOnly().asNumeric(19, 0));
        eb.field("fxRate", f -> f.physicalColumn("fx_rate").processOnly().asNumeric(20, 10));
        // Refunds and disputes (D-Q6-6): what was taken back from the wallet, and what could not be.
        eb.field("refundedAmount", f -> f.physicalColumn("refunded_amount").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("refundShortfall", f -> f.physicalColumn("refund_shortfall").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("disputeId", f -> f.physicalColumn("dispute_id").processOnly().asText(STRIPE_ID_LENGTH));
        eb.field("disputeStatus", f -> f.physicalColumn("dispute_status").processOnly().asText(20));
        eb.field("disputeAmount", f -> f.physicalColumn("dispute_amount").processOnly().asMonetary("JPY", 0));
        eb.field("disputeClawback", f -> f.physicalColumn("dispute_clawback").required(true).processOnly()
            .asMonetary("JPY", 0));
        // What a lost dispute could not take back (a person's), and what an open one holds in the wallet.
        eb.field("disputeShortfall", f -> f.physicalColumn("dispute_shortfall").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("disputeHeld", f -> f.physicalColumn("dispute_held").required(true).processOnly()
            .asMonetary("JPY", 0));
        // The last reason a person was asked to look, and when Stripe was last asked about a stale one.
        eb.field("checkReason", f -> f.physicalColumn("check_reason").processOnly().asText(40));
        eb.field("checkRequestedAt", f -> f.physicalColumn("check_requested_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_topup_session", "checkoutSessionId");
        eb.unique("uk_qb_topup_payment", "paymentIntentId");
        eb.stateTransitions("status", st -> st
            .initial(TopUpStatus.OPENING)
            .from(TopUpStatus.OPENING).to(TopUpStatus.OPEN, TopUpStatus.PAID, TopUpStatus.EXPIRED, TopUpStatus.FAILED)
            .from(TopUpStatus.OPEN).to(TopUpStatus.PAID, TopUpStatus.EXPIRED));
        eb.listView("default", lv -> lv
            .columns("sponsorId", "amount", "status", "createdAt", "paidAt", "stripeFee", "refundedAmount",
                "disputeStatus")
            .filters("sponsorId", "status", "createdAt", "paidAt", "checkoutSessionId", "paymentIntentId")
            .sorts("createdAt", "paidAt", "amount")
            .defaultSort("createdAt", false));
    });

    /** The default (finance administrators' read-only) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** The record as {@link TopUpChecks} sees it; null for none. */
    static TopUpChecks.TopUpView view(EntityInstance topUp) {
        return topUp == null ? null : new TopUpChecks.TopUpView(String.valueOf(topUp.id()), topUp.get("sponsorId"),
            money(topUp.get("amount")), topUp.get("status"), topUp.get("checkoutSessionId"));
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    static java.time.Instant instant(Object value) {
        return value == null ? null : value instanceof java.time.OffsetDateTime time ? time.toInstant()
            : value instanceof java.time.Instant time ? time : java.time.Instant.parse(value.toString());
    }

    private QbTopUps() {}
}
