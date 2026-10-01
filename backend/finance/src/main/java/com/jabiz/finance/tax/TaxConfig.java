package com.jabiz.finance.tax;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the sales tax master data and processes ({@link TaxEntities}). */
@Configuration
class TaxConfig {

    @Bean
    EntityDefinition finTaxJurisdictionEntity() {
        return TaxEntities.JURISDICTION_ENTITY;
    }

    @Bean
    EntityDefinition finTaxRateEntity() {
        return TaxEntities.RATE_ENTITY;
    }

    @Bean
    EntityDefinition finTaxCodeEntity() {
        return TaxEntities.CODE_ENTITY;
    }

    @Bean
    DatasetDefinition finTaxJurisdictionDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(TaxEntities.JURISDICTION_DATASET, TaxEntities.JURISDICTION, poolRef);
    }

    @Bean
    DatasetDefinition finTaxRateDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(TaxEntities.RATE_DATASET, TaxEntities.RATE, poolRef);
    }

    @Bean
    DatasetDefinition finTaxCodeDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(TaxEntities.CODE_DATASET, TaxEntities.CODE, poolRef);
    }

    /** Master data every finance user reads; written only through {@link TaxProcesses}. */
    private static DatasetDefinition dataset(String id, String entity, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(FinancePermissions.MASTER_READ, FinancePermissions.TAX_MAINTAIN)
            .policy(p -> p.maxQueryBatchSize(500).processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary taxJurisdictionLevelDictionary() {
        return StaticDictionary.define(TaxEntities.LEVELS, d -> d
            .item("STATE", "en", "State")
            .item("COUNTY", "en", "County")
            .item("CITY", "en", "City")
            .item("SPECIAL", "en", "Special district"));
    }

    @Bean
    StaticDictionary taxKindDictionary() {
        return StaticDictionary.define(TaxEntities.KINDS, d -> d
            .item("TAXABLE", "en", "Taxable")
            .item("EXEMPT", "en", "Exempt")
            .item("NON_TAXABLE", "en", "Non-taxable"));
    }

    @Bean
    StaticDictionary taxExemptReasonDictionary() {
        return StaticDictionary.define(TaxEntities.REASONS, d -> d
            .item("RESALE", "en", "Sale for resale")
            .item("EXEMPT_ORGANIZATION", "en", "Exempt organization")
            .item("GOVERNMENT", "en", "Government")
            .item("NON_TAXABLE_SERVICE", "en", "Non-taxable service")
            .item("NO_SALES_TAX", "en", "No sales tax in the state")
            .item("EXPORT", "en", "Export")
            .item("OTHER", "en", "Other"));
    }

    @Bean
    ProcessDefinition<TaxProcesses.JurisdictionInput, TaxProcesses.JurisdictionOutput, ProcessContext>
        finTaxJurisdictionSaveProcess() {
        return TaxProcesses.JURISDICTION_PROCESS;
    }

    @Bean
    ProcessDefinition<TaxProcesses.RateInput, TaxProcesses.RateOutput, ProcessContext> finTaxRateSetProcess() {
        return TaxProcesses.RATE_PROCESS;
    }

    @Bean
    ProcessDefinition<TaxProcesses.TaxCodeInput, TaxProcesses.TaxCodeOutput, ProcessContext> finTaxCodeSaveProcess() {
        return TaxProcesses.CODE_PROCESS;
    }
}
