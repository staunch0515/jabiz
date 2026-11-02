package com.jabiz.quizbuks.publishing;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of a publication's prerequisites (Q76): the entity, its datasets and the save. */
@Configuration
public class PrerequisiteConfig {

    private final String poolRef;

    PrerequisiteConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbPublicationPrerequisiteEntity() {
        return QbPrerequisites.ENTITY;
    }

    /**
     * Written by the process only; the administration reads every publication's (an operator's session its sponsors'
     * only, Q59c-2); the feed, the publication page and the start read them through templates and processes.
     */
    @Bean
    DatasetDefinition qbPublicationPrerequisiteDataset() {
        return DatasetDefinition.define(QbPrerequisites.DATASET, d -> d
            .targetEntityType(QbPrerequisites.PREREQUISITE)
            .asDefault()
            .scope(OperatorScopes.of(QbPrerequisites.PREREQUISITE))
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(QbPrerequisites.MAX_PREREQUISITES * 4))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The sponsor's own publications' prerequisites, read only (its write permission is no role's). */
    @Bean
    DatasetDefinition qbSponsorPublicationPrerequisiteDataset() {
        return DatasetDefinition.define(QbPrerequisites.SPONSOR_DATASET, d -> d
            .targetEntityType(QbPrerequisites.PREREQUISITE)
            .permissions(QbPermissions.PUBLICATION_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("publisherId", "actorId", RequestContext::actorId)
                .fixed("publisherKind", PublisherKind.SPONSOR)));
    }

    /**
     * Which publication requires which, and how: keys only, for the layers of the sponsor's page (D-Q76-8). The public
     * template joins live public cards on both sides, so only those show.
     */
    @Bean
    DatasetDefinition qbPublicPublicationPrerequisiteDataset() {
        return DatasetDefinition.define(QbPrerequisites.PUBLIC_DATASET, d -> d
            .targetEntityType(QbPrerequisites.PREREQUISITE)
            .publicRead(p -> p.allRows().fields("publicationId", "requiredPublicationId", "requirement"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    ProcessDefinition<PublicationPrerequisites.PrerequisitesInput, PublicationOutput, ProcessContext>
        qbPublicationPrerequisitesSave() {
        return PublicationPrerequisites.save();
    }
}
