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
        return dataset(ApEntities.VENDOR_DATASET, ApEntities.VENDOR, FinancePermissions.VENDOR_MAINTAIN, poolRef);
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
