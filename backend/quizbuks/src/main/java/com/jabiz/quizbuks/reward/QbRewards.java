package com.jabiz.quizbuks.reward;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.publishing.PublishingLimits;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.publishing.PublisherKind;

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
    /** The codes a sponsor's console shows or prints for takers to scan (Q22b): an ordinary table. */
    public static final String REDEEM_TOKEN = "QbRedeemToken";
    /** A sponsor's staff (Q39): takers the sponsor named to hand out prizes at the counter. An ordinary table. */
    public static final String STAFF = "QbSponsorStaff";
    /** The most staff a sponsor may name. */
    public static final int MAX_STAFF = 50;

    public static final String CASH_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:cash-reward-status";
    public static final String CASH_RESOLUTION_DICTIONARY = "urn:jabiz:dict:quizbuks:cash-reward-resolution";
    public static final String REDEMPTION_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:redemption-status";
    public static final String REDEEM_KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:redeem-kind";

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

    /** The lock of a sponsor's staff list: taken by naming and removing staff (the count, Q39). */
    public static String staffListLockName(Object sponsorId) {
        return sponsorId == null ? null : "qb.sponsor-staff:" + sponsorId;
    }

    /**
     * The lock of one person's place on a staff (Q39): exclusive while they are named or removed, shared while they
     * act for the sponsor, so a removal waits for the act in hand and every later act sees it.
     */
    public static String staffLockName(Object userId) {
        return userId == null ? null : "qb.staff:" + userId;
    }

    /** The lock of a cash reward: taken by every process that writes it, before it is read. */
    public static String cashLockName(Object cashRewardId) {
        return cashRewardId == null ? null : "qb.cash:" + cashRewardId;
    }

    /** The lock of a redemption: taken by every process that writes it, before it is read. */
    public static String redemptionLockName(Object redemptionId) {
        return redemptionId == null ? null : "qb.redeem:" + redemptionId;
    }

    /** The lock of a sponsor's codes: one printed code at a time, the rotating ones issued one after another. */
    public static String tokenLockName(Object sponsorId) {
        return "qb.redeem-token:" + sponsorId;
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
    public static final int TOKEN_LENGTH = 64;
    /** How long the console's rotating code is good for; the console asks for a new one every minute. */
    public static final java.time.Duration ROTATING_TOKEN_LIFE = java.time.Duration.ofMinutes(2);
    /** How long the sponsor has to confirm a claim made by scanning their printed code. */
    public static final java.time.Duration PRINTED_CLAIM_LIFE = java.time.Duration.ofMinutes(10);

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
        PublisherKind.declareSealed(eb, false);
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
            .columns("code", "publicationId", "publisherKind", "publisherId", "userId", "amount", "status", "dueAt",
                "markedPaidAt", "confirmBy", "confirmedAt", "disputedAt")
            .filters("publicationId", "publisherKind", "publisherId", "userId", "status", "dueAt", "markedPaidAt",
                "confirmBy")
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
        PublisherKind.declareSealed(eb, false);
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
        // Q22b: the scan. A printed code's claim waits for the sponsor until confirmBy; the console's rotating code
        // completes the redemption at once. redeemedBy is who confirmed (the sponsor or, Q39, one of their staff),
        // or who asked for the rotating code that was scanned.
        eb.field("claimKind", f -> f.physicalColumn("claim_kind").processOnly()
            .asCode(REDEEM_KIND_DICTIONARY, RedemptionStatus.KINDS.toArray(String[]::new)));
        eb.field("claimedAt", f -> f.physicalColumn("claimed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("confirmBy", f -> f.physicalColumn("confirm_by").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("redeemedBy", f -> f.physicalColumn("redeemed_by").processOnly().asText(128));
        eb.field("voidedBy", f -> f.physicalColumn("voided_by").processOnly().asText(128));
        eb.field("voidNote", f -> f.physicalColumn("void_note").processOnly()
            .asText(PublishingLimits.REJECT_REASON_LENGTH, true));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        // A claim the sponsor declines, or that lapses, is due again; redeemed, expired and void are final.
        eb.stateTransitions("status", st -> st
            .initial(RedemptionStatus.DUE)
            .from(RedemptionStatus.DUE).to(RedemptionStatus.CLAIMED, RedemptionStatus.REDEEMED,
                RedemptionStatus.EXPIRED, RedemptionStatus.VOID)
            .from(RedemptionStatus.CLAIMED).to(RedemptionStatus.REDEEMED, RedemptionStatus.DUE,
                RedemptionStatus.EXPIRED, RedemptionStatus.VOID));
        eb.unique("uk_qb_redemption_attempt", "attemptId");
        eb.display("code");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("code", "publicationId", "publisherKind", "publisherId", "userId", "amount", "status", "dueAt",
                "expiresAt", "claimedAt", "redeemedAt")
            .filters("publicationId", "publisherKind", "publisherId", "userId", "status", "dueAt", "expiresAt",
                "confirmBy")
            .sorts("dueAt", "expiresAt", "amount", "status")
            .defaultSort("dueAt", false));
    });

    /**
     * A code a sponsor's console shows or prints (Q22b). The token is the secret in the QR: never read back through
     * the datasets (it is sensitive), only returned by the process that issues it (as {@code redeemKey}). A rotating one expires; a printed
     * one lasts until the sponsor replaces it (revoked).
     */
    public static final EntityDefinition REDEEM_TOKEN_ENTITY = EntityDefinition.define(REDEEM_TOKEN, eb -> {
        eb.physicalTable("qb_redeem_token");
        eb.primaryKey("tokenId");
        eb.field("tokenId", f -> f.physicalColumn("token_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:redeem-token"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        // Sensitive, and named with "token": masked by name wherever the platform records or returns it.
        eb.field("tokenValue", f -> f.physicalColumn("token_value").immutable(true).required(true)
            .asText(TOKEN_LENGTH).sensitive());
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(REDEEM_KIND_DICTIONARY, RedemptionStatus.KINDS.toArray(String[]::new)));
        eb.field("issuedAt", f -> f.physicalColumn("issued_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("expiresAt", f -> f.physicalColumn("expires_at").immutable(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revokedAt", f -> f.physicalColumn("revoked_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        // Q39: who asked for it, the sponsor or one of their staff; a rotating code redeems in their name.
        eb.field("issuedBy", f -> f.physicalColumn("issued_by").immutable(true).asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_redeem_token", "tokenValue");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "kind", "issuedAt", "expiresAt", "revokedAt")
            .filters("sponsorId", "kind", "issuedAt")
            .sorts("issuedAt")
            .defaultSort("issuedAt", false));
    });

    /**
     * A sponsor's staff member (Q39, docs/quizbuks/plans/Q39-sponsor-staff.md): a taker the sponsor named, who shows
     * the sponsor's rotating code and answers claims from the printed one in the App. A person is on one sponsor's
     * staff at most; the row goes when the sponsor removes them or they leave.
     */
    public static final EntityDefinition STAFF_ENTITY = EntityDefinition.define(STAFF, eb -> {
        eb.physicalTable("qb_sponsor_staff");
        eb.primaryKey("staffId");
        eb.field("staffId", f -> f.physicalColumn("staff_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor-staff"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("addedAt", f -> f.physicalColumn("added_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("addedBy", f -> f.physicalColumn("added_by").immutable(true).required(true).asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_sponsor_staff_user", "userId");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "userId", "addedAt")
            .filters("sponsorId", "userId", "addedAt")
            .sorts("addedAt")
            .defaultSort("addedAt", false));
    });

    static BigDecimal decimal(long value) {
        return BigDecimal.valueOf(value);
    }

    private QbRewards() {}
}
