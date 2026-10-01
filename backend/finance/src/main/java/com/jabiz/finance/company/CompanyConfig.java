package com.jabiz.finance.company;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the company's profile ({@link CompanyEntities}). */
@Configuration
class CompanyConfig {

    @Bean
    EntityDefinition finCompanyProfileEntity() {
        return CompanyEntities.PROFILE_ENTITY;
    }

    /** Every finance user reads it, as other master data; only its process writes it. */
    @Bean
    DatasetDefinition finCompanyProfileDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(CompanyEntities.PROFILE_DATASET, d -> d
            .targetEntityType(CompanyEntities.PROFILE)
            .asDefault()
            .permissions(FinancePermissions.MASTER_READ, FinancePermissions.COMPANY_MAINTAIN)
            .policy(p -> p.maxQueryBatchSize(10).processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    ProcessDefinition<CompanyProcesses.ProfileInput, CompanyProcesses.ProfileOutput, ProcessContext>
        finCompanyProfileSetProcess() {
        return CompanyProcesses.SET_PROCESS;
    }
}
