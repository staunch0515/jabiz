package com.jabiz.finance.config;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.report.StatementEntities;
import com.jabiz.finance.tax.TaxEntities;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.finance.gl.GlEntities.dataset;

/** Configuration promotion (ROADMAP F10d). */
@Configuration
class ConfigConfig {

    @Bean
    EntityDefinition finConfigImportEntity() {
        return ConfigEntities.IMPORT_ENTITY;
    }

    @Bean
    DatasetDefinition finConfigImportDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ConfigEntities.IMPORT_DATASET, ConfigEntities.IMPORT, FinancePermissions.CONFIG_PROMOTE,
            FinancePermissions.CONFIG_PROMOTE, true, poolRef);
    }

    @Bean
    DatasetDefinition finConfigAccountRead(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return reading(ConfigEntities.READ_ACCOUNTS, GlEntities.ACCOUNT, poolRef);
    }

    @Bean
    DatasetDefinition finConfigLedgerAccountRead(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return reading(ConfigEntities.READ_LEDGER_ACCOUNTS, LedgerEntities.ACCOUNT, poolRef);
    }

    @Bean
    DatasetDefinition finConfigJurisdictionRead(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return reading(ConfigEntities.READ_JURISDICTIONS, TaxEntities.JURISDICTION, poolRef);
    }

    @Bean
    DatasetDefinition finConfigRateRead(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return reading(ConfigEntities.READ_RATES, TaxEntities.RATE, poolRef);
    }

    @Bean
    DatasetDefinition finConfigCodeRead(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return reading(ConfigEntities.READ_CODES, TaxEntities.CODE, poolRef);
    }

    @Bean
    DatasetDefinition finConfigLayoutRead(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return reading(ConfigEntities.READ_LAYOUTS, StatementEntities.LAYOUT, poolRef);
    }

    @Bean
    DatasetDefinition finConfigRowRead(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return reading(ConfigEntities.READ_ROWS, StatementEntities.ROW, poolRef);
    }

    @Bean
    DatasetDefinition finConfigSettingsRead(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return reading(ConfigEntities.READ_SETTINGS, StatementEntities.SETTINGS, poolRef);
    }

    /** A read-only dataset of a configuration entity that reads it whole, for the packages' processes. */
    private static DatasetDefinition reading(String id, String entity, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(FinancePermissions.CONFIG_PROMOTE, FinancePermissions.CONFIG_PROMOTE)
            .policy(p -> p.readOnly(true).maxQueryBatchSize(ConfigProcesses.CAP))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary finConfigImportStatusDictionary() {
        return StaticDictionary.define(ConfigEntities.STATUSES, d -> d
            .item(ConfigEntities.PROPOSED, "en", "Proposed: waiting for another person")
            .item(ConfigEntities.PUBLISHED, "en", "Published")
            .item(ConfigEntities.WITHDRAWN, "en", "Withdrawn"));
    }

    @Bean
    ProcessDefinition<ConfigProcesses.ExportInput, ConfigProcesses.ExportOutput, ProcessContext>
        finConfigExportProcess() {
        return ConfigProcesses.EXPORT_PROCESS;
    }

    @Bean
    ProcessDefinition<ConfigProcesses.ProposeInput, ConfigProcesses.ProposeOutput, ProcessContext>
        finConfigImportProposeProcess() {
        return ConfigProcesses.PROPOSE_PROCESS;
    }

    @Bean
    ProcessDefinition<ConfigProcesses.ImportId, ConfigProcesses.PublishOutput, ProcessContext>
        finConfigImportPublishProcess() {
        return ConfigProcesses.PUBLISH_PROCESS;
    }

    @Bean
    ProcessDefinition<ConfigProcesses.ImportId, ConfigProcesses.WithdrawOutput, ProcessContext>
        finConfigImportWithdrawProcess() {
        return ConfigProcesses.WITHDRAW_PROCESS;
    }
}
