package com.jabiz.quizbuks.journal;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Registers the journal export (docs/quizbuks/plans/Q9d-journal-receipts.md): its two entities, read by finance and
 * written by the processes only, the voucher numbers and the processes, within the platform's limits of a read and of
 * a generated file ({@link JournalLimits}).
 */
@Configuration
class JournalConfig {

    private final String poolRef;
    private final JournalLimits limits;

    JournalConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.process.max-read-rows:100000}") int maxReadRows,
        @Value("${jabiz.files.generated.max-bytes:10485760}") long maxFileBytes,
        @Value("${quizbuks.journal.max-open-transaction:PT5M}") Duration maxOpenTransaction) {
        this.poolRef = poolRef;
        this.limits = new JournalLimits(maxReadRows, maxFileBytes, maxOpenTransaction);
    }

    @Bean
    EntityDefinition qbJournalExportEntity() {
        return QbJournal.EXPORT_ENTITY;
    }

    @Bean
    EntityDefinition qbJournalExportConfirmationEntity() {
        return QbJournal.CONFIRMATION_ENTITY;
    }

    @Bean
    EntityDefinition qbJournalExportPartEntity() {
        return QbJournal.PART_ENTITY;
    }

    @Bean
    DatasetDefinition qbJournalExportDataset() {
        return processOnly(QbJournal.EXPORT, 10);
    }

    /** One row per part of the detail, all written by one export. */
    @Bean
    DatasetDefinition qbJournalExportPartDataset() {
        return processOnly(QbJournal.PART, 10_000);
    }

    @Bean
    DatasetDefinition qbJournalExportConfirmationDataset() {
        return processOnly(QbJournal.CONFIRMATION, 10);
    }

    private DatasetDefinition processOnly(String entity, int maxWriteBatchSize) {
        return DatasetDefinition.define(QbJournal.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(maxWriteBatchSize))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** {@code QBJ-2026-00001}: one count per year of the periods, without gaps (platform 18 section 2). */
    @Bean
    NumberSequence qbJournalVoucherNumbers() {
        return NumberSequence.define(QbJournal.VOUCHER_SEQUENCE, s -> s.format("QBJ-{scope}-{n:5}").scoped());
    }

    @Bean
    ProcessDefinition<JournalProcesses.PreviewInput, JournalProcesses.PreviewOutput, ProcessContext>
        qbJournalPreview() {
        return JournalProcesses.preview(limits);
    }

    @Bean
    ProcessDefinition<JournalProcesses.ExportInput, JournalProcesses.ExportOutput, ProcessContext>
        qbJournalExport() {
        return JournalProcesses.export(limits);
    }

    @Bean
    ProcessDefinition<JournalProcesses.PartInput, JournalProcesses.DetailFile, ProcessContext> qbJournalDetailPart() {
        return JournalProcesses.detailPart(limits);
    }

    @Bean
    ProcessDefinition<JournalProcesses.ConfirmInput, JournalProcesses.ConfirmOutput, ProcessContext>
        qbJournalExportConfirm() {
        return JournalProcesses.confirm();
    }
}
