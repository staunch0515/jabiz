package com.jabiz.quizbuks.reward;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.publishing.PublishingLimits;
import com.jabiz.quizbuks.publishing.QbPublications;

import java.math.BigDecimal;

import static com.jabiz.quizbuks.reward.CashRewardStatus.CONFIRMED;
import static com.jabiz.quizbuks.reward.CashRewardStatus.DISPUTED;
import static com.jabiz.quizbuks.reward.CashRewardStatus.DUE;
import static com.jabiz.quizbuks.reward.CashRewardStatus.MARKED_PAID;
import static com.jabiz.quizbuks.reward.CashRewardStatus.OVERDUE;
import static com.jabiz.quizbuks.reward.CashRewardStatus.VOID;

/**
 * The records of rewards settled outside the wallet (Q22, docs/quizbuks/plans/Q22-reward-methods.md): a cash reward
 * the sponsor hands the taker, and a prize the taker collects at the sponsor's place. Both are made when an attempt
 * finishes with a reward above 0 ({@code play.AttemptFinishing}), one per attempt, and carry the sponsor and the taker
 * so that each side's dataset is limited without a join. Temporal: their versions are the record of who did what
 * when; nothing in them moves money (the ledger is untouched, the reward figure is the prize's value in Kudos).
 */
public final class QbRewards {

    public static final String CASH_REWARD = "QbCashReward";

    /** The messages naming a cash reward's state in the reader's language: {@code qb.cash-reward.status.<state>}. */
    public static final String STATUS_MESSAGE_PREFIX = "qb.cash-reward.status.";
    public static final String REDEMPTION = "QbRedemption";

    public static final String CASH_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:cash-reward-status";
    public static final String CASH_RESOLUTION_DICTIONARY = "urn:jabiz:dict:quizbuks:cash-reward-resolution";
    public static final String REDEMPTION_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:redemption-status";

    /** The default (administrators' read-only) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** The sponsor's own records, read only. */
    public static String sponsorDataset(String entity) {
        return "urn:jabiz:dataset:sponsor:" + entity;
    }

    /** The taker's own records, read only. */
    public static String takerDataset(String entity) {
        return "urn:jabiz:dataset:taker:" + entity;
    }

    /** The lock of a cash reward: taken by every process that writes it, before it is read. */
    public static String cashLockName(Object cashRewardId) {
        return cashRewardId == null ? null : "qb.cash:" + cashRewardId;
    }

    /** The source key of the administrators' task about a disputed cash reward. */
    public static String disputeTaskSource(Object cashRewardId) {
        return "qb.cash-dispute:" + cashRewardId;
    }

    /** The source key of the sponsor's task to pay a cash reward that is late. */
    public static String payTaskSource(Object cashRewardId) {
        return "qb.cash-pay:" + cashRewardId;
    }

    public static final String DISPUTE_TASK = "qb.cash-reward.dispute";
    public static final String DISPUTE_TASK_TITLE = "task.qb.cash-reward.dispute";
    public static final String PAY_TASK = "qb.cash-reward.pay";
    public static final String PAY_TASK_TITLE = "task.qb.cash-reward.pay";

    public static final int CODE_LENGTH = 16;
    public static final int NOTE_LENGTH = 200;

    /**
     * The code the taker and the sponsor say to each other at the counter: {@code C-} and the last eight characters of
     * the attempt's id (its random part, so two codes of one sponsor seldom coincide; the id itself stays the key).
     */
    public static String codeOf(Object attemptId) {
        String id = String.valueOf(attemptId).replace("-", "");
        return "C-" + id.substring(Math.max(0, id.length() - 8)).toUpperCase(java.util.Locale.ROOT);
    }

    public static final EntityDefinition CASH_REWARD_ENTITY = EntityDefinition.define(CASH_REWARD, eb -> {
        eb.physicalTable("qb_cash_reward_version");
        eb.primaryKey("cashRewardId");
        eb.field("cashRewardId", f -> f.physicalColumn("cash_reward_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:cash-reward"));
        // The attempt's key as text, not a reference: attempts are read through datasets of their own.
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").immutable(true).required(true).asText(36));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("code", f -> f.physicalColumn("code").immutable(true).required(true).asText(CODE_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(CASH_STATUS_DICTIONARY, CashRewardStatus.ALL.toArray(String[]::new)));
        eb.field("dueAt", f -> f.physicalColumn("due_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // When the sponsor should have paid by (qb.reward.cash.pay-days after the attempt): a late one is reminded.
        eb.field("payBy", f -> f.physicalColumn("pay_by").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("remindedAt", f -> f.physicalColumn("reminded_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("markedPaidAt", f -> f.physicalColumn("marked_paid_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("markedPaidNote", f -> f.physicalColumn("marked_paid_note").processOnly().asText(NOTE_LENGTH));
        // When the taker must have confirmed or disputed by (qb.reward.cash.confirm-days after it was marked paid).
        eb.field("confirmBy", f -> f.physicalColumn("confirm_by").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("confirmedAt", f -> f.physicalColumn("confirmed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("disputedAt", f -> f.physicalColumn("disputed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("disputeNote", f -> f.physicalColumn("dispute_note").processOnly()
            .asText(PublishingLimits.REJECT_REASON_LENGTH, true));
        eb.field("overdueAt", f -> f.physicalColumn("overdue_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("resolvedAt", f -> f.physicalColumn("resolved_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("resolvedBy", f -> f.physicalColumn("resolved_by").processOnly().asText(128));
        eb.field("resolution", f -> f.physicalColumn("resolution").processOnly()
            .asCode(CASH_RESOLUTION_DICTIONARY, CashRewardStatus.RESOLUTIONS.toArray(String[]::new)));
        eb.field("resolveNote", f -> f.physicalColumn("resolve_note").processOnly()
            .asText(PublishingLimits.REJECT_REASON_LENGTH, true));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        // Confirmed and void are final. A dispute may be found unpaid (back to due) or paid (confirmed).
        eb.stateTransitions("status", st -> st
            .initial(DUE)
            .from(DUE).to(MARKED_PAID, DISPUTED, VOID)
            .from(MARKED_PAID).to(CONFIRMED, DISPUTED, OVERDUE, VOID)
            .from(OVERDUE).to(CONFIRMED, DISPUTED, VOID)
            .from(DISPUTED).to(CONFIRMED, DUE, VOID));
        eb.unique("uk_qb_cash_reward_attempt", "attemptId");
        eb.display("code");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("code", "publicationId", "sponsorId", "userId", "amount", "status", "dueAt", "markedPaidAt",
                "confirmBy", "confirmedAt", "disputedAt")
            .filters("publicationId", "sponsorId", "userId", "status", "dueAt", "markedPaidAt", "confirmBy")
            .sorts("dueAt", "markedPaidAt", "amount", "status")
            .defaultSort("dueAt", false));
    });

    public static final EntityDefinition REDEMPTION_ENTITY = EntityDefinition.define(REDEMPTION, eb -> {
        eb.physicalTable("qb_redemption_version");
        eb.primaryKey("redemptionId");
        eb.field("redemptionId", f -> f.physicalColumn("redemption_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:redemption"));
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").immutable(true).required(true).asText(36));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        // The prize's value in Kudos (the tier's reward) and what it is, as the card said when the attempt finished.
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("description", f -> f.physicalColumn("description").immutable(true).required(true)
            .asText(PublishingLimits.REDEEM_DESCRIPTION_LENGTH, true));
        eb.field("code", f -> f.physicalColumn("code").immutable(true).required(true).asText(CODE_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(REDEMPTION_STATUS_DICTIONARY, RedemptionStatus.ALL.toArray(String[]::new)));
        eb.field("dueAt", f -> f.physicalColumn("due_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("expiresAt", f -> f.physicalColumn("expires_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("redeemedAt", f -> f.physicalColumn("redeemed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.stateTransitions("status", st -> st
            .initial(RedemptionStatus.DUE)
            .from(RedemptionStatus.DUE).to(RedemptionStatus.REDEEMED, RedemptionStatus.EXPIRED,
                RedemptionStatus.VOID));
        eb.unique("uk_qb_redemption_attempt", "attemptId");
        eb.display("code");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("code", "publicationId", "sponsorId", "userId", "amount", "status", "dueAt", "expiresAt",
                "redeemedAt")
            .filters("publicationId", "sponsorId", "userId", "status", "dueAt", "expiresAt")
            .sorts("dueAt", "expiresAt", "amount", "status")
            .defaultSort("dueAt", false));
    });

    static BigDecimal decimal(long value) {
        return BigDecimal.valueOf(value);
    }

    private QbRewards() {}
}
