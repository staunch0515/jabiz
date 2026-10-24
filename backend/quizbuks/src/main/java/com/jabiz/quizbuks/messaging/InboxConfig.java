package com.jabiz.quizbuks.messaging;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.AttemptFinishing;
import com.jabiz.quizbuks.reward.CashRewardProcesses;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.quizbuks.wallet.PayoutProcesses;
import com.jabiz.quizbuks.wallet.QbPayouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Registers the inbox and the announcements (docs/quizbuks/plans/Q7-messages-legal.md, Q7c): the eight entities and
 * their datasets, the dictionaries, the processes, the five subscriptions that make messages, the minute's job that
 * sends the scheduled announcements and the daily one of payout reminders.
 *
 * <p>Every default dataset is written by the processes only ({@code processOnlyWrites}; the write permission is one
 * no role holds) and read by the administrators of content (the broadcasts, their names and feed) or by nobody (the
 * messages, receipts, states and schedule: the system's). A user reads their own rows through the {@code me}
 * datasets and the inbox's templates; a sponsor their own notices through theirs.
 */
@Configuration
public class InboxConfig {

    private final String poolRef;

    InboxConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    MessageTexts qbMessageTexts(MessageCatalog messages) {
        return new MessageTexts(messages);
    }

    @Bean
    EntityDefinition qbMessageEntity() {
        return QbInbox.MESSAGE_ENTITY;
    }

    @Bean
    EntityDefinition qbBroadcastEntity() {
        return QbInbox.BROADCAST_ENTITY;
    }

    @Bean
    EntityDefinition qbBroadcastTargetEntity() {
        return QbInbox.TARGET_ENTITY;
    }

    @Bean
    EntityDefinition qbBroadcastFeedEntity() {
        return QbInbox.FEED_ENTITY;
    }

    @Bean
    EntityDefinition qbBroadcastScheduleEntity() {
        return QbInbox.SCHEDULE_ENTITY;
    }

    @Bean
    EntityDefinition qbBroadcastReceiptEntity() {
        return QbInbox.RECEIPT_ENTITY;
    }

    @Bean
    EntityDefinition qbBroadcastStatsEntity() {
        return QbInbox.STATS_ENTITY;
    }

    @Bean
    EntityDefinition qbInboxStateEntity() {
        return QbInbox.STATE_ENTITY;
    }

    @Bean
    EntityDefinition qbAttemptStartEntity() {
        return QbInbox.ATTEMPT_START_ENTITY;
    }

    private DatasetDefinition processWritten(String entity, String readPermission, int maxWriteBatch) {
        return DatasetDefinition.define(QbInbox.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(readPermission, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatch))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private DatasetDefinition mine(String dataset, String entity, String field) {
        return DatasetDefinition.define(dataset, d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.ACCOUNT_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext(field, "actorId", RequestContext::actorId)));
    }

    /** Personal messages are their recipient's: read by no role through the generic API. */
    @Bean
    DatasetDefinition qbMessageDataset() {
        return processWritten(QbInbox.MESSAGE, QbPermissions.MESSAGING_SYSTEM, 100);
    }

    @Bean
    DatasetDefinition qbBroadcastDataset() {
        return processWritten(QbInbox.BROADCAST, QbPermissions.ADMIN_CONTENT_WRITE, 500);
    }

    /** An announcement's named users are replaced whole: as many deletes and inserts. */
    @Bean
    DatasetDefinition qbBroadcastTargetDataset() {
        return processWritten(QbInbox.TARGET, QbPermissions.ADMIN_CONTENT_WRITE,
            2 * BroadcastRules.MAX_TARGETS + 10);
    }

    @Bean
    DatasetDefinition qbBroadcastFeedDataset() {
        return processWritten(QbInbox.FEED, QbPermissions.ADMIN_CONTENT_WRITE, 500);
    }

    @Bean
    DatasetDefinition qbBroadcastScheduleDataset() {
        return processWritten(QbInbox.SCHEDULE, QbPermissions.MESSAGING_SYSTEM, 500);
    }

    @Bean
    DatasetDefinition qbBroadcastReceiptDataset() {
        return processWritten(QbInbox.RECEIPT, QbPermissions.MESSAGING_SYSTEM, 10);
    }

    /** The daily snapshot of the sponsors' statistics: written by its job, read by the sponsors' template. */
    @Bean
    DatasetDefinition qbBroadcastStatsDataset() {
        return processWritten(QbInbox.STATS, QbPermissions.MESSAGING_SYSTEM, 10);
    }

    @Bean
    DatasetDefinition qbInboxStateDataset() {
        return processWritten(QbInbox.STATE, QbPermissions.MESSAGING_SYSTEM, 10);
    }

    /** Who started which publication when: the statistics' conversions, read by templates only. */
    @Bean
    DatasetDefinition qbAttemptStartDataset() {
        return DatasetDefinition.define(QbInbox.defaultDataset(QbInbox.ATTEMPT_START), d -> d
            .targetEntityType(QbInbox.ATTEMPT_START)
            .asDefault()
            .permissions(QbPermissions.MESSAGING_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbMyMessageDataset() {
        return mine(QbInbox.MY_MESSAGES, QbInbox.MESSAGE, "recipientId");
    }

    @Bean
    DatasetDefinition qbMyReceiptDataset() {
        return mine(QbInbox.MY_RECEIPTS, QbInbox.RECEIPT, "userId");
    }

    @Bean
    DatasetDefinition qbMyTargetDataset() {
        return mine(QbInbox.MY_TARGETS, QbInbox.TARGET, "userId");
    }

    @Bean
    DatasetDefinition qbMyInboxStateDataset() {
        return mine(QbInbox.MY_STATE, QbInbox.STATE, "userId");
    }

    /** A sponsor's own notices, read only. */
    @Bean
    DatasetDefinition qbSponsorBroadcastDataset() {
        return DatasetDefinition.define(QbInbox.SPONSOR_BROADCASTS, d -> d
            .targetEntityType(QbInbox.BROADCAST)
            .permissions(QbPermissions.BROADCAST_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("senderId", "actorId", RequestContext::actorId)));
    }

    // ---- dictionaries ----------------------------------------------------------------------------------------------

    @Bean
    StaticDictionary qbMessageTypeDictionary() {
        return StaticDictionary.define(QbInbox.MESSAGE_TYPE_DICTIONARY, d -> d
            .item(QbInbox.TYPE_SYSTEM, "en", "System", "zh", "系统", "ja", "システム")
            .item(QbInbox.TYPE_REWARD, "en", "Reward", "zh", "奖励", "ja", "報酬")
            .item(QbInbox.TYPE_REMINDER, "en", "Reminder", "zh", "提醒", "ja", "リマインダー"));
    }

    @Bean
    StaticDictionary qbMessageKindDictionary() {
        return StaticDictionary.define(QbInbox.MESSAGE_KIND_DICTIONARY, d -> d
            .item(QbInbox.REWARD_PAID, "en", "Reward paid", "zh", "奖励到账", "ja", "報酬の受け取り")
            .item(QbInbox.REWARD_PENDING, "en", "Reward pending", "zh", "奖励处理中", "ja", "報酬の処理中")
            .item(QbInbox.TRANSFER_SENT, "en", "Transfer sent", "zh", "转账已发出", "ja", "送金済み")
            .item(QbInbox.TRANSFER_FAILED, "en", "Transfer failed", "zh", "转账失败", "ja", "送金の失敗")
            .item(QbInbox.PAYOUT_ACTION_NEEDED, "en", "Payout account needs information", "zh", "收款账户需补资料",
                "ja", "受取口座の情報が必要")
            .item(QbInbox.PAYOUT_REMINDER, "en", "Open a payout account", "zh", "开通收款账户", "ja",
                "受取口座の開設"));
    }

    @Bean
    StaticDictionary qbBroadcastTypeDictionary() {
        return StaticDictionary.define(QbInbox.BROADCAST_TYPE_DICTIONARY, d -> d
            .item(QbInbox.BROADCAST_SYSTEM, "en", "System announcement", "zh", "系统公告", "ja", "システムのお知らせ")
            .item(QbInbox.BROADCAST_NOTICE, "en", "Notice", "zh", "通知", "ja", "お知らせ")
            .item(QbInbox.BROADCAST_PROMO, "en", "Promotion", "zh", "推广", "ja", "プロモーション"));
    }

    @Bean
    StaticDictionary qbBroadcastStatusDictionary() {
        return StaticDictionary.define(QbInbox.BROADCAST_STATUS_DICTIONARY, d -> d
            .item(QbInbox.DRAFT, "en", "Draft", "zh", "草稿", "ja", "下書き")
            .item(QbInbox.SCHEDULED, "en", "Scheduled", "zh", "已定时", "ja", "予約済み")
            .item(QbInbox.SENT, "en", "Sent", "zh", "已发送", "ja", "送信済み")
            .item(QbInbox.CANCELLED, "en", "Cancelled", "zh", "已取消", "ja", "取り消し")
            .item(QbInbox.WITHDRAWN, "en", "Withdrawn", "zh", "已撤回", "ja", "取り下げ"));
    }

    @Bean
    StaticDictionary qbBroadcastAudienceDictionary() {
        return StaticDictionary.define(QbInbox.AUDIENCE_DICTIONARY, d -> d
            .item(QbInbox.AUDIENCE_APP, "en", "App", "zh", "App", "ja", "アプリ")
            .item(QbInbox.AUDIENCE_SPONSOR, "en", "Sponsor console", "zh", "商家后台", "ja", "スポンサー管理画面"));
    }

    @Bean
    StaticDictionary qbBroadcastTargetDictionary() {
        return StaticDictionary.define(QbInbox.TARGET_MODE_DICTIONARY, d -> d
            .item(QbInbox.TARGET_ALL, "en", "Everyone", "zh", "全体", "ja", "全員")
            .item(QbInbox.TARGET_REGIONS, "en", "Regions", "zh", "指定地区", "ja", "地域指定")
            .item(QbInbox.TARGET_USERS, "en", "Named users", "zh", "指定用户", "ja", "ユーザー指定"));
    }

    @Bean
    StaticDictionary qbBroadcastSenderDictionary() {
        return StaticDictionary.define(QbInbox.SENDER_DICTIONARY, d -> d
            .item(QbInbox.SENDER_PLATFORM, "en", "QuizBuks", "zh", "QuizBuks", "ja", "QuizBuks")
            .item(QbInbox.SENDER_SPONSOR, "en", "Sponsor", "zh", "商家", "ja", "スポンサー"));
    }

    // ---- processes -------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext> qbMessageFromEvent(
        MessageTexts qbMessageTexts) {
        return MessageProcesses.fromEvent(qbMessageTexts);
    }

    @Bean
    ProcessDefinition<MessageProcesses.ReminderInput, MessageProcesses.MessageOutput, ProcessContext>
        qbPayoutReminder(MessageTexts qbMessageTexts) {
        return MessageProcesses.payoutReminder(qbMessageTexts);
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.SponsorSaveInput, BroadcastProcesses.SaveOutput, ProcessContext>
        qbBroadcastSave(MessageCatalog messages) {
        return BroadcastProcesses.sponsorSave(messages);
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.AdminSaveInput, BroadcastProcesses.SaveOutput, ProcessContext>
        qbAdminBroadcastSave(MessageCatalog messages) {
        return BroadcastProcesses.adminSave(messages);
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.BroadcastRef, BroadcastProcesses.BroadcastOutput, ProcessContext>
        qbBroadcastSend() {
        return BroadcastProcesses.sponsorSend();
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.BroadcastRef, BroadcastProcesses.BroadcastOutput, ProcessContext>
        qbAdminBroadcastSend() {
        return BroadcastProcesses.adminSend();
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.BroadcastRef, BroadcastProcesses.BroadcastOutput, ProcessContext>
        qbBroadcastCancel() {
        return BroadcastProcesses.sponsorCancel();
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.BroadcastRef, BroadcastProcesses.BroadcastOutput, ProcessContext>
        qbAdminBroadcastCancel() {
        return BroadcastProcesses.adminCancel();
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.BroadcastRef, BroadcastProcesses.BroadcastOutput, ProcessContext>
        qbBroadcastWithdraw() {
        return BroadcastProcesses.sponsorWithdraw();
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.WithdrawInput, BroadcastProcesses.BroadcastOutput, ProcessContext>
        qbAdminBroadcastWithdraw() {
        return BroadcastProcesses.adminWithdraw();
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.SponsorRef, BroadcastProcesses.BanOutput, ProcessContext>
        qbBroadcastBanWithdraw() {
        return BroadcastProcesses.banWithdraw();
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.DispatchInput, BroadcastProcesses.BroadcastOutput, ProcessContext>
        qbBroadcastDispatch() {
        return BroadcastProcesses.dispatch();
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.DispatchInput, BroadcastProcesses.JobOutput, ProcessContext>
        qbBroadcastExpire() {
        return BroadcastProcesses.expire();
    }

    @Bean
    ProcessDefinition<BroadcastProcesses.StatsInput, BroadcastProcesses.JobOutput, ProcessContext>
        qbBroadcastStats() {
        return BroadcastProcesses.stats();
    }

    @Bean
    ProcessDefinition<InboxProcesses.MarkInput, InboxProcesses.MarkOutput, ProcessContext> qbMessageMark() {
        return InboxProcesses.mark();
    }

    @Bean
    ProcessDefinition<InboxProcesses.ReadAllInput, InboxProcesses.StateOutput, ProcessContext> qbInboxReadAll() {
        return InboxProcesses.readAll();
    }

    @Bean
    ProcessDefinition<InboxProcesses.SettingsInput, InboxProcesses.StateOutput, ProcessContext> qbInboxSettings() {
        return InboxProcesses.settings();
    }

    // ---- jobs ------------------------------------------------------------------------------------------------------

    /**
     * Every minute: each announcement or notice whose time has come ({@value QbInbox#DUE_TEMPLATE}) is sent by
     * {@value BroadcastProcesses#DISPATCH} in a transaction of its own (decision D42), at most
     * {@link BroadcastProcesses#DISPATCH_BATCH} a run; a run finds everything due, so the minutes missed are made up.
     */
    @Bean
    JobDefinition<BroadcastProcesses.DispatchInput> qbBroadcastDispatchJob(
        @Value("${quizbuks.messaging.dispatch-cron:0 * * * * *}") String cron,
        ProcessDefinition<BroadcastProcesses.DispatchInput, BroadcastProcesses.BroadcastOutput, ProcessContext>
            qbBroadcastDispatch) {
        return JobDefinition.forEach(QbInbox.DISPATCH_JOB, cron, ZoneOffset.UTC,
                JobItems.template(QbInbox.DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime), "broadcastId"),
                qbBroadcastDispatch, (scheduledTime, row) -> new BroadcastProcesses.DispatchInput(
                    UUID.fromString(String.valueOf(row.get("broadcastId")))))
            .maxItems(BroadcastProcesses.DISPATCH_BATCH);
    }

    /**
     * Every minute (review H1): each live row of the feed that expired, or whose publication ended, is switched off by
     * {@value BroadcastProcesses#EXPIRE} in a transaction of its own, so the inbox's partial index holds only the
     * current ones.
     */
    @Bean
    JobDefinition<BroadcastProcesses.DispatchInput> qbBroadcastExpireJob(
        @Value("${quizbuks.messaging.expire-cron:30 * * * * *}") String cron,
        ProcessDefinition<BroadcastProcesses.DispatchInput, BroadcastProcesses.JobOutput, ProcessContext>
            qbBroadcastExpire) {
        return JobDefinition.forEach(QbInbox.EXPIRE_JOB, cron, ZoneOffset.UTC,
                JobItems.template(QbInbox.EXPIRED_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "broadcastId"),
                qbBroadcastExpire, (scheduledTime, row) -> new BroadcastProcesses.DispatchInput(
                    UUID.fromString(String.valueOf(row.get("broadcastId")))))
            .maxItems(BroadcastProcesses.EXPIRE_BATCH);
    }

    /**
     * Daily (review M2): the counts of the sponsors' recent notices that changed, each written by
     * {@value BroadcastProcesses#STATS} in a transaction of its own; the sponsors read only this snapshot.
     */
    @Bean
    JobDefinition<BroadcastProcesses.StatsInput> qbBroadcastStatsJob(
        @Value("${quizbuks.messaging.stats-cron:0 47 4 * * *}") String cron,
        ProcessDefinition<BroadcastProcesses.StatsInput, BroadcastProcesses.JobOutput, ProcessContext>
            qbBroadcastStats) {
        return JobDefinition.forEach(QbInbox.STATS_JOB, cron, ZoneOffset.UTC,
                JobItems.template(QbInbox.STATS_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "broadcastId"),
                qbBroadcastStats, (scheduledTime, row) -> new BroadcastProcesses.StatsInput(
                    UUID.fromString(String.valueOf(row.get("broadcastId"))), count(row.get("reads")),
                    count(row.get("clicks")), count(row.get("conversions"))))
            .maxItems(BroadcastProcesses.STATS_BATCH);
    }

    private static Long count(Object value) {
        return value == null ? 0L : new java.math.BigDecimal(String.valueOf(value)).longValue();
    }

    /** Daily (Q7-16): the takers to remind of a payout account, each in a transaction of its own. */
    @Bean
    JobDefinition<MessageProcesses.ReminderInput> qbPayoutReminderJob(
        @Value("${quizbuks.messaging.payout-reminder-cron:0 17 3 * * *}") String cron,
        ProcessDefinition<MessageProcesses.ReminderInput, MessageProcesses.MessageOutput, ProcessContext>
            qbPayoutReminder) {
        return JobDefinition.forEach(QbInbox.REMINDER_JOB, cron, ZoneOffset.UTC,
                JobItems.template(QbInbox.REMINDERS_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime), "userId"),
                qbPayoutReminder, (scheduledTime, row) -> new MessageProcesses.ReminderInput(
                    String.valueOf(row.get("userId"))))
            .maxItems(5_000);
    }

    // ---- subscriptions ---------------------------------------------------------------------------------------------

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageRewardSettledSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.REWARD_SETTLED_CONSUMER, AttemptFinishing.SETTLED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.rewardInput(event, QbInbox.REWARD_PAID));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageRewardPendingSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.REWARD_PENDING_CONSUMER, AttemptFinishing.PENDING_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.rewardInput(event, QbInbox.REWARD_PENDING));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageTransferSentSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.TRANSFER_SENT_CONSUMER, QbPayouts.SENT_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.transferInput(event, QbInbox.TRANSFER_SENT));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageTransferFailedSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.TRANSFER_FAILED_CONSUMER, QbPayouts.FAILED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.transferInput(event, QbInbox.TRANSFER_FAILED));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessagePayoutRestrictedSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.PAYOUT_RESTRICTED_CONSUMER, PayoutProcesses.RESTRICTED_EVENT,
            qbMessageFromEvent, MessageProcesses::payoutInput);
    }

    // Q22: the cash reward's steps, each to the side that must hear of it, and the prize to collect.

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageCashDueSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.CASH_DUE_CONSUMER, AttemptFinishing.CASH_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.cashInput(event, QbInbox.CASH_DUE));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageCashMarkedPaidSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.CASH_MARKED_PAID_CONSUMER, CashRewardProcesses.MARKED_PAID_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.cashInput(event, QbInbox.CASH_MARKED_PAID));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageCashConfirmedSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.CASH_CONFIRMED_CONSUMER, CashRewardProcesses.CONFIRMED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.cashInput(event, QbInbox.CASH_CONFIRMED));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageCashDisputedSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.CASH_DISPUTED_CONSUMER, CashRewardProcesses.DISPUTED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.cashInput(event, QbInbox.CASH_DISPUTED));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageCashResolvedSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.CASH_RESOLVED_CONSUMER, CashRewardProcesses.RESOLVED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.cashInput(event, QbInbox.CASH_RESOLVED));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageCashResolvedSponsorSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.CASH_RESOLVED_SPONSOR_CONSUMER,
            CashRewardProcesses.RESOLVED_EVENT, qbMessageFromEvent,
            event -> MessageProcesses.cashInput(event, QbInbox.CASH_RESOLVED_SPONSOR));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageCashOverdueSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.CASH_OVERDUE_CONSUMER, CashRewardProcesses.OVERDUE_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.cashInput(event, QbInbox.CASH_OVERDUE));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageRedeemClaimedSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.REDEEM_CLAIMED_CONSUMER, RedemptionProcesses.CLAIMED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.redeemInput(event, QbInbox.REDEEM_CLAIMED));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageRedeemRedeemedSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.REDEEM_REDEEMED_CONSUMER, RedemptionProcesses.REDEEMED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.redeemInput(event, QbInbox.REDEEM_REDEEMED));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageRedeemDeclinedSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.REDEEM_DECLINED_CONSUMER, RedemptionProcesses.DECLINED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.redeemInput(event, QbInbox.REDEEM_DECLINED));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageRedeemExpiredSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.REDEEM_EXPIRED_CONSUMER, RedemptionProcesses.EXPIRED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.redeemInput(event, QbInbox.REDEEM_EXPIRED));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageRedeemVoidSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.REDEEM_VOID_CONSUMER, RedemptionProcesses.VOIDED_EVENT,
            qbMessageFromEvent, event -> MessageProcesses.redeemInput(event, QbInbox.REDEEM_VOID));
    }

    @Bean
    EventSubscription<MessageProcesses.EventInput> qbMessageRedeemDueSubscription(
        ProcessDefinition<MessageProcesses.EventInput, MessageProcesses.MessageOutput, ProcessContext>
            qbMessageFromEvent) {
        return EventSubscription.of(MessageProcesses.REDEEM_DUE_CONSUMER, AttemptFinishing.REDEEM_EVENT,
            qbMessageFromEvent, MessageProcesses::redeemInput);
    }
}
