package com.jabiz.finance.ar;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.job.JobDefinition;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the receivables' master data and processes ({@link ArEntities}). */
@Configuration
class ArConfig {

    @Bean
    EntityDefinition finCustomerEntity() {
        return ArEntities.CUSTOMER_ENTITY;
    }

    @Bean
    EntityDefinition finPaymentTermsEntity() {
        return ArEntities.PAYMENT_TERMS_ENTITY;
    }

    @Bean
    EntityDefinition finExemptionCertificateEntity() {
        return ArEntities.CERTIFICATE_ENTITY;
    }

    @Bean
    EntityDefinition finArSettingsEntity() {
        return ArEntities.SETTINGS_ENTITY;
    }

    @Bean
    EntityDefinition finInvoiceEntity() {
        return InvoiceEntities.INVOICE_ENTITY;
    }

    @Bean
    EntityDefinition finInvoiceLineEntity() {
        return InvoiceEntities.LINE_ENTITY;
    }

    @Bean
    EntityDefinition finInvoiceTaxEntity() {
        return InvoiceEntities.TAX_ENTITY;
    }

    @Bean
    EntityDefinition finApplicationEntity() {
        return InvoiceEntities.APPLICATION_ENTITY;
    }

    @Bean
    DatasetDefinition finInvoiceDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(InvoiceEntities.INVOICE_DATASET, InvoiceEntities.INVOICE, FinancePermissions.AR_READ,
            FinancePermissions.INVOICE_PREPARE, true, true, poolRef);
    }

    @Bean
    DatasetDefinition finInvoiceLineDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(InvoiceEntities.LINE_DATASET, InvoiceEntities.LINE, FinancePermissions.INVOICE_PREPARE,
            poolRef);
    }

    @Bean
    DatasetDefinition finInvoiceTaxDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(InvoiceEntities.TAX_DATASET, InvoiceEntities.TAX, FinancePermissions.INVOICE_PREPARE, poolRef);
    }

    @Bean
    DatasetDefinition finApplicationDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(InvoiceEntities.APPLICATION_DATASET, InvoiceEntities.APPLICATION,
            FinancePermissions.INVOICE_PREPARE, poolRef);
    }

    /** Invoice numbers without gaps (FIN-AR-004); the first is configurable, the sample company's next is 1004. */
    @Bean
    NumberSequence invoiceNumbers(@Value("${finance.ar.invoice-numbers-start:1001}") long first) {
        return InvoiceProcesses.invoiceNumbers(first);
    }

    @Bean
    NumberSequence creditMemoNumbers(@Value("${finance.ar.credit-memo-numbers-start:2001}") long first) {
        return InvoiceProcesses.creditMemoNumbers(first);
    }

    @Bean
    StaticDictionary arDocumentKindDictionary() {
        return StaticDictionary.define(InvoiceEntities.KINDS, d -> d
            .item(InvoiceEntities.INVOICE_KIND, "en", "Invoice")
            .item(InvoiceEntities.CREDIT_MEMO, "en", "Credit memo"));
    }

    @Bean
    StaticDictionary arDocumentStatusDictionary() {
        return StaticDictionary.define(InvoiceEntities.STATUSES, d -> d
            .item(InvoiceEntities.DRAFT, "en", "Draft")
            .item(InvoiceEntities.POSTED, "en", "Posted")
            .item(InvoiceEntities.VOID, "en", "Void")
            .item(InvoiceEntities.WRITTEN_OFF, "en", "Written off"));
    }

    @Bean
    StaticDictionary arDocumentSourceDictionary() {
        return StaticDictionary.define(InvoiceEntities.SOURCES, d -> d
            .item(InvoiceEntities.MANUAL, "en", "Entered")
            .item(InvoiceEntities.OPENING, "en", "Opening item")
            .item(InvoiceEntities.RECURRING, "en", "Recurring"));
    }

    @Bean
    ProcessDefinition<InvoiceProcesses.InvoiceInput, InvoiceProcesses.InvoiceOutput, ProcessContext>
        finInvoiceSaveProcess() {
        return InvoiceProcesses.SAVE_PROCESS;
    }

    @Bean
    ProcessDefinition<InvoiceProcesses.InvoiceId, InvoiceProcesses.InvoiceOutput, ProcessContext>
        finInvoiceDeleteProcess() {
        return InvoiceProcesses.DELETE_PROCESS;
    }

    @Bean
    ProcessDefinition<InvoiceProcesses.InvoiceId, InvoiceProcesses.InvoiceOutput, ProcessContext>
        finInvoicePostProcess() {
        return InvoiceProcesses.POST_PROCESS;
    }

    @Bean
    ProcessDefinition<InvoiceProcesses.VoidInput, InvoiceProcesses.InvoiceOutput, ProcessContext>
        finInvoiceVoidProcess() {
        return InvoiceProcesses.VOID_PROCESS;
    }

    @Bean
    ProcessDefinition<InvoiceProcesses.ApplyInput, InvoiceProcesses.ApplyOutput, ProcessContext>
        finCreditApplyProcess() {
        return InvoiceProcesses.APPLY_PROCESS;
    }

    @Bean
    ProcessDefinition<InvoiceProcesses.OpeningInput, InvoiceProcesses.OpeningOutput, ProcessContext>
        finArOpeningProcess(@Value("${finance.ar.invoice-numbers-start:1001}") long invoiceStart,
        @Value("${finance.ar.credit-memo-numbers-start:2001}") long creditMemoStart) {
        return InvoiceProcesses.openingProcess(invoiceStart, creditMemoStart);
    }

    @Bean
    DatasetDefinition finCustomerDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ArEntities.CUSTOMER_DATASET, ArEntities.CUSTOMER, FinancePermissions.CUSTOMER_MAINTAIN,
            poolRef);
    }

    @Bean
    DatasetDefinition finPaymentTermsDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ArEntities.PAYMENT_TERMS_DATASET, ArEntities.PAYMENT_TERMS, FinancePermissions.AR_SETTINGS,
            poolRef);
    }

    @Bean
    DatasetDefinition finExemptionCertificateDataset(
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ArEntities.CERTIFICATE_DATASET, ArEntities.CERTIFICATE, FinancePermissions.CUSTOMER_MAINTAIN,
            poolRef);
    }

    @Bean
    DatasetDefinition finArSettingsDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ArEntities.SETTINGS_DATASET, ArEntities.SETTINGS, FinancePermissions.AR_SETTINGS, poolRef);
    }

    /** Read by whoever reads the receivables; written only through the processes of {@link ArEntities}. */
    private static DatasetDefinition dataset(String id, String entity, String writePermission, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(FinancePermissions.AR_READ, writePermission)
            .policy(p -> p.maxQueryBatchSize(500).processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    FilePolicy certificateFiles() {
        return FilePolicy.define(ArEntities.CERTIFICATE_FILES)
            .allow(MediaTypes.PDF, MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(25 * FilePolicy.MB)
            .permissions(FinancePermissions.CUSTOMER_MAINTAIN, FinancePermissions.AR_READ)
            .build();
    }

    @Bean
    StaticDictionary customerStatusDictionary() {
        return StaticDictionary.define(ArEntities.CUSTOMER_STATUSES, d -> d
            .item("ACTIVE", "en", "Active")
            .item("INACTIVE", "en", "Inactive"));
    }

    @Bean
    StaticDictionary certificateTypeDictionary() {
        return StaticDictionary.define(ArEntities.CERTIFICATE_TYPES, d -> d
            .item("RESALE", "en", "Resale")
            .item("EXEMPT_ORGANIZATION", "en", "Exempt organization")
            .item("GOVERNMENT", "en", "Government")
            .item("OTHER", "en", "Other"));
    }

    @Bean
    StaticDictionary missingCertificateDictionary() {
        return StaticDictionary.define(ArEntities.MISSING_CERTIFICATE_POLICIES, d -> d
            .item("BLOCK", "en", "Refuse the posting")
            .item("CHARGE", "en", "Charge tax"));
    }

    @Bean
    StaticDictionary creditLimitCheckDictionary() {
        return StaticDictionary.define(ArEntities.CREDIT_LIMIT_CHECKS, d -> d
            .item("OFF", "en", "Off")
            .item("WARN", "en", "Warn"));
    }

    // ---- F3c: receipts, write-offs, recurring invoices, approvals --------------------------------------------------

    @Bean
    EntityDefinition finReceiptEntity() {
        return ReceiptEntities.RECEIPT_ENTITY;
    }

    @Bean
    EntityDefinition finWriteOffEntity() {
        return ReceiptEntities.WRITE_OFF_ENTITY;
    }

    @Bean
    EntityDefinition finRecurringInvoiceEntity() {
        return ReceiptEntities.RECURRING_ENTITY;
    }

    @Bean
    EntityDefinition finRecurringInvoiceLineEntity() {
        return ReceiptEntities.RECURRING_LINE_ENTITY;
    }

    @Bean
    DatasetDefinition finReceiptDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ReceiptEntities.RECEIPT_DATASET, ReceiptEntities.RECEIPT, FinancePermissions.RECEIPT_RECORD,
            poolRef);
    }

    @Bean
    DatasetDefinition finWriteOffDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ReceiptEntities.WRITE_OFF_DATASET, ReceiptEntities.WRITE_OFF,
            FinancePermissions.WRITE_OFF_REQUEST, poolRef);
    }

    /** Templates are kept through their data views; the run checks them and the posting checks the invoice. */
    @Bean
    DatasetDefinition finRecurringInvoiceDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return com.jabiz.finance.gl.GlEntities.dataset(ReceiptEntities.RECURRING_DATASET, ReceiptEntities.RECURRING,
            FinancePermissions.AR_READ, FinancePermissions.RECURRING_INVOICE_MAINTAIN, false, poolRef);
    }

    @Bean
    DatasetDefinition finRecurringInvoiceLineDataset(
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return com.jabiz.finance.gl.GlEntities.dataset(ReceiptEntities.RECURRING_LINE_DATASET,
            ReceiptEntities.RECURRING_LINE, FinancePermissions.AR_READ, FinancePermissions.RECURRING_INVOICE_MAINTAIN,
            false, poolRef);
    }

    /** Receipt numbers without gaps (FIN-AR-007), RCPT-0001 on. */
    @Bean
    NumberSequence receiptNumbers() {
        return ReceiptProcesses.receiptNumbers();
    }

    @Bean
    StaticDictionary receiptMethodDictionary() {
        return StaticDictionary.define(ReceiptEntities.METHODS, d -> d
            .item("CHECK", "en", "Check")
            .item("ACH", "en", "ACH")
            .item("WIRE", "en", "Wire")
            .item("CARD", "en", "Card settlement"));
    }

    @Bean
    StaticDictionary receiptStatusDictionary() {
        return StaticDictionary.define(ReceiptEntities.RECEIPT_STATUSES, d -> d
            .item(ReceiptEntities.POSTED, "en", "Posted")
            .item(ReceiptEntities.VOID, "en", "Void"));
    }

    @Bean
    StaticDictionary writeOffStatusDictionary() {
        return StaticDictionary.define(ReceiptEntities.WRITE_OFF_STATUSES, d -> d
            .item(ReceiptEntities.PENDING, "en", "Waiting for approval")
            .item(ReceiptEntities.POSTED, "en", "Posted")
            .item(ReceiptEntities.REJECTED, "en", "Rejected")
            .item(ReceiptEntities.REFUSED, "en", "Approved, not possible"));
    }

    /**
     * What the approval rules of invoices can ask about (FIN-AR-013): the amount in US dollars, the customer and
     * whether the invoice takes the customer over the credit limit. No rule is set up: invoices post at once unless
     * the controller adds one.
     */
    @Bean
    ApprovalSubject invoiceApprovals() {
        return ApprovalSubject.define(InvoiceProcesses.SUBJECT, s -> s
            .entity(InvoiceEntities.INVOICE)
            .number("amount")
            .text("customerCode")
            .bool("overCreditLimit"));
    }

    /** Write-offs always need approval (FIN-AR-012): the rule {@code FIN_SETUP} proposes says by whom. */
    @Bean
    ApprovalSubject writeOffApprovals() {
        return ApprovalSubject.define(WriteOffProcesses.SUBJECT, s -> s
            .entity(InvoiceEntities.INVOICE)
            .number("amount")
            .text("customerCode"));
    }

    @Bean
    EventSubscription<InvoiceProcesses.ApprovalResultInput> invoiceApprovedSubscription() {
        return EventSubscription.of("fin.invoice-approved", ApprovalProcesses.APPROVED_EVENT,
            InvoiceProcesses.APPROVAL_RESULT_PROCESS, e -> new InvoiceProcesses.ApprovalResultInput(text(e, "subject"),
                text(e, "entityId"), text(e, "status"), text(e, "contentHash"), text(e, "requestId")));
    }

    @Bean
    EventSubscription<InvoiceProcesses.ApprovalResultInput> invoiceRejectedSubscription() {
        return EventSubscription.of("fin.invoice-rejected", ApprovalProcesses.REJECTED_EVENT,
            InvoiceProcesses.APPROVAL_RESULT_PROCESS, e -> new InvoiceProcesses.ApprovalResultInput(text(e, "subject"),
                text(e, "entityId"), text(e, "status"), text(e, "contentHash"), text(e, "requestId")));
    }

    @Bean
    EventSubscription<WriteOffProcesses.ApprovalResultInput> writeOffApprovedSubscription() {
        return EventSubscription.of("fin.write-off-approved", ApprovalProcesses.APPROVED_EVENT,
            WriteOffProcesses.APPROVAL_RESULT_PROCESS, ArConfig::writeOffDecision);
    }

    @Bean
    EventSubscription<WriteOffProcesses.ApprovalResultInput> writeOffRejectedSubscription() {
        return EventSubscription.of("fin.write-off-rejected", ApprovalProcesses.REJECTED_EVENT,
            WriteOffProcesses.APPROVAL_RESULT_PROCESS, ArConfig::writeOffDecision);
    }

    private static WriteOffProcesses.ApprovalResultInput writeOffDecision(DomainEvent event) {
        return new WriteOffProcesses.ApprovalResultInput(text(event, "subject"), text(event, "entityId"),
            text(event, "status"), text(event, "contentHash"), text(event, "requestId"));
    }

    private static String text(DomainEvent event, String key) {
        Object value = event.payload().get(key);
        return value == null ? null : String.valueOf(value);
    }

    @Bean
    ProcessDefinition<InvoiceProcesses.ApprovalResultInput, InvoiceProcesses.InvoiceOutput, ProcessContext>
        finInvoiceApprovalResultProcess() {
        return InvoiceProcesses.APPROVAL_RESULT_PROCESS;
    }

    @Bean
    ProcessDefinition<ReceiptProcesses.ReceiptInput, ReceiptProcesses.ReceiptOutput, ProcessContext>
        finReceiptRecordProcess() {
        return ReceiptProcesses.RECORD_PROCESS;
    }

    @Bean
    ProcessDefinition<ReceiptProcesses.ApplyInput, ReceiptProcesses.ReceiptOutput, ProcessContext>
        finReceiptApplyProcess() {
        return ReceiptProcesses.APPLY_PROCESS;
    }

    @Bean
    ProcessDefinition<ReceiptProcesses.ReverseInput, ReceiptProcesses.ReverseOutput, ProcessContext>
        finApplicationReverseProcess() {
        return ReceiptProcesses.REVERSE_PROCESS;
    }

    @Bean
    ProcessDefinition<ReceiptProcesses.ReassignInput, ReceiptProcesses.ReceiptOutput, ProcessContext>
        finReceiptReassignProcess() {
        return ReceiptProcesses.REASSIGN_PROCESS;
    }

    @Bean
    ProcessDefinition<ReceiptProcesses.VoidInput, ReceiptProcesses.ReceiptOutput, ProcessContext>
        finReceiptVoidProcess() {
        return ReceiptProcesses.VOID_PROCESS;
    }

    @Bean
    ProcessDefinition<ReceiptProcesses.RefundInput, ReceiptProcesses.RefundOutput, ProcessContext>
        finCreditRefundProcess() {
        return ReceiptProcesses.REFUND_PROCESS;
    }

    @Bean
    ProcessDefinition<WriteOffProcesses.RequestInput, WriteOffProcesses.WriteOffOutput, ProcessContext>
        finWriteOffRequestProcess() {
        return WriteOffProcesses.REQUEST_PROCESS;
    }

    @Bean
    ProcessDefinition<WriteOffProcesses.ApprovalResultInput, WriteOffProcesses.WriteOffOutput, ProcessContext>
        finWriteOffApprovalResultProcess() {
        return WriteOffProcesses.APPROVAL_RESULT_PROCESS;
    }

    @Bean
    ProcessDefinition<WriteOffProcesses.RecoverInput, WriteOffProcesses.RecoverOutput, ProcessContext>
        finWriteOffRecoverProcess() {
        return WriteOffProcesses.RECOVER_PROCESS;
    }

    @Bean
    ProcessDefinition<RecurringInvoiceProcesses.RunInput, RecurringInvoiceProcesses.RunOutput, ProcessContext>
        finRecurringInvoiceRunProcess() {
        return RecurringInvoiceProcesses.RUN_PROCESS;
    }

    /** The recurring invoices of a month, made on its first day at 06:00 in the company's zone (FIN-AR-014). */
    @Bean
    JobDefinition<RecurringInvoiceProcesses.RunInput> recurringInvoiceJob(BookingTime booking) {
        return JobDefinition.cron(RecurringInvoiceProcesses.JOB, "0 0 6 1 * *", booking.zone(),
            RecurringInvoiceProcesses.RUN_PROCESS,
            time -> new RecurringInvoiceProcesses.RunInput(booking.dateOf(time)));
    }

    @Bean
    ProcessDefinition<CustomerProcesses.CustomerInput, CustomerProcesses.CustomerOutput, ProcessContext>
        finCustomerSaveProcess(BookingTime booking) {
        return CustomerProcesses.saveProcess(booking);
    }

    @Bean
    ProcessDefinition<CustomerProcesses.CertificateSave, CustomerProcesses.CertificateOutput, ProcessContext>
        finExemptionCertificateSaveProcess() {
        return CustomerProcesses.CERTIFICATE_PROCESS;
    }

    @Bean
    ProcessDefinition<CustomerProcesses.TermsInput, CustomerProcesses.TermsOutput, ProcessContext>
        finPaymentTermsSaveProcess() {
        return CustomerProcesses.TERMS_PROCESS;
    }

    @Bean
    ProcessDefinition<ArSettingsProcesses.SettingsInput, ArSettingsProcesses.SettingsOutput, ProcessContext>
        finArSettingsSetProcess() {
        return ArSettingsProcesses.SET_PROCESS;
    }

    @Bean
    DocumentLayout finInvoiceDocument() {
        return InvoiceDocuments.INVOICE;
    }

    @Bean
    DocumentLayout finCreditMemoDocument() {
        return InvoiceDocuments.CREDIT_MEMO;
    }

    @Bean
    ProcessDefinition<InvoiceDocuments.InvoiceId, InvoiceDocuments.IssueOutput, ProcessContext>
        finInvoiceIssueProcess(BookingTime booking) {
        return InvoiceDocuments.issueProcess(booking);
    }

    @Bean
    ProcessDefinition<InvoiceDocuments.InvoiceId, InvoiceDocuments.SendOutput, ProcessContext>
        finInvoiceSendProcess(BookingTime booking) {
        return InvoiceDocuments.sendProcess(booking);
    }
}
