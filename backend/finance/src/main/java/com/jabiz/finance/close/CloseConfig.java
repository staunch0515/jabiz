package com.jabiz.finance.close;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.finance.gl.GlEntities.dataset;

/** Registers the close's checklist, tasks and artifacts ({@link CloseEntities}) and their processes. */
@Configuration
class CloseConfig {

    @Bean
    EntityDefinition finCloseTemplateEntity() {
        return CloseEntities.TEMPLATE_ENTITY;
    }

    @Bean
    EntityDefinition finCloseTaskEntity() {
        return CloseEntities.TASK_ENTITY;
    }

    @Bean
    EntityDefinition finCloseArtifactEntity() {
        return CloseEntities.ARTIFACT_ENTITY;
    }

    @Bean
    EntityDefinition finCloseArtifactLineEntity() {
        return CloseEntities.ARTIFACT_LINE_ENTITY;
    }

    // Every finance user reads the checklist and the artifacts; only the close processes write them.
    @Bean
    DatasetDefinition finCloseTemplateDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CloseEntities.TEMPLATE_DATASET, CloseEntities.TEMPLATE, FinancePermissions.PERIOD_READ,
            FinancePermissions.PERIOD_CLOSE, true, poolRef);
    }

    @Bean
    DatasetDefinition finCloseTaskDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CloseEntities.TASK_DATASET, CloseEntities.TASK, FinancePermissions.PERIOD_READ,
            FinancePermissions.CLOSE_TASK, true, poolRef);
    }

    @Bean
    DatasetDefinition finCloseArtifactDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CloseEntities.ARTIFACT_DATASET, CloseEntities.ARTIFACT, FinancePermissions.PERIOD_READ,
            FinancePermissions.PERIOD_CLOSE, true, poolRef);
    }

    @Bean
    DatasetDefinition finCloseArtifactLineDataset(
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CloseEntities.ARTIFACT_LINE_DATASET, CloseEntities.ARTIFACT_LINE,
            FinancePermissions.PERIOD_READ, FinancePermissions.PERIOD_CLOSE, true, poolRef);
    }

    @Bean
    StaticDictionary finCloseTaskKindDictionary() {
        return StaticDictionary.define(CloseEntities.KINDS, d -> d
            .item(CloseEntities.MANUAL, "en", "Manual")
            .item(CloseEntities.AUTO, "en", "Automatic"));
    }

    @Bean
    StaticDictionary finCloseTaskStatusDictionary() {
        return StaticDictionary.define(CloseEntities.STATUSES, d -> d
            .item(CloseEntities.OPEN, "en", "Open")
            .item(CloseEntities.DONE, "en", "Done")
            .item(CloseEntities.PASSED, "en", "Passed")
            .item(CloseEntities.FAILED, "en", "Failed"));
    }

    /** Evidence of manual close tasks: PDF and images. */
    @Bean
    FilePolicy closeEvidenceFiles() {
        return FilePolicy.define(CloseEntities.EVIDENCE_FILES)
            .allow(MediaTypes.PDF, MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(25 * FilePolicy.MB)
            .permissions(FinancePermissions.CLOSE_TASK, FinancePermissions.PERIOD_READ)
            .build();
    }

    @Bean
    ProcessDefinition<CloseProcesses.TemplateInput, CloseProcesses.TemplateOutput, ProcessContext>
        finCloseTemplateSaveProcess() {
        return CloseProcesses.TEMPLATE_PROCESS;
    }

    @Bean
    ProcessDefinition<CloseProcesses.PeriodInput, CloseProcesses.ChecklistOutput, ProcessContext>
        finCloseStartProcess(BookingTime booking) {
        return CloseProcesses.startProcess(booking);
    }

    @Bean
    ProcessDefinition<CloseProcesses.AssignInput, CloseProcesses.AssignOutput, ProcessContext>
        finCloseTaskAssignProcess() {
        return CloseProcesses.ASSIGN_PROCESS;
    }

    @Bean
    ProcessDefinition<CloseProcesses.CompleteInput, CloseProcesses.TaskOutput, ProcessContext>
        finCloseTaskCompleteProcess() {
        return CloseProcesses.COMPLETE_PROCESS;
    }

    @Bean
    ProcessDefinition<CloseProcesses.PeriodInput, CloseProcesses.ChecklistOutput, ProcessContext>
        finCloseCheckProcess() {
        return CloseProcesses.CHECK_PROCESS;
    }

    @Bean
    ProcessDefinition<CloseProcesses.PeriodInput, CloseProcesses.CloseOutput, ProcessContext>
        finPeriodCloseProcess() {
        return CloseProcesses.CLOSE_PROCESS;
    }
}
