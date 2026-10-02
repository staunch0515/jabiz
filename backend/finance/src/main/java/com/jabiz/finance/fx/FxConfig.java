package com.jabiz.finance.fx;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the foreign currency settings and revaluation runs ({@link FxEntities}) and their processes. */
@Configuration
class FxConfig {

    @Bean
    EntityDefinition finFxSettingsEntity() {
        return FxEntities.SETTINGS_ENTITY;
    }

    @Bean
    DatasetDefinition finFxSettingsDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(FxEntities.SETTINGS_DATASET, FxEntities.SETTINGS, FinancePermissions.MASTER_READ,
            FinancePermissions.FX_SETTINGS, true, poolRef);
    }

    @Bean
    EntityDefinition finFxRevaluationRunEntity() {
        return FxEntities.RUN_ENTITY;
    }

    @Bean
    EntityDefinition finFxRevaluationLineEntity() {
        return FxEntities.LINE_ENTITY;
    }

    // Every finance user reads them: the agings show the items remeasured.
    @Bean
    DatasetDefinition finFxRevaluationRunDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(FxEntities.RUN_DATASET, FxEntities.RUN, FinancePermissions.MASTER_READ,
            FinancePermissions.FX_RUN, true, poolRef);
    }

    @Bean
    DatasetDefinition finFxRevaluationLineDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return GlEntities.dataset(FxEntities.LINE_DATASET, FxEntities.LINE, FinancePermissions.MASTER_READ,
            FinancePermissions.FX_RUN, true, poolRef);
    }

    @Bean
    ProcessDefinition<FxRevaluationProcesses.RevalueInput, FxRevaluationProcesses.RunOutput, ProcessContext>
        finFxRevalueProcess() {
        return FxRevaluationProcesses.REVALUE_PROCESS;
    }

    @Bean
    ProcessDefinition<FxRevaluationProcesses.SimulateInput, FxRevaluationProcesses.SimulateOutput, ProcessContext>
        finFxRevalueSimulateProcess() {
        return FxRevaluationProcesses.SIMULATE_PROCESS;
    }

    @Bean
    ProcessDefinition<FxSettingsProcesses.SettingsInput, FxSettingsProcesses.SettingsOutput, ProcessContext>
        finFxSettingsSetProcess() {
        return FxSettingsProcesses.SET_PROCESS;
    }
}
