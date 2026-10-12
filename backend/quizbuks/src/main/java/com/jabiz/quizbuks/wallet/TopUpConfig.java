package com.jabiz.quizbuks.wallet;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.ExternalService;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountLinks;
import com.jabiz.retention.RetentionPolicy;
import com.jabiz.runtime.inbound.InboundEntities;
import com.jabiz.runtime.inbound.InboundEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Registers top-ups and the daily reconciliation (docs/quizbuks/plans/Q6-payments.md, Q6a): Stripe's service and
 * settings, the top-up and reconciliation entities with their datasets and dictionaries, the jobs that call Stripe,
 * the processes, the consumers of Stripe's events and the two scheduled jobs.
 */
@Configuration
class TopUpConfig {

    private final String poolRef;

    TopUpConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    StripeSettings stripeSettings(@Value("${quizbuks.stripe.livemode:false}") boolean livemode,
        @Value("${quizbuks.stripe.api-version:2025-03-31.basil}") String apiVersion,
        @Value("${quizbuks.stripe.checkout-ttl:PT1H}") Duration checkoutTtl,
        @Value("${quizbuks.stripe.checkout-url-prefix:" + StripeSettings.STRIPE_CHECKOUT + "}") String urlPrefix,
        AccountLinks links) {
        return new StripeSettings(livemode, apiVersion, checkoutTtl, urlPrefix, links.sponsor());
    }

    @Bean
    ExternalService stripeService() {
        return StripeService.DEFINITION;
    }

    // ---- entities, datasets, dictionaries --------------------------------------------------------------------------

    @Bean
    EntityDefinition qbTopUpEntity() {
        return QbTopUps.TOPUP_ENTITY;
    }

    /** Finance administrators read every top-up; only the processes write them. */
    @Bean
    DatasetDefinition qbTopUpDataset() {
        return processOnly(QbTopUps.defaultDataset(QbTopUps.TOPUP), QbTopUps.TOPUP, QbPermissions.ADMIN_FINANCE_READ,
            100);
    }

    /** A sponsor's own top-ups, read only (the checkout's address included: it is theirs). */
    @Bean
    DatasetDefinition qbSponsorTopUpDataset() {
        return DatasetDefinition.define(QbTopUps.SPONSOR_TOPUPS, d -> d
            .targetEntityType(QbTopUps.TOPUP)
            .permissions(QbPermissions.SPONSOR_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    @Bean
    EntityDefinition qbReconRunEntity() {
        return Reconciliation.RUN_ENTITY;
    }

    @Bean
    EntityDefinition qbReconItemEntity() {
        return Reconciliation.ITEM_ENTITY;
    }

    @Bean
    DatasetDefinition qbReconRunDataset() {
        return processOnly(Reconciliation.defaultDataset(Reconciliation.RUN_ENTITY_NAME),
            Reconciliation.RUN_ENTITY_NAME, QbPermissions.RECONCILE, 10);
    }

    @Bean
    DatasetDefinition qbReconItemDataset() {
        return processOnly(Reconciliation.defaultDataset(Reconciliation.ITEM_ENTITY_NAME),
            Reconciliation.ITEM_ENTITY_NAME, QbPermissions.RECONCILE, WalletLimits.MAX_RECON_ITEMS + 10);
    }

    private DatasetDefinition processOnly(String id, String entity, String readPermission, int maxWriteBatch) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(readPermission, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatch))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbTopUpStatusDictionary() {
        return StaticDictionary.define(QbTopUps.STATUS_DICTIONARY, d -> d
            .item(TopUpStatus.OPENING, "en", "Opening", "zh", "创建中", "ja", "作成中")
            .item(TopUpStatus.OPEN, "en", "Awaiting payment", "zh", "待支付", "ja", "支払い待ち")
            .item(TopUpStatus.PAID, "en", "Paid", "zh", "已到账", "ja", "入金済み")
            .item(TopUpStatus.EXPIRED, "en", "Expired", "zh", "已过期", "ja", "期限切れ")
            .item(TopUpStatus.FAILED, "en", "Failed", "zh", "失败", "ja", "失敗"));
    }

    @Bean
    StaticDictionary qbReconCheckDictionary() {
        return StaticDictionary.define(Reconciliation.CHECK_DICTIONARY, d -> d
            .item(ReconChecks.SPONSOR_WALLET, "en", "Sponsor wallet", "zh", "商家钱包", "ja", "スポンサーウォレット")
            .item(ReconChecks.SPONSOR_PENDING_COUNT, "en", "Pending rewards (count)", "zh", "待支付笔数",
                "ja", "支払待ち件数")
            .item(ReconChecks.SPONSOR_PENDING_AMOUNT, "en", "Pending rewards (amount)", "zh", "待支付金额",
                "ja", "支払待ち金額")
            .item(ReconChecks.TOTAL_2110, "en", "Total of sponsor wallets", "zh", "商家钱包合计",
                "ja", "スポンサーウォレット合計")
            .item(ReconChecks.LEDGER_ZERO, "en", "Ledger balance", "zh", "账本平衡", "ja", "元帳の貸借")
            .item(ReconChecks.TOPUP_LEDGER, "en", "Top-up posting", "zh", "充值分录", "ja", "チャージの仕訳")
            .item(ReconChecks.TOPUP_ARRIVALS, "en", "Top-up postings (count)", "zh", "充值分录笔数",
                "ja", "チャージの仕訳件数")
            .item(ReconChecks.TOPUP_REFUNDS, "en", "Top-up refunds", "zh", "充值退款", "ja", "チャージの返金")
            .item(ReconChecks.TOPUP_FEE, "en", "Top-up fee", "zh", "充值手续费", "ja", "チャージの手数料"));
    }

    /** The bodies of Stripe's events may hold payers' names and addresses: kept as accounting evidence is. */
    @Bean
    RetentionPolicy qbInboundEventRetention() {
        return RetentionPolicy.of(InboundEntities.EVENT).keep(Period.ofYears(7)).from("receivedTime");
    }

    // ---- jobs of Stripe --------------------------------------------------------------------------------------------

    @Bean
    ExternalJobType<CheckoutRequest.CheckoutInput, StripeObjects.CheckoutSession> qbCheckoutCreateJob() {
        return TopUpJobs.CHECKOUT_JOB;
    }

    @Bean
    ExternalJobType<TopUpJobs.SessionCheckInput, StripeObjects.CheckoutSession> qbSessionCheckJob() {
        return TopUpJobs.SESSION_CHECK_JOB;
    }

    @Bean
    ExternalJobType<TopUpJobs.FeeInput, TopUpChecks.Fee> qbPaymentFeeJob() {
        return TopUpJobs.FEE_JOB;
    }

    // ---- processes -------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<TopUpProcesses.StartInput, TopUpProcesses.StartOutput, ProcessContext> qbTopUpStart(
        StripeSettings settings) {
        return TopUpProcesses.start(settings);
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, TopUpProcesses.TopUpOutput, ProcessContext> qbTopUpOpened(
        StripeSettings settings) {
        return TopUpProcesses.opened(settings);
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, TopUpProcesses.TopUpOutput, ProcessContext> qbTopUpOpenFailed() {
        return TopUpProcesses.openFailed();
    }

    @Bean
    ProcessDefinition<TopUpProcesses.TopUpRef, TopUpProcesses.TopUpOutput, ProcessContext> qbTopUpCheck() {
        return TopUpProcesses.check();
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, TopUpCrediting.Outcome, ProcessContext> qbTopUpChecked(
        StripeSettings settings) {
        return TopUpProcesses.checked(settings);
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, TopUpProcesses.TopUpOutput, ProcessContext> qbTopUpCheckFailed() {
        return TopUpProcesses.checkFailed();
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, TopUpProcesses.TopUpOutput, ProcessContext> qbTopUpFeeRecord() {
        return TopUpProcesses.feeRecord();
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, TopUpProcesses.TopUpOutput, ProcessContext> qbTopUpFeeFailed() {
        return TopUpProcesses.feeFailed();
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, TopUpCrediting.Outcome, ProcessContext> qbTopUpConfirm(
        StripeSettings settings) {
        return TopUpEvents.confirm(settings);
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, TopUpCrediting.Outcome, ProcessContext> qbTopUpExpire(
        StripeSettings settings) {
        return TopUpEvents.expire(settings);
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, TopUpClawback.ClawbackOutput, ProcessContext> qbTopUpRefunded(
        StripeSettings settings) {
        return TopUpClawback.refunded(settings);
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, TopUpClawback.ClawbackOutput, ProcessContext> qbTopUpDisputeOpened(
        StripeSettings settings) {
        return TopUpClawback.disputeOpened(settings);
    }

    @Bean
    ProcessDefinition<TopUpEvents.InboundRef, TopUpClawback.ClawbackOutput, ProcessContext> qbTopUpDisputeClosed(
        StripeSettings settings) {
        return TopUpClawback.disputeClosed(settings);
    }

    @Bean
    ProcessDefinition<Reconciliation.ReconcileInput, Reconciliation.ReconcileOutput, ProcessContext>
        qbReconcileRun() {
        return Reconciliation.process();
    }

    // ---- Stripe's events -------------------------------------------------------------------------------------------

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbTopUpConfirmSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, TopUpCrediting.Outcome, ProcessContext> qbTopUpConfirm) {
        return inbound(TopUpEvents.CONFIRM_CONSUMER, StripeService.CHECKOUT_COMPLETED, qbTopUpConfirm);
    }

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbTopUpExpireSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, TopUpCrediting.Outcome, ProcessContext> qbTopUpExpire) {
        return inbound(TopUpEvents.EXPIRE_CONSUMER, StripeService.CHECKOUT_EXPIRED, qbTopUpExpire);
    }

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbTopUpRefundSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, TopUpClawback.ClawbackOutput, ProcessContext> qbTopUpRefunded) {
        return inbound(TopUpClawback.REFUND_CONSUMER, StripeService.CHARGE_REFUNDED, qbTopUpRefunded);
    }

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbTopUpDisputeOpenSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, TopUpClawback.ClawbackOutput, ProcessContext>
            qbTopUpDisputeOpened) {
        return inbound(TopUpClawback.DISPUTE_OPEN_CONSUMER, StripeService.DISPUTE_CREATED, qbTopUpDisputeOpened);
    }

    @Bean
    EventSubscription<TopUpEvents.InboundRef> qbTopUpDisputeCloseSubscription(
        ProcessDefinition<TopUpEvents.InboundRef, TopUpClawback.ClawbackOutput, ProcessContext>
            qbTopUpDisputeClosed) {
        return inbound(TopUpClawback.DISPUTE_CLOSE_CONSUMER, StripeService.DISPUTE_CLOSED, qbTopUpDisputeClosed);
    }

    private static EventSubscription<TopUpEvents.InboundRef> inbound(String consumer, String type,
        ProcessDefinition<TopUpEvents.InboundRef, ?, ?> process) {
        return EventSubscription.of(consumer, InboundEvents.type(StripeService.PROVIDER, type), process,
            TopUpEvents::input);
    }

    // ---- scheduled jobs --------------------------------------------------------------------------------------------

    /**
     * Every hour at minute 17 (D-Q6-4): the open top-ups whose checkout expired an hour ago without word from Stripe
     * (a lost event), each asked about at Stripe by {@code QB_TOPUP_CHECK} in a transaction of its own (platform
     * decision D42); one asked within six hours is left alone.
     */
    @Bean
    JobDefinition<TopUpProcesses.TopUpRef> qbTopUpStaleJob(
        ProcessDefinition<TopUpProcesses.TopUpRef, TopUpProcesses.TopUpOutput, ProcessContext> qbTopUpCheck) {
        return JobDefinition.forEach(TopUpProcesses.STALE_JOB, "0 17 * * * *", ZoneOffset.UTC,
                JobItems.template(TopUpProcesses.STALE_TEMPLATE, scheduledTime -> Map.of(
                    "expiredBefore", scheduledTime.minus(WalletLimits.CHECK_AFTER_EXPIRY),
                    "askedBefore", scheduledTime.minus(WalletLimits.CHECK_AGAIN_AFTER)), "topUpId"),
                qbTopUpCheck,
                (scheduledTime, row) -> new TopUpProcesses.TopUpRef(String.valueOf(row.get("topUpId"))))
            .maxItems(WalletLimits.STALE_BATCH);
    }

    /** Every day at 18:37 UTC (03:37 in Tokyo), F-06. */
    @Bean
    JobDefinition<Reconciliation.ReconcileInput> qbReconcileJob(
        ProcessDefinition<Reconciliation.ReconcileInput, Reconciliation.ReconcileOutput, ProcessContext>
            qbReconcileRun) {
        return JobDefinition.cron(Reconciliation.JOB, "0 37 18 * * *", ZoneOffset.UTC, qbReconcileRun,
            Reconciliation.ReconcileInput::new);
    }
}
