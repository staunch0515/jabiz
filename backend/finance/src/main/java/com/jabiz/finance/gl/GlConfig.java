package com.jabiz.finance.gl;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.ledger.LedgerDimension;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.function.Consumer;

import static com.jabiz.finance.gl.GlEntities.dataset;

/** Registers the general ledger's master data and processes ({@link GlEntities}). */
@Configuration
class GlConfig {

    /** Where posting dates become ledger booking times (docs/finance/00-design.md section 4.2). */
    @Bean
    BookingTime bookingTime(@Value("${finance.company.zone:America/Chicago}") String zone) {
        return new BookingTime(ZoneId.of(zone));
    }

    @Bean
    EntityDefinition finAccountEntity() {
        return GlEntities.ACCOUNT_ENTITY;
    }

    @Bean
    EntityDefinition finDepartmentEntity() {
        return GlEntities.DEPARTMENT_ENTITY;
    }

    @Bean
    EntityDefinition finLocationEntity() {
        return GlEntities.LOCATION_ENTITY;
    }

    @Bean
    EntityDefinition finCurrencyEntity() {
        return GlEntities.CURRENCY_ENTITY;
    }

    @Bean
    EntityDefinition finExchangeRateEntity() {
        return GlEntities.EXCHANGE_RATE_ENTITY;
    }

    @Bean
    EntityDefinition finFiscalYearEntity() {
        return GlEntities.FISCAL_YEAR_ENTITY;
    }

    @Bean
    EntityDefinition finPeriodEntity() {
        return GlEntities.PERIOD_ENTITY;
    }

    @Bean
    DatasetDefinition finAccountDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(GlEntities.ACCOUNT_DATASET, GlEntities.ACCOUNT, FinancePermissions.ACCOUNT_READ,
            FinancePermissions.ACCOUNT_MAINTAIN, true, poolRef);
    }

    @Bean
    DatasetDefinition finDepartmentDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(GlEntities.DEPARTMENT_DATASET, GlEntities.DEPARTMENT, FinancePermissions.MASTER_READ,
            FinancePermissions.DIMENSION_MAINTAIN, false, poolRef);
    }

    @Bean
    DatasetDefinition finLocationDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(GlEntities.LOCATION_DATASET, GlEntities.LOCATION, FinancePermissions.MASTER_READ,
            FinancePermissions.DIMENSION_MAINTAIN, false, poolRef);
    }

    @Bean
    DatasetDefinition finCurrencyDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(GlEntities.CURRENCY_DATASET, GlEntities.CURRENCY, FinancePermissions.MASTER_READ,
            FinancePermissions.FX_MAINTAIN, false, poolRef);
    }

    @Bean
    DatasetDefinition finExchangeRateDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(GlEntities.EXCHANGE_RATE_DATASET, GlEntities.EXCHANGE_RATE, FinancePermissions.MASTER_READ,
            FinancePermissions.FX_MAINTAIN, false, poolRef);
    }

    @Bean
    DatasetDefinition finFiscalYearDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(GlEntities.FISCAL_YEAR_DATASET, GlEntities.FISCAL_YEAR, FinancePermissions.PERIOD_READ,
            FinancePermissions.PERIOD_MAINTAIN, true, poolRef);
    }

    @Bean
    DatasetDefinition finPeriodDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(GlEntities.PERIOD_DATASET, GlEntities.PERIOD, FinancePermissions.PERIOD_READ,
            FinancePermissions.PERIOD_CLOSE, true, poolRef);
    }

    /** The two analysis dimensions of ledger lines (FIN-GL-006), valid values from their lists. */
    @Bean
    LedgerDimension departmentDimension() {
        return LedgerDimension.define(1, "department", d -> d.entity(GlEntities.DEPARTMENT, "departmentCode"));
    }

    @Bean
    LedgerDimension locationDimension() {
        return LedgerDimension.define(2, "location", d -> d.entity(GlEntities.LOCATION, "locationCode"));
    }

    @Bean
    StaticDictionary financialTypeDictionary() {
        return dictionary(GlEntities.FINANCIAL_TYPES, d -> d
            .item("ASSET", "en", "Asset")
            .item("LIABILITY", "en", "Liability")
            .item("EQUITY", "en", "Equity")
            .item("REVENUE", "en", "Revenue")
            .item("EXPENSE", "en", "Expense")
            .item("OTHER", "en", "Other income or expense")
            .item("TAX", "en", "Income tax"));
    }

    @Bean
    StaticDictionary normalBalanceDictionary() {
        return dictionary(GlEntities.NORMAL_BALANCES, d -> d
            .item("DEBIT", "en", "Debit")
            .item("CREDIT", "en", "Credit"));
    }

    @Bean
    StaticDictionary cashFlowClassDictionary() {
        return dictionary(GlEntities.CASH_FLOW_CLASSES, d -> d
            .item("CASH", "en", "Cash and cash equivalents")
            .item("OPERATING", "en", "Operating activities")
            .item("INVESTING", "en", "Investing activities")
            .item("FINANCING", "en", "Financing activities"));
    }

    @Bean
    StaticDictionary controlClassDictionary() {
        return dictionary(GlEntities.CONTROL_CLASSES, d -> d
            .item("AR", "en", "Accounts receivable")
            .item("AP", "en", "Accounts payable")
            .item("FA_COST", "en", "Fixed-asset cost")
            .item("FA_ACCUM", "en", "Accumulated depreciation")
            .item("BANK", "en", "Bank"));
    }

    @Bean
    StaticDictionary dimensionDictionary() {
        return dictionary(GlEntities.DIMENSIONS, d -> d
            .item("department", "en", "Department")
            .item("location", "en", "Location"));
    }

    @Bean
    StaticDictionary rateTypeDictionary() {
        return dictionary(GlEntities.RATE_TYPES, d -> d
            .item("SPOT", "en", "Spot")
            .item("CLOSING", "en", "Closing")
            .item("AVERAGE", "en", "Average"));
    }

    @Bean
    StaticDictionary periodStatusDictionary() {
        return dictionary(GlEntities.PERIOD_STATUSES, d -> d
            .item("OPEN", "en", "Open")
            .item("SOFT_CLOSED", "en", "Soft-closed")
            .item("CLOSED", "en", "Closed"));
    }

    @Bean
    StaticDictionary subledgerStatusDictionary() {
        return dictionary(GlEntities.SUBLEDGER_STATUSES, d -> d
            .item("OPEN", "en", "Open")
            .item("CLOSED", "en", "Closed"));
    }

    private static StaticDictionary dictionary(String urn, Consumer<StaticDictionary.Builder> items) {
        return StaticDictionary.define(urn, items);
    }

    @Bean
    ProcessDefinition<AccountProcesses.AccountInput, AccountProcesses.AccountOutput, ProcessContext>
        finAccountCreateProcess() {
        return AccountProcesses.CREATE_PROCESS;
    }

    @Bean
    ProcessDefinition<AccountProcesses.AccountChange, AccountProcesses.AccountOutput, ProcessContext>
        finAccountUpdateProcess() {
        return AccountProcesses.UPDATE_PROCESS;
    }

    @Bean
    ProcessDefinition<AccountProcesses.AccountCode, AccountProcesses.AccountOutput, ProcessContext>
        finAccountDeactivateProcess() {
        return AccountProcesses.DEACTIVATE_PROCESS;
    }

    @Bean
    ProcessDefinition<AccountProcesses.AccountCode, AccountProcesses.AccountOutput, ProcessContext>
        finAccountReactivateProcess() {
        return AccountProcesses.REACTIVATE_PROCESS;
    }

    @Bean
    ProcessDefinition<AccountProcesses.AccountCode, AccountProcesses.AccountOutput, ProcessContext>
        finAccountDeleteProcess() {
        return AccountProcesses.DELETE_PROCESS;
    }

    @Bean
    ProcessDefinition<ChartTemplateProcesses.PreviewInput, ChartTemplateProcesses.TemplateOutput, ProcessContext>
        finChartTemplatePreviewProcess() {
        // Reads the template now, at startup, rather than on the first request's thread.
        ChartTemplate.lines();
        return ChartTemplateProcesses.PREVIEW_PROCESS;
    }

    @Bean
    ProcessDefinition<ChartTemplateProcesses.ApplyInput, ChartTemplateProcesses.TemplateOutput, ProcessContext>
        finChartTemplateApplyProcess() {
        return ChartTemplateProcesses.APPLY_PROCESS;
    }

    @Bean
    ProcessDefinition<PeriodProcesses.FiscalYearInput, PeriodProcesses.FiscalYearOutput, ProcessContext>
        finFiscalYearCreateProcess() {
        return PeriodProcesses.FISCAL_YEAR_PROCESS;
    }

    @Bean
    ProcessDefinition<PeriodProcesses.StateInput, PeriodProcesses.PeriodOutput, ProcessContext>
        finPeriodSetStateProcess() {
        return PeriodProcesses.STATE_PROCESS;
    }

    @Bean
    ProcessDefinition<PeriodProcesses.SubledgerStateInput, PeriodProcesses.PeriodOutput, ProcessContext>
        finPeriodSetSubledgerStateProcess() {
        return PeriodProcesses.SUBLEDGER_STATE_PROCESS;
    }
}
