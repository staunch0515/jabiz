package com.jabiz.finance.report;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.finance.gl.GlEntities.dataset;

/**
 * Registers the statement layouts and report settings ({@link StatementEntities}) and their processes
 * ({@link StatementProcesses}, {@link CashFlowProcesses}).
 */
@Configuration
class StatementConfig {

    @Bean
    EntityDefinition finStatementLayoutEntity() {
        return StatementEntities.LAYOUT_ENTITY;
    }

    @Bean
    EntityDefinition finStatementLayoutRowEntity() {
        return StatementEntities.ROW_ENTITY;
    }

    // Every finance user reads the layouts; only the publishing process writes them.
    @Bean
    DatasetDefinition finStatementLayoutDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(StatementEntities.LAYOUT_DATASET, StatementEntities.LAYOUT, FinancePermissions.PERIOD_READ,
            FinancePermissions.PERIOD_CLOSE, true, poolRef);
    }

    @Bean
    DatasetDefinition finStatementLayoutRowDataset(
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(StatementEntities.ROW_DATASET, StatementEntities.ROW, FinancePermissions.PERIOD_READ,
            FinancePermissions.PERIOD_CLOSE, true, poolRef);
    }

    @Bean
    EntityDefinition finReportSettingsEntity() {
        return StatementEntities.SETTINGS_ENTITY;
    }

    @Bean
    DatasetDefinition finReportSettingsDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(StatementEntities.SETTINGS_DATASET, StatementEntities.SETTINGS, FinancePermissions.PERIOD_READ,
            FinancePermissions.PERIOD_CLOSE, true, poolRef);
    }

    @Bean
    StaticDictionary finStatementDictionary() {
        return StaticDictionary.define(StatementEntities.STATEMENTS, d -> d
            .item(StatementEntities.BALANCE_SHEET, "en", "Balance sheet")
            .item(StatementEntities.INCOME_STATEMENT, "en", "Income statement")
            .item(StatementEntities.EQUITY, "en", "Statement of stockholders' equity"));
    }

    @Bean
    StaticDictionary finStatementRowKindDictionary() {
        return StaticDictionary.define(StatementEntities.ROW_KINDS, d -> d
            .item(StatementEntities.HEADING, "en", "Heading")
            .item(StatementEntities.LINE, "en", "Line")
            .item(StatementEntities.TOTAL, "en", "Total"));
    }

    @Bean
    ProcessDefinition<StatementProcesses.PublishInput, StatementProcesses.PublishOutput, ProcessContext>
        finStatementLayoutPublishProcess() {
        return StatementProcesses.PUBLISH_PROCESS;
    }

    @Bean
    ProcessDefinition<StatementProcesses.IssueInput, StatementProcesses.IssueOutput, ProcessContext>
        finStatementIssueProcess() {
        return StatementProcesses.ISSUE_PROCESS;
    }

    @Bean
    ProcessDefinition<CashFlowProcesses.SettingsInput, CashFlowProcesses.SettingsOutput, ProcessContext>
        finReportSettingsSetProcess() {
        return CashFlowProcesses.SETTINGS_PROCESS;
    }

    @Bean
    ProcessDefinition<CashFlowProcesses.IssueInput, CashFlowProcesses.IssueOutput, ProcessContext>
        finCashFlowIssueProcess() {
        return CashFlowProcesses.ISSUE_PROCESS;
    }
}
