package com.jabiz.runtime.retention;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.retention.RetentionPolicy;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Period;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup check of retention policies, for the platform's archives (ROADMAP 16v-1 item 8). */
class RetentionChecksTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> problems(RetentionPolicy... policies) {
        GenericApplicationContext beans = new GenericApplicationContext();
        beans.registerBean("files", EntityDefinition.class, () -> ArchiveEntities.GENERATED_FILE_ENTITY);
        beans.registerBean("runs", EntityDefinition.class, () -> ArchiveEntities.DOCUMENT_RUN_ENTITY);
        for (int i = 0; i < policies.length; i++) {
            RetentionPolicy policy = policies[i];
            beans.registerBean("policy" + i, RetentionPolicy.class, () -> policy);
        }
        beans.refresh();
        try {
            RetentionPolicies declared = new RetentionPolicies((ObjectProvider) beans.getBeanProvider(
                RetentionPolicy.class), 12, "UTC");
            EntityDefinitionRegistry entities = new EntityDefinitionRegistry(beans.getBeanProvider(
                EntityDefinition.class));
            return new RetentionChecks(declared, entities).check().stream().map(CheckProblem::format).toList();
        } finally {
            beans.close();
        }
    }

    @Test
    void generatedFilesAndDocumentRunsAreAcceptedFromTheirTimes() {
        assertThat(problems(
            RetentionPolicy.of("GeneratedFile").keep(Period.ofYears(7)).from("createdTime"),
            RetentionPolicy.of("DocumentRun").keep(Period.ofYears(10)).from("issuedTime").afterFiscalYearEnd()))
            .isEmpty();
    }

    @Test
    void onlyTheirTimesCount() {
        assertThat(problems(RetentionPolicy.of("DocumentRun").keep(Period.ofYears(10)).from("layoutId"),
            RetentionPolicy.of("GeneratedFile").keep(Period.ofYears(7)).from("content")))
            .containsExactly(
                "RETENTION | RetentionPolicy DocumentRun | field layoutId is not a time or a date",
                "RETENTION | RetentionPolicy GeneratedFile | field content is not declared");
    }

    /** What the archives' records show: no content, PDF, file name, title, parameters or scope. */
    @Test
    void theArchivesShowNoContentNameOrTitle() {
        assertThat(ArchiveEntities.GENERATED_FILE_ENTITY.fields.keySet()).containsExactlyInAnyOrder("fileId",
            "mediaType", "size", "sha256", "subjectEntity", "subjectId", "createdBy", "createdTime", "processSeqId");
        assertThat(ArchiveEntities.DOCUMENT_RUN_ENTITY.fields.keySet()).containsExactlyInAnyOrder("runId", "layoutId",
            "pdfSize", "pdfHash", "subjectEntity", "subjectId", "issuedBy", "issuedTime", "processSeqId");
    }
}
