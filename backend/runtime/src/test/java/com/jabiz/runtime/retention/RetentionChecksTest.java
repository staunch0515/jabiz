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
        beans.registerBean("notes", EntityDefinition.class, () -> NOTE);
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

    /** An entity with a field that can change: no scope may rest on it. */
    private static final EntityDefinition NOTE = EntityDefinition.define("ItNote", eb -> {
        eb.physicalTable("it_note");
        eb.primaryKey("noteId");
        eb.field("noteId", f -> f.physicalColumn("note_id").immutable(true).required(true).asText(36));
        eb.field("kind", f -> f.physicalColumn("kind").asText(20));
        eb.field("writtenTime", f -> f.physicalColumn("written_time").immutable(true).required(true)
            .asTemporal(com.jabiz.entity.TemporalRole.EVENT_TIME));
    });

    private static RetentionPolicy runs(int years, String... layouts) {
        return RetentionPolicy.of("DocumentRun").only("layoutId", layouts).keep(Period.ofYears(years))
            .from("issuedTime");
    }

    /** Decision D70: an entity's policies may each cover some values of one immutable field, none twice. */
    @Test
    void severalPoliciesOfAnEntityEachCoverTheirOwnValues() {
        assertThat(problems(runs(10, "receipt", "statement"), runs(1, "notice"))).isEmpty();
        assertThat(problems(runs(10, "receipt"), runs(1, "receipt", "notice"))).containsExactly(
            "RETENTION | RetentionPolicy DocumentRun | value receipt of layoutId is covered by more than one policy");
        assertThat(problems(runs(10, "receipt"),
            RetentionPolicy.of("DocumentRun").keep(Period.ofYears(1)).from("issuedTime"))).containsExactly(
            "RETENTION | RetentionPolicy DocumentRun | declared more than once: several policies of an entity must "
                + "each cover some values with only(...)");
        assertThat(problems(runs(10, "receipt"), RetentionPolicy.of("DocumentRun").only("subjectEntity", "Order")
            .keep(Period.ofYears(1)).from("issuedTime"))).containsExactly(
            "RETENTION | RetentionPolicy DocumentRun | its policies cover values of different fields");
        assertThat(problems(
            RetentionPolicy.of("DocumentRun").keep(Period.ofYears(1)).from("issuedTime"),
            RetentionPolicy.of("DocumentRun").keep(Period.ofYears(2)).from("issuedTime"))).containsExactly(
            "RETENTION | RetentionPolicy DocumentRun | declared more than once: several policies of an entity must "
                + "each cover some values with only(...)");
    }

    @Test
    void aScopeRestsOnADeclaredImmutableField() {
        assertThat(problems(
            RetentionPolicy.of("DocumentRun").only("layout", "receipt").keep(Period.ofYears(1)).from("issuedTime"),
            RetentionPolicy.of("ItNote").only("kind", "memo").keep(Period.ofYears(1)).from("writtenTime")))
            .containsExactly(
                "RETENTION | RetentionPolicy DocumentRun only layout [receipt] | field layout is not declared",
                "RETENTION | RetentionPolicy ItNote only kind [memo] | field kind is not immutable");
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
