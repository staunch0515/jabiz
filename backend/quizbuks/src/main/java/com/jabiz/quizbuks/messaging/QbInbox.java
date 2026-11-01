package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.publishing.QbPublications;

import java.util.List;

/**
 * The entities of the in-app inbox and the announcements (docs/quizbuks/plans/Q7-messages-legal.md, Q7c, D-Q7-7):
 * <ul>
 *   <li>{@code QbMessage}: a personal message, made from an event (a reward paid or pending, a transfer sent or failed,
 *       a payout account held back) or by the daily reminder job. Its title and text are written in every language
 *       when it is made (Q7-15). An ordinary table: many rows that never change but for when they were read and
 *       clicked; one row per {@code sourceKey}, so a replayed event makes no second one;</li>
 *   <li>{@code QbBroadcast}: an announcement of the platform (to the app's users or the sponsors) or a sponsor's notice
 *       to the takers of one of their publications. Temporal: its versions are the record of who wrote, sent and
 *       withdrew it. Never copied per user: the inbox matches the sent ones ({@code QbBroadcastFeed}) when it is read;</li>
 *   <li>{@code QbBroadcastTarget}: the users an announcement to named users goes to (at most
 *       {@link BroadcastRules#MAX_TARGETS} each);</li>
 *   <li>{@code QbBroadcastFeed}: the sent announcements, written when one is sent and switched off when it is
 *       withdrawn, expires or its publication ends (the minute's job {@value #EXPIRE_JOB}): the inbox reads the few
 *       live rows of this ordinary table, not the versions of every broadcast;</li>
 *   <li>{@code QbBroadcastSchedule}: a scheduled announcement's due time (the minute's job reads it);</li>
 *   <li>{@code QbBroadcastReceipt}: when a user first opened an announcement and clicked through to its quiz (M-61);</li>
 *   <li>{@code QbBroadcastStats}: the daily snapshot of a sponsor's notice's statistics, all the sponsor sees of them
 *       (review M2);</li>
 *   <li>{@code QbInboxState}: a user's "all read" mark and whether they turned promotions off (Q7-11);</li>
 *   <li>{@code QbAttemptStart}: who started which publication when, a read-only view of the attempts without their
 *       rewards, for the conversions of the statistics (Q7-14).</li>
 * </ul>
 * Only the processes of {@link MessageProcesses}, {@link BroadcastProcesses} and {@link InboxProcesses} write them.
 */
public final class QbInbox {

    public static final String MESSAGE = "QbMessage";
    public static final String BROADCAST = "QbBroadcast";
    public static final String TARGET = "QbBroadcastTarget";
    public static final String FEED = "QbBroadcastFeed";
    public static final String SCHEDULE = "QbBroadcastSchedule";
    public static final String RECEIPT = "QbBroadcastReceipt";
    public static final String STATS = "QbBroadcastStats";
    public static final String STATE = "QbInboxState";
    public static final String ATTEMPT_START = "QbAttemptStart";

    public static final String MESSAGE_TYPE_DICTIONARY = "urn:jabiz:dict:quizbuks:message-type";
    public static final String MESSAGE_KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:message-kind";
    public static final String BROADCAST_TYPE_DICTIONARY = "urn:jabiz:dict:quizbuks:broadcast-type";
    public static final String BROADCAST_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:broadcast-status";
    public static final String AUDIENCE_DICTIONARY = "urn:jabiz:dict:quizbuks:broadcast-audience";
    public static final String TARGET_MODE_DICTIONARY = "urn:jabiz:dict:quizbuks:broadcast-target";
    public static final String SENDER_DICTIONARY = "urn:jabiz:dict:quizbuks:broadcast-sender";

    // ---- personal messages -----------------------------------------------------------------------------------------

    /** The types of personal message (the inbox's filters, U-60). */
    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_REWARD = "REWARD";
    public static final String TYPE_REMINDER = "REMINDER";
    public static final List<String> MESSAGE_TYPES = List.of(TYPE_SYSTEM, TYPE_REWARD, TYPE_REMINDER);

    /** What a message is about; its texts are the messages {@code qb.msg.<kind>.title|body}. */
    public static final String REWARD_PAID = "REWARD_PAID";
    public static final String REWARD_PENDING = "REWARD_PENDING";
    public static final String TRANSFER_SENT = "TRANSFER_SENT";
    public static final String TRANSFER_FAILED = "TRANSFER_FAILED";
    public static final String PAYOUT_ACTION_NEEDED = "PAYOUT_ACTION_NEEDED";
    public static final String PAYOUT_REMINDER = "PAYOUT_REMINDER";
    /** Q22: a cash reward owed to the taker, each step of it, and a prize to collect. */
    public static final String CASH_DUE = "CASH_DUE";
    public static final String CASH_MARKED_PAID = "CASH_MARKED_PAID";
    public static final String CASH_CONFIRMED = "CASH_CONFIRMED";
    public static final String CASH_DISPUTED = "CASH_DISPUTED";
    public static final String CASH_RESOLVED = "CASH_RESOLVED";
    public static final String CASH_RESOLVED_SPONSOR = "CASH_RESOLVED_SPONSOR";
    public static final String CASH_OVERDUE = "CASH_OVERDUE";
    public static final String REDEEM_DUE = "REDEEM_DUE";
    /** Q22b: a claim from the sponsor's printed code (to the sponsor); the prize's fate (to the taker). */
    public static final String REDEEM_CLAIMED = "REDEEM_CLAIMED";
    public static final String REDEEM_REDEEMED = "REDEEM_REDEEMED";
    public static final String REDEEM_DECLINED = "REDEEM_DECLINED";
    public static final String REDEEM_EXPIRED = "REDEEM_EXPIRED";
    public static final String REDEEM_VOID = "REDEEM_VOID";
    public static final List<String> CASH_KINDS = List.of(CASH_DUE, CASH_MARKED_PAID, CASH_CONFIRMED, CASH_DISPUTED,
        CASH_RESOLVED, CASH_RESOLVED_SPONSOR, CASH_OVERDUE);
    public static final List<String> REDEEM_KINDS = List.of(REDEEM_DUE, REDEEM_CLAIMED, REDEEM_REDEEMED,
        REDEEM_DECLINED, REDEEM_EXPIRED, REDEEM_VOID);
    /** Q33: a digital prize delivered or owed (to the taker); confirmed or disputed (to the sponsor); decided. */
    public static final String DIGITAL_DELIVERED = "DIGITAL_DELIVERED";
    public static final String DIGITAL_DUE = "DIGITAL_DUE";
    public static final String DIGITAL_CONFIRMED = "DIGITAL_CONFIRMED";
    public static final String DIGITAL_DISPUTED = "DIGITAL_DISPUTED";
    public static final String DIGITAL_RESOLVED = "DIGITAL_RESOLVED";
    public static final String DIGITAL_RESOLVED_SPONSOR = "DIGITAL_RESOLVED_SPONSOR";
    public static final String DIGITAL_OVERDUE = "DIGITAL_OVERDUE";
    public static final List<String> DIGITAL_KINDS = List.of(DIGITAL_DELIVERED, DIGITAL_DUE, DIGITAL_CONFIRMED,
        DIGITAL_DISPUTED, DIGITAL_RESOLVED, DIGITAL_RESOLVED_SPONSOR, DIGITAL_OVERDUE);
    /**
     * Q70: a referral of the referrer's was claimed (a friend came), rewarded, or ended without a pass (to the
     * referrer, never naming the friend); the sponsor's wallet could not pay a referral reward (to the sponsor).
     */
    public static final String REFERRAL_CLAIMED = "REFERRAL_CLAIMED";
    public static final String REFERRAL_REWARDED = "REFERRAL_REWARDED";
    public static final String REFERRAL_EXPIRED = "REFERRAL_EXPIRED";
    public static final String REFERRAL_FUNDS_SHORT = "REFERRAL_FUNDS_SHORT";
    public static final List<String> REFERRAL_KINDS = List.of(REFERRAL_CLAIMED, REFERRAL_REWARDED, REFERRAL_EXPIRED,
        REFERRAL_FUNDS_SHORT);
    public static final List<String> MESSAGE_KINDS = List.of(REFERRAL_CLAIMED, REFERRAL_REWARDED, REFERRAL_EXPIRED,
        REFERRAL_FUNDS_SHORT, REWARD_PAID, REWARD_PENDING, TRANSFER_SENT,
        TRANSFER_FAILED, PAYOUT_ACTION_NEEDED, PAYOUT_REMINDER, CASH_DUE, CASH_MARKED_PAID, CASH_CONFIRMED,
        CASH_DISPUTED, CASH_RESOLVED, CASH_RESOLVED_SPONSOR, CASH_OVERDUE, REDEEM_DUE, REDEEM_CLAIMED,
        REDEEM_REDEEMED, REDEEM_DECLINED, REDEEM_EXPIRED, REDEEM_VOID, DIGITAL_DELIVERED, DIGITAL_DUE,
        DIGITAL_CONFIRMED, DIGITAL_DISPUTED, DIGITAL_RESOLVED, DIGITAL_RESOLVED_SPONSOR, DIGITAL_OVERDUE);

    /** Where a message leads the app: an attempt's result, the wallet or the payout account. */
    public static final String ACTION_RESULT = "RESULT";
    public static final String ACTION_WALLET = "WALLET";
    public static final String ACTION_PAYOUT = "PAYOUT";
    /** Q22: the sponsor console's cash rewards (a sponsor's message). */
    public static final String ACTION_CASH = "CASH";
    /** Q22b: the sponsor console's redeem page (a claim to confirm). */
    public static final String ACTION_REDEEM = "REDEEM";
    /** Q33: the sponsor console's digital prizes page. */
    public static final String ACTION_DIGITAL = "DIGITAL";
    /** Q70: the App's referrals page (the referrer's), and a reward a referral earned (it has no result). */
    public static final String ACTION_REFERRALS = "REFERRALS";
    public static final List<String> ACTIONS = List.of(ACTION_RESULT, ACTION_WALLET, ACTION_PAYOUT, ACTION_CASH,
        ACTION_REDEEM, ACTION_DIGITAL, ACTION_REFERRALS);

    // ---- broadcasts ------------------------------------------------------------------------------------------------

    public static final String SENDER_PLATFORM = "PLATFORM";
    public static final String SENDER_SPONSOR = "SPONSOR";
    public static final List<String> SENDERS = List.of(SENDER_PLATFORM, SENDER_SPONSOR);

    /** Who an announcement is for: the app's users or the sponsors (in the sponsor console). */
    public static final String AUDIENCE_APP = "APP";
    public static final String AUDIENCE_SPONSOR = "SPONSOR";
    public static final List<String> AUDIENCES = List.of(AUDIENCE_APP, AUDIENCE_SPONSOR);

    /** A system announcement, a notice to people (both seen by everyone matched), a promotion (Q7-9, Q7-11). */
    public static final String BROADCAST_SYSTEM = "SYSTEM";
    public static final String BROADCAST_NOTICE = "NOTICE";
    public static final String BROADCAST_PROMO = "PROMO";
    public static final List<String> BROADCAST_TYPES = List.of(BROADCAST_SYSTEM, BROADCAST_NOTICE, BROADCAST_PROMO);

    public static final String TARGET_ALL = "ALL";
    public static final String TARGET_REGIONS = "REGIONS";
    public static final String TARGET_USERS = "USERS";
    public static final List<String> TARGET_MODES = List.of(TARGET_ALL, TARGET_REGIONS, TARGET_USERS);

    public static final String DRAFT = "DRAFT";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String SENT = "SENT";
    public static final String CANCELLED = "CANCELLED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final List<String> STATUSES = List.of(DRAFT, SCHEDULED, SENT, CANCELLED, WITHDRAWN);

    /**
     * Who withdrew a broadcast, in kind only: its sender, an administrator or the system (a ban, a withdrawn legal
     * text). Which administrator is the audit's (review L3: a sponsor's dataset shows this field).
     */
    public static final String BY_SENDER = "SENDER";
    public static final String BY_ADMIN = "ADMIN";
    public static final String BY_SYSTEM = "SYSTEM";

    /** The inbox's kinds of item, as the mark process and the templates name them. */
    public static final String ITEM_MESSAGE = "MESSAGE";
    public static final String ITEM_BROADCAST = "BROADCAST";

    public static final int MESSAGE_TITLE_LENGTH = 200;
    public static final int MESSAGE_BODY_LENGTH = 2_000;
    public static final int TITLE_LENGTH = 120;
    public static final int BODY_LENGTH = 5_000;
    public static final int REASON_LENGTH = 1_000;
    static final int USER_ID_LENGTH = 128;
    static final int SOURCE_KEY_LENGTH = 200;

    // ---- datasets and templates ------------------------------------------------------------------------------------

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** One's own messages, receipts, targets and inbox state, read only (the scope is the caller). */
    public static final String MY_MESSAGES = "urn:jabiz:dataset:me:" + MESSAGE;
    public static final String MY_RECEIPTS = "urn:jabiz:dataset:me:" + RECEIPT;
    public static final String MY_TARGETS = "urn:jabiz:dataset:me:" + TARGET;
    public static final String MY_STATE = "urn:jabiz:dataset:me:" + STATE;
    /** A sponsor's own notices, read only. */
    public static final String SPONSOR_BROADCASTS = "urn:jabiz:dataset:sponsor:" + BROADCAST;

    public static final String APP_INBOX_TEMPLATE = "qb.app.inbox";
    public static final String APP_UNREAD_TEMPLATE = "qb.app.inbox-unread";
    public static final String SPONSOR_INBOX_TEMPLATE = "qb.sponsor.inbox";
    public static final String SPONSOR_BROADCASTS_TEMPLATE = "qb.sponsor.broadcasts";
    public static final String SPONSOR_PUBLICATIONS_TEMPLATE = "qb.sponsor.broadcast-publications";
    public static final String ADMIN_BROADCASTS_TEMPLATE = "qb.admin.broadcasts";
    public static final String DUE_TEMPLATE = "qb.system.broadcasts-due";
    public static final String AUDIENCE_TEMPLATE = "qb.system.broadcast-audience";
    public static final String REMINDERS_TEMPLATE = "qb.system.payout-reminders";
    public static final String EXPIRED_TEMPLATE = "qb.system.broadcasts-expired";
    public static final String STATS_TEMPLATE = "qb.system.broadcast-stats";

    /** The minute's job sending the scheduled announcements and the daily job of payout reminders. */
    public static final String DISPATCH_JOB = "qb.broadcast-dispatch";
    public static final String REMINDER_JOB = "qb.payout-reminder";
    /** The minute's job that takes the expired and the ended ones out of the inbox (review H1). */
    public static final String EXPIRE_JOB = "qb.broadcast-expire";
    /** The daily job of the sponsors' statistics' snapshot (review M2). */
    public static final String STATS_JOB = "qb.broadcast-stats";

    /**
     * The lock of one broadcast: every process that writes it takes it before reading it again. A sponsor's processes
     * take the sponsor's account lock ({@code qb.account:<sponsor>}) before it, as a ban does before withdrawing their
     * notices; no process takes a publication's or wallet's lock with it.
     */
    public static String broadcastLock(Object broadcastId) {
        return broadcastId == null ? null : "qb.broadcast:" + broadcastId;
    }

    /** The lock of a user's receipts and inbox state (two tabs marking at once write one row). */
    public static String inboxLock(Object userId) {
        return userId == null ? null : "qb.inbox:" + userId;
    }

    // ---- entities --------------------------------------------------------------------------------------------------

    public static final EntityDefinition MESSAGE_ENTITY = EntityDefinition.define(MESSAGE, eb -> {
        eb.physicalTable("qb_message");
        eb.primaryKey("messageId");
        eb.field("messageId", f -> f.physicalColumn("message_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:message"));
        eb.field("recipientId", f -> f.physicalColumn("recipient_id").immutable(true).required(true)
            .asText(USER_ID_LENGTH));
        eb.field("type", f -> f.physicalColumn("type").immutable(true).required(true)
            .asCode(MESSAGE_TYPE_DICTIONARY, MESSAGE_TYPES.toArray(String[]::new)));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(MESSAGE_KIND_DICTIONARY, MESSAGE_KINDS.toArray(String[]::new)));
        eb.field("title", f -> f.physicalColumn("title").immutable(true).required(true)
            .apply(I18nText.of(MESSAGE_TITLE_LENGTH)));
        eb.field("body", f -> f.physicalColumn("body").immutable(true).required(true)
            .apply(I18nText.markdown(MESSAGE_BODY_LENGTH)));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true)
            .asReference(QbPublications.PUBLICATION));
        eb.field("action", f -> f.physicalColumn("action").immutable(true).asText(20));
        eb.field("actionId", f -> f.physicalColumn("action_id").immutable(true).asText(36));
        eb.field("sourceKey", f -> f.physicalColumn("source_key").immutable(true).required(true)
            .asText(SOURCE_KEY_LENGTH));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("readAt", f -> f.physicalColumn("read_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("clickedAt", f -> f.physicalColumn("clicked_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_message_source", "sourceKey");
        eb.listView("default", lv -> lv
            .columns("recipientId", "type", "kind", "title", "createdAt", "readAt")
            .filters("recipientId", "type", "kind", "createdAt")
            .sorts("createdAt")
            .defaultSort("createdAt", false));
    });

    public static final EntityDefinition BROADCAST_ENTITY = EntityDefinition.define(BROADCAST, eb -> {
        eb.physicalTable("qb_broadcast_version");
        eb.primaryKey("broadcastId");
        eb.field("broadcastId", f -> f.physicalColumn("broadcast_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:broadcast"));
        eb.field("senderKind", f -> f.physicalColumn("sender_kind").immutable(true).required(true)
            .asCode(SENDER_DICTIONARY, SENDERS.toArray(String[]::new)));
        // The sponsor of a notice; for the platform's, the administrator who wrote it.
        eb.field("senderId", f -> f.physicalColumn("sender_id").immutable(true).required(true)
            .asText(USER_ID_LENGTH));
        eb.field("audience", f -> f.physicalColumn("audience").required(true)
            .asCode(AUDIENCE_DICTIONARY, AUDIENCES.toArray(String[]::new)));
        eb.field("type", f -> f.physicalColumn("type").required(true)
            .asCode(BROADCAST_TYPE_DICTIONARY, BROADCAST_TYPES.toArray(String[]::new)));
        eb.field("title", f -> f.physicalColumn("title").apply(I18nText.of(TITLE_LENGTH)));
        // Markdown for the platform's; a sponsor's notice is shown as plain text (Q7-10), its rules refuse links.
        eb.field("body", f -> f.physicalColumn("body").apply(I18nText.markdown(BODY_LENGTH)));
        eb.field("targetMode", f -> f.physicalColumn("target_mode").required(true)
            .asCode(TARGET_MODE_DICTIONARY, TARGET_MODES.toArray(String[]::new)));
        eb.field("regions", f -> f.physicalColumn("regions").asText(Regions.MAX_LENGTH)
            .apply(Rules.pattern(BroadcastCodes.REGIONS_FORMAT, Regions.TARGETS.pattern())));
        eb.field("targetCount", f -> f.physicalColumn("target_count").asNumeric(5, 0));
        eb.field("publicationId", f -> f.physicalColumn("publication_id")
            .asReference(QbPublications.PUBLICATION));
        eb.field("scheduledAt", f -> f.physicalColumn("scheduled_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("expiresAt", f -> f.physicalColumn("expires_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(BROADCAST_STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("sentAt", f -> f.physicalColumn("sent_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("audienceCount", f -> f.physicalColumn("audience_count").asNumeric(9, 0));
        // Why the job did not send it (a code: the publication no longer on, the sponsor banned).
        eb.field("cancelReason", f -> f.physicalColumn("cancel_reason").asText(64));
        eb.field("withdrawnAt", f -> f.physicalColumn("withdrawn_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("withdrawnBy", f -> f.physicalColumn("withdrawn_by").asText(10)
            .apply(Rules.pattern(BroadcastCodes.INVALID_VALUE, "SENDER|ADMIN|SYSTEM")));
        // The administrator's reason (the sponsor reads it), or the code of a ban's withdrawal.
        eb.field("withdrawReason", f -> f.physicalColumn("withdraw_reason").asText(REASON_LENGTH, true));
        // What made it, when not a person: "legal:<version>" for the announcement of a legal text (review L2).
        eb.field("sourceKey", f -> f.physicalColumn("source_key").immutable(true).asText(100));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).asNumeric(9, 0));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        // A draft is written, scheduled or sent; a scheduled one goes back to its draft, is sent by the job or
        // cancelled by it (or withdrawn with its sponsor's ban); a sent one is withdrawn. The legal texts' notice of a
        // change is written scheduled at once (QB_LEGAL_PUBLISH with announce).
        eb.stateTransitions("status", st -> st
            .initial(DRAFT, SCHEDULED)
            .from(DRAFT).to(SCHEDULED, SENT, CANCELLED)
            .from(SCHEDULED).to(DRAFT, SENT, CANCELLED, WITHDRAWN)
            .from(SENT).to(WITHDRAWN));
        eb.display("title");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("senderKind", "senderId", "audience", "type", "title", "status", "scheduledAt", "sentAt",
                "expiresAt", "audienceCount")
            .filters("senderKind", "senderId", "audience", "type", "status", "sentAt")
            .sorts("editedAt", "sentAt", "scheduledAt")
            .defaultSort("editedAt", false));
    });

    public static final EntityDefinition TARGET_ENTITY = EntityDefinition.define(TARGET, eb -> {
        eb.physicalTable("qb_broadcast_target");
        eb.primaryKey("targetId");
        eb.field("targetId", f -> f.physicalColumn("target_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:broadcast-target"));
        eb.field("broadcastId", f -> f.physicalColumn("broadcast_id").immutable(true).required(true)
            .asReference(BROADCAST));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(USER_ID_LENGTH));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_broadcast_target", "broadcastId", "userId");
        eb.listView("default", lv -> lv
            .columns("broadcastId", "userId")
            .filters("broadcastId", "userId")
            .sorts("userId")
            .defaultSort("userId", true));
    });

    public static final EntityDefinition FEED_ENTITY = EntityDefinition.define(FEED, eb -> {
        eb.physicalTable("qb_broadcast_feed");
        eb.primaryKey("broadcastId");
        eb.field("broadcastId", f -> f.physicalColumn("broadcast_id").immutable(true).required(true)
            .asReference(BROADCAST));
        eb.field("senderKind", f -> f.physicalColumn("sender_kind").immutable(true).required(true)
            .asCode(SENDER_DICTIONARY, SENDERS.toArray(String[]::new)));
        eb.field("senderId", f -> f.physicalColumn("sender_id").immutable(true).required(true)
            .asText(USER_ID_LENGTH));
        eb.field("audience", f -> f.physicalColumn("audience").immutable(true).required(true)
            .asCode(AUDIENCE_DICTIONARY, AUDIENCES.toArray(String[]::new)));
        eb.field("type", f -> f.physicalColumn("type").immutable(true).required(true)
            .asCode(BROADCAST_TYPE_DICTIONARY, BROADCAST_TYPES.toArray(String[]::new)));
        eb.field("targetMode", f -> f.physicalColumn("target_mode").immutable(true).required(true)
            .asCode(TARGET_MODE_DICTIONARY, TARGET_MODES.toArray(String[]::new)));
        eb.field("regions", f -> f.physicalColumn("regions").immutable(true).asText(Regions.MAX_LENGTH));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true)
            .asReference(QbPublications.PUBLICATION));
        eb.field("title", f -> f.physicalColumn("title").immutable(true).required(true)
            .apply(I18nText.of(TITLE_LENGTH)));
        eb.field("body", f -> f.physicalColumn("body").immutable(true).required(true)
            .apply(I18nText.markdown(BODY_LENGTH)));
        eb.field("sentAt", f -> f.physicalColumn("sent_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("expiresAt", f -> f.physicalColumn("expires_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("live", f -> f.physicalColumn("live").required(true).asBool());
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("audience", "type", "targetMode", "title", "sentAt", "expiresAt", "live")
            .filters("audience", "type", "live", "sentAt")
            .sorts("sentAt")
            .defaultSort("sentAt", false));
    });

    public static final EntityDefinition SCHEDULE_ENTITY = EntityDefinition.define(SCHEDULE, eb -> {
        eb.physicalTable("qb_broadcast_schedule");
        eb.primaryKey("broadcastId");
        eb.field("broadcastId", f -> f.physicalColumn("broadcast_id").immutable(true).required(true)
            .asReference(BROADCAST));
        eb.field("dueAt", f -> f.physicalColumn("due_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("broadcastId", "dueAt")
            .filters("dueAt")
            .sorts("dueAt")
            .defaultSort("dueAt", true));
    });

    public static final EntityDefinition RECEIPT_ENTITY = EntityDefinition.define(RECEIPT, eb -> {
        eb.physicalTable("qb_broadcast_receipt");
        eb.primaryKey("receiptId");
        eb.field("receiptId", f -> f.physicalColumn("receipt_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:broadcast-receipt"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(USER_ID_LENGTH));
        eb.field("broadcastId", f -> f.physicalColumn("broadcast_id").immutable(true).required(true)
            .asReference(BROADCAST));
        eb.field("readAt", f -> f.physicalColumn("read_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("clickedAt", f -> f.physicalColumn("clicked_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_broadcast_receipt", "userId", "broadcastId");
        eb.listView("default", lv -> lv
            .columns("userId", "broadcastId", "readAt", "clickedAt")
            .filters("userId", "broadcastId")
            .sorts("readAt")
            .defaultSort("readAt", false));
    });

    /** A sponsor's notice's statistics as of the day's snapshot (written by the job only). */
    public static final EntityDefinition STATS_ENTITY = EntityDefinition.define(STATS, eb -> {
        eb.physicalTable("qb_broadcast_stats");
        eb.primaryKey("broadcastId");
        eb.field("broadcastId", f -> f.physicalColumn("broadcast_id").immutable(true).required(true)
            .asReference(BROADCAST));
        eb.field("reads", f -> f.physicalColumn("reads").required(true).asNumeric(9, 0));
        eb.field("clicks", f -> f.physicalColumn("clicks").required(true).asNumeric(9, 0));
        eb.field("conversions", f -> f.physicalColumn("conversions").required(true).asNumeric(9, 0));
        eb.field("snapshotAt", f -> f.physicalColumn("snapshot_at").required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("broadcastId", "reads", "clicks", "conversions", "snapshotAt")
            .filters("broadcastId")
            .sorts("snapshotAt")
            .defaultSort("snapshotAt", false));
    });

    public static final EntityDefinition STATE_ENTITY = EntityDefinition.define(STATE, eb -> {
        eb.physicalTable("qb_inbox_state");
        eb.primaryKey("userId");
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(USER_ID_LENGTH));
        // "Mark all as read": what came before it is read (not counted as read in the statistics, Q7-14).
        eb.field("readAllBefore", f -> f.physicalColumn("read_all_before").asTemporal(TemporalRole.EVENT_TIME));
        // Every broadcast the inbox showed sent before this was read one by one (kept by QB_MESSAGE_MARK, at most
        // READ_THROUGH_MARGIN behind its time): the badge counts only the later ones (review H1). Not a reading.
        eb.field("readThrough", f -> f.physicalColumn("read_through").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("promoOff", f -> f.physicalColumn("promo_off").required(true).asBool());
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("userId", "readAllBefore", "promoOff")
            .filters("userId", "promoOff")
            .sorts("userId")
            .defaultSort("userId", true));
    });

    /** A read-only view of the attempts (qb_attempt_start): no answers, rewards or commissions. */
    public static final EntityDefinition ATTEMPT_START_ENTITY = EntityDefinition.define(ATTEMPT_START, eb -> {
        eb.physicalTable("qb_attempt_start");
        eb.primaryKey("attemptId");
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").immutable(true).required(true).asText(36));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(USER_ID_LENGTH));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        eb.field("startedAt", f -> f.physicalColumn("started_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.listView("default", lv -> lv
            .columns("userId", "publicationId", "startedAt")
            .filters("publicationId")
            .sorts("startedAt")
            .defaultSort("startedAt", false));
    });

    private QbInbox() {}
}
