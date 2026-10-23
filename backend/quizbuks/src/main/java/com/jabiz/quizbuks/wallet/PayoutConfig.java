package com.jabiz.quizbuks.wallet;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.AttemptFinishing;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.inbound.InboundEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.Map;

/**
 * Registers payout accounts, the takers' wallet cache, automatic transfers and the platform's bank payouts
 * (docs/quizbuks/plans/Q6-payments.md, Q6b): the settings, entities with their datasets and dictionaries, the approval
 * subject, the jobs that call Stripe, the processes, the consumers of Stripe's and QuizBuks's events and the two
 * scheduled jobs.
 */
@Configuration
class PayoutConfig {

    private final String poolRef;

    PayoutConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    PayoutSettings payoutSettings(@Value("${quizbuks.stripe.connect-country:JP}") String country,
        @Value("${quizbuks.links.app:}") String appLink,
        @Value("${quizbuks.stripe.connect-url-prefix:" + PayoutSettings.STRIPE_CONNECT + "}") String prefix) {
        return new PayoutSettings(country, appLink, prefix);
    }

    // ---- entities, datasets, dictionaries --------------------------------------------------------------------------

    @Bean
    EntityDefinition qbPayoutAccountEntity() {
        return QbPayouts.ACCOUNT_ENTITY;
    }

    @Bean
    EntityDefinition qbPayoutOnboardingEntity() {
        return QbPayouts.ONBOARDING_ENTITY;
    }

    @Bean
    EntityDefinition qbTransferEntity() {
        return QbPayouts.TRANSFER_ENTITY;
    }

    @Bean
    EntityDefinition qbStripePayoutEntity() {
        return QbPayouts.STRIPE_PAYOUT_ENTITY;
    }

    @Bean
    EntityDefinition qbBankAccountEntity() {
        return QbPayouts.BANK_ACCOUNT_ENTITY;
    }

    @Bean
    DatasetDefinition qbBankAccountDataset() {
        return processOnly(QbPayouts.BANK_ACCOUNT, 10);
    }

    /** A taker's own bank account, read only (the number masked: they typed it). */
    @Bean
    DatasetDefinition qbMyBankAccountDataset() {
        return mine(QbPayouts.MY_BANK_ACCOUNT, QbPayouts.BANK_ACCOUNT);
    }

    @Bean
    StaticDictionary qbBankAccountTypeDictionary() {
        return StaticDictionary.define(QbPayouts.BANK_ACCOUNT_TYPE_DICTIONARY, d -> d
            .item("ORDINARY", "en", "Ordinary", "zh", "普通", "ja", "普通")
            .item("CURRENT", "en", "Current", "zh", "当座", "ja", "当座")
            .item("SAVINGS", "en", "Savings", "zh", "储蓄", "ja", "貯蓄"));
    }

    @Bean
    StaticDictionary qbPayoutMethodDictionary() {
        return StaticDictionary.define(QbPayouts.PAYOUT_METHOD_DICTIONARY, d -> d
            .item(QbPayouts.STRIPE, "en", "Stripe", "zh", "Stripe", "ja", "Stripe")
            .item(QbPayouts.BANK, "en", "Bank account", "zh", "银行账户", "ja", "銀行口座"));
    }

    @Bean
    com.jabiz.mail.MailTemplate qbBankAccountChangedMail() {
        return BankWithdrawals.ACCOUNT_CHANGED_MAIL;
    }

    @Bean
    ProcessDefinition<BankWithdrawals.AccountInput, BankWithdrawals.AccountOutput, ProcessContext>
        qbBankAccountSave() {
        return BankWithdrawals.saveAccount();
    }

    @Bean
    ProcessDefinition<BankWithdrawals.MethodInput, BankWithdrawals.MethodOutput, ProcessContext> qbPayoutMethodSet() {
        return BankWithdrawals.setMethod();
    }

    @Bean
    ProcessDefinition<BankWithdrawals.RequestInput, TransferProcesses.TransferOutput, ProcessContext>
        qbWithdrawalRequest() {
        return BankWithdrawals.request();
    }

    @Bean
    ProcessDefinition<BankWithdrawals.PaidInput, TransferProcesses.TransferOutput, ProcessContext> qbWithdrawalPaid() {
        return BankWithdrawals.paid();
    }

    @Bean
    ProcessDefinition<BankWithdrawals.ReturnedInput, TransferProcesses.TransferOutput, ProcessContext>
        qbWithdrawalReturned() {
        return BankWithdrawals.returned();
    }

    @Bean
    DatasetDefinition qbPayoutAccountDataset() {
        return processOnly(QbPayouts.ACCOUNT, 10);
    }

    /**
     * Read by the system only (a permission no role holds): whoever follows a pending account's link could put their
     * own bank account on it. The taker reads theirs through {@link QbPayouts#MY_ONBOARDING}.
     */
    @Bean
    DatasetDefinition qbPayoutOnboardingDataset() {
        return processOnly(QbPayouts.ONBOARDING, 10, QbPermissions.PAYOUT_SYSTEM);
    }

    @Bean
    DatasetDefinition qbTransferDataset() {
        return processOnly(QbPayouts.TRANSFER, 10);
    }

    @Bean
    DatasetDefinition qbStripePayoutDataset() {
        return processOnly(QbPayouts.STRIPE_PAYOUT, 10);
    }

    /** A taker's own payout account, read only. */
    @Bean
    DatasetDefinition qbMyPayoutAccountDataset() {
        return mine(QbPayouts.MY_ACCOUNT, QbPayouts.ACCOUNT);
    }

    /** A taker's own onboarding link, read only: nobody else's dataset gives it. */
    @Bean
    DatasetDefinition qbMyPayoutOnboardingDataset() {
        return mine(QbPayouts.MY_ONBOARDING, QbPayouts.ONBOARDING);
    }

    /** A taker's own transfers, read only. */
    @Bean
    DatasetDefinition qbMyTransferDataset() {
        return mine(QbPayouts.MY_TRANSFERS, QbPayouts.TRANSFER);
    }

    private DatasetDefinition processOnly(String entity, int maxWriteBatch) {
        return processOnly(entity, maxWriteBatch, QbPermissions.ADMIN_FINANCE_READ);
    }

    private DatasetDefinition processOnly(String entity, int maxWriteBatch, String readPermission) {
        return DatasetDefinition.define(QbPayouts.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(readPermission, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatch))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private DatasetDefinition mine(String id, String entity) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbPayoutAccountStatusDictionary() {
        return StaticDictionary.define(QbPayouts.ACCOUNT_STATUS_DICTIONARY, d -> d
            .item(PayoutAccountStatus.PENDING, "en", "Being set up", "zh", "开通中", "ja", "設定中")
            .item(PayoutAccountStatus.ENABLED, "en", "Enabled", "zh", "已开通", "ja", "有効")
            .item(PayoutAccountStatus.RESTRICTED, "en", "Restricted", "zh", "受限", "ja", "制限中")
            .item(PayoutAccountStatus.DEAUTHORIZED, "en", "Disconnected", "zh", "已解除", "ja", "連携解除"));
    }

    @Bean
    StaticDictionary qbTransferStatusDictionary() {
        return StaticDictionary.define(QbPayouts.TRANSFER_STATUS_DICTIONARY, d -> d
            .item(TransferStatus.REVIEW, "en", "In review", "zh", "审核中", "ja", "審査中")
            .item(TransferStatus.SENDING, "en", "Sending", "zh", "转账中", "ja", "送金中")
            .item(TransferStatus.SENT, "en", "Sent", "zh", "已转账", "ja", "送金済み")
            .item(TransferStatus.FAILED, "en", "Failed", "zh", "失败", "ja", "失敗")
            .item(TransferStatus.CHECK, "en", "Being checked", "zh", "核实中", "ja", "確認中")
            .item(TransferStatus.REJECTED, "en", "Rejected", "zh", "已驳回", "ja", "却下")
            .item(TransferStatus.REVERSED, "en", "Reversed", "zh", "已撤回", "ja", "取消済み"));
    }

    @Bean
    ApprovalSubject qbTransferApprovalSubject() {
        return QbPayouts.approvalSubject();
    }

    // ---- jobs of Stripe --------------------------------------------------------------------------------------------

    @Bean
    ExternalJobType<PayoutJobs.OnboardInput, PayoutObjects.Onboarding> qbConnectOnboardJob() {
        return PayoutJobs.ONBOARD_JOB;
    }

    @Bean
    ExternalJobType<PayoutJobs.TransferInput, PayoutObjects.TransferLookup> qbTransferJob() {
        return PayoutJobs.TRANSFER_JOB;
    }

    @Bean
    ExternalJobType<PayoutJobs.TransferInput, PayoutObjects.TransferLookup> qbTransferCheckJob() {
        return PayoutJobs.TRANSFER_CHECK_JOB;
    }

    // ---- processes -------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<PayoutProcesses.OnboardRequest, PayoutProcesses.OnboardOutput, ProcessContext> qbPayoutOnboard(
        StripeSettings stripe, PayoutSettings settings) {
        return PayoutProcesses.onboard(stripe, settings);
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, PayoutProcesses.AccountOutput, ProcessContext> qbPayoutOnboardReady(
        StripeSettings stripe, PayoutSettings settings) {
        return PayoutProcesses.onboardReady(stripe, settings);
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, PayoutProcesses.AccountOutput, ProcessContext> qbPayoutOnboardFailed() {
        return PayoutProcesses.onboardFailed();
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, PayoutProcesses.AccountOutput, ProcessContext> qbPayoutAccountUpdated(
        StripeSettings stripe) {
        return PayoutProcesses.accountUpdated(stripe);
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, PayoutProcesses.AccountOutput, ProcessContext>
        qbPayoutAccountDeauthorized(StripeSettings stripe) {
        return PayoutProcesses.accountDeauthorized(stripe);
    }

    @Bean
    ProcessDefinition<TakerWallets.TakerRef, TakerWallets.SyncOutput, ProcessContext> qbTakerWalletSync() {
        return TakerWallets.sync();
    }

    @Bean
    ProcessDefinition<TakerWallets.ResyncInput, TakerWallets.ResyncOutput, ProcessContext> qbTakerWalletResync() {
        return TakerWallets.resync();
    }

    @Bean
    ProcessDefinition<TransferProcesses.UserRef, TransferProcesses.TransferOutput, ProcessContext> qbTransferCreate(
        StripeSettings stripe) {
        return TransferProcesses.create(stripe);
    }

    @Bean
    ProcessDefinition<TransferProcesses.UserRef, TransferProcesses.HoldReleaseOutput, ProcessContext>
        qbTransferHoldRelease() {
        return TransferProcesses.holdRelease();
    }

    @Bean
    ProcessDefinition<TransferProcesses.ApprovalResultInput, TransferProcesses.TransferOutput, ProcessContext>
        qbTransferApprovalResult(StripeSettings stripe) {
        return TransferProcesses.approvalResult(stripe);
    }

    @Bean
    ProcessDefinition<TransferProcesses.UserRef, TransferProcesses.TransferOutput, ProcessContext> qbTransferNow(
        StripeSettings stripe) {
        return TransferProcesses.now(stripe);
    }

    @Bean
    ProcessDefinition<TransferProcesses.UserRef, TransferProcesses.PreviewOutput, ProcessContext>
        qbTransferPreview() {
        return TransferProcesses.preview();
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, TransferProcesses.TransferOutput, ProcessContext> qbTransferSent(
        StripeSettings stripe) {
        return TransferProcesses.sent(stripe);
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, TransferProcesses.TransferOutput, ProcessContext> qbTransferSendFailed() {
        return TransferProcesses.sendFailed();
    }

    @Bean
    ProcessDefinition<TransferProcesses.TransferRef, TransferProcesses.TransferOutput, ProcessContext>
        qbTransferCheck() {
        return TransferProcesses.check();
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, TransferProcesses.TransferOutput, ProcessContext> qbTransferChecked(
        StripeSettings stripe) {
        return TransferProcesses.checked(stripe);
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, TransferProcesses.TransferOutput, ProcessContext> qbTransferCheckFailed() {
        return TransferProcesses.checkFailed();
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, TransferProcesses.TransferOutput, ProcessContext> qbTransferReversed(
        StripeSettings stripe) {
        return TransferProcesses.reversed(stripe);
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, StripePayouts.PayoutOutput, ProcessContext> qbStripePayoutRecord(
        StripeSettings stripe) {
        return StripePayouts.paid(stripe);
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, StripePayouts.PayoutOutput, ProcessContext> qbStripePayoutFailed(
        StripeSettings stripe) {
        return StripePayouts.failed(stripe);
    }

    // ---- events ----------------------------------------------------------------------------------------------------

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbPayoutAccountSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, PayoutProcesses.AccountOutput, ProcessContext>
            qbPayoutAccountUpdated) {
        return EventSubscription.of(PayoutProcesses.UPDATED_CONSUMER,
            InboundEvents.type(StripeService.PROVIDER_CONNECT, StripeService.ACCOUNT_UPDATED), qbPayoutAccountUpdated,
            TopUpEvents::input);
    }

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbPayoutAccountDeauthSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, PayoutProcesses.AccountOutput, ProcessContext>
            qbPayoutAccountDeauthorized) {
        return EventSubscription.of(PayoutProcesses.DEAUTHORIZED_CONSUMER,
            InboundEvents.type(StripeService.PROVIDER_CONNECT, StripeService.ACCOUNT_DEAUTHORIZED),
            qbPayoutAccountDeauthorized, TopUpEvents::input);
    }

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbTransferReversedSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, TransferProcesses.TransferOutput, ProcessContext>
            qbTransferReversed) {
        return EventSubscription.of(TransferProcesses.REVERSED_CONSUMER,
            InboundEvents.type(StripeService.PROVIDER, StripeService.TRANSFER_REVERSED), qbTransferReversed,
            TopUpEvents::input);
    }

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbStripePayoutSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, StripePayouts.PayoutOutput, ProcessContext> qbStripePayoutRecord) {
        return EventSubscription.of(StripePayouts.PAID_CONSUMER,
            InboundEvents.type(StripeService.PROVIDER, StripeService.PAYOUT_PAID), qbStripePayoutRecord,
            TopUpEvents::input);
    }

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbStripePayoutFailedSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, StripePayouts.PayoutOutput, ProcessContext> qbStripePayoutFailed) {
        return EventSubscription.of(StripePayouts.FAILED_CONSUMER,
            InboundEvents.type(StripeService.PROVIDER, StripeService.PAYOUT_FAILED), qbStripePayoutFailed,
            TopUpEvents::input);
    }

    /** Every reward settled (paid at once or later) changes the taker's 2120: their cache follows. */
    @Bean
    EventSubscription<TakerWallets.TakerRef> qbTakerWalletRewardSubscription(
        ProcessDefinition<TakerWallets.TakerRef, TakerWallets.SyncOutput, ProcessContext> qbTakerWalletSync) {
        return EventSubscription.of(TakerWallets.REWARD_CONSUMER, AttemptFinishing.SETTLED_EVENT, qbTakerWalletSync,
            TakerWallets::input);
    }

    @Bean
    EventSubscription<TakerWallets.TakerRef> qbTakerWalletTransferSubscription(
        ProcessDefinition<TakerWallets.TakerRef, TakerWallets.SyncOutput, ProcessContext> qbTakerWalletSync) {
        return EventSubscription.of(TakerWallets.TRANSFER_CONSUMER, QbPayouts.CHANGED_EVENT, qbTakerWalletSync,
            TakerWallets::input);
    }

    @Bean
    EventSubscription<TakerWallets.ResyncInput> qbTakerWalletResyncSubscription(
        ProcessDefinition<TakerWallets.ResyncInput, TakerWallets.ResyncOutput, ProcessContext> qbTakerWalletResync) {
        return EventSubscription.of(TakerWallets.RESYNC_CONSUMER, TakerWallets.RESYNC_EVENT, qbTakerWalletResync,
            TakerWallets::resyncInput);
    }

    @Bean
    EventSubscription<TransferProcesses.ApprovalResultInput> qbTransferApprovedSubscription(
        ProcessDefinition<TransferProcesses.ApprovalResultInput, TransferProcesses.TransferOutput, ProcessContext>
            qbTransferApprovalResult) {
        return EventSubscription.of("qb.transfer-approved", ApprovalProcesses.APPROVED_EVENT,
            qbTransferApprovalResult, TransferProcesses::resultInput);
    }

    @Bean
    EventSubscription<TransferProcesses.ApprovalResultInput> qbTransferRejectedSubscription(
        ProcessDefinition<TransferProcesses.ApprovalResultInput, TransferProcesses.TransferOutput, ProcessContext>
            qbTransferApprovalResult) {
        return EventSubscription.of("qb.transfer-rejected", ApprovalProcesses.REJECTED_EVENT,
            qbTransferApprovalResult, TransferProcesses::resultInput);
    }

    // ---- scheduled jobs --------------------------------------------------------------------------------------------

    /**
     * Every day at 01:07 UTC (10:07 in Tokyo; D-Q6-12): the takers to transfer to, each in a transaction of its own
     * under their wallet lock (platform decision D42). With transfers off nothing is selected. The schedule is
     * {@code quizbuks.transfers.run-cron}: only the end-to-end tests change it (every minute, Q9c), and
     * {@link TransferScheduleCheck} refuses that next to live mode or a live key.
     */
    @Bean
    JobDefinition<TransferProcesses.UserRef> qbTransferRunJob(
        ProcessDefinition<TransferProcesses.UserRef, TransferProcesses.TransferOutput, ProcessContext>
            qbTransferCreate,
        @Value("${" + TransferProcesses.RUN_CRON_SETTING + ":" + TransferProcesses.RUN_CRON + "}") String cron) {
        return JobDefinition.forEach(TransferProcesses.RUN_JOB, cron, ZoneOffset.UTC,
                JobItems.template(TransferProcesses.DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "userId"),
                qbTransferCreate,
                (scheduledTime, row) -> new TransferProcesses.UserRef(String.valueOf(row.get("userId"))))
            .maxItems(TransferProcesses.RUN_BATCH);
    }

    /** Every hour at minute 47: the transfers in doubt, each asked about at Stripe. */
    @Bean
    JobDefinition<TransferProcesses.TransferRef> qbTransferStaleJob(
        ProcessDefinition<TransferProcesses.TransferRef, TransferProcesses.TransferOutput, ProcessContext>
            qbTransferCheck) {
        return JobDefinition.forEach(TransferProcesses.STALE_JOB, "0 47 * * * *", ZoneOffset.UTC,
                JobItems.template(TransferProcesses.STALE_TEMPLATE, scheduledTime -> Map.of(
                    "sendingBefore", scheduledTime.minus(TransferProcesses.STALE_AFTER),
                    "askedBefore", scheduledTime.minus(TransferProcesses.ASK_AGAIN_AFTER)), "transferId"),
                qbTransferCheck,
                (scheduledTime, row) -> new TransferProcesses.TransferRef(String.valueOf(row.get("transferId"))))
            .maxItems(TransferProcesses.STALE_BATCH);
    }
}
