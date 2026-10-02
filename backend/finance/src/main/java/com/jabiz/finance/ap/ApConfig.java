package com.jabiz.finance.ap;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the payables' master data and processes ({@link ApEntities}). */
@Configuration
class ApConfig {

    @Bean
    EntityDefinition finVendorEntity() {
        return ApEntities.VENDOR_ENTITY;
    }

    @Bean
    EntityDefinition finVendorTaxInfoEntity() {
        return ApEntities.TAX_INFO_ENTITY;
    }

    @Bean
    EntityDefinition finVendorBankAccountEntity() {
        return ApEntities.VENDOR_BANK_ENTITY;
    }

    @Bean
    EntityDefinition fin1099ThresholdEntity() {
        return ApEntities.THRESHOLD_ENTITY;
    }

    @Bean
    EntityDefinition finApSettingsEntity() {
        return ApEntities.SETTINGS_ENTITY;
    }

    @Bean
    DatasetDefinition finVendorDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        // Read by the opening import for all its vendors at once.
        return DatasetDefinition.define(ApEntities.VENDOR_DATASET, d -> d
            .targetEntityType(ApEntities.VENDOR)
            .asDefault()
            .permissions(FinancePermissions.AP_READ, FinancePermissions.VENDOR_MAINTAIN)
            .policy(p -> p.maxQueryBatchSize(5000).processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition finVendorTaxInfoDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ApEntities.TAX_INFO_DATASET, ApEntities.TAX_INFO, FinancePermissions.VENDOR_MAINTAIN, poolRef);
    }

    @Bean
    DatasetDefinition finVendorBankAccountDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ApEntities.VENDOR_BANK_DATASET, ApEntities.VENDOR_BANK, FinancePermissions.VENDOR_BANK_MAINTAIN,
            poolRef);
    }

    @Bean
    DatasetDefinition fin1099ThresholdDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ApEntities.THRESHOLD_DATASET, ApEntities.THRESHOLD, FinancePermissions.FORM_1099_MAINTAIN,
            poolRef);
    }

    @Bean
    DatasetDefinition finApSettingsDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ApEntities.SETTINGS_DATASET, ApEntities.SETTINGS, FinancePermissions.AP_SETTINGS, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String writePermission, String poolRef) {
        return GlEntities.dataset(id, entity, FinancePermissions.AP_READ, writePermission, true, poolRef);
    }

    /**
     * The documents' datasets take a whole bill at once: up to 500 lines (and as many to replace), and the opening
     * import's up to 5,000 open items in one change set.
     */
    private static DatasetDefinition documents(String id, String entity, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(FinancePermissions.AP_READ, FinancePermissions.BILL_PREPARE)
            .policy(p -> p.maxQueryBatchSize(5000).maxWriteBatchSize(5000).processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    FilePolicy w9Files() {
        return FilePolicy.define(ApEntities.W9_FILES)
            .allow(MediaTypes.PDF, MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(25 * FilePolicy.MB)
            // A W-9 shows the TIN in plain text: only who reads TINs reads it.
            .permissions(FinancePermissions.VENDOR_MAINTAIN, FinancePermissions.TAX_DATA_READ)
            .build();
    }

    @Bean
    StaticDictionary vendorStatusDictionary() {
        return StaticDictionary.define(ApEntities.VENDOR_STATUSES, d -> d
            .item("ACTIVE", "en", "Active")
            .item("INACTIVE", "en", "Inactive"));
    }

    @Bean
    StaticDictionary paymentMethodDictionary() {
        return StaticDictionary.define(ApEntities.PAYMENT_METHODS, d -> d
            .item("ACH", "en", "ACH")
            .item("CHECK", "en", "Check")
            .item("WIRE", "en", "Wire")
            .item("CARD", "en", "Card"));
    }

    @Bean
    StaticDictionary vendorEntityTypeDictionary() {
        return StaticDictionary.define(ApEntities.ENTITY_TYPES, d -> d
            .item("INDIVIDUAL", "en", "Individual or sole proprietor")
            .item("SINGLE_MEMBER_LLC", "en", "Single-member LLC")
            .item("PARTNERSHIP", "en", "Partnership")
            .item("C_CORPORATION", "en", "C corporation")
            .item("S_CORPORATION", "en", "S corporation")
            .item("TRUST_ESTATE", "en", "Trust or estate")
            .item("GOVERNMENT", "en", "Government")
            .item("TAX_EXEMPT", "en", "Tax-exempt organization")
            .item("OTHER", "en", "Other"));
    }

    @Bean
    StaticDictionary form1099Dictionary() {
        return StaticDictionary.define(ApEntities.FORMS_1099, d -> d
            .item("NEC", "en", "1099-NEC")
            .item("MISC", "en", "1099-MISC"));
    }

    @Bean
    StaticDictionary tinTypeDictionary() {
        return StaticDictionary.define(ApEntities.TIN_TYPES, d -> d
            .item("SSN", "en", "SSN")
            .item("EIN", "en", "EIN")
            .item("ITIN", "en", "ITIN"));
    }

    @Bean
    StaticDictionary tinStatusDictionary() {
        return StaticDictionary.define(ApEntities.TIN_STATUSES, d -> d
            .item("UNVERIFIED", "en", "Not verified")
            .item("MATCHED", "en", "Matched")
            .item("MISMATCH", "en", "Mismatch"));
    }

    @Bean
    StaticDictionary bankAccountTypeDictionary() {
        return StaticDictionary.define(ApEntities.ACCOUNT_TYPES, d -> d
            .item("CHECKING", "en", "Checking")
            .item("SAVINGS", "en", "Savings"));
    }

    @Bean
    StaticDictionary vendorBankStatusDictionary() {
        return StaticDictionary.define(ApEntities.BANK_STATUSES, d -> d
            .item(ApEntities.PENDING, "en", "Waiting for approval")
            .item(ApEntities.ACTIVE, "en", "In use")
            .item(ApEntities.REJECTED, "en", "Rejected")
            .item(ApEntities.REPLACED, "en", "Replaced"));
    }

    /** Bank changes always need approval (FIN-AP-003): the rule {@code FIN_SETUP} proposes says by whom. */
    @Bean
    ApprovalSubject vendorBankApprovals() {
        return ApprovalSubject.define(VendorBankProcesses.SUBJECT, s -> s
            .entity(ApEntities.VENDOR)
            .text("vendorCode"));
    }

    @Bean
    EventSubscription<VendorBankProcesses.ApprovalResultInput> vendorBankApprovedSubscription() {
        return EventSubscription.of("fin.vendor-bank-approved", ApprovalProcesses.APPROVED_EVENT,
            VendorBankProcesses.RESULT_PROCESS, ApConfig::bankDecision);
    }

    @Bean
    EventSubscription<VendorBankProcesses.ApprovalResultInput> vendorBankRejectedSubscription() {
        return EventSubscription.of("fin.vendor-bank-rejected", ApprovalProcesses.REJECTED_EVENT,
            VendorBankProcesses.RESULT_PROCESS, ApConfig::bankDecision);
    }

    private static VendorBankProcesses.ApprovalResultInput bankDecision(DomainEvent event) {
        return new VendorBankProcesses.ApprovalResultInput(text(event, "subject"), text(event, "entityId"),
            text(event, "status"), text(event, "contentHash"), text(event, "requestId"));
    }

    private static String text(DomainEvent event, String key) {
        Object value = event.payload().get(key);
        return value == null ? null : String.valueOf(value);
    }

    // ---- F4b: bills ------------------------------------------------------------------------------------------------

    @Bean
    EntityDefinition finBillEntity() {
        return BillEntities.BILL_ENTITY;
    }

    @Bean
    EntityDefinition finBillLineEntity() {
        return BillEntities.LINE_ENTITY;
    }

    @Bean
    EntityDefinition finBillTaxEntity() {
        return BillEntities.TAX_ENTITY;
    }

    @Bean
    EntityDefinition finApApplicationEntity() {
        return BillEntities.APPLICATION_ENTITY;
    }

    @Bean
    DatasetDefinition finBillDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return documents(BillEntities.BILL_DATASET, BillEntities.BILL, poolRef);
    }

    @Bean
    DatasetDefinition finBillLineDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return documents(BillEntities.LINE_DATASET, BillEntities.LINE, poolRef);
    }

    @Bean
    DatasetDefinition finBillTaxDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return documents(BillEntities.TAX_DATASET, BillEntities.TAX, poolRef);
    }

    @Bean
    DatasetDefinition finApApplicationDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return documents(BillEntities.APPLICATION_DATASET, BillEntities.APPLICATION, poolRef);
    }

    @Bean
    NumberSequence billNumbers() {
        return BillProcesses.billNumbers();
    }

    @Bean
    NumberSequence vendorCreditNumbers() {
        return BillProcesses.creditNumbers();
    }

    @Bean
    FilePolicy billFiles() {
        return FilePolicy.define(BillEntities.BILL_FILES)
            .allow(MediaTypes.PDF, MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(25 * FilePolicy.MB)
            .permissions(FinancePermissions.BILL_PREPARE, FinancePermissions.AP_READ)
            .build();
    }

    @Bean
    StaticDictionary apDocumentKindDictionary() {
        return StaticDictionary.define(BillEntities.KINDS, d -> d
            .item(BillEntities.BILL_KIND, "en", "Bill")
            .item(BillEntities.CREDIT, "en", "Vendor credit"));
    }

    @Bean
    StaticDictionary apDocumentStatusDictionary() {
        return StaticDictionary.define(BillEntities.STATUSES, d -> d
            .item(BillEntities.DRAFT, "en", "Draft")
            .item(BillEntities.POSTED, "en", "Posted")
            .item(BillEntities.VOID, "en", "Void"));
    }

    @Bean
    StaticDictionary apDocumentSourceDictionary() {
        return StaticDictionary.define(BillEntities.SOURCES, d -> d
            .item(BillEntities.MANUAL, "en", "Entered")
            .item(BillEntities.OPENING, "en", "Opening item"));
    }

    @Bean
    StaticDictionary apApprovalDictionary() {
        return StaticDictionary.define(BillEntities.APPROVALS, d -> d
            .item(BillEntities.NOT_REQUIRED, "en", "Not required")
            .item(BillEntities.PENDING, "en", "Waiting for approval")
            .item(BillEntities.APPROVED, "en", "Approved")
            .item(BillEntities.REJECTED, "en", "Rejected"));
    }

    /**
     * What the approval rules of bills can ask about (FIN-AP-006): the amount, the vendor, the account and department
     * of the largest line, and whether the bill makes an asset. {@code FIN_SETUP} proposes the rule of bills above
     * 10,000.00.
     */
    @Bean
    ApprovalSubject billApprovals() {
        return ApprovalSubject.define(BillProcesses.SUBJECT, s -> s
            .entity(BillEntities.BILL)
            .number("amount")
            .text("vendorCode")
            .text("account")
            .text("department")
            .bool("capital"));
    }

    @Bean
    EventSubscription<BillProcesses.ApprovalResultInput> billApprovedSubscription() {
        return EventSubscription.of("fin.bill-approved", ApprovalProcesses.APPROVED_EVENT,
            BillProcesses.APPROVAL_RESULT_PROCESS, ApConfig::billDecision);
    }

    @Bean
    EventSubscription<BillProcesses.ApprovalResultInput> billRejectedSubscription() {
        return EventSubscription.of("fin.bill-rejected", ApprovalProcesses.REJECTED_EVENT,
            BillProcesses.APPROVAL_RESULT_PROCESS, ApConfig::billDecision);
    }

    private static BillProcesses.ApprovalResultInput billDecision(DomainEvent event) {
        return new BillProcesses.ApprovalResultInput(text(event, "subject"), text(event, "entityId"),
            text(event, "status"), text(event, "requestId"));
    }

    @Bean
    ProcessDefinition<BillProcesses.BillInput, BillProcesses.BillOutput, ProcessContext> finBillSaveProcess() {
        return BillProcesses.SAVE_PROCESS;
    }

    @Bean
    ProcessDefinition<BillProcesses.BillId, BillProcesses.BillOutput, ProcessContext> finBillDeleteProcess() {
        return BillProcesses.DELETE_PROCESS;
    }

    @Bean
    ProcessDefinition<BillProcesses.BillId, BillProcesses.BillOutput, ProcessContext> finBillPostProcess() {
        return BillProcesses.POST_PROCESS;
    }

    @Bean
    ProcessDefinition<BillProcesses.VoidInput, BillProcesses.BillOutput, ProcessContext> finBillVoidProcess() {
        return BillProcesses.VOID_PROCESS;
    }

    @Bean
    ProcessDefinition<BillProcesses.ApplyInput, BillProcesses.ApplyOutput, ProcessContext> finApApplyProcess() {
        return BillProcesses.APPLY_PROCESS;
    }

    @Bean
    ProcessDefinition<BillProcesses.UnapplyInput, BillProcesses.ApplyOutput, ProcessContext> finApUnapplyProcess() {
        return BillProcesses.UNAPPLY_PROCESS;
    }

    @Bean
    ProcessDefinition<BillProcesses.ApprovalResultInput, BillProcesses.BillOutput, ProcessContext>
        finBillApprovalResultProcess() {
        return BillProcesses.APPROVAL_RESULT_PROCESS;
    }

    @Bean
    ProcessDefinition<BillProcesses.OpeningInput, BillProcesses.OpeningOutput, ProcessContext> finApOpeningProcess() {
        return BillProcesses.OPENING_PROCESS;
    }

    @Bean
    ProcessDefinition<VendorProcesses.VendorInput, VendorProcesses.VendorOutput, ProcessContext>
        finVendorSaveProcess(BookingTime booking) {
        return VendorProcesses.saveProcess(booking);
    }

    @Bean
    ProcessDefinition<VendorProcesses.TaxInput, VendorProcesses.TaxOutput, ProcessContext> finVendorTaxSaveProcess() {
        return VendorProcesses.TAX_PROCESS;
    }

    @Bean
    ProcessDefinition<VendorBankProcesses.ChangeInput, VendorBankProcesses.ChangeOutput, ProcessContext>
        finVendorBankChangeProcess() {
        return VendorBankProcesses.CHANGE_PROCESS;
    }

    @Bean
    ProcessDefinition<VendorBankProcesses.ApprovalResultInput, VendorBankProcesses.ResultOutput, ProcessContext>
        finVendorBankApprovalResultProcess() {
        return VendorBankProcesses.RESULT_PROCESS;
    }

    @Bean
    ProcessDefinition<ApSettingsProcesses.SettingsInput, ApSettingsProcesses.SettingsOutput, ProcessContext>
        finApSettingsSetProcess() {
        return ApSettingsProcesses.SET_PROCESS;
    }

    @Bean
    ProcessDefinition<ApSettingsProcesses.ThresholdInput, ApSettingsProcesses.ThresholdOutput, ProcessContext>
        fin1099ThresholdSetProcess() {
        return ApSettingsProcesses.THRESHOLD_PROCESS;
    }
}
