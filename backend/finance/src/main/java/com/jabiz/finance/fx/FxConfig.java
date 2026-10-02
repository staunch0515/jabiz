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

/** Registers the foreign currency settings ({@link FxEntities}) and their process. */
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
    ProcessDefinition<FxSettingsProcesses.SettingsInput, FxSettingsProcesses.SettingsOutput, ProcessContext>
        finFxSettingsSetProcess() {
        return FxSettingsProcesses.SET_PROCESS;
    }
}
