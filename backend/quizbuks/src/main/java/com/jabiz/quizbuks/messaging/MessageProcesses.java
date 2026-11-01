package com.jabiz.quizbuks.messaging;

import com.jabiz.event.DomainEvent;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.play.RewardStatus;
import com.jabiz.quizbuks.reward.CashRewardStatus;
import com.jabiz.quizbuks.reward.DigitalPrizeStatus;
import com.jabiz.quizbuks.reward.QbDigitalPrizes;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.quizbuks.reward.RedemptionStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.PayoutAccountStatus;
import com.jabiz.quizbuks.wallet.QbPayouts;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TransferRules;
import com.jabiz.quizbuks.wallet.TransferStatus;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The personal messages (docs/quizbuks/plans/Q7-messages-legal.md, Q7c, D-Q7-7, D-Q7-8): only ever made by the
 * system, never in the processes of answering or paying (their events are delivered to these consumers afterwards).
 * <ul>
 *   <li>{@value #FROM_EVENT}, the consumer of five events: a reward paid ({@code qb.reward.settled}) or pending
 *       ({@code qb.reward.pending}), a transfer sent or failed, a payout account held back. It reads the record the
 *       event names (the attempt, the transfer, the account: the recipient and the amounts come from it, not from the
 *       event) and makes one message while what it says still holds;</li>
 *   <li>{@value #PAYOUT_REMINDER}, the item of the daily job {@value QbInbox#REMINDER_JOB} (Q7-16): a taker whose
 *       balance reached the transfer threshold without a payout account that can receive it is reminded, at most once
 *       in {@code qb.payout.reminder-days}.</li>
 * </ul>
 * Both are {@code internal()} (decision D41), with the permission {@value QbPermissions#MESSAGING_SYSTEM} that no role
 * holds. A message is made once per {@code sourceKey} (unique): a redelivered or replayed event finds it and makes
 * nothing. A new subscription is handed the events published before it (platform 11 §2.3): those older than
 * {@link #MAX_AGE} make nothing, as Q7a's mail, so that deploying the inbox does not fill it with old news.
 */
public final class MessageProcesses {

    public static final String FROM_EVENT = "QB_MESSAGE_FROM_EVENT";
    public static final String PAYOUT_REMINDER = "QB_PAYOUT_REMINDER";

    public static final String REWARD_SETTLED_CONSUMER = "qb.msg-reward-settled";
    /** Q70: a referral's steps, one consumer each. */
    public static final String REFERRAL_CLAIMED_CONSUMER = "qb.msg-referral-claimed";
    public static final String REFERRAL_REWARDED_CONSUMER = "qb.msg-referral-rewarded";
    public static final String REFERRAL_EXPIRED_CONSUMER = "qb.msg-referral-expired";
    public static final String REFERRAL_FUNDS_SHORT_CONSUMER = "qb.msg-referral-funds-short";
    public static final String REWARD_PENDING_CONSUMER = "qb.msg-reward-pending";
    public static final String TRANSFER_SENT_CONSUMER = "qb.msg-transfer-sent";
    public static final String TRANSFER_FAILED_CONSUMER = "qb.msg-transfer-failed";
    public static final String PAYOUT_RESTRICTED_CONSUMER = "qb.msg-payout-restricted";
    /** Q22: the cash reward's steps and the prize, one consumer each (two for a resolution: both sides hear). */
    public static final String CASH_DUE_CONSUMER = "qb.msg-cash-due";
    public static final String CASH_MARKED_PAID_CONSUMER = "qb.msg-cash-marked-paid";
    public static final String CASH_CONFIRMED_CONSUMER = "qb.msg-cash-confirmed";
    public static final String CASH_DISPUTED_CONSUMER = "qb.msg-cash-disputed";
    public static final String CASH_RESOLVED_CONSUMER = "qb.msg-cash-resolved";
    public static final String CASH_RESOLVED_SPONSOR_CONSUMER = "qb.msg-cash-resolved-sponsor";
    public static final String CASH_OVERDUE_CONSUMER = "qb.msg-cash-overdue";
    public static final String REDEEM_DUE_CONSUMER = "qb.msg-redeem-due";
    public static final String REDEEM_CLAIMED_CONSUMER = "qb.msg-redeem-claimed";
    public static final String REDEEM_REDEEMED_CONSUMER = "qb.msg-redeem-redeemed";
    public static final String REDEEM_DECLINED_CONSUMER = "qb.msg-redeem-declined";
    public static final String REDEEM_EXPIRED_CONSUMER = "qb.msg-redeem-expired";
    public static final String REDEEM_VOID_CONSUMER = "qb.msg-redeem-void";
    /** Q33: the digital prize's steps, one consumer each (two for a resolution: both sides hear). */
    public static final String DIGITAL_WON_CONSUMER = "qb.msg-digital-won";
    public static final String DIGITAL_CONFIRMED_CONSUMER = "qb.msg-digital-confirmed";
    public static final String DIGITAL_DISPUTED_CONSUMER = "qb.msg-digital-disputed";
    public static final String DIGITAL_RESOLVED_CONSUMER = "qb.msg-digital-resolved";
    public static final String DIGITAL_RESOLVED_SPONSOR_CONSUMER = "qb.msg-digital-resolved-sponsor";

    /** An event older than this makes no message (a replay to a new subscription, a long backlog). */
    public static final Duration MAX_AGE = Duration.ofDays(7);
    /** The default of {@code qb.payout.reminder-days}. */
    public static final int DEFAULT_REMINDER_DAYS = 30;

    private static final String UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /**
     * @param kind       the message to make ({@code QbInbox.REWARD_PAID} …)
     * @param sourceId   the attempt, the transfer or the user the event names
     * @param occurredAt when the event was published
     */
    public record EventInput(@NotBlank @Size(max = 30) String kind, @NotBlank @Size(max = 128) String sourceId,
        @Pattern(regexp = UUID_PATTERN) String publicationId, @NotNull Instant occurredAt) {}

    /** The item of the reminder job: a taker. */
    public record ReminderInput(@NotBlank @Size(max = 128) String userId) {}

    /** @param messageId the message made; null when none was */
    public record MessageOutput(String messageId) {}

    static final String INPUT = "input";
    static final String SOURCE_KEY = "sourceKey";
    static final String EXISTING = "existing";
    static final String ATTEMPT = "attempt";
    static final String CARD = "card";
    static final String TRANSFER = "transfer";
    static final String ACCOUNT = "account";
    static final String CASH = "cash";
    static final String REDEMPTION = "redemption";
    static final String DIGITAL = "digital";
    static final String WALLET = "wallet";
    static final String REFERRAL = "referral";
    static final String LAST = "lastReminder";
    static final String PARAMS = "params";
    static final String OUTPUT = "output";

    private MessageProcesses() {}

    // ---- from events -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<EventInput, MessageOutput, ProcessContext> fromEvent(MessageTexts texts) {
        return ProcessDefinition.define(FROM_EVENT, 1, EventInput.class, MessageOutput.class, ProcessContext.class,
            pb -> pb
                .description("Makes a taker's message from a reward, transfer or payout account event.")
                .permissions(QbPermissions.MESSAGING_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(SOURCE_KEY, sourceKey(input));
                    return ctx;
                })
                .outputMapper(ctx -> new MessageOutput(ctx.contains(OUTPUT) ? (String) ctx.get(OUTPUT) : null))
                // One statement: the message made already, and the record the event names (only the one its kind
                // reads: the others have no key).
                .step("Load the source", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.MESSAGE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("sourceKey", ctx.get(SOURCE_KEY))).limit(1).build(), EXISTING),
                    QueryEntities.query(QbPlay.defaultDataset(QbPlay.ATTEMPT), ctx -> byId("attemptId",
                        attemptReward(ctx) ? input(ctx).sourceId() : null), ATTEMPT),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> byId(
                        "publicationId", reward(ctx) ? uuid(input(ctx).publicationId()) : null), CARD),
                    QueryEntities.query(QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> byId("transferId",
                        transfer(ctx) ? input(ctx).sourceId() : null), TRANSFER),
                    QueryEntities.query(QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byId("userId",
                        QbInbox.PAYOUT_ACTION_NEEDED.equals(input(ctx).kind()) ? input(ctx).sourceId() : null),
                        ACCOUNT),
                    // Q22: the cash reward or the redemption the event names, with its publication's card.
                    QueryEntities.query(QbRewards.defaultDataset(QbRewards.CASH_REWARD), ctx -> byId("cashRewardId",
                        QbInbox.CASH_KINDS.contains(input(ctx).kind()) ? uuid(input(ctx).sourceId()) : null), CASH),
                    QueryEntities.query(QbRewards.defaultDataset(QbRewards.REDEMPTION), ctx -> byId("redemptionId",
                        QbInbox.REDEEM_KINDS.contains(input(ctx).kind()) ? uuid(input(ctx).sourceId()) : null),
                        REDEMPTION),
                    // Q70: the referral the event names.
                    QueryEntities.query(com.jabiz.quizbuks.referral.QbReferrals.defaultDataset(
                        com.jabiz.quizbuks.referral.QbReferrals.REFERRAL), ctx -> byId("referralId",
                        QbInbox.REFERRAL_KINDS.contains(input(ctx).kind()) ? referralId(ctx) : null), REFERRAL),
                    // Q33: the digital prize the event names.
                    QueryEntities.query(QbDigitalPrizes.defaultDataset(QbDigitalPrizes.DIGITAL_PRIZE),
                        ctx -> byId("digitalPrizeId", QbInbox.DIGITAL_KINDS.contains(input(ctx).kind())
                            ? uuid(input(ctx).sourceId()) : null), DIGITAL)))
                .compute("Make the message", (metadata, ctx) -> fromEvent(ctx, texts)));
    }

    /** One message per attempt and kind, per transfer and kind, per user held back and day. */
    static String sourceKey(EventInput input) {
        String id = input.sourceId();
        if (QbInbox.PAYOUT_ACTION_NEEDED.equals(input.kind())) {
            return "payout-action:" + id + ":" + input.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate();
        }
        if (QbInbox.REFERRAL_FUNDS_SHORT.equals(input.kind())) {
            // Q70: one a day to the sponsor, however many referrals wait (the source is "<sponsor>|<referral>").
            return "referral-funds-short:" + id.substring(0, id.indexOf('|')) + ":"
                + input.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate();
        }
        String key = input.kind().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ":" + id;
        // Q22: a cash reward can be marked paid, disputed or late more than once (an "unpaid" decision sends it
        // back): each round is its own message, a redelivery of the same event is not.
        // Q33: likewise a digital prize can be sent, disputed and decided more than once.
        if (QbInbox.CASH_KINDS.contains(input.kind()) && !QbInbox.CASH_DUE.equals(input.kind())
            || QbInbox.REDEEM_CLAIMED.equals(input.kind()) || QbInbox.REDEEM_DECLINED.equals(input.kind())
            || QbInbox.DIGITAL_KINDS.contains(input.kind())) {
            return key + ":" + input.occurredAt().getEpochSecond();
        }
        return key;
    }

    /** The kinds that read the publication's card: the platform's reward, and the cash or prize of Q22. */
    private static boolean reward(ProcessContext ctx) {
        String kind = input(ctx).kind();
        return attemptReward(ctx) || QbInbox.CASH_KINDS.contains(kind) || QbInbox.REDEEM_KINDS.contains(kind)
            || QbInbox.DIGITAL_KINDS.contains(kind) || QbInbox.REFERRAL_KINDS.contains(kind);
    }

    /** The kinds whose source is the attempt itself. */
    private static boolean attemptReward(ProcessContext ctx) {
        String kind = input(ctx).kind();
        return QbInbox.REWARD_PAID.equals(kind) || QbInbox.REWARD_PENDING.equals(kind);
    }

    private static boolean transfer(ProcessContext ctx) {
        String kind = input(ctx).kind();
        return QbInbox.TRANSFER_SENT.equals(kind) || QbInbox.TRANSFER_FAILED.equals(kind);
    }

    static void fromEvent(ProcessContext ctx, MessageTexts texts) {
        EventInput input = input(ctx);
        if (first(ctx, EXISTING) != null || ctx.opTime().isAfter(input.occurredAt().plus(MAX_AGE))) {
            return;
        }
        Map<String, MessageTexts.Value> params = new LinkedHashMap<>();
        Draft draft = switch (input.kind()) {
            case QbInbox.REWARD_PAID, QbInbox.REWARD_PENDING -> rewardMessage(ctx, texts, input, params);
            case QbInbox.TRANSFER_SENT, QbInbox.TRANSFER_FAILED -> transferMessage(ctx, texts, input, params);
            case QbInbox.PAYOUT_ACTION_NEEDED -> payoutMessage(ctx);
            case QbInbox.CASH_DUE, QbInbox.CASH_MARKED_PAID, QbInbox.CASH_CONFIRMED, QbInbox.CASH_DISPUTED,
                 QbInbox.CASH_RESOLVED, QbInbox.CASH_RESOLVED_SPONSOR, QbInbox.CASH_OVERDUE ->
                cashMessage(ctx, input, params, texts);
            case QbInbox.REDEEM_DUE, QbInbox.REDEEM_CLAIMED, QbInbox.REDEEM_REDEEMED, QbInbox.REDEEM_DECLINED,
                 QbInbox.REDEEM_EXPIRED, QbInbox.REDEEM_VOID -> redeemMessage(ctx, texts, input, params);
            case QbInbox.DIGITAL_DELIVERED, QbInbox.DIGITAL_DUE, QbInbox.DIGITAL_CONFIRMED, QbInbox.DIGITAL_DISPUTED,
                 QbInbox.DIGITAL_RESOLVED, QbInbox.DIGITAL_RESOLVED_SPONSOR, QbInbox.DIGITAL_OVERDUE ->
                digitalMessage(ctx, input, params, texts);
            case QbInbox.REFERRAL_CLAIMED, QbInbox.REFERRAL_REWARDED, QbInbox.REFERRAL_EXPIRED,
                 QbInbox.REFERRAL_FUNDS_SHORT -> referralMessage(ctx, input, params, texts);
            default -> null;
        };
        if (draft != null) {
            insert(ctx, texts, draft, params, (String) ctx.get(SOURCE_KEY));
        }
    }

    /** What a message will be, but its texts. */
    record Draft(String recipientId, String type, String kind, Object publicationId, String action,
        String actionId) {}

    private static Draft rewardMessage(ProcessContext ctx, MessageTexts texts, EventInput input,
        Map<String, MessageTexts.Value> params) {
        EntityInstance attempt = first(ctx, ATTEMPT);
        EntityInstance card = first(ctx, CARD);
        // Only while it is so: a pending reward paid since says so with its own message.
        String status = QbInbox.REWARD_PAID.equals(input.kind()) ? RewardStatus.PAID : RewardStatus.PENDING;
        if (attempt == null || card == null || !status.equals(attempt.get("rewardStatus"))
            || !String.valueOf((Object) attempt.get("publicationId")).equals(String.valueOf(card.id()))) {
            return null;
        }
        BigDecimal reward = money(attempt.get("reward"));
        BigDecimal fee = money(attempt.get("fee"));
        params.put("amount", texts.kudos(RewardStatus.PAID.equals(status)
            ? reward.subtract(fee) : reward));
        params.put("name", MessageTexts.Value.of(card.get("name")));
        return new Draft(attempt.get("userId"), QbInbox.TYPE_REWARD, input.kind(), card.id(), QbInbox.ACTION_RESULT,
            String.valueOf(attempt.id()));
    }

    /**
     * A cash reward's message (Q22): to the taker when it is owed, marked paid, overdue or resolved; to the sponsor
     * when it is confirmed, disputed or resolved. Only while the reward still stands as the event said (a later step
     * has its own message); the card gives the publication's name.
     */
    private static Draft cashMessage(ProcessContext ctx, EventInput input, Map<String, MessageTexts.Value> params,
        MessageTexts texts) {
        EntityInstance cash = first(ctx, CASH);
        EntityInstance card = first(ctx, CARD);
        if (cash == null || card == null) {
            return null;
        }
        String expected = switch (input.kind()) {
            case QbInbox.CASH_DUE -> CashRewardStatus.DUE;
            case QbInbox.CASH_MARKED_PAID -> CashRewardStatus.MARKED_PAID;
            case QbInbox.CASH_CONFIRMED -> CashRewardStatus.CONFIRMED;
            case QbInbox.CASH_DISPUTED -> CashRewardStatus.DISPUTED;
            case QbInbox.CASH_OVERDUE -> CashRewardStatus.OVERDUE;
            default -> null;
        };
        if (expected != null && !expected.equals(cash.get("status"))) {
            return null;
        }
        params.put("amount", texts.kudos(cash.get("amount")));
        params.put("name", MessageTexts.Value.of(card.get("name")));
        params.put("code", MessageTexts.Value.of(cash.get("code")));
        // The state in the reader's language (the dictionary's words), not its code.
        params.put("status", texts.message(QbRewards.STATUS_MESSAGE_PREFIX + cash.get("status")));
        boolean sponsors = QbInbox.CASH_CONFIRMED.equals(input.kind()) || QbInbox.CASH_DISPUTED.equals(input.kind())
            || QbInbox.CASH_RESOLVED_SPONSOR.equals(input.kind());
        return sponsors
            ? new Draft(cash.get("publisherId"), QbInbox.TYPE_SYSTEM, input.kind(), card.id(), QbInbox.ACTION_CASH,
                String.valueOf(cash.id()))
            : new Draft(cash.get("userId"), QbInbox.TYPE_REWARD, input.kind(), card.id(), takerAction(cash),
                cash.get("attemptId"));
    }

    /**
     * The prize to collect (Q22): to the taker, with what it is and until when; and its fate (Q22b): a claim from the
     * printed code to the sponsor, collected, declined, expired or voided to the taker. Skipped when the prize moved on
     * since the event (the next message says where it stands).
     */
    private static Draft redeemMessage(ProcessContext ctx, MessageTexts texts, EventInput input,
        Map<String, MessageTexts.Value> params) {
        EntityInstance redemption = first(ctx, REDEMPTION);
        EntityInstance card = first(ctx, CARD);
        if (redemption == null || card == null) {
            return null;
        }
        String expected = switch (input.kind()) {
            case QbInbox.REDEEM_DUE, QbInbox.REDEEM_DECLINED -> RedemptionStatus.DUE;
            case QbInbox.REDEEM_CLAIMED -> RedemptionStatus.CLAIMED;
            case QbInbox.REDEEM_REDEEMED -> RedemptionStatus.REDEEMED;
            case QbInbox.REDEEM_EXPIRED -> RedemptionStatus.EXPIRED;
            case QbInbox.REDEEM_VOID -> RedemptionStatus.VOID;
            default -> null;
        };
        if (expected != null && !expected.equals(redemption.get("status"))) {
            return null;
        }
        params.put("code", MessageTexts.Value.of(redemption.get("code")));
        params.put("amount", texts.kudos(redemption.get("amount")));
        params.put("name", MessageTexts.Value.of(card.get("name")));
        params.put("description", MessageTexts.Value.of(redemption.get("description")));
        // The day only (UTC): the message is written once, in every language, without the reader's zone.
        Object expires = redemption.get("expiresAt");
        params.put("expiresAt", MessageTexts.Value.of(expires == null ? "" : String.valueOf(expires).substring(0,
            Math.min(10, String.valueOf(expires).length()))));
        if (QbInbox.REDEEM_CLAIMED.equals(input.kind())) {
            return new Draft(redemption.get("publisherId"), QbInbox.TYPE_SYSTEM, input.kind(), card.id(),
                QbInbox.ACTION_REDEEM, String.valueOf(redemption.id()));
        }
        return new Draft(redemption.get("userId"), QbInbox.TYPE_REWARD, input.kind(), card.id(),
            takerAction(redemption), redemption.get("attemptId"));
    }

    /**
     * The digital prize (Q33): delivered or owed to the taker, with what it is (never its content: the result page
     * opens it); confirmed or disputed to the sponsor; a decision to both; late to the sponsor. Skipped when the
     * prize moved on since the event.
     */
    private static Draft digitalMessage(ProcessContext ctx, EventInput input, Map<String, MessageTexts.Value> params,
        MessageTexts texts) {
        EntityInstance prize = first(ctx, DIGITAL);
        EntityInstance card = first(ctx, CARD);
        if (prize == null || card == null) {
            return null;
        }
        String expected = switch (input.kind()) {
            case QbInbox.DIGITAL_DELIVERED -> DigitalPrizeStatus.DELIVERED;
            case QbInbox.DIGITAL_DUE -> DigitalPrizeStatus.DUE;
            case QbInbox.DIGITAL_CONFIRMED -> DigitalPrizeStatus.CONFIRMED;
            case QbInbox.DIGITAL_DISPUTED -> DigitalPrizeStatus.DISPUTED;
            case QbInbox.DIGITAL_OVERDUE -> DigitalPrizeStatus.OVERDUE;
            default -> null;
        };
        if (expected != null && !expected.equals(prize.get("status"))) {
            return null;
        }
        params.put("amount", texts.kudos(prize.get("amount")));
        params.put("name", MessageTexts.Value.of(card.get("name")));
        params.put("description", MessageTexts.Value.of(prize.get("description") == null ? ""
            : prize.get("description")));
        Object sendBy = prize.get("sendBy");
        params.put("sendBy", MessageTexts.Value.of(sendBy == null ? "" : String.valueOf(sendBy).substring(0,
            Math.min(10, String.valueOf(sendBy).length()))));
        params.put("status", texts.message(QbDigitalPrizes.STATUS_MESSAGE_PREFIX + prize.get("status")));
        boolean sponsors = QbInbox.DIGITAL_CONFIRMED.equals(input.kind())
            || QbInbox.DIGITAL_DISPUTED.equals(input.kind()) || QbInbox.DIGITAL_OVERDUE.equals(input.kind())
            || QbInbox.DIGITAL_RESOLVED_SPONSOR.equals(input.kind());
        return sponsors
            ? new Draft(prize.get("publisherId"), QbInbox.TYPE_SYSTEM, input.kind(), card.id(),
                QbInbox.ACTION_DIGITAL, String.valueOf(prize.id()))
            : new Draft(prize.get("userId"), QbInbox.TYPE_REWARD, input.kind(), card.id(), takerAction(prize),
                prize.get("attemptId"));
    }

    /**
     * Where a reward record's message leads its winner (Q70, D-Q70-20): an attempt's result, or for a referral's reward
     * (whose {@code attemptId} is the referral) the referrals page.
     */
    static String takerAction(EntityInstance record) {
        return com.jabiz.quizbuks.reward.RewardSources.referral(record.get("sourceKind")) ? QbInbox.ACTION_REFERRALS
            : QbInbox.ACTION_RESULT;
    }

    /**
     * A referral's message (Q70): to the referrer when a friend came, when the reward was paid (with what it is) and
     * when it ended without a pass, never naming the friend; to the sponsor when their wallet could not pay one.
     * Skipped when the referral moved on since the event.
     */
    private static Draft referralMessage(ProcessContext ctx, EventInput input, Map<String, MessageTexts.Value> params,
        MessageTexts texts) {
        EntityInstance referral = first(ctx, REFERRAL);
        EntityInstance card = first(ctx, CARD);
        if (referral == null || card == null) {
            return null;
        }
        // A friend came whatever came of it since (the later steps have their own messages); the others only while
        // the referral stands as the event said.
        String expected = switch (input.kind()) {
            case QbInbox.REFERRAL_CLAIMED -> null;
            case QbInbox.REFERRAL_FUNDS_SHORT -> com.jabiz.quizbuks.referral.QbReferrals.REFERRED;
            case QbInbox.REFERRAL_REWARDED -> com.jabiz.quizbuks.referral.QbReferrals.REWARDED;
            default -> com.jabiz.quizbuks.referral.QbReferrals.EXPIRED;
        };
        if (expected != null && !expected.equals(referral.get("status"))) {
            return null;
        }
        params.put("name", MessageTexts.Value.of(card.get("name")));
        params.put("amount", texts.kudos(referral.get("amount")));
        params.put("method", texts.message("qb.referral.method." + referral.get("rewardMethod")));
        Object expires = referral.get("expiresAt");
        params.put("expiresAt", MessageTexts.Value.of(expires == null ? "" : String.valueOf(expires).substring(0,
            Math.min(10, String.valueOf(expires).length()))));
        if (QbInbox.REFERRAL_FUNDS_SHORT.equals(input.kind())) {
            return new Draft(referral.get("publisherId"), QbInbox.TYPE_SYSTEM, input.kind(), card.id(),
                QbInbox.ACTION_WALLET, null);
        }
        Object referrer = referral.get("referrerId");
        return referrer == null ? null : new Draft(String.valueOf(referrer), QbInbox.TYPE_REWARD, input.kind(),
            card.id(), QbInbox.ACTION_REFERRALS, String.valueOf(referral.id()));
    }

    /** The referral an event names (the funds-short event names it after its sponsor, for the day's key). */
    private static Object referralId(ProcessContext ctx) {
        String id = input(ctx).sourceId();
        return id.contains("|") ? id.substring(id.indexOf('|') + 1) : id;
    }

    private static Draft transferMessage(ProcessContext ctx, MessageTexts texts, EventInput input,
        Map<String, MessageTexts.Value> params) {
        EntityInstance transfer = first(ctx, TRANSFER);
        String status = QbInbox.TRANSFER_SENT.equals(input.kind()) ? TransferStatus.SENT : TransferStatus.FAILED;
        if (transfer == null || !status.equals(transfer.get("status"))) {
            return null;
        }
        params.put("amount", texts.kudos(transfer.get("amount")));
        params.put("net", texts.kudos(transfer.get("net")));
        params.put("fee", texts.kudos(transfer.get("fee")));
        if (TransferStatus.FAILED.equals(status)) {
            // Whether the taker can do something about it, never Stripe's own words (as the mail).
            params.put("reason", texts.message("qb.mail.transfer-failure."
                + (TransferRules.ACCOUNT_FAILURES.contains(transfer.get("failureCode")) ? "ACCOUNT" : "OTHER")));
        }
        return new Draft(transfer.get("userId"), QbInbox.TYPE_SYSTEM, input.kind(), null,
            TransferStatus.FAILED.equals(status) ? QbInbox.ACTION_PAYOUT : QbInbox.ACTION_WALLET, null);
    }

    private static Draft payoutMessage(ProcessContext ctx) {
        EntityInstance account = first(ctx, ACCOUNT);
        if (account == null || !PayoutAccountStatus.RESTRICTED.equals(account.get("status"))) {
            return null;
        }
        return new Draft(account.get("userId"), QbInbox.TYPE_SYSTEM, QbInbox.PAYOUT_ACTION_NEEDED, null,
            QbInbox.ACTION_PAYOUT, null);
    }

    private static void insert(ProcessContext ctx, MessageTexts texts, Draft draft,
        Map<String, MessageTexts.Value> params, String sourceKey) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("recipientId", draft.recipientId());
        values.put("type", draft.type());
        values.put("kind", draft.kind());
        values.put("title", texts.render(MessageTexts.titleCode(draft.kind()), params));
        values.put("body", texts.render(MessageTexts.bodyCode(draft.kind()), params));
        values.put("publicationId", draft.publicationId());
        values.put("action", draft.action());
        values.put("actionId", draft.actionId());
        values.put("sourceKey", sourceKey);
        values.put("createdAt", ctx.opTime());
        values.put("readAt", null);
        values.put("clickedAt", null);
        ctx.put(OUTPUT, String.valueOf(ctx.changes().insert(QbInbox.MESSAGE, values)));
    }

    /** Q70: a referral's event: the referral (with its sponsor first for the funds-short one) and its publication. */
    static EventInput referralInput(DomainEvent event, String kind) {
        String id = text(event, "referralId");
        String source = QbInbox.REFERRAL_FUNDS_SHORT.equals(kind) ? text(event, "publisherId") + "|" + id : id;
        return new EventInput(kind, source, text(event, "publicationId"), event.createdTime());
    }

    static EventInput rewardInput(DomainEvent event, String kind) {
        return new EventInput(kind, text(event, "attemptId"), text(event, "publicationId"), event.createdTime());
    }

    /** Q22: a cash reward's or a redemption's event: the record's id and its publication. */
    static EventInput cashInput(DomainEvent event, String kind) {
        return new EventInput(kind, text(event, "cashRewardId"), text(event, "publicationId"), event.createdTime());
    }

    static EventInput redeemInput(DomainEvent event) {
        return redeemInput(event, QbInbox.REDEEM_DUE);
    }

    static EventInput redeemInput(DomainEvent event, String kind) {
        return new EventInput(kind, text(event, "redemptionId"), text(event, "publicationId"), event.createdTime());
    }

    /** Q33: a prize won is delivered or owed, as the event's payload says. */
    static EventInput digitalWonInput(DomainEvent event) {
        return new EventInput(DigitalPrizeStatus.DELIVERED.equals(text(event, "status")) ? QbInbox.DIGITAL_DELIVERED
            : QbInbox.DIGITAL_DUE, text(event, "digitalPrizeId"), text(event, "publicationId"), event.createdTime());
    }

    static EventInput digitalInput(DomainEvent event, String kind) {
        return new EventInput(kind, text(event, "digitalPrizeId"), text(event, "publicationId"),
            event.createdTime());
    }

    static EventInput transferInput(DomainEvent event, String kind) {
        return new EventInput(kind, text(event, "transferId"), null, event.createdTime());
    }

    static EventInput payoutInput(DomainEvent event) {
        return new EventInput(QbInbox.PAYOUT_ACTION_NEEDED, text(event, "userId"), null, event.createdTime());
    }

    // ---- the payout reminder ---------------------------------------------------------------------------------------

    public static ProcessDefinition<ReminderInput, MessageOutput, ProcessContext> payoutReminder(MessageTexts texts) {
        return ProcessDefinition.define(PAYOUT_REMINDER, 1, ReminderInput.class, MessageOutput.class,
            ProcessContext.class, pb -> pb
                .description("Reminds a taker whose rewards reached the transfer threshold to open a payout account.")
                .permissions(QbPermissions.MESSAGING_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new MessageOutput(ctx.contains(OUTPUT) ? (String) ctx.get(OUTPUT) : null))
                // The job's template chose the taker; everything is read again here, in one statement.
                .step("Load the taker's standing", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(
                        QbWallets.TAKER, reminderInput(ctx).userId()), WALLET),
                    QueryEntities.query(QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byId("userId",
                        reminderInput(ctx).userId()), ACCOUNT),
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.MESSAGE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("recipientId", reminderInput(ctx).userId()),
                            new QueryPredicate.Eq("kind", QbInbox.PAYOUT_REMINDER))))
                        .orderBy("createdAt", false).limit(1).build(), LAST),
                    ParamReads.query(PARAMS, List.of(QbParams.TRANSFER_THRESHOLD, QbParams.PAYOUT_REMINDER_DAYS))))
                .compute("Remind", (metadata, ctx) -> remind(ctx, texts)));
    }

    static void remind(ProcessContext ctx, MessageTexts texts) {
        String userId = reminderInput(ctx).userId();
        EntityInstance wallet = first(ctx, WALLET);
        EntityInstance account = first(ctx, ACCOUNT);
        EntityInstance last = first(ctx, LAST);
        Map<String, Object> params = ParamReads.values(ctx, PARAMS);
        BigDecimal threshold = decimal(params.get(QbParams.TRANSFER_THRESHOLD));
        int days = ParamReads.days(params.get(QbParams.PAYOUT_REMINDER_DAYS), DEFAULT_REMINDER_DAYS);
        if (wallet == null || threshold == null || threshold.signum() <= 0
            || QbWallets.balance(wallet).compareTo(threshold) < 0
            || account != null && PayoutAccountStatus.ENABLED.equals(account.get("status"))
            || last != null && ctx.opTime().isBefore(instant(last.get("createdAt")).plus(Duration.ofDays(days)))) {
            return;
        }
        Map<String, MessageTexts.Value> values = new LinkedHashMap<>();
        values.put("amount", texts.kudos(QbWallets.balance(wallet)));
        values.put("threshold", texts.kudos(threshold));
        insert(ctx, texts, new Draft(userId, QbInbox.TYPE_REMINDER, QbInbox.PAYOUT_REMINDER, null,
            QbInbox.ACTION_PAYOUT, null), values, "payout-reminder:" + userId + ":"
            + ctx.opTime().atOffset(ZoneOffset.UTC).toLocalDate());
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static EventInput input(ProcessContext ctx) {
        return ctx.get(INPUT, EventInput.class);
    }

    private static ReminderInput reminderInput(ProcessContext ctx) {
        return ctx.get(INPUT, ReminderInput.class);
    }

    /** At most one row whose {@code field} is {@code id}; none when there is no id. */
    static EntityQuery byId(String field, Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            ids.add(id);
        }
        return EntityQuery.builder().where(new QueryPredicate.In(field, ids)).limit(1).build();
    }

    static UUID uuid(Object id) {
        if (id == null) {
            return null;
        }
        try {
            return id instanceof UUID uuid ? uuid : UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    static BigDecimal decimal(Object value) {
        try {
            return value == null || value.toString().isBlank() ? null : new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    @SuppressWarnings("unchecked")
    static EntityInstance first(ProcessContext ctx, String key) {
        if (!ctx.contains(key)) {
            return null;
        }
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(key);
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private static String text(DomainEvent event, String key) {
        Object value = event.payload().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
