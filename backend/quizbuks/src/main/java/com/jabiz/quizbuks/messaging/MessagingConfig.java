package com.jabiz.quizbuks.messaging;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationNotices;
import com.jabiz.quizbuks.wallet.PayoutProcesses;
import com.jabiz.quizbuks.wallet.QbPayouts;
import com.jabiz.quizbuks.wallet.QbTopUps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.Map;

/**
 * Registers the mail of Q7a (docs/quizbuks/plans/Q7-messages-legal.md D-Q7-1, D-Q7-2): the nine templates, the
 * notices' entity and its dataset (read by the system only), the five mail processes, their five subscriptions and the
 * hourly job of deferred short-of-funds mail.
 */
@Configuration
class MessagingConfig {

    private final String poolRef;

    MessagingConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    /**
     * How the mail shows values: times in the zone of the business's dates ({@code jabiz.fiscal-year-zone}, Asia/Tokyo
     * for QuizBuks), not a zone of its own.
     */
    @Bean
    MailValues qbMailValues(@Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        return new MailValues(java.time.ZoneId.of(zone));
    }

    @Bean
    EntityDefinition qbSponsorNoticeEntity() {
        return QbMessaging.NOTICE_ENTITY;
    }

    /** Read and written by the notice process only: the read permission is one no role holds. */
    @Bean
    DatasetDefinition qbSponsorNoticeDataset() {
        return DatasetDefinition.define(QbMessaging.defaultDataset(QbMessaging.NOTICE), d -> d
            .targetEntityType(QbMessaging.NOTICE)
            .asDefault()
            .permissions(QbPermissions.MESSAGING_SYSTEM, QbPermissions.MESSAGING_SYSTEM)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(10))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    MailTemplate qbTransferSentMail() {
        return NotificationMails.TRANSFER_SENT;
    }

    @Bean
    MailTemplate qbTransferFailedMail() {
        return NotificationMails.TRANSFER_FAILED;
    }

    @Bean
    MailTemplate qbPayoutActionNeededMail() {
        return NotificationMails.PAYOUT_ACTION_NEEDED;
    }

    @Bean
    MailTemplate qbTopUpPaidMail() {
        return NotificationMails.TOPUP_PAID;
    }

    @Bean
    MailTemplate qbTopUpPaidCashMail() {
        return NotificationMails.TOPUP_PAID_CASH;
    }

    @Bean
    MailTemplate qbTopUpGrantedMail() {
        return NotificationMails.TOPUP_GRANTED;
    }

    @Bean
    MailTemplate qbPublicationApprovedMail() {
        return NotificationMails.PUBLICATION_APPROVED;
    }

    @Bean
    MailTemplate qbPublicationRejectedMail() {
        return NotificationMails.PUBLICATION_REJECTED;
    }

    @Bean
    MailTemplate qbPublicationTakenDownMail() {
        return NotificationMails.PUBLICATION_TAKEN_DOWN;
    }

    @Bean
    MailTemplate qbPublicationFundsShortMail() {
        return NotificationMails.PUBLICATION_FUNDS_SHORT;
    }

    @Bean
    MailTemplate qbPublicationEndedMail() {
        return NotificationMails.PUBLICATION_ENDED;
    }

    @Bean
    MailTemplate qbPublicationExposureLowMail() {
        return NotificationMails.PUBLICATION_EXPOSURE_LOW;
    }

    @Bean
    ProcessDefinition<MailProcesses.TransferMailInput, MailProcesses.MailOutput, ProcessContext> qbMailTransfer(
        MessageCatalog messages) {
        return MailProcesses.transfer(messages);
    }

    @Bean
    ProcessDefinition<MailProcesses.PayoutMailInput, MailProcesses.MailOutput, ProcessContext> qbMailPayoutAccount(
        MessageCatalog messages) {
        return MailProcesses.payoutAccount(messages);
    }

    @Bean
    ProcessDefinition<MailProcesses.TopUpMailInput, MailProcesses.MailOutput, ProcessContext> qbMailTopUp(
        MessageCatalog messages) {
        return MailProcesses.topUp(messages);
    }

    @Bean
    ProcessDefinition<MailProcesses.FeeInvoiceMailInput, MailProcesses.MailOutput, ProcessContext> qbMailFeeInvoice(
        MessageCatalog messages) {
        return MailProcesses.feeInvoice(messages);
    }

    @Bean
    MailTemplate qbFeeInvoiceIssuedMail() {
        return NotificationMails.FEE_INVOICE_ISSUED;
    }

    @Bean
    MailTemplate qbFeeInvoiceOverdueMail() {
        return NotificationMails.FEE_INVOICE_OVERDUE;
    }

    @Bean
    MailTemplate qbFeeCreditExpiringMail() {
        return NotificationMails.FEE_CREDIT_EXPIRING;
    }

    @Bean
    ProcessDefinition<MailProcesses.FeeAutoPaidMailInput, MailProcesses.MailOutput, ProcessContext> qbMailFeeAutoPaid(
        MessageCatalog messages, @Value("${quizbuks.mail.fee-paid-grace:PT2M}") java.time.Duration grace) {
        return MailProcesses.feeAutoPaid(messages, grace);
    }

    @Bean
    MailTemplate qbFeeAutoPaidMail() {
        return NotificationMails.FEE_AUTO_PAID;
    }

    @Bean
    ProcessDefinition<MailProcesses.FeeVoidedMailInput, MailProcesses.MailOutput, ProcessContext> qbMailFeeInvoiceVoided(
        MessageCatalog messages) {
        return MailProcesses.feeInvoiceVoided(messages);
    }

    @Bean
    MailTemplate qbFeeInvoiceVoidedMail() {
        return NotificationMails.FEE_INVOICE_VOIDED;
    }

    @Bean
    EventSubscription<MailProcesses.FeeVoidedMailInput> qbMailFeeInvoiceVoidedSubscription(
        ProcessDefinition<MailProcesses.FeeVoidedMailInput, MailProcesses.MailOutput, ProcessContext> qbMailFeeInvoiceVoided) {
        return EventSubscription.of(MailProcesses.FEE_INVOICE_VOIDED_CONSUMER,
            com.jabiz.quizbuks.fee.FeeRequests.VOIDED_EVENT, qbMailFeeInvoiceVoided, MailProcesses::feeVoidedInput);
    }

    @Bean
    ProcessDefinition<MailProcesses.FeePaidDueInput, MailProcesses.MailOutput, ProcessContext> qbMailFeePaidDue(
        MessageCatalog messages) {
        return MailProcesses.feePaidDue(messages);
    }

    /**
     * Every few minutes, each sponsor with invoices paid from their account that no mail told yet and whose last mail
     * went before the window: one mail lists everything paid up to {@code now - grace} (the grace outlasts a payment
     * still being committed). Both are properties; a sponsor's payments over a window are one mail. The grace must be
     * longer than the longest a payment can take from its time to its commit (the lock wait and the statement
     * timeout of the platform): a payment committed after the cut is told by the next mail, never lost.
     */
    @Bean
    JobDefinition<MailProcesses.FeePaidDueInput> qbMailFeePaidDueJob(
        @Value("${quizbuks.mail.fee-paid-due-cron:0 */10 * * * *}") String cron,
        @Value("${quizbuks.mail.fee-paid-window:PT10M}") java.time.Duration window,
        @Value("${quizbuks.mail.fee-paid-grace:PT2M}") java.time.Duration grace,
        ProcessDefinition<MailProcesses.FeePaidDueInput, MailProcesses.MailOutput, ProcessContext> qbMailFeePaidDue) {
        return JobDefinition.forEach(MailProcesses.FEE_PAID_JOB, cron, ZoneOffset.UTC,
            JobItems.template(MailProcesses.FEE_PAID_DUE_TEMPLATE, scheduledTime -> Map.of("before",
                scheduledTime.minus(window)), "sponsorId"),
            qbMailFeePaidDue, (scheduledTime, row) -> new MailProcesses.FeePaidDueInput(
                String.valueOf(row.get("sponsorId")), scheduledTime.minus(grace)));
    }

    @Bean
    EventSubscription<MailProcesses.FeeAutoPaidMailInput> qbMailFeeAutoPaidSubscription(
        ProcessDefinition<MailProcesses.FeeAutoPaidMailInput, MailProcesses.MailOutput, ProcessContext> qbMailFeeAutoPaid) {
        return EventSubscription.of(MailProcesses.FEE_AUTO_PAID_CONSUMER,
            com.jabiz.quizbuks.fee.FeeAutoPay.PAID_EVENT, qbMailFeeAutoPaid, MailProcesses::feeAutoPaidInput);
    }

    @Bean
    ProcessDefinition<MailProcesses.FeeCreditMailInput, MailProcesses.MailOutput, ProcessContext> qbMailFeeCredit(
        MessageCatalog messages, MailValues qbMailValues) {
        return MailProcesses.feeCredit(messages, qbMailValues);
    }

    @Bean
    EventSubscription<MailProcesses.FeeCreditMailInput> qbMailFeeCreditExpiringSubscription(
        ProcessDefinition<MailProcesses.FeeCreditMailInput, MailProcesses.MailOutput, ProcessContext> qbMailFeeCredit) {
        return EventSubscription.of(MailProcesses.FEE_CREDIT_EXPIRING_CONSUMER,
            com.jabiz.quizbuks.fee.FeeCredits.EXPIRING_EVENT, qbMailFeeCredit, MailProcesses::feeCreditInput);
    }

    @Bean
    ProcessDefinition<MailProcesses.PublicationMailInput, MailProcesses.MailOutput, ProcessContext>
        qbPublicationNotify(MessageCatalog messages, MailValues qbMailValues) {
        return MailProcesses.publication(messages, qbMailValues);
    }

    @Bean
    ProcessDefinition<MailProcesses.SponsorRef, MailProcesses.MailOutput, ProcessContext> qbMailFundsShortDue(
        MessageCatalog messages) {
        return MailProcesses.fundsShortDue(messages);
    }

    /**
     * Hourly, each sponsor whose short-of-funds mail was deferred within the day and whose day has passed, in a
     * transaction of its own (platform decision D42): the mail goes when a publication is still short.
     */
    @Bean
    JobDefinition<MailProcesses.SponsorRef> qbMailFundsShortDueJob(
        @Value("${quizbuks.mail.funds-short-due-cron:0 11 * * * *}") String cron,
        ProcessDefinition<MailProcesses.SponsorRef, MailProcesses.MailOutput, ProcessContext> qbMailFundsShortDue) {
        return JobDefinition.forEach(MailProcesses.FUNDS_SHORT_JOB, cron, ZoneOffset.UTC,
            JobItems.template(MailProcesses.FUNDS_SHORT_DUE_TEMPLATE, scheduledTime -> Map.of("before",
                scheduledTime.minus(NotificationMails.NOTICE_INTERVAL)), "sponsorId"),
            qbMailFundsShortDue, (scheduledTime, row) -> new MailProcesses.SponsorRef(
                String.valueOf(row.get("sponsorId"))));
    }

    @Bean
    EventSubscription<MailProcesses.TransferMailInput> qbMailTransferSentSubscription(
        ProcessDefinition<MailProcesses.TransferMailInput, MailProcesses.MailOutput, ProcessContext> qbMailTransfer) {
        return EventSubscription.of(MailProcesses.TRANSFER_SENT_CONSUMER, QbPayouts.SENT_EVENT, qbMailTransfer,
            event -> MailProcesses.transferInput(event, MailProcesses.SENT));
    }

    @Bean
    EventSubscription<MailProcesses.TransferMailInput> qbMailTransferFailedSubscription(
        ProcessDefinition<MailProcesses.TransferMailInput, MailProcesses.MailOutput, ProcessContext> qbMailTransfer) {
        return EventSubscription.of(MailProcesses.TRANSFER_FAILED_CONSUMER, QbPayouts.FAILED_EVENT, qbMailTransfer,
            event -> MailProcesses.transferInput(event, MailProcesses.FAILED));
    }

    @Bean
    EventSubscription<MailProcesses.PayoutMailInput> qbMailPayoutRestrictedSubscription(
        ProcessDefinition<MailProcesses.PayoutMailInput, MailProcesses.MailOutput, ProcessContext>
            qbMailPayoutAccount) {
        return EventSubscription.of(MailProcesses.PAYOUT_RESTRICTED_CONSUMER, PayoutProcesses.RESTRICTED_EVENT,
            qbMailPayoutAccount, MailProcesses::payoutInput);
    }

    @Bean
    EventSubscription<MailProcesses.FeeInvoiceMailInput> qbMailFeeInvoiceIssuedSubscription(
        ProcessDefinition<MailProcesses.FeeInvoiceMailInput, MailProcesses.MailOutput, ProcessContext> qbMailFeeInvoice) {
        return EventSubscription.of(MailProcesses.FEE_INVOICE_ISSUED_CONSUMER,
            com.jabiz.quizbuks.fee.QbFees.INVOICE_ISSUED_EVENT, qbMailFeeInvoice, MailProcesses::feeInvoiceIssuedInput);
    }

    @Bean
    EventSubscription<MailProcesses.FeeInvoiceMailInput> qbMailFeeInvoiceOverdueSubscription(
        ProcessDefinition<MailProcesses.FeeInvoiceMailInput, MailProcesses.MailOutput, ProcessContext> qbMailFeeInvoice) {
        return EventSubscription.of(MailProcesses.FEE_INVOICE_OVERDUE_CONSUMER,
            com.jabiz.quizbuks.fee.QbFees.INVOICE_OVERDUE_EVENT, qbMailFeeInvoice, MailProcesses::feeInvoiceOverdueInput);
    }

    @Bean
    EventSubscription<MailProcesses.TopUpMailInput> qbMailTopUpPaidSubscription(
        ProcessDefinition<MailProcesses.TopUpMailInput, MailProcesses.MailOutput, ProcessContext> qbMailTopUp) {
        return EventSubscription.of(MailProcesses.TOPUP_PAID_CONSUMER, QbTopUps.PAID_EVENT, qbMailTopUp,
            MailProcesses::topUpInput);
    }

    @Bean
    EventSubscription<MailProcesses.PublicationMailInput> qbPublicationNotifySubscription(
        ProcessDefinition<MailProcesses.PublicationMailInput, MailProcesses.MailOutput, ProcessContext>
            qbPublicationNotify) {
        return EventSubscription.of(MailProcesses.PUBLICATION_CONSUMER, PublicationNotices.EVENT, qbPublicationNotify,
            MailProcesses::publicationInput);
    }
}
