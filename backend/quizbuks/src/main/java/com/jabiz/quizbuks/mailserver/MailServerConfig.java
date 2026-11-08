package com.jabiz.quizbuks.mailserver;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.mail.MailSenderSource;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of the sponsors' mail servers (Q29): the entity, its datasets, the processes and the platform's source. */
@Configuration
public class MailServerConfig {

    private final String poolRef;

    MailServerConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbSponsorMailServerEntity() {
        return QbMailServers.ENTITY_DEFINITION;
    }

    /**
     * The most servers one commit writes: the processes write one, but the platform's key rotation (decision D78,
     * {@link MailServerSealedFields}) stores a batch of sealed passwords again in one commit, up to
     * {@code jabiz.key-rotation.batch-size} (at most 10,000).
     */
    static final int MAX_WRITE_BATCH = 10_000;

    /** Written by the processes only; the administration reads every server (the password is never returned). */
    @Bean
    DatasetDefinition qbSponsorMailServerDataset() {
        return DatasetDefinition.define(QbMailServers.DEFAULT_DATASET, d -> d
            .targetEntityType(QbMailServers.ENTITY)
            .asDefault()
            .permissions(QbPermissions.ADMIN_USERS_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(MAX_WRITE_BATCH))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The sponsor's own row, read only (its write permission is no role's). */
    @Bean
    DatasetDefinition qbMySponsorMailServerDataset() {
        return DatasetDefinition.define(QbMailServers.SPONSOR_DATASET, d -> d
            .targetEntityType(QbMailServers.ENTITY)
            .permissions(QbPermissions.PUBLICATION_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    /**
     * Where the platform finds a sender's settings (decision D56): the sender key of a mail is the sponsor's id, and
     * only a verified server is handed out.
     */
    @Bean
    MailSenderSource qbSponsorMailServers() {
        return MailSenderSource.of(QbMailServers.ENTITY, "sponsorId", QbMailServers::settingsOf);
    }

    @Bean
    ProcessDefinition<MailServerProcesses.SetInput, MailServerProcesses.SetOutput, ProcessContext> qbMailServerSet() {
        return MailServerProcesses.set();
    }

    @Bean
    ProcessDefinition<MailServerProcesses.RemoveInput, MailServerProcesses.RemoveOutput, ProcessContext>
        qbMailServerRemove() {
        return MailServerProcesses.remove();
    }
}
