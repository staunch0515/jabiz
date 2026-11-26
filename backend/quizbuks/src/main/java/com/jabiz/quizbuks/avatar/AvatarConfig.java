package com.jabiz.quizbuks.avatar;

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
 * Registers the takers' avatars (Q114a): the entity, the administrators' dataset (read by those who read users;
 * written by the process only, its write permission one no role holds), one's own dataset and the choice.
 */
@Configuration
public class AvatarConfig {

    private final String poolRef;

    AvatarConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbTakerAvatarEntity() {
        return QbAvatars.ENTITY;
    }

    @Bean
    DatasetDefinition qbTakerAvatarDataset() {
        return DatasetDefinition.define(QbAvatars.DEFAULT_DATASET, d -> d
            .targetEntityType(QbAvatars.AVATAR)
            .asDefault()
            .permissions(QbPermissions.ADMIN_USERS_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(1))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbMyTakerAvatarDataset() {
        return DatasetDefinition.define(QbAvatars.MY_DATASET, d -> d
            .targetEntityType(QbAvatars.AVATAR)
            .permissions(QbPermissions.ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    ProcessDefinition<AvatarProcesses.SetInput, AvatarProcesses.AvatarOutput, ProcessContext> qbAvatarSet() {
        return AvatarProcesses.set();
    }
}
