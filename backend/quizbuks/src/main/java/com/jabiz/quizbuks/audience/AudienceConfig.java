package com.jabiz.quizbuks.audience;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationAudience;
import com.jabiz.quizbuks.publishing.PublicationOutput;
import com.jabiz.quizbuks.publishing.PublisherKind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of matching (Q47): entities, datasets and processes. */
@Configuration
public class AudienceConfig {

    private final String poolRef;

    AudienceConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbProfileAttributeEntity() {
        return QbAudience.ATTRIBUTE_ENTITY;
    }

    @Bean
    EntityDefinition qbPublicationCriterionEntity() {
        return QbAudience.CRITERION_ENTITY;
    }

    /**
     * Written by the process only and read by nobody directly (its read permission is no role's, D-Q47-5): the
     * administration does not browse what takers told either.
     */
    @Bean
    DatasetDefinition qbProfileAttributeDataset() {
        return DatasetDefinition.define(QbAudience.ATTRIBUTE_DATASET, d -> d
            .targetEntityType(QbAudience.ATTRIBUTE)
            .asDefault()
            .permissions(QbPermissions.AUDIENCE_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(QbAudience.MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The caller's own answers, read only: their profile page, and the feed's matching. */
    @Bean
    DatasetDefinition qbMyProfileAttributeDataset() {
        return DatasetDefinition.define(QbAudience.MY_ATTRIBUTE_DATASET, d -> d
            .targetEntityType(QbAudience.ATTRIBUTE)
            .permissions(QbPermissions.ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    /** Written by the processes only; the administration reads every publication's conditions. */
    @Bean
    DatasetDefinition qbPublicationCriterionDataset() {
        return DatasetDefinition.define(QbAudience.CRITERION_DATASET, d -> d
            .targetEntityType(QbAudience.CRITERION)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(QbAudience.MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The sponsor's own publications' conditions, read only (its write permission is no role's). */
    @Bean
    DatasetDefinition qbSponsorPublicationCriterionDataset() {
        return DatasetDefinition.define(QbAudience.SPONSOR_CRITERION_DATASET, d -> d
            .targetEntityType(QbAudience.CRITERION)
            .permissions(QbPermissions.PUBLICATION_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("publisherId", "actorId", RequestContext::actorId)
                .fixed("publisherKind", PublisherKind.SPONSOR)));
    }

    @Bean
    ProcessDefinition<ProfileAttributeProcesses.AttributesInput, ProfileAttributeProcesses.AttributesOutput,
        ProcessContext> qbProfileAttributesSave() {
        return ProfileAttributeProcesses.save();
    }

    @Bean
    ProcessDefinition<PublicationAudience.AudienceInput, PublicationOutput, ProcessContext> qbPublicationAudienceSave() {
        return PublicationAudience.save();
    }

    @Bean
    ProcessDefinition<PublicationAudience.CountInput, AudienceCount, ProcessContext> qbAudienceCount() {
        return PublicationAudience.count();
    }
}
