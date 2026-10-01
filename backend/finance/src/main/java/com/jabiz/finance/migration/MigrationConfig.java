package com.jabiz.finance.migration;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The migration's decisions (FIN-DI-003): entity, dataset (written only by its process), kinds and process. */
@Configuration
class MigrationConfig {

    @Bean
    EntityDefinition finMigrationDecisionEntity() {
        return MigrationEntities.DECISION_ENTITY;
    }

    @Bean
    DatasetDefinition finMigrationDecisionDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(MigrationEntities.DECISION_DATASET, d -> d
            .targetEntityType(MigrationEntities.DECISION)
            .asDefault()
            .permissions(FinancePermissions.JOURNAL_READ, FinancePermissions.MIGRATION)
            .policy(p -> p.maxQueryBatchSize(500).processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary migrationDecisionKindDictionary() {
        return StaticDictionary.define(MigrationEntities.DECISION_KINDS, d -> d
            .item(MigrationEntities.ACCOUNT, "en", "Account"));
    }

    @Bean
    ProcessDefinition<MigrationProcesses.DecisionInput, MigrationProcesses.DecisionOutput, ProcessContext>
        finMigrationDecideProcess() {
        return MigrationProcesses.DECIDE_PROCESS;
    }
}
