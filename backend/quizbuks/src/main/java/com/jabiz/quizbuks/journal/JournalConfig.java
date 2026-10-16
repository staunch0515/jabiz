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

/**
 * Registers the journal export (docs/quizbuks/plans/Q9d-journal-receipts.md): its two entities, read by finance and
 * written by the processes only, the voucher numbers and the three processes.
 */
@Configuration
class JournalConfig {

    private final String poolRef;

    JournalConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
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
    DatasetDefinition qbJournalExportDataset() {
        return processOnly(QbJournal.EXPORT);
    }

    @Bean
    DatasetDefinition qbJournalExportConfirmationDataset() {
        return processOnly(QbJournal.CONFIRMATION);
    }

    private DatasetDefinition processOnly(String entity) {
        return DatasetDefinition.define(QbJournal.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(10))
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
        return JournalProcesses.preview();
    }

    @Bean
    ProcessDefinition<JournalProcesses.ExportInput, JournalProcesses.ExportOutput, ProcessContext>
        qbJournalExport() {
        return JournalProcesses.export();
    }

    @Bean
    ProcessDefinition<JournalProcesses.ConfirmInput, JournalProcesses.ConfirmOutput, ProcessContext>
        qbJournalExportConfirm() {
        return JournalProcesses.confirm();
    }
}
