package com.jabiz.finance.ar;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
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
}
