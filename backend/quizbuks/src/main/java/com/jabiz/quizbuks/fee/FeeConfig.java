package com.jabiz.quizbuks.fee;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.TransferProcesses;
import com.jabiz.retention.RetentionPolicy;
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Period;
import java.time.YearMonth;
import java.util.Map;

/**
 * Registers the platform fees (Q87b, docs/quizbuks/plans/Q87-fee-account.md §11): the records with their datasets
 * (finance reads all, a sponsor their own) and dictionaries, the invoices' numbers and layout, the approval subject, the
 * processes, the approval's subscriptions, the three jobs and the retention.
 */
@Configuration
class FeeConfig {

    /** Accounting evidence is kept ten years from the end of its fiscal year, as the top-ups are. */
    static final Period RETENTION = Period.ofYears(10);

    private final String poolRef;

    FeeConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    // ---- entities, datasets, dictionaries --------------------------------------------------------------------------

    @Bean
    EntityDefinition qbFeeChargeEntity() {
        return QbFees.CHARGE_ENTITY;
    }

    @Bean
    EntityDefinition qbSponsorBillingEntity() {
        return QbFees.BILLING_ENTITY;
    }

    @Bean
    EntityDefinition qbFeeInvoiceEntity() {
        return QbFees.INVOICE_ENTITY;
    }

    @Bean
    EntityDefinition qbFeePaymentEntity() {
        return QbFees.PAYMENT_ENTITY;
    }

    @Bean
    EntityDefinition qbFeeRequestEntity() {
        return QbFees.REQUEST_ENTITY;
    }

    @Bean
    DatasetDefinition qbFeeChargeDataset() {
        return processOnly(QbFees.CHARGE);
    }

    @Bean
    DatasetDefinition qbSponsorBillingDataset() {
        return processOnly(QbFees.BILLING);
    }

    @Bean
    DatasetDefinition qbFeeInvoiceDataset() {
        return processOnly(QbFees.INVOICE);
    }

    @Bean
    DatasetDefinition qbFeePaymentDataset() {
        return processOnly(QbFees.PAYMENT);
    }

    @Bean
    DatasetDefinition qbFeeRequestDataset() {
        return processOnly(QbFees.REQUEST);
    }

    /** A sponsor's own charges, invoices, payments and billing mode, read only. */
    @Bean
    DatasetDefinition qbSponsorFeeChargeDataset() {
        return sponsorOwn(FeeProcesses.SPONSOR_CHARGES, QbFees.CHARGE);
    }

    @Bean
    DatasetDefinition qbSponsorFeeInvoiceDataset() {
        return sponsorOwn(FeeProcesses.SPONSOR_INVOICES, QbFees.INVOICE);
    }

    @Bean
    DatasetDefinition qbSponsorFeePaymentDataset() {
        return sponsorOwn(FeeProcesses.SPONSOR_PAYMENTS, QbFees.PAYMENT);
    }

    @Bean
    DatasetDefinition qbSponsorOwnBillingDataset() {
        return sponsorOwn("urn:jabiz:dataset:sponsor:" + QbFees.BILLING, QbFees.BILLING);
    }

    /** Finance reads every record; only the processes write them. */
    private DatasetDefinition processOnly(String entity) {
        return DatasetDefinition.define(QbFees.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(100))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private DatasetDefinition sponsorOwn(String id, String entity) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.SPONSOR_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbFeeKindDictionary() {
        return StaticDictionary.define(QbFees.KIND_DICTIONARY, d -> d
            .item(QbFees.MONTHLY, "en", "Monthly fee", "zh", "月费", "ja", "月額料金")
            .item(QbFees.MANUAL, "en", "Charged by hand", "zh", "手工记费", "ja", "手動の請求"));
    }

    @Bean
    StaticDictionary qbBillingModeDictionary() {
        return StaticDictionary.define(QbFees.MODE_DICTIONARY, d -> d
            .item(FeeRules.PREPAID, "en", "Prepaid", "zh", "预付", "ja", "前払い")
            .item(FeeRules.POSTPAID, "en", "Postpaid", "zh", "后付", "ja", "後払い"));
    }

    @Bean
    StaticDictionary qbFeeInvoiceStatusDictionary() {
        return StaticDictionary.define(QbFees.INVOICE_STATUS_DICTIONARY, d -> d
            .item(QbFees.PAID, "en", "Paid", "zh", "已付", "ja", "支払済み")
            .item(QbFees.ISSUED, "en", "Unpaid", "zh", "待付", "ja", "未払い")
            .item(QbFees.OVERDUE, "en", "Overdue", "zh", "逾期", "ja", "期限超過")
            .item(QbFees.VOID, "en", "Void", "zh", "已作废", "ja", "無効"));
    }

    // ---- the invoices -----------------------------------------------------------------------------------------------

    /** {@code QBF-<year>-00001}: never repeated, never a gap (platform decision D23), counted per year. */
    @Bean
    NumberSequence qbFeeInvoiceNumbers() {
        return NumberSequence.define(FeeProcesses.SEQUENCE, s -> s.format("QBF-{scope}-{n:5}").scoped());
    }

    /**
     * The qualified invoice (ja: 適格請求書): the issuer and its registration number ({@code qb.issuer.*}, kept with
     * each), the sponsor, the month's charges with their rate, the amounts before tax, the tax, what was paid and what
     * is due. The tax is rounded once per invoice and rate.
     */
    @Bean
    DocumentLayout qbFeeInvoiceLayout() {
        return DocumentLayout.define(FeeProcesses.LAYOUT_ID, d -> d
            .permissions(QbPermissions.TOPUP)
            .subject(QbFees.INVOICE, "invoiceId")
            .number(FeeProcesses.DOCUMENT_TEMPLATE, "invoiceNo")
            .party("issuer", FeeProcesses.ISSUER_TEMPLATE, "name", "address", "registrationNo")
            .party("sponsor", FeeProcesses.DOCUMENT_TEMPLATE, "company", "contactName")
            .facts(FeeProcesses.DOCUMENT_TEMPLATE, "period", "issuedAt", "dueOn", "billingMode", "amount", "tax",
                "paidAtCharge", "due")
            .table(FeeProcesses.LINES_TEMPLATE, "chargedAt", "kind", "note", "taxRate", "amount")
            .totals(FeeProcesses.DOCUMENT_TEMPLATE, "total")
            .note("note"));
    }

    @Bean
    ApprovalSubject qbFeeApprovalSubject() {
        return ApprovalSubject.define(FeeProcesses.APPROVAL_SUBJECT, s -> s.entity(QbFees.REQUEST).bool("fee")
            .number("amount").text("sponsorId").text("kind"));
    }

    // ---- processes --------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<FeeProcesses.ChargeInput, FeeProcesses.ChargeOutput, ProcessContext> qbFeeCharge() {
        return FeeProcesses.charge();
    }

    @Bean
    ProcessDefinition<FeeProcesses.MonthlyInput, FeeProcesses.MonthlyOutput, ProcessContext> qbFeeMonthlyItem() {
        return FeeProcesses.monthlyItem();
    }

    @Bean
    ProcessDefinition<FeeProcesses.InvoiceItemInput, FeeProcesses.InvoiceOutput, ProcessContext> qbFeeInvoiceItem() {
        return FeeProcesses.invoiceItem();
    }

    @Bean
    ProcessDefinition<FeeProcesses.PayInput, FeeProcesses.PayOutput, ProcessContext> qbFeePay() {
        return FeeProcesses.pay();
    }

    @Bean
    ProcessDefinition<FeeProcesses.DocumentInput, FeeProcesses.DocumentOutput, ProcessContext> qbFeeDocument() {
        return FeeProcesses.document();
    }

    @Bean
    ProcessDefinition<FeeProcesses.BillingInput, FeeProcesses.BillingOutput, ProcessContext> qbSponsorBillingSet() {
        return FeeProcesses.billingSet();
    }

    @Bean
    ProcessDefinition<FeeProcesses.OverdueInput, FeeProcesses.OverdueOutput, ProcessContext> qbFeeOverdueItem() {
        return FeeProcesses.overdueItem();
    }

    @Bean
    ProcessDefinition<FeeProcesses.RequestInput, FeeProcesses.RequestOutput, ProcessContext> qbFeeRequest() {
        return FeeProcesses.request();
    }

    @Bean
    ProcessDefinition<TransferProcesses.ApprovalResultInput, FeeProcesses.RequestOutput, ProcessContext>
        qbFeeRequestResult() {
        return FeeProcesses.requestResult();
    }

    @Bean
    EventSubscription<TransferProcesses.ApprovalResultInput> qbFeeApprovedSubscription(
        ProcessDefinition<TransferProcesses.ApprovalResultInput, FeeProcesses.RequestOutput, ProcessContext>
            qbFeeRequestResult) {
        return EventSubscription.of("qb.fee-approved", ApprovalProcesses.APPROVED_EVENT, qbFeeRequestResult,
            TransferProcesses::resultInput);
    }

    @Bean
    EventSubscription<TransferProcesses.ApprovalResultInput> qbFeeRejectedSubscription(
        ProcessDefinition<TransferProcesses.ApprovalResultInput, FeeProcesses.RequestOutput, ProcessContext>
            qbFeeRequestResult) {
        return EventSubscription.of("qb.fee-rejected", ApprovalProcesses.REJECTED_EVENT, qbFeeRequestResult,
            TransferProcesses::resultInput);
    }

    // ---- jobs (Japan's calendar) ------------------------------------------------------------------------------------

    /** On the 1st at 00:23 in Tokyo: the month's fee of every approved sponsor, one a transaction. */
    @Bean
    JobDefinition<FeeProcesses.MonthlyInput> qbFeeMonthlyJob(
        @Value("${quizbuks.fee.monthly-cron:0 23 0 1 * *}") String cron,
        ProcessDefinition<FeeProcesses.MonthlyInput, FeeProcesses.MonthlyOutput, ProcessContext> qbFeeMonthlyItem) {
        return JobDefinition.forEach(FeeProcesses.MONTHLY_JOB, cron, FeeProcesses.ZONE,
                JobItems.template(FeeProcesses.MONTHLY_TEMPLATE,
                    scheduledTime -> Map.of("period", FeeProcesses.period(scheduledTime)), "sponsorId"),
                qbFeeMonthlyItem,
                (scheduledTime, row) -> new FeeProcesses.MonthlyInput(String.valueOf(row.get("sponsorId")),
                    FeeProcesses.period(scheduledTime)))
            .maxItems(JobDefinition.MAX_MAX_ITEMS);
    }

    /** On the 2nd at 01:43 in Tokyo: the previous month's invoice of every sponsor charged in it. */
    @Bean
    JobDefinition<FeeProcesses.InvoiceItemInput> qbFeeInvoiceJob(
        @Value("${quizbuks.fee.invoice-cron:0 43 1 2 * *}") String cron,
        ProcessDefinition<FeeProcesses.InvoiceItemInput, FeeProcesses.InvoiceOutput, ProcessContext>
            qbFeeInvoiceItem) {
        return JobDefinition.forEach(FeeProcesses.INVOICE_JOB, cron, FeeProcesses.ZONE,
                JobItems.template(FeeProcesses.INVOICES_DUE_TEMPLATE,
                    scheduledTime -> Map.of("period", previousMonth(scheduledTime)), "sponsorId"),
                qbFeeInvoiceItem,
                (scheduledTime, row) -> new FeeProcesses.InvoiceItemInput(String.valueOf(row.get("sponsorId")),
                    previousMonth(scheduledTime)))
            .maxItems(JobDefinition.MAX_MAX_ITEMS);
    }

    /** Every day at 09:07 in Tokyo: unpaid invoices past their due date, and the weekly reminders. */
    @Bean
    JobDefinition<FeeProcesses.OverdueInput> qbFeeOverdueJob(
        @Value("${quizbuks.fee.overdue-cron:0 7 9 * * *}") String cron,
        ProcessDefinition<FeeProcesses.OverdueInput, FeeProcesses.OverdueOutput, ProcessContext> qbFeeOverdueItem) {
        return JobDefinition.forEach(FeeProcesses.OVERDUE_JOB, cron, FeeProcesses.ZONE,
                JobItems.template(FeeProcesses.OVERDUE_TEMPLATE, scheduledTime -> Map.of(
                    "today", FeeProcesses.day(scheduledTime),
                    "remindBy", scheduledTime.minus(Duration.ofDays(QbFees.REMINDER_DAYS))), "invoiceId"),
                qbFeeOverdueItem,
                (scheduledTime, row) -> new FeeProcesses.OverdueInput(String.valueOf(row.get("sponsorId")),
                    String.valueOf(row.get("invoiceId"))))
            .maxItems(JobDefinition.MAX_MAX_ITEMS);
    }

    static String previousMonth(java.time.Instant at) {
        return YearMonth.parse(FeeProcesses.period(at)).minusMonths(1).toString();
    }

    // ---- retention --------------------------------------------------------------------------------------------------

    @Bean
    RetentionPolicy qbFeeChargeRetention() {
        return RetentionPolicy.of(QbFees.CHARGE).keep(RETENTION).from("chargedAt").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy qbFeeInvoiceRetention() {
        return RetentionPolicy.of(QbFees.INVOICE).keep(RETENTION).from("issuedAt").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy qbFeePaymentRetention() {
        return RetentionPolicy.of(QbFees.PAYMENT).keep(RETENTION).from("paidAt").afterFiscalYearEnd();
    }
}
