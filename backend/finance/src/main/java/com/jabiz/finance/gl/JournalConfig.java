package com.jabiz.finance.gl;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.finance.gl.GlEntities.dataset;

/** Registers journal entries ({@link JournalEntities}, {@link JournalProcesses}). */
@Configuration
class JournalConfig {

    @Bean
    EntityDefinition finJournalEntity() {
        return JournalEntities.JOURNAL_ENTITY;
    }

    @Bean
    EntityDefinition finJournalLineEntity() {
        return JournalEntities.LINE_ENTITY;
    }

    @Bean
    EntityDefinition finJournalAttachmentEntity() {
        return JournalEntities.ATTACHMENT_ENTITY;
    }

    @Bean
    EntityDefinition finPostingEntity() {
        return JournalEntities.POSTING_ENTITY;
    }

    @Bean
    EntityDefinition finRecurringTemplateEntity() {
        return JournalEntities.RECURRING_ENTITY;
    }

    @Bean
    EntityDefinition finRecurringLineEntity() {
        return JournalEntities.RECURRING_LINE_ENTITY;
    }

    @Bean
    DatasetDefinition finJournalDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(JournalEntities.JOURNAL_DATASET, JournalEntities.JOURNAL, FinancePermissions.JOURNAL_READ,
            FinancePermissions.JOURNAL_PREPARE, true, poolRef);
    }

    @Bean
    DatasetDefinition finJournalLineDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(JournalEntities.LINE_DATASET, JournalEntities.LINE, FinancePermissions.JOURNAL_READ,
            FinancePermissions.JOURNAL_PREPARE, true, poolRef);
    }

    @Bean
    DatasetDefinition finJournalAttachmentDataset(
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(JournalEntities.ATTACHMENT_DATASET, JournalEntities.ATTACHMENT,
            FinancePermissions.JOURNAL_READ, FinancePermissions.JOURNAL_PREPARE, true, poolRef);
    }

    @Bean
    DatasetDefinition finPostingDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(JournalEntities.POSTING_DATASET, JournalEntities.POSTING, FinancePermissions.JOURNAL_READ,
            FinancePermissions.JOURNAL_POST, true, poolRef);
    }

    @Bean
    DatasetDefinition finRecurringTemplateDataset(
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(JournalEntities.RECURRING_DATASET, JournalEntities.RECURRING, FinancePermissions.JOURNAL_READ,
            FinancePermissions.RECURRING_MAINTAIN, false, poolRef);
    }

    @Bean
    DatasetDefinition finRecurringLineDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(JournalEntities.RECURRING_LINE_DATASET, JournalEntities.RECURRING_LINE,
            FinancePermissions.JOURNAL_READ, FinancePermissions.RECURRING_MAINTAIN, false, poolRef);
    }

    @Bean
    StaticDictionary journalStatusDictionary() {
        return StaticDictionary.define(JournalEntities.STATUSES, d -> d
            .item(JournalEntities.DRAFT, "en", "Draft")
            .item(JournalEntities.SUBMITTED, "en", "Submitted")
            .item(JournalEntities.APPROVED, "en", "Approved")
            .item(JournalEntities.POSTED, "en", "Posted")
            .item(JournalEntities.REJECTED, "en", "Rejected"));
    }

    @Bean
    StaticDictionary journalSourceDictionary() {
        return StaticDictionary.define(JournalEntities.SOURCES, d -> d
            .item(JournalEntities.MANUAL, "en", "Manual")
            .item(JournalEntities.RECURRING_SOURCE, "en", "Recurring")
            .item(JournalEntities.REVERSING, "en", "Reversing")
            .item(JournalEntities.AUTO_REVERSING, "en", "Automatic reversal")
            .item(JournalEntities.IMPORT, "en", "Import"));
    }

    @Bean
    StaticDictionary postingSourceDictionary() {
        return StaticDictionary.define(JournalEntities.POSTING_SOURCES, d -> d
            .item("MAN", "en", "Manual journals")
            .item("AR", "en", "Receivables")
            .item("AP", "en", "Payables")
            .item("BK", "en", "Bank")
            .item("FA", "en", "Fixed assets")
            .item("FX", "en", "Revaluation")
            .item("IMP", "en", "Import")
            .item("CLS", "en", "Closing"));
    }

    /** JE-0001 …, counted per fiscal year (design section 4.5). */
    @Bean
    NumberSequence journalNumbers() {
        return NumberSequence.define(JournalProcesses.JOURNAL_NUMBERS, s -> s.format("JE-{n:4}").scoped());
    }

    /** GJ-MAN-2026-000001 …, counted per source and fiscal year (FIN-GL-013). */
    @Bean
    NumberSequence glNumbers() {
        return NumberSequence.define(JournalProcesses.GL_NUMBERS, s -> s.format("GJ-{scope}-{n:6}").scoped());
    }

    /**
     * What the approval rules of journal entries can ask about (FIN-GL-015): the amount (total debits), the source,
     * whether it is manual (all but the automatic reversal) and whether it was recorded after its period ended.
     */
    @Bean
    ApprovalSubject journalApprovals() {
        return ApprovalSubject.define(JournalProcesses.SUBJECT, s -> s
            .entity(JournalEntities.JOURNAL)
            .number("amount")
            .text("source")
            .bool("manual")
            .bool("afterPeriodEnd"));
    }

    /** Supporting documents (FIN-GL-016): PDF and images … */
    @Bean
    FilePolicy journalSupportFiles() {
        return FilePolicy.define(JournalEntities.SUPPORT_FILES)
            .allow(MediaTypes.PDF, MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(25 * FilePolicy.MB)
            .permissions(FinancePermissions.JOURNAL_ATTACH, FinancePermissions.JOURNAL_READ)
            .build();
    }

    /** … and spreadsheets, which the platform keeps in a policy of their own. */
    @Bean
    FilePolicy journalSheetFiles() {
        return FilePolicy.define(JournalEntities.SHEET_FILES)
            .allow(MediaTypes.XLSX, MediaTypes.TEXT)
            .maxBytes(25 * FilePolicy.MB)
            .permissions(FinancePermissions.JOURNAL_ATTACH, FinancePermissions.JOURNAL_READ)
            .build();
    }

    @Bean
    EventSubscription<JournalProcesses.ApprovalResultInput> journalApprovedSubscription() {
        return EventSubscription.of("fin.journal-approved", ApprovalProcesses.APPROVED_EVENT,
            JournalProcesses.APPROVAL_RESULT_PROCESS, JournalConfig::decision);
    }

    @Bean
    EventSubscription<JournalProcesses.ApprovalResultInput> journalRejectedSubscription() {
        return EventSubscription.of("fin.journal-rejected", ApprovalProcesses.REJECTED_EVENT,
            JournalProcesses.APPROVAL_RESULT_PROCESS, JournalConfig::decision);
    }

    private static JournalProcesses.ApprovalResultInput decision(DomainEvent event) {
        return new JournalProcesses.ApprovalResultInput(text(event, "subject"), text(event, "entityId"),
            text(event, "status"), text(event, "contentHash"));
    }

    private static String text(DomainEvent event, String key) {
        Object value = event.payload().get(key);
        return value == null ? null : String.valueOf(value);
    }

    @Bean
    ProcessDefinition<JournalProcesses.JournalInput, JournalProcesses.JournalOutput, ProcessContext>
        finJournalSaveProcess() {
        return JournalProcesses.SAVE_PROCESS;
    }

    @Bean
    ProcessDefinition<JournalProcesses.JournalId, JournalProcesses.JournalOutput, ProcessContext>
        finJournalDeleteProcess() {
        return JournalProcesses.DELETE_PROCESS;
    }

    @Bean
    ProcessDefinition<JournalProcesses.JournalId, JournalProcesses.JournalOutput, ProcessContext>
        finJournalSubmitProcess(BookingTime booking) {
        return JournalProcesses.submitProcess(booking);
    }

    @Bean
    ProcessDefinition<JournalProcesses.PostInput, JournalProcesses.PostOutput, ProcessContext>
        finJournalPostProcess(BookingTime booking) {
        return JournalProcesses.postProcess(booking);
    }

    @Bean
    ProcessDefinition<JournalProcesses.ApprovalResultInput, JournalProcesses.PostOutput, ProcessContext>
        finJournalApprovalResultProcess() {
        return JournalProcesses.APPROVAL_RESULT_PROCESS;
    }

    @Bean
    ProcessDefinition<JournalProcesses.ExceptionInput, JournalProcesses.JournalOutput, ProcessContext>
        finJournalExceptionProcess() {
        return JournalProcesses.EXCEPTION_PROCESS;
    }

    @Bean
    ProcessDefinition<JournalProcesses.ReverseInput, JournalProcesses.JournalOutput, ProcessContext>
        finJournalReverseProcess() {
        return JournalProcesses.REVERSE_PROCESS;
    }
}
