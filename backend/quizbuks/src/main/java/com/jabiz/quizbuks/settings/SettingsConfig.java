package com.jabiz.quizbuks.settings;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the users' settings (Q57): the entity, the administrators' dataset (read by those who read users; written
 * by the process only, its write permission one no role holds), one's own dataset and the save.
 */
@Configuration
public class SettingsConfig {

    private final String poolRef;

    SettingsConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbUserSettingsEntity() {
        return QbSettings.ENTITY;
    }

    @Bean
    DatasetDefinition qbUserSettingsDataset() {
        return DatasetDefinition.define(QbSettings.DEFAULT_DATASET, d -> d
            .targetEntityType(QbSettings.SETTINGS)
            .asDefault()
            .permissions(QbPermissions.ADMIN_USERS_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(1))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbMyUserSettingsDataset() {
        return DatasetDefinition.define(QbSettings.MY_DATASET, d -> d
            .targetEntityType(QbSettings.SETTINGS)
            .permissions(QbPermissions.ACCOUNT_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    ProcessDefinition<SettingsProcesses.SaveInput, SettingsProcesses.SettingsOutput, ProcessContext> qbSettingsSave() {
        return SettingsProcesses.save();
    }
}
