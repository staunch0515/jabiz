package com.jabiz.quizbuks.help;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers the AI help's materials (F-C10b, docs/quizbuks/plans/impl/F-C10.md §6): the manuals and the snapshots,
 * their datasets, the snapshots' file policy and job, the processes, the rebuild on publishing and every hour, and the
 * snapshots' reader of the startup check {@code QB_HELP_MATERIALS}.
 *
 * <p>The manuals are edited by the content administrators in the management system like the legal texts' drafts and
 * the FAQ (their default dataset, {@code qb.admin.content.write}; the platform audits every change; the published text
 * and its time are the processes' only). The snapshots' rows are the processes' only; the content administrators read
 * them (what is in use, its size, the latest build).
 */
@Configuration
public class HelpMaterialsConfig {

    private final String poolRef;

    HelpMaterialsConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
        // The repository's manuals are read now, at startup: never on the request path (QB_SETUP, QB_HELP_MANUAL_LOAD).
        HelpManualDrafts.of(QbHelp.APP);
    }

    @Bean
    EntityDefinition qbHelpManualEntity() {
        return QbHelpMaterials.MANUAL_ENTITY;
    }

    @Bean
    EntityDefinition qbHelpMaterialEntity() {
        return QbHelpMaterials.MATERIAL_ENTITY;
    }

    @Bean
    DatasetDefinition qbHelpManualDataset() {
        return DatasetDefinition.define(QbHelpMaterials.defaultDataset(QbHelpMaterials.MANUAL), d -> d
            .targetEntityType(QbHelpMaterials.MANUAL)
            .asDefault()
            .permissions(QbPermissions.ADMIN_CONTENT_WRITE, QbPermissions.ADMIN_CONTENT_WRITE)
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbHelpMaterialDataset() {
        return DatasetDefinition.define(QbHelpMaterials.defaultDataset(QbHelpMaterials.MATERIAL), d -> d
            .targetEntityType(QbHelpMaterials.MATERIAL)
            .asDefault()
            .permissions(QbPermissions.ADMIN_CONTENT_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /**
     * The snapshots: plain text (a type stored as it is and never served inline or publicly), stored by the system's
     * job; read by everybody who asks the help (the help's job checks its requester may read its file). Their content
     * is what the Takers' and Sponsors' help pages and legal pages publish anyway, and the manuals.
     */
    @Bean
    FilePolicy qbHelpMaterialsPolicy() {
        return FilePolicy.define(QbHelpMaterials.POLICY)
            .allow(MediaTypes.TEXT)
            .maxBytes(QbHelpMaterials.MAX_FILE_BYTES)
            .permissions(QbPermissions.ADMIN_CONTENT_WRITE, QbPermissions.HELP_USE)
            .privateMetadata()
            .build();
    }

    @Bean
    ExternalJobType<HelpMaterialsJob.Input, HelpMaterialsJob.Result> qbHelpMaterialsJob() {
        return HelpMaterialsJob.type();
    }

    @Bean
    ProcessDefinition<HelpMaterialsProcesses.PublishInput, HelpMaterialsProcesses.PublishOutput, ProcessContext>
        qbHelpPublish() {
        return HelpMaterialsProcesses.publish();
    }

    @Bean
    ProcessDefinition<HelpMaterialsProcesses.LoadInput, HelpMaterialsProcesses.LoadOutput, ProcessContext>
        qbHelpManualLoad() {
        return HelpMaterialsProcesses.load();
    }

    @Bean
    ProcessDefinition<HelpMaterialsProcesses.BuildInput, HelpMaterialsProcesses.BuildOutput, ProcessContext>
        qbHelpMaterialsBuild() {
        return HelpMaterialsProcesses.build();
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, HelpMaterialsProcesses.StoredOutput, ProcessContext>
        qbHelpMaterialsStored() {
        return HelpMaterialsProcesses.stored();
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, HelpMaterialsProcesses.StoredOutput, ProcessContext>
        qbHelpMaterialsNotStored() {
        return HelpMaterialsProcesses.notStored();
    }

    /** A manual published, or the materials asked to be built again: built at once. */
    @Bean
    EventSubscription<HelpMaterialsProcesses.BuildInput> qbHelpMaterialsChanged(
        ProcessDefinition<HelpMaterialsProcesses.BuildInput, HelpMaterialsProcesses.BuildOutput, ProcessContext>
            qbHelpMaterialsBuild) {
        return EventSubscription.of("qb.help-materials", QbHelpMaterials.CHANGED_EVENT, qbHelpMaterialsBuild,
            event -> new HelpMaterialsProcesses.BuildInput(null));
    }

    /**
     * Every hour: what changed without publishing a manual (an edited FAQ, a legal text taking effect at its time) is
     * built then; an unchanged source writes nothing.
     */
    @Bean
    JobDefinition<HelpMaterialsProcesses.BuildInput> qbHelpMaterialsJobSchedule(
        ProcessDefinition<HelpMaterialsProcesses.BuildInput, HelpMaterialsProcesses.BuildOutput, ProcessContext>
            qbHelpMaterialsBuild) {
        return JobDefinition.cron(HelpMaterialsProcesses.BUILD_JOB, HelpMaterialsProcesses.BUILD_CRON,
            ZoneOffset.UTC, qbHelpMaterialsBuild, HelpMaterialsProcesses.BuildInput::new);
    }

    /**
     * The snapshots' sizes for the startup check {@code QB_HELP_MATERIALS}: each entry's and language's latest build,
     * stored or not (one above the limit is not stored, and fails the check).
     */
    @Bean
    HelpMaterialsCheck.HelpMaterials qbHelpMaterialsReader() {
        return connection -> {
            List<HelpMaterialsCheck.Materials> materials = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT entry, language, latest_bytes FROM qb_help_material ORDER BY entry, language");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    BigDecimal bytes = rows.getBigDecimal(3);
                    materials.add(new HelpMaterialsCheck.Materials(rows.getString(1), rows.getString(2),
                        bytes == null ? 0 : bytes.longValue()));
                }
            }
            return materials;
        };
    }
}
