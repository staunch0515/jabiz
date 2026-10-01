package com.jabiz.finance.payroll;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.finance.gl.JournalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The payroll mapping (kept by the controller through its dataset) and the payroll import (FIN-DI-004). */
@Configuration
class PayrollConfig {

    @Bean
    EntityDefinition finPayrollMappingEntity() {
        return PayrollEntities.MAPPING_ENTITY;
    }

    @Bean
    DatasetDefinition finPayrollMappingDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(PayrollEntities.MAPPING_DATASET, d -> d
            .targetEntityType(PayrollEntities.MAPPING)
            .asDefault()
            .permissions(FinancePermissions.JOURNAL_READ, FinancePermissions.PAYROLL_MAINTAIN)
            .policy(p -> p.maxQueryBatchSize(500))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    ProcessDefinition<PayrollProcesses.PayrollInput, JournalProcesses.JournalOutput, ProcessContext>
        finPayrollImportProcess() {
        return PayrollProcesses.IMPORT_PROCESS;
    }
}
